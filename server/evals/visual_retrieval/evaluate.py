"""Metrics use observation events, never image count as a recall denominator."""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
import math

from .schema import Dataset, RankedResult, in_scope

DEFAULT_K = (1, 3, 10, 50)


def proportion(numerator: int, denominator: int) -> dict:
    """95% Wilson interval for query-level Bernoulli counts, including 0/n."""
    if denominator == 0:
        return {"numerator": numerator, "denominator": 0, "estimate": None, "wilson_95": None}
    if not 0 <= numerator <= denominator:
        raise ValueError("proportion requires 0 <= numerator <= denominator")
    z = 1.959963984540054
    rate = numerator / denominator
    divisor = 1 + z * z / denominator
    centre = (rate + z * z / (2 * denominator)) / divisor
    radius = z * math.sqrt(rate * (1 - rate) / denominator + z * z / (4 * denominator ** 2)) / divisor
    return {"numerator": numerator, "denominator": denominator, "estimate": rate,
            "wilson_95": {"lower": max(0.0, centre - radius), "upper": min(1.0, centre + radius)}}


def _aggregate(rows: list[dict], ks: tuple[int, ...]) -> dict:
    answerable = [r for r in rows if r["coverage"] == "available"]
    negatives = [r for r in rows if r["coverage"] in ("verified_absent", "not_captured", "expired")]
    latest = [r for r in answerable if r["intent"] == "latest"]
    metrics = {}
    for k in ks:
        key = str(k)
        hits = [r["candidate"][key]["relevant_events_found"] for r in answerable]
        gold_counts = [r["relevant_event_count"] for r in answerable]
        metrics[f"candidate_hit_at_{k}"] = proportion(sum(n > 0 for n in hits), len(answerable))
        metrics[f"event_recall_at_{k}"] = {
            "query_count": len(answerable),
            "macro_estimate": sum(n / d for n, d in zip(hits, gold_counts)) / len(answerable) if answerable else None,
            "relevant_events_found": sum(hits),
            "relevant_events_total": sum(gold_counts),
            "micro_estimate": sum(hits) / sum(gold_counts) if gold_counts else None,
            "confidence_interval": None,
            "interval_note": "Event recall observations are correlated and fractional; no binomial Wilson interval is claimed.",
        }
    metrics["final_hit_at_3"] = proportion(sum(r["final_relevant_events_found"] > 0 for r in answerable), len(answerable))
    metrics["latest_event_hit_at_1"] = proportion(sum(r["latest_hit_at_1"] for r in latest), len(latest))
    metrics["no_answer_false_positive_rate"] = proportion(sum(not r["abstained"] for r in negatives), len(negatives))
    metrics["error_rate"] = proportion(sum(r["error"] is not None for r in rows), len(rows))
    metrics["abstention_rate"] = proportion(sum(r["abstained"] for r in rows), len(rows))
    return {"query_count": len(rows), "available_query_count": len(answerable),
            "known_no_answer_query_count": len(negatives),
            "unknown_coverage_query_count": sum(r["coverage"] == "unknown" for r in rows),
            "metrics": metrics}


