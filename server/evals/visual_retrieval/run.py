"""Run from server/: python -m evals.visual_retrieval.run --help."""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
from pathlib import Path

from .evaluate import evaluate
from .schema import Dataset, RankedResult, in_scope, read_jsonl


def metadata_baseline(dataset: Dataset, candidate_limit: int = 50) -> list[RankedResult]:
    """Frozen adapter contract: current production metadata_score, no model calls.

    Owner/time/split filters and score/time/id ordering mirror the metadata store.
    Candidate ranking is exposed to evaluate recall before the final top 3.
    This does not exercise JPEG decoding, network, retention or Android behavior.
    """
    from app.visual_memory_store import metadata_score

    if candidate_limit < 3:
        raise ValueError("candidate_limit must be at least 3")
    results = []
    for query in dataset.queries:
        scored = [(metadata_score(query.text, record.model_dump()), record)
                  for record in dataset.records if in_scope(record, query)]
        scored = sorted((pair for pair in scored if pair[0] > 0),
                        key=lambda pair: (-pair[0], -pair[1].captured_at_ms, pair[1].record_id))
        candidates = [record.record_id for _, record in scored[:candidate_limit]]
        final = candidates[:3]
        results.append(RankedResult(query_id=query.query_id, candidate_record_ids=candidates,
                                    final_record_ids=final, abstained=not final))
    return results


def write_jsonl(path: Path, rows: list[RankedResult]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row.model_dump(), ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True, help="directory with manifest/records/queries/gold")
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--adapter", choices=["metadata"], help="local no-model baseline")
    inputs.add_argument("--ranked-results", type=Path, help="strict RankedResult JSONL from any retrieval adapter")
    parser.add_argument("--output", type=Path, required=True, help="full JSON report (summary, groups and per_query)")
    parser.add_argument("--save-ranked-results", type=Path)
    parser.add_argument("--candidate-limit", type=int, default=50)
    parser.add_argument("--k", type=int, nargs="+", default=[1, 3, 10, 50])
    parser.add_argument("--verify-media", action="store_true", help="verify only explicitly declared media files and hashes")
    args = parser.parse_args(argv)
    try:
        dataset = Dataset.load(args.dataset)
        if args.adapter:
            results = metadata_baseline(dataset, args.candidate_limit)
            adapter = "production_metadata_score"
        else:
            results = read_jsonl(args.ranked_results, RankedResult)
            adapter = "external_ranked_results"
        report = evaluate(dataset, results, adapter, tuple(args.k))
        report["media_integrity"] = dataset.verify_media(args.dataset) if args.verify_media else {
            "verified": False, "note": "Media was not opened; use --verify-media with an explicitly selected dataset directory."}
        if args.adapter:
            from app.visual_memory_store import metadata_score
            report["adapter_details"] = {"candidate_limit": args.candidate_limit,
                                         "metadata_score_source_sha256": hashlib.sha256(inspect.getsource(metadata_score).encode()).hexdigest(),
                                         "models_called": [], "evaluates_image_content": False}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if args.save_ranked_results:
            write_jsonl(args.save_ranked_results, results)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps({"report": str(args.output), "kind": dataset.manifest.kind,
                      "queries": len(dataset.queries), "constraints_passed": report["constraints"]["passed"],
                      "notice": report["warnings"][0]}, ensure_ascii=False))
    return 0 if report["constraints"]["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
