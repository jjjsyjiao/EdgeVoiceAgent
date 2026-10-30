"""Behavioral tests with deterministic vectors, not semantic model evaluation."""
import base64
from dataclasses import replace
import io
import json
import time

from fastapi.testclient import TestClient
import httpx
from PIL import Image
import pytest

from app.config import Settings
from app.main import create_app
from app.session_store import SessionFrameStore
from app.visual_memory_store import VisualMemoryStore
from app.visual_retrieval.index import VisualVectorIndex
from app.visual_retrieval.providers import EmbeddingError
from app.visual_retrieval.query import variants
from app.visual_retrieval.rerank import DashscopeReranker, RerankUnavailable
from app.visual_retrieval.service import VisualRetrievalService
from app.visual_retrieval.worker import VisualIndexWorker


def jpeg(color):
    out = io.BytesIO()
    Image.new("RGB", (32, 24), color).save(out, format="JPEG")
    return out.getvalue()


class Vectors:
    namespace, dimension = "synthetic-contract-not-semantic", 2
    fail = False

    def encode_texts(self, texts):
        if self.fail:
            raise EmbeddingError("embedding_timeout")
        return [[1., 0.] if "红" in text else [0., 1.] for text in texts]

    def encode_images(self, images):
        if self.fail:
            raise EmbeddingError("embedding_timeout")
        result = []
        for raw in images:
            r, _, b = Image.open(io.BytesIO(raw)).getpixel((0, 0))
            result.append([1., 0.] if r > b else [0., 1.])
        return result


@pytest.fixture
def setup(tmp_path):
    store = VisualMemoryStore(str(tmp_path / "memory"))
    provider = Vectors()
    index = VisualVectorIndex(store.root, provider.namespace, provider.dimension)
    service = VisualRetrievalService(store, index, provider)
    worker = VisualIndexWorker(index, provider)
    return store, provider, index, service, worker


def add(store, record_id="a", owner="alpha", ts=1000, name="记录", color="red", note=""):
    return store.add(owner, {"record_id": record_id, "session_id": "old-session", "captured_at_ms": ts,
                      "object_name": name, "aliases": [], "location": "", "note": note}, jpeg(color))


def test_original_constraints_survive_expansion():
    raw = "不是昨天，是前天白色的充电头"
    result = variants("前天白色充电头", raw)
    assert result[0] == raw and len(result) <= 3
    assert any("电源适配器" in v for v in result)
    assert all("前天" in v and "白色" in v for v in result)


def test_unindexed_no_hit_is_indexing_not_definite_absence(setup):
    store, _, _, service, _ = setup
    add(store)
    result = service.search("alpha", "红色杯子")
    assert result["records"] == []
    assert result["retrieval"]["status"] == "indexing"
    assert result["retrieval"]["pending_records_count"] == 1


def test_text_to_image_retrieves_an_image_without_object_annotation(setup):
    store, _, _, service, worker = setup
    add(store, "red", color="red")
    add(store, "blue", color="blue")
    while worker.run_once():
        pass
    result, trace = service.search_with_trace("alpha", "红色杯子", limit=1)
    assert trace["channels"]["metadata"] == []
    assert trace["channels"]["text_to_image"][0] == "red"
    assert result["records"][0]["record_id"] == "red"
    assert "uncalibrated_similarity" in result["retrieval"]["warnings"]


def test_named_object_does_not_fill_evidence_with_unrelated_ranked_records(setup):
    store, _, _, service, worker = setup
    add(store, "phone", name="手机", color="red")
    add(store, "lamp", name="台灯", color="blue")
    add(store, "bag", name="蓝色的包", color="blue")
    while worker.run_once():
        pass
    # All three have vectors, so an unfiltered top-3 would show lamp and bag.
    for query, raw in (("手机", "我的手机在哪里"),
                       ("手机", "不是台灯，是手机"),
                       ("这个", "我的手机在哪里")):
        result, trace = service.search_with_trace("alpha", query, raw_query=raw)
        assert [row["record_id"] for row in result["records"]] == ["phone"]
        assert trace["candidate_record_ids"] == ["phone"]