def evaluate(dataset: Dataset, results: list[RankedResult], adapter: str = "external_ranked_results",
             ks: tuple[int, ...] = DEFAULT_K) -> dict:
    if not ks or any(type(k) is not int or k <= 0 for k in ks) or len(set(ks)) != len(ks):
        raise ValueError("K values must be unique positive integers")
    dataset.validate_results(results)
    records = {r.record_id: r for r in dataset.records}
    labels = {g.query_id: g for g in dataset.gold}
    ranked = {r.query_id: r for r in results}
    rows, all_violations = [], []
    for query in dataset.queries:
        result, gold = ranked[query.query_id], labels[query.query_id]
        valid = set()
        violations = []
        for rank, record_id in enumerate(result.candidate_record_ids, 1):
            record = records.get(record_id)
            reasons = []
            if record is None:
                reasons.append("unknown_record")
            else:
                if record.owner_id != query.owner_id:
                    reasons.append("owner_violation")
                if record.split != query.split:
                    reasons.append("split_violation")
                if ((query.from_ts_ms is not None and record.captured_at_ms < query.from_ts_ms)
                        or (query.to_ts_ms is not None and record.captured_at_ms >= query.to_ts_ms)):
                    reasons.append("time_violation")
                if in_scope(record, query):
                    valid.add(record_id)
            for reason in reasons:
                violations.append({"query_id": query.query_id, "record_id": record_id,
                                   "candidate_rank": rank, "in_final": record_id in result.final_record_ids,
                                   "reason": reason})
        relevant = set(gold.relevant_event_ids)

        def event_ids(ids: list[str]) -> set[str]:
            return {records[record_id].event_id for record_id in ids if record_id in valid}

        candidate = {}
        for k in ks:
            # Invalid rows consume their original rank; removing them must not
            # move a legitimate hit from rank 51 into the reported top 50.
            events = event_ids(result.candidate_record_ids[:k])
            hits = relevant.intersection(events)
            candidate[str(k)] = {"returned_record_count": min(k, len(result.candidate_record_ids)),
                                 "distinct_valid_event_count": len(events),
                                 "relevant_events_found": len(hits),
                                 "hit": bool(hits) if gold.coverage == "available" else None,
                                 "event_recall": len(hits) / len(relevant) if relevant else None}
        final_hits = relevant.intersection(event_ids(result.final_record_ids[:3]))
        first = result.final_record_ids[0] if result.final_record_ids else None
        row = {"query_id": query.query_id, "split": query.split, "intent": query.intent, "tags": query.tags,
               "coverage": gold.coverage, "relevant_event_count": len(relevant),
               "candidate_record_ids": result.candidate_record_ids, "final_record_ids": result.final_record_ids,
               "candidate": candidate, "final_relevant_events_found": len(final_hits),
               "final_hit_at_3": bool(final_hits) if relevant else None,
               "latest_hit_at_1": bool(first in valid and records[first].event_id == gold.latest_event_id)
                                  if gold.latest_event_id else None,
               "abstained": result.abstained, "error": result.error, "violations": violations}
        rows.append(row)
        all_violations.extend(violations)
    coverage_counts = Counter(g.coverage for g in dataset.gold)
    capture_known = sum(coverage_counts[k] for k in ("available", "not_captured", "expired"))
    groups = {}
    for field in ("intent", "split"):
        groups[field] = {value: _aggregate([r for r in rows if r[field] == value], ks)
                         for value in sorted({r[field] for r in rows})}
    groups["tag"] = {value: _aggregate([r for r in rows if value in r["tags"]], ks)
                     for value in sorted({tag for r in rows for tag in r["tags"]})}
    warnings = ["Retrieval metrics condition on labeled retained evidence; they are not end-to-end daily recall or final answer correctness.",
                "Wilson intervals assume independent queries; correlated families/users reduce effective sample size. Real deployment decisions need grouped resampling."]
    if dataset.manifest.kind == "synthetic_contract":
        warnings.insert(0, "SYNTHETIC CONTRACT FIXTURE ONLY: not real user data and not evidence of high semantic or multimodal recall.")
    return {"schema_version": 1, "generated_at": datetime.now(timezone.utc).isoformat(),
            "dataset": {"dataset_id": dataset.manifest.dataset_id, "kind": dataset.manifest.kind,
                        "sha256": dataset.fingerprint, "records": len(dataset.records),
                        "events": len({r.event_id for r in dataset.records}), "queries": len(dataset.queries)},
            "adapter": adapter, "warnings": warnings,
            "summary": _aggregate(rows, ks),
            "coverage": {"counts": dict(sorted(coverage_counts.items())),
                         "audited_capture_availability": proportion(coverage_counts["available"], capture_known),
                         "availability_note": "Denominator includes available/not_captured/expired only; verified absence and unknown coverage are excluded. This is labeled corpus coverage, not continuous recording coverage."},
            "constraints": {"passed": not all_violations, "violation_count": len(all_violations),
                            "counts_by_reason": dict(Counter(v["reason"] for v in all_violations)),
                            "violations": all_violations},
            "groups": groups, "per_query": rows}
