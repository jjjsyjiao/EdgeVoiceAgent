"""真实 JPEG + 临时 SQLite：视觉记忆契约、隔离与证据完整性。零模型调用。"""

from __future__ import annotations

import base64
from dataclasses import replace
from datetime import datetime
import io
import json
from zoneinfo import ZoneInfo

from fastapi.testclient import TestClient
from PIL import Image
import pytest

from app.config import Settings
from app.main import create_app
from app.session_store import SessionFrameStore
from app.visual_memory_store import VisualMemoryStore, metadata_score

TOKEN_A = "fixture-token-for-owner-alpha"
TOKEN_B = "fixture-token-for-owner-beta"
HEADERS_A = {"Authorization": f"Bearer {TOKEN_A}"}
HEADERS_B = {"Authorization": f"Bearer {TOKEN_B}"}


def jpeg(colour: str = "red") -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (32, 24), colour).save(output, format="JPEG")
    return output.getvalue()


class Clock:
    now = 1_800_000_000.0

    def __call__(self):
        return self.now


@pytest.fixture
def harness(tmp_path):
    clock = Clock()
    store = VisualMemoryStore(str(tmp_path / "visual"), clock=clock)
    config = replace(Settings.from_env(), visual_memory_auth_tokens=json.dumps({TOKEN_A: "alpha", TOKEN_B: "beta"}))
    app = create_app(config, service=object(), session_store=SessionFrameStore(str(tmp_path / "legacy")), visual_store=store)
    return TestClient(app), store, clock


def payload(record_id="r1", ts=1_799_900_000_000, **changes):
    result = {
        "record_id": record_id, "session_id": "session_old", "captured_at_ms": ts,
        "object_name": "备用钥匙", "aliases": ["备份钥匙"], "location": "玄关柜上层",
        "note": "口述：出门前放在蓝色托盘", "image_base64": base64.b64encode(jpeg()).decode(),
    }
    return result | changes


def add(client, data=None, headers=HEADERS_A):
    response = client.post("/visual-memory/records", json=data or payload(), headers=headers)
    assert response.status_code == 200, response.text
    return response.json()["record"]


