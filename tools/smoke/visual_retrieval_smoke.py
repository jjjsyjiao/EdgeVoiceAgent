#!/usr/bin/env python3
"""Real-cloud retrieval component probe using disposable synthetic drawings only.

This is not a recall benchmark, a real-life photo evaluation, or a Realtime
voice/camera test. --live is explicit because it sends synthetic data to paid
embedding/rerank endpoints. Credentials are read into memory, never reported.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

import httpx
from PIL import Image, ImageDraw

from app.visual_memory_store import VisualMemoryStore
from app.visual_retrieval.index import VisualVectorIndex
from app.visual_retrieval.providers import DEFAULT_ENDPOINT, DashscopeMultimodalEmbeddingProvider, EmbeddingError
from app.visual_retrieval.rerank import DashscopeReranker, RerankUnavailable
from app.visual_retrieval.service import VisualRetrievalService
from app.visual_retrieval.worker import VisualIndexWorker

RERANK_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
OWNER, OTHER_OWNER = "synthetic", "synthetic-other"


class ProbeFailure(RuntimeError):
    pass


class CountingTransport(httpx.BaseTransport):
    """Counts actual outbound requests without retaining request data/headers."""
    def __init__(self):
        self.transport = httpx.HTTPTransport(retries=0)
        self.lock = threading.Lock()
        self.count = 0
        self.status_counts: dict[str, int] = {}

    def handle_request(self, request):
        with self.lock:
            self.count += 1
        try:
            response = self.transport.handle_request(request)
        except httpx.HTTPError:
            with self.lock:
                self.status_counts["transport_error"] = self.status_counts.get("transport_error", 0) + 1
            raise
        with self.lock:
            status = str(response.status_code)
            self.status_counts[status] = self.status_counts.get(status, 0) + 1
        return response

    def close(self):
        self.transport.close()


def read_properties(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text().splitlines()
    except OSError:
        raise ProbeFailure("local_properties_unavailable") from None
    values = {}
    for line in lines:
        if not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def drawing(kind: str) -> bytes:
    """Simple geometric icons intentionally unlike a representative photo set."""
    image = Image.new("RGB", (512, 512), "#f8f8f4")
    draw = ImageDraw.Draw(image)
    draw.ellipse((90, 410, 425, 450), fill="#ddddda")
    if kind == "cup":
        draw.ellipse((304, 180, 430, 340), fill="#c92c31")
        draw.ellipse((325, 205, 405, 315), fill="#f8f8f4")
        draw.rounded_rectangle((132, 166, 337, 407), radius=30, fill="#d9363e", outline="#8f1720", width=5)
        draw.ellipse((132, 142, 337, 197), fill="#8f1720", outline="#b31b27", width=5)
        draw.ellipse((149, 154, 321, 183), fill="#f0b4ab")
        draw.line((155, 218, 155, 365), fill="#fa646b", width=14)
    elif kind == "bowl":
        draw.pieslice((96, 115, 416, 437), 0, 180, fill="#2076d4", outline="#174a9a", width=5)
        draw.ellipse((96, 219, 416, 299), fill="#2d81df", outline="#174a9a", width=5)
        draw.ellipse((112, 232, 400, 283), fill="#a8d7fa")
    elif kind == "lamp":
        draw.ellipse((141, 389, 371, 440), fill="#d9a61b", outline="#956b0f", width=5)
        draw.rectangle((246, 242, 266, 408), fill="#b48319")
        draw.polygon([(189, 106), (324, 106), (374, 253), (137, 253)], fill="#f5cf39", outline="#a47b13", width=5)
        draw.ellipse((137, 227, 374, 274), fill="#ffe584", outline="#b98a1b", width=4)
        draw.ellipse((238, 248, 276, 280), fill="#fff4a3")
    elif kind == "cube":
        draw.polygon([(130, 180), (260, 108), (391, 181), (260, 258)], fill="#59b46c", outline="#25683d", width=5)
        draw.polygon([(130, 180), (260, 258), (260, 416), (130, 339)], fill="#3a9e52", outline="#25683d", width=5)
        draw.polygon([(260, 258), (391, 181), (391, 338), (260, 416)], fill="#287943", outline="#205b34", width=5)
    else:
        raise ProbeFailure("unknown_fixture_kind")
    output = io.BytesIO()
    image.save(output, "JPEG", quality=90)
    return output.getvalue()


def require(condition: bool, code: str) -> None:
    if not condition:
        raise ProbeFailure(code)


def result_ids(response: dict) -> list[str]:
    return [record["record_id"] for record in response["records"]]


def all_trace_ids(trace: dict) -> set[str]:
    return set(trace["candidate_record_ids"]) | set(trace["final_record_ids"]) | set(trace["reranked_record_ids"]) | {
        rid for rows in trace["channels"].values() for rid in rows
    }


def run(args) -> dict:
    report = {
        "scope": "synthetic_component_e2e", "real_cloud_models": True,
        "representative_recall_benchmark": False, "recall_rate": None,
        "realtime_voice_camera_verified": False, "passed": False,
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
        "fixtures": {"owner_records": 4, "other_owner_records": 1,
                     "source": "Pillow geometric drawings, not real-life photographs",
                     "metadata": "generic record labels only, no object names or appearance descriptors"},
        "checks": {}, "traces": {}, "error": None,
    }
    started = time.monotonic()
    embedding_transport, rerank_transport = CountingTransport(), CountingTransport()
    provider = reranker = rerank_client = None
    temporary_path = None
    try:
        values = read_properties(args.properties)
        key = values.get("history.retrieval.api.key") or values.get("openai.api.key", "")
        require(bool(key), "embedding_credential_missing")
        provider = DashscopeMultimodalEmbeddingProvider(key,
            endpoint=values.get("history.retrieval.embedding.url") or DEFAULT_ENDPOINT,
            model=values.get("history.retrieval.embedding.model") or "qwen3-vl-embedding",
            dimension=int(values.get("history.retrieval.embedding.dimension") or 2560),
            timeout_seconds=args.timeout, transport=embedding_transport)
        rerank_client = httpx.Client(timeout=args.timeout, follow_redirects=False,
                                    transport=rerank_transport, trust_env=False)
        reranker = DashscopeReranker(key, values.get("history.retrieval.rerank.url") or RERANK_ENDPOINT,
            model=values.get("history.retrieval.rerank.model") or "qwen3-vl-rerank",
            timeout_seconds=args.timeout, client=rerank_client)
        # Never put the properties map, key, or request bodies in this report.
        report["models"] = {"embedding": provider.model, "dimension": provider.dimension,
                            "rerank": reranker.model, "namespace": provider.namespace}
        del key, values
        with tempfile.TemporaryDirectory(prefix="edgevoice-visual-probe-") as directory:
            temporary_path = Path(directory)
            store = VisualMemoryStore(directory)
            index = VisualVectorIndex(store.root, provider.namespace, provider.dimension)
            worker = VisualIndexWorker(index, provider)
            service = VisualRetrievalService(store, index, provider, reranker)
            end = int(time.time() * 1000) - 3600000
            start = end - 3600000
            cup = drawing("cup")
            fixtures = [
                (OWNER, "synthetic-a", "A", start, cup),
                (OWNER, "synthetic-b", "B", start + 1000, drawing("bowl")),
                (OWNER, "synthetic-c", "C", end - 1, drawing("lamp")),
                (OWNER, "synthetic-d", "D", end, drawing("cube")),
                (OTHER_OWNER, "other-synthetic-a", "A", start, cup),
            ]
            for owner, rid, label, captured_at, jpeg in fixtures:
                store.add(owner, {"record_id": rid, "session_id": "synthetic-probe",
                    "captured_at_ms": captured_at, "object_name": "记录" + label,
                    "aliases": [], "location": "", "note": "主动保存的合成组件验证记录"}, jpeg)
            index_started = time.monotonic()
            for _ in fixtures:
                require(worker.run_once(), "index_job_missing")
            report["indexing_elapsed_ms"] = round((time.monotonic() - index_started) * 1000)
            states = {owner: index.status(owner) for owner in (OWNER, OTHER_OWNER)}
            report["index_status"] = states
            require(states[OWNER]["ready"] == 4 and states[OTHER_OWNER]["ready"] == 1, "fixture_indexing_failed")
            report["checks"]["all_records_have_image_and_user_text_vectors"] = True

            response, trace = service.search_with_trace(OWNER, "红色杯子", raw_query="帮我找一下红色杯子的历史画面")
            report["traces"]["text_to_image"] = trace
            require(trace["channels"].get("text_to_image", [None])[0] == "synthetic-a", "text_to_image_top1_mismatch")
            require("synthetic-a" in result_ids(response), "target_missing_from_final_three")
            require(response["retrieval"]["status"] == "ready", "text_query_degraded")
            require(not trace["channels"].get("metadata") and not trace["channels"].get("keyword"), "fixture_metadata_shortcut")
            require("other-synthetic-a" not in all_trace_ids(trace), "cross_owner_evidence_leak")
            report["checks"].update({"text_to_image_top1": True, "target_in_final_three": True,
                                    "object_name_absent_from_lexical_channels": True, "cross_owner_service_isolation": True})

            response, trace = service.search_with_trace(OWNER, "红色杯子", query_image=cup, intent="compare_current")
            report["traces"]["current_image"] = trace
            require(trace["channels"].get("image_to_image", [None])[0] == "synthetic-a", "image_to_image_top1_mismatch")
            require("synthetic-a" in result_ids(response), "image_target_missing_from_final_three")
            require(response["retrieval"]["status"] == "ready", "image_query_degraded")
            report["checks"]["same_image_query_top1"] = True

            response, trace = service.search_with_trace(OWNER, "红色杯子", from_ts_ms=start, to_ts_ms=end)
            report["traces"]["half_open_time_scope"] = trace
            require("synthetic-a" in all_trace_ids(trace), "inclusive_start_missing")
            require("synthetic-c" in all_trace_ids(trace), "end_minus_one_missing")
            require("synthetic-d" not in all_trace_ids(trace), "exclusive_end_leak")
            require(all(start <= record["captured_at_ms"] < end for record in response["records"]), "response_outside_time_scope")
            report["checks"]["half_open_time_scope"] = True

            query_vector = provider.encode_images([cup])[0]
            other_hits = index.search(OTHER_OWNER, query_vector)
            require([item["record_id"] for item in other_hits] == ["other-synthetic-a"], "cross_owner_vector_leak")
            report["checks"]["cross_owner_vector_isolation"] = True
            require(store.delete(OWNER, "synthetic-a"), "fixture_delete_failed")
            require("synthetic-a" not in [item["record_id"] for item in index.search(OWNER, query_vector)], "deleted_vector_returned")
            response, trace = service.search_with_trace(OWNER, "红色杯子")
            report["traces"]["after_delete"] = trace
            require("synthetic-a" not in all_trace_ids(trace), "deleted_evidence_returned")
            report["checks"]["deletion_removes_vectors_and_evidence"] = True
            # Other visually similar candidates may remain. Do not confuse this
            # with calibrated no-match detection, which needs real evaluation.
            for owner, rid, _, _, _ in fixtures:
                store.delete(owner, rid)
            with index._connect() as db:
                counts = {table: db.execute("SELECT count(*) FROM " + table).fetchone()[0]
                          for table in ("records", "visual_vectors", "visual_index_jobs")}
            require(all(count == 0 for count in counts.values()), "fixture_cleanup_incomplete")
            report["cleanup_table_counts"] = counts
            report["checks"]["fixture_records_vectors_jobs_removed"] = True
        report["passed"] = True
    except ProbeFailure as exc:
        report["error"] = str(exc)  # Only our fixed codes are raised by this type.
    except EmbeddingError as exc:
        report["error"] = exc.code
    except RerankUnavailable:
        report["error"] = "rerank_unavailable"
    except Exception:
        report["error"] = "probe_internal_error"
    finally:
        if provider is not None:
            provider.close()
        else:
            embedding_transport.close()
        if reranker is not None:
            reranker.close()
        if rerank_client is not None:
            rerank_client.close()
        else:
            rerank_transport.close()
        report["temporary_directory_removed"] = temporary_path is not None and not temporary_path.exists()
        report["actual_http_requests"] = {
            "embedding": embedding_transport.count, "rerank": rerank_transport.count,
            "embedding_status_counts": embedding_transport.status_counts,
            "rerank_status_counts": rerank_transport.status_counts,
        }
        report["total_elapsed_ms"] = round((time.monotonic() - started) * 1000)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Send synthetic fixtures to real cloud embedding/rerank models")
    parser.add_argument("--properties", type=Path, default=ROOT / "local.properties")
    parser.add_argument("--timeout", type=float, default=12)
    parser.add_argument("--output", type=Path, default=ROOT / "tools/smoke/artifacts/visual-retrieval-20261028.json")
    args = parser.parse_args()
    if not args.live:
        print("No cloud calls made. Use --live to run the disposable synthetic component probe.")
        return 0
    if not 0 < args.timeout <= 60:
        parser.error("--timeout must be within (0,60]")
    report = run(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"scope": report["scope"], "passed": report["passed"], "error": report["error"],
        "checks_passed": len(report["checks"]), "actual_http_requests": report["actual_http_requests"],
        "total_elapsed_ms": report["total_elapsed_ms"], "temporary_directory_removed": report["temporary_directory_removed"],
        "report": str(args.output)}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