def test_partial_name_keeps_matching_saved_object_without_other_candidates(setup):
    store, _, _, service, worker = setup
    add(store, "bag", name="蓝色的包", color="blue")
    add(store, "lamp", name="台灯", color="red")
    while worker.run_once():
        pass
    result = service.search("alpha", "包", raw_query="包放在哪里")
    assert [row["record_id"] for row in result["records"]] == ["bag"]


def test_named_object_survives_tiny_fused_candidate_budget(setup):
    store, provider, index, _, worker = setup
    add(store, "z-phone", name="手机", color="red", ts=1000)
    add(store, "a-lamp", name="台灯", color="blue", ts=2000)
    while worker.run_once():
        pass
    service = VisualRetrievalService(store, index, provider, channel_limit=1, candidate_limit=1, rerank_limit=1)
    result, trace = service.search_with_trace("alpha", "手机")
    assert trace["candidate_record_ids"] == ["z-phone"]
    assert [row["record_id"] for row in result["records"]] == ["z-phone"]


def test_current_picture_channel_is_explicit_and_query_is_not_saved(setup):
    store, _, _, service, worker = setup
    add(store, "red", color="red")
    add(store, "blue", color="blue")
    while worker.run_once():
        pass
    _, trace = service.search_with_trace("alpha", "这个", query_image=jpeg("blue"), intent="compare")
    assert trace["channels"]["image_to_image"][0] == "blue"
    assert store.coverage("alpha")["records_count"] == 2


def test_all_channels_enforce_owner_and_half_open_time(setup):
    store, _, _, service, worker = setup
    add(store, "inside", ts=1000, name="红杯子")
    add(store, "outside", ts=2000, name="红杯子")
    add(store, "other", owner="beta", ts=1000, name="红杯子")
    while worker.run_once():
        pass
    result, trace = service.search_with_trace("alpha", "红杯子", 1000, 2000)
    assert [r["record_id"] for r in result["records"]] == ["inside"]
    assert all(set(ids) <= {"inside"} for ids in trace["channels"].values())
    assert result["retrieval"]["pending_records_count"] == 0


def test_embedding_outage_preserves_keyword_evidence_with_degraded_status(setup):
    store, provider, _, service, worker = setup
    add(store, name="钥匙")
    worker.run_once()
    provider.fail = True
    result = service.search("alpha", "钥匙")
    assert result["status"] == "matched"
    assert result["retrieval"]["status"] == "degraded"
    assert "embedding_unavailable" in result["retrieval"]["warnings"]
    empty = service.search("alpha", "眼镜")
    assert not empty["records"] and empty["retrieval"]["status"] == "unavailable"


def test_bad_selected_picture_is_replaced_not_global_failure(setup):
    store, _, _, service, _ = setup
    add(store, "bad", ts=2000, name="钥匙")
    add(store, "good", ts=1000, name="钥匙")
    store._path("alpha", "bad").unlink()
    result = service.search("alpha", "钥匙", limit=1)
    assert result["records"][0]["record_id"] == "good"
    assert "partial_evidence" in result["retrieval"]["warnings"]


def test_delete_during_remote_rerank_cannot_return_deleted_evidence(setup):
    store, _, _, service, _ = setup
    add(store, name="钥匙")

    class DeletingReranker:
        def rank(self, query, records, query_image=None):
            store.delete("alpha", "a")
            return [("a", .9)]

    service.reranker = DeletingReranker()
    result = service.search("alpha", "钥匙")
    assert result["records"] == [] and result["status"] == "evidence_unavailable"
    assert service.keywords.search("alpha", "钥匙") == []


def test_last_location_uses_capture_time_not_save_order(setup):
    store, _, _, service, worker = setup
    add(store, "new_observation", ts=3000, name="钥匙")
    add(store, "late_upload_old_observation", ts=1000, name="钥匙")
    while worker.run_once():
        pass
    result = service.search("alpha", "钥匙", limit=1, intent="find_last_location")
    assert result["records"][0]["record_id"] == "new_observation"