def search(client, query="钥匙", headers=HEADERS_A, **changes):
    response = client.post("/visual-memory/search", json={"query": query} | changes, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


def test_disabled_interface_does_not_create_private_storage(tmp_path):
    root = tmp_path / "disabled"
    settings = replace(Settings.from_env(), visual_memory_dir=str(root), visual_memory_auth_tokens="")
    client = TestClient(create_app(settings, service=object(), session_store=SessionFrameStore(str(tmp_path / "legacy"))))
    assert client.post("/visual-memory/search", json={"query": "台灯"}).status_code == 503
    assert not root.exists()


def test_auth_is_required_and_client_cannot_choose_owner(harness):
    client, _, _ = harness
    for headers in ({}, {"Authorization": "Bearer not-a-valid-token"}, {"Authorization": "Basic abc"}):
        assert client.post("/visual-memory/search", json={"query": "钥匙"}, headers=headers).status_code == 401
    assert client.post("/visual-memory/search", json={"query": "钥匙", "user_id": "beta"}, headers=HEADERS_A).status_code == 422


def test_roundtrip_contains_evidence_and_user_supplied_source(harness):
    client, _, clock = harness
    record = add(client)
    assert record["recorded_at_ms"] == int(clock.now * 1000)
    assert record["source"] == "user_recorded"
    result = search(client, "我的备份钥匙放哪儿")
    assert result["status"] == "matched"
    assert result["records"][0]["record_id"] == record["record_id"]
    assert base64.b64decode(result["records"][0]["image_base64"]) == jpeg()
    assert result["coverage"]["continuous"] is False


def test_owners_cannot_read_overwrite_or_delete_each_others_records(harness):
    client, _, _ = harness
    add(client)
    assert search(client, headers=HEADERS_B)["status"] == "no_match"
    assert client.delete("/visual-memory/records/r1", headers=HEADERS_B).json()["deleted"] is False
    add(client, payload(location="书房"), headers=HEADERS_B)
    assert search(client)["records"][0]["location"] == "玄关柜上层"
    assert search(client, headers=HEADERS_B)["records"][0]["location"] == "书房"


def test_cross_session_and_local_day_window_is_left_closed_right_open(harness):
    client, _, _ = harness
    zone = ZoneInfo("Asia/Shanghai")
    start = int(datetime(2026, 10, 27, tzinfo=zone).timestamp() * 1000)
    end = int(datetime(2026, 10, 28, tzinfo=zone).timestamp() * 1000)
    for name, ts, session in (("y0", start, "s0"), ("y1", end - 1, "s1"), ("today", end, "s2")):
        add(client, payload(name, ts, session_id=session))
    results = search(client, from_ts_ms=start, to_ts_ms=end)
    assert [record["record_id"] for record in results["records"]] == ["y1", "y0"]
    assert len(search(client)["records"]) == 3


def test_idempotent_retry_and_conflicting_id(harness):
    client, _, _ = harness
    first, second = add(client), add(client)
    assert first == second
    assert search(client)["coverage"]["records_count"] == 1
    response = client.post("/visual-memory/records", json=payload(location="另一个位置"), headers=HEADERS_A)
    assert response.status_code == 409


@pytest.mark.parametrize("bad", ["../escape", "folder/name", "a.b", "x" * 97])
def test_path_like_ids_rejected(harness, bad):
    client, _, _ = harness
    assert client.post("/visual-memory/records", json=payload(record_id=bad), headers=HEADERS_A).status_code == 422


@pytest.mark.parametrize("raw", [b"\xff\xd8\xff\xd9", jpeg()[:80], b"not an image"])
def test_undecodable_jpeg_is_rejected_without_returning_image_content(harness, raw):
    client, store, _ = harness
    encoded = base64.b64encode(raw).decode()
    response = client.post("/visual-memory/records", json=payload(image_base64=encoded), headers=HEADERS_A)
    assert response.status_code == 422
    assert encoded not in response.text
    assert not list(store.root.glob("images/**/*.jpg"))


def test_missing_or_changed_image_is_explicit_evidence_failure(harness):
    client, store, _ = harness
    add(client)
    path = store._path("alpha", "r1")
    path.write_bytes(jpeg("blue"))
    result = search(client)
    assert result["status"] == "evidence_unavailable"
    assert result["records"] == [] and result["unavailable_records_count"] == 1
    path.unlink()
    assert search(client)["status"] == "evidence_unavailable"


def test_good_evidence_survives_a_corrupt_newer_match(harness):
    client, store, _ = harness
    add(client, payload("good", 1000))
    add(client, payload("bad", 2000))
    store._path("alpha", "bad").unlink()
    result = search(client, limit=1)
    assert result["status"] == "matched"
    assert result["records"][0]["record_id"] == "good"
    assert result["unavailable_records_count"] == 1


def test_unrelated_query_is_not_a_random_picture(harness):
    client, _, _ = harness
    add(client)
    result = search(client, "台灯")
    assert result["status"] == "no_match" and result["records"] == []
    assert result["coverage"]["records_count"] == 1


def test_search_ranks_matching_object_before_note_mention(harness):
    client, _, _ = harness
    add(client, payload("object", 1000, object_name="台灯", aliases=[]))
    add(client, payload("mention", 2000, object_name="钥匙", aliases=[], note="台灯旁边"))
    assert [r["record_id"] for r in search(client, "台灯")["records"]] == ["object", "mention"]


def test_delete_removes_image_and_record_and_is_idempotent(harness):
    client, store, _ = harness
    add(client)
    assert client.delete("/visual-memory/records/r1", headers=HEADERS_A).json() == {"ok": True, "deleted": True}
    assert not store._path("alpha", "r1").exists()
    assert search(client)["coverage"]["records_count"] == 0
    assert client.delete("/visual-memory/records/r1", headers=HEADERS_A).json()["deleted"] is False


def test_server_recording_time_controls_ttl_and_restart_preserves_state(harness):
    client, store, clock = harness
    add(client, payload(ts=1000))
    reloaded = VisualMemoryStore(str(store.root), clock=clock)
    assert reloaded.search("alpha", "钥匙", None, None, 3)["status"] == "matched"
    clock.now += 7 * 86400 + 1
    assert search(client)["status"] == "no_match"
    assert not store._path("alpha", "r1").exists()


def test_quota_evicts_oldest_and_preserves_new_record(tmp_path):
    clock = Clock()
    image = jpeg()
    store = VisualMemoryStore(str(tmp_path / "quota"), max_owner_bytes=len(image) * 2, clock=clock)
    for number in range(3):
        values = payload(str(number), number)
        values.pop("image_base64")
        store.add("alpha", values, image)
        clock.now += 1
    result = store.search("alpha", "钥匙", None, None, 3)
    assert [r["record_id"] for r in result["records"]] == ["2", "1"]
    assert sum(path.stat().st_size for path in store.root.glob("images/**/*.jpg")) <= 2 * len(image)


def test_restart_cleans_uncommitted_image_files_without_losing_records(harness):
    client, store, clock = harness
    add(client)
    orphan = store._path("alpha", "orphan")
    orphan.write_bytes(jpeg())
    temporary = orphan.with_suffix(".crash.tmp")
    temporary.write_bytes(b"incomplete")
    restarted = VisualMemoryStore(str(store.root), clock=clock)
    assert not orphan.exists() and not temporary.exists()
    assert restarted.search("alpha", "钥匙", None, None, 3)["records"][0]["record_id"] == "r1"


@pytest.mark.parametrize("changes", [{"limit": 4}, {"from_ts_ms": 10, "to_ts_ms": 10}, {"from_ts_ms": 20, "to_ts_ms": 10}, {"query": "  "}])
def test_search_constraints_are_explicit(harness, changes):
    client, _, _ = harness
    assert client.post("/visual-memory/search", json={"query": "钥匙"} | changes, headers=HEADERS_A).status_code == 422


def test_malformed_auth_config_fails_closed(tmp_path):
    config = replace(Settings.from_env(), visual_memory_auth_tokens='{"short":"alpha"}')
    with pytest.raises(ValueError, match="VISUAL_MEMORY_AUTH_TOKENS"):
        create_app(config)
