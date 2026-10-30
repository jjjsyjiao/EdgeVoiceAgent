"""Evaluate the production service in an isolated store; cloud is opt-in only.

Default keyword mode runs the real metadata/FTS/expansion/fusion/evidence path,
with no embedding implementation and no provider/network calls. All input media
must be explicitly present in the dataset; no placeholder evidence is invented.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import inspect
import json
import os
from pathlib import Path
import tempfile

from app.visual_memory_store import VisualMemoryStore, validate_jpeg
from app.visual_retrieval.service import VisualRetrievalService
from .evaluate import evaluate
from .export_store import _write_private
from .make_template import make_private_directory
from .schema import Dataset, RankedResult


class _KeywordProvider:
    namespace = "evaluation-keyword-only-no-embedding"
    dimension = 1

    def encode_texts(self, texts):
        raise AssertionError("keyword-only adapter must never encode or call a model")

    def encode_images(self, images):
        raise AssertionError("keyword-only adapter must never encode or call a model")


class _NoVectorIndex:
    def status(self, owner, from_ts_ms=None, to_ts_ms=None):
        return {"ready": 0, "pending": 0, "failed": 0}


def _media(dataset: Dataset, directory: Path) -> dict[str, bytes]:
    dataset.verify_media(directory)
    result = {}
    for record in dataset.records:
        if not record.image_path:
            raise ValueError("pipeline evaluation requires actual images for every record; use --adapter metadata for metadata-only fixtures")
        raw = (directory / record.image_path).read_bytes()
        validate_jpeg(raw)
        result[record.record_id] = raw
    for query in dataset.queries:
        if query.current_image_path:
            validate_jpeg((directory / query.current_image_path).read_bytes())
    return result


def _scoped_owner(owner: str, split: str) -> str:
    # The production service has owner isolation, not an evaluation split field.
    # Include split in the isolated store's owner namespace to enforce both.
    return "eval-" + hashlib.sha256((owner + "\0" + split).encode()).hexdigest()


def run_pipeline(dataset: Dataset, dataset_directory: Path, workspace: Path, *,
                 provider=None, reranker=None) -> tuple[list[RankedResult], list[dict], dict]:
    """Supplying a provider is explicit at the caller; default is local only."""
    media = _media(dataset, dataset_directory)
    # Temporary isolated store; never instantiate a user's production directory.
    # Large enough to avoid quota eviction changing the benchmark corpus.
    total_bytes = max(1, sum(map(len, media.values())))
    store = VisualMemoryStore(str(workspace), retention_days=36500,
                              max_total_bytes=total_bytes + 1, max_owner_bytes=total_bytes + 1)
    for record in dataset.records:
        store.add(_scoped_owner(record.owner_id, record.split), {
            "record_id": record.record_id, "session_id": "eval-" + record.event_id,
            "captured_at_ms": record.captured_at_ms, "object_name": record.object_name,
            "aliases": record.aliases, "location": record.location, "note": record.note,
        }, media[record.record_id])
    if provider is None:
        provider, index = _KeywordProvider(), _NoVectorIndex()
        cloud_enabled = False
    else:
        from app.visual_retrieval.index import VisualVectorIndex
        from app.visual_retrieval.worker import VisualIndexWorker
        index = VisualVectorIndex(workspace, provider.namespace, provider.dimension)
        worker = VisualIndexWorker(index, provider, max_attempts=1)
        # One bounded indexing attempt per record, with no background thread or
        # silent retry loop. Failure remains visible in service traces.
        for _ in dataset.records:
            if not worker.run_once():
                break
        cloud_enabled = True
    service = VisualRetrievalService(store, index, provider, reranker)
    results, traces = [], []
    for query in dataset.queries:
        current = (dataset_directory / query.current_image_path).read_bytes() if query.current_image_path else None
        if current is not None:
            validate_jpeg(current)
        intent = {"lookup": "recall", "recall": "recall", "latest": "find_last_location", "compare": "compare"}[query.intent]
        try:
            response, trace = service.search_with_trace(
                _scoped_owner(query.owner_id, query.split), query.text, query.from_ts_ms, query.to_ts_ms,
                limit=3, raw_query=query.text, query_image=current, intent=intent)
            error = "retrieval_unavailable" if response["retrieval"]["status"] == "unavailable" else None
            result = RankedResult(query_id=query.query_id, candidate_record_ids=trace["candidate_record_ids"],
                                  final_record_ids=trace["final_record_ids"], abstained=trace["abstained"], error=error)
            traces.append({"query_id": query.query_id, **trace, "error": error})
        except Exception as exc:
            # Exception text may contain media or raw request information. Keep
            # only a class code and retain the failed query in the denominator.
            code = "pipeline_" + type(exc).__name__.lower()
            result = RankedResult(query_id=query.query_id, candidate_record_ids=[], final_record_ids=[],
                                  abstained=True, error=code)
            traces.append({"query_id": query.query_id, "candidate_record_ids": [], "final_record_ids": [],
                           "abstained": True, "error": code})
        results.append(result)
    statuses = Counter(t.get("retrieval", {}).get("status", "error") for t in traces)
    warnings = Counter(warning for t in traces for warning in t.get("retrieval", {}).get("warnings", []))
    details = {"service_source_sha256": hashlib.sha256(inspect.getsource(VisualRetrievalService).encode()).hexdigest(),
               "cloud_enabled": cloud_enabled, "rerank_enabled": reranker is not None,
               "embedding_namespace": provider.namespace,
               "retrieval_status_counts": dict(statuses), "warning_query_counts": dict(warnings),
               "channel_limit": service.channel_limit, "candidate_limit": service.candidate_limit,
               "rerank_limit": service.rerank_limit, "index_attempts_per_record": 1 if cloud_enabled else 0,
               "scope": "isolated_offline_production_service_no_Android_no_Realtime",
               "notice": "Keyword mode measures metadata/FTS/expansion/fusion only; cloud mode must inspect indexing/degradation statuses before drawing retrieval conclusions."}
    return results, traces, details


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="new private report directory, never overwritten")
    parser.add_argument("--mode", choices=["keyword", "cloud"], default="keyword")
    parser.add_argument("--allow-cloud", action="store_true", help="explicitly allow selected dataset images/text to leave this computer")
    parser.add_argument("--rerank", action="store_true", help="opt-in additional cloud rerank calls")
    parser.add_argument("--api-key-env", default="VISUAL_EMBEDDING_API_KEY", help="environment variable name; never pass the credential itself")
    parser.add_argument("--embedding-model", default="qwen3-vl-embedding")
    parser.add_argument("--embedding-dimension", type=int, default=2560)
    parser.add_argument("--embedding-endpoint", default="https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding")
    parser.add_argument("--rerank-endpoint", default="https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank")
    parser.add_argument("--rerank-model", default="qwen3-vl-rerank")
    parser.add_argument("--cloud-record-limit", type=int, default=50)
    parser.add_argument("--cloud-query-limit", type=int, default=25)
    args = parser.parse_args(argv)
    provider, reranker = None, None
    try:
        if args.mode == "cloud" and not args.allow_cloud:
            raise ValueError("cloud mode requires explicit --allow-cloud; default keyword mode makes no network calls")
        if args.mode != "cloud" and (args.allow_cloud or args.rerank):
            raise ValueError("--allow-cloud/--rerank are only valid together with --mode cloud")
        dataset = Dataset.load(args.dataset)
        _media(dataset, args.dataset)
        if args.output.exists() or args.output.is_symlink():
            raise FileExistsError("output directory exists; choose a new report directory")
        if args.mode == "cloud":
            if not 1 <= args.cloud_record_limit or not 1 <= args.cloud_query_limit:
                raise ValueError("cloud budgets must be positive")
            if len(dataset.records) > args.cloud_record_limit or len(dataset.queries) > args.cloud_query_limit:
                raise ValueError("dataset exceeds explicit cloud record/query budgets; review workload before increasing limits")
            key = os.environ.get(args.api_key_env, "")
            if not key:
                raise ValueError("configured API-key environment variable is empty; no local.properties is read")
            from app.visual_retrieval.providers import DashscopeMultimodalEmbeddingProvider
            provider = DashscopeMultimodalEmbeddingProvider(key, args.embedding_endpoint,
                        args.embedding_model, args.embedding_dimension)
            if args.rerank:
                from app.visual_retrieval.rerank import DashscopeReranker
                reranker = DashscopeReranker(key, args.rerank_endpoint, args.rerank_model)
        make_private_directory(args.output)
        with tempfile.TemporaryDirectory(prefix="isolated-store-", dir=args.output) as temporary:
            results, traces, details = run_pipeline(dataset, args.dataset, Path(temporary), provider=provider, reranker=reranker)
        report = evaluate(dataset, results, "production_pipeline_" + args.mode)
        report["adapter_details"] = details
        report["media_integrity"] = {"verified": True, "decodable_jpeg": True}
        _write_private(args.output / "ranked_results.jsonl", "".join(json.dumps(r.model_dump(), ensure_ascii=False) + "\n" for r in results))
        _write_private(args.output / "traces.jsonl", "".join(json.dumps(trace, ensure_ascii=False) + "\n" for trace in traces))
        _write_private(args.output / "report.json", json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        _write_private(args.output / ".gitignore", "*\n")
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    finally:
        if provider is not None:
            provider.close()
        if reranker is not None:
            reranker.close()
    print(json.dumps({"mode": args.mode, "dataset_kind": dataset.manifest.kind, "queries": len(results),
                      "constraints_passed": report["constraints"]["passed"], "output": str(args.output)}, ensure_ascii=False))
    return 0 if report["constraints"]["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