def test_api_extended_contract_and_image_validation(setup, tmp_path):
    store, _, _, service, _ = setup
    config = replace(Settings.from_env(), visual_memory_auth_tokens=json.dumps({"test-token-at-least-16": "alpha"}))
    client = TestClient(create_app(config, service=object(), session_store=SessionFrameStore(str(tmp_path / "old")),
                                  visual_store=store, visual_retrieval=service))
    headers = {"Authorization": "Bearer test-token-at-least-16"}
    result = client.post("/visual-memory/search", headers=headers, json={"query": "这个", "raw_query": "这个和昨天的是同一个吗", "intent": "compare",
                         "query_image_base64": base64.b64encode(jpeg("red")).decode()})
    assert result.status_code == 200 and result.json()["retrieval"]["mode"] == "hybrid"
    bad = client.post("/visual-memory/search", headers=headers, json={"query": "这个", "query_image_base64": "c2VjcmV0"})
    assert bad.status_code == 422 and "c2VjcmV0" not in bad.text
    assert client.get("/visual-memory/status", headers=headers).json()["index"]["total"] == 0


def test_app_lifespan_starts_durable_worker_and_closes_provider(tmp_path, monkeypatch):
    provider = Vectors()
    closed = []
    provider.close = lambda: closed.append(True)
    monkeypatch.setattr("app.visual_retrieval.providers.DashscopeMultimodalEmbeddingProvider", lambda *args: provider)
    store = VisualMemoryStore(str(tmp_path / "memory"))
    config = replace(Settings.from_env(), visual_memory_auth_tokens=json.dumps({"test-token-at-least-16": "alpha"}),
                     visual_retrieval_enabled=True, visual_rerank_enabled=False)
    app = create_app(config, service=object(), session_store=SessionFrameStore(str(tmp_path / "old")), visual_store=store)
    headers = {"Authorization": "Bearer test-token-at-least-16"}
    with TestClient(app) as client:
        add(store)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            status = client.get("/visual-memory/status", headers=headers).json()["index"]
            if status["ready"] == 1:
                break
            time.sleep(.05)
        assert status["ready"] == 1 and status["pending"] == 0
    assert closed == [True]


def ranker(handler):
    return DashscopeReranker("test-secret", "https://example.test/rerank", client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_reranker_transmits_actual_images_and_maps_indices():
    seen = []
    def handler(req):
        body = json.loads(req.content)
        assert body["input"]["documents"][0]["image"].startswith("data:image/jpeg;base64,")
        seen.append(set(body["input"]["query"]))
        assert body["parameters"]["return_documents"] is False
        return httpx.Response(200, json={"output": {"results": [{"index": 1, "relevance_score": .9}, {"index": 0, "relevance_score": .1}]}})
    records = [{"record_id": rid, "image_base64": base64.b64encode(jpeg("red")).decode()} for rid in ("a", "b")]
    assert ranker(handler).rank("这个", records, jpeg("red"))[0][0] == "b"
    assert {"image"} in seen and {"text"} in seen


@pytest.mark.parametrize("intent", ["recall", "find_last_location", "compare"])
def test_empty_scope_is_complete_no_match(setup, intent):
    result = setup[3].search("alpha", "钥匙", intent=intent)
    assert result["status"] == "no_match"
    assert result["retrieval"]["warnings"] == []


def test_same_id_recreated_during_rerank_does_not_replace_evidence(setup):
    store, _, _, service, _ = setup
    add(store, name="台灯")
    class ReplacingReranker:
        def rank(self, query, records, query_image=None):
            store.delete("alpha", "a")
            add(store, name="电饭锅", color="blue")
            return [("a", .9)]
    service.reranker = ReplacingReranker()
    assert service.search("alpha", "台灯")["records"] == []


def test_latest_relevant_observation_survives_rerank_budget(setup):
    store, _, _, service, _ = setup
    for n in range(35):
        add(store, str(n), ts=n, name="台灯")
    add(store, "latest", ts=1000, name="备用台灯")
    result = service.search("alpha", "台灯", intent="find_last_location")
    assert result["records"][0]["record_id"] == "latest"


@pytest.mark.parametrize("results", [[{"index": 0, "relevance_score": float("nan")}],
                                     [{"index": True, "relevance_score": .8}], [],
                                     [{"index": 99, "relevance_score": .8}]])
def test_invalid_rerank_response_fails_closed(results):
    r = ranker(lambda _: httpx.Response(200, json={"output": {"results": results}}) if not results or results[0].get("relevance_score") == results[0].get("relevance_score")
               else httpx.Response(200, content='{"output":{"results":[{"index":0,"relevance_score":NaN}]}}'))
    with pytest.raises(RerankUnavailable):
        r.rank("杯子", [{"record_id": "a", "image_base64": "test"}])
