"""Offline evaluation contracts; synthetic fixtures never measure real recall."""

from dataclasses import replace
import hashlib
import json
from pathlib import Path

from pydantic import ValidationError
import pytest

from evals.visual_retrieval.evaluate import evaluate, proportion
from evals.visual_retrieval.make_template import create_template
from evals.visual_retrieval.run import main, metadata_baseline, write_jsonl
from evals.visual_retrieval.schema import Dataset, Gold, Manifest, Query, RankedResult, Record, read_jsonl

FIXTURE = Path(__file__).resolve().parents[1] / "evals/visual_retrieval/fixtures/synthetic_contract"


@pytest.fixture
def dataset():
    return Dataset.load(FIXTURE)


def change_result(results, query_id, candidates, final=None, error=None):
    if final is None:
        final = candidates[:3]
    updated = RankedResult(query_id=query_id, candidate_record_ids=candidates, final_record_ids=final,
                           abstained=not final, error=error)
    return [updated if result.query_id == query_id else result for result in results]


def test_fixture_is_explicitly_synthetic_and_report_contains_per_query(dataset):
    report = evaluate(dataset, metadata_baseline(dataset))
    assert dataset.manifest.kind == report["dataset"]["kind"] == "synthetic_contract"
    assert "SYNTHETIC CONTRACT" in report["warnings"][0]
    assert len(report["per_query"]) == report["summary"]["query_count"] == 16
    assert report["summary"]["available_query_count"] == 11
    assert report["summary"]["metrics"]["candidate_hit_at_50"]["numerator"] == 9
    assert report["constraints"]["passed"]
    assert report["groups"]["tag"]["synonym_gap"]["metrics"]["candidate_hit_at_50"]["estimate"] == 0
    assert len(dataset.fingerprint) == 64


def test_event_recall_deduplicates_frames_and_does_not_truncate_gold(dataset):
    results = change_result(metadata_baseline(dataset), "q-key-name", ["key-new-wide", "key-new-close"])
    report = evaluate(dataset, results, ks=(1, 2, 50))
    row = next(row for row in report["per_query"] if row["query_id"] == "q-key-name")
    assert row["relevant_event_count"] == 2
    assert row["candidate"]["1"]["event_recall"] == .5
    assert row["candidate"]["2"]["distinct_valid_event_count"] == 1
    assert row["candidate"]["2"]["event_recall"] == .5
    assert row["candidate"]["50"]["event_recall"] == .5
    assert report["summary"]["metrics"]["event_recall_at_1"]["confidence_interval"] is None


def test_unknown_coverage_is_neither_positive_nor_negative(dataset):
    results = metadata_baseline(dataset)
    with_return = evaluate(dataset, results)
    no_return = evaluate(dataset, change_result(results, "q-unknown", []))
    for report in (with_return, no_return):
        assert report["summary"]["known_no_answer_query_count"] == 4
        assert report["summary"]["unknown_coverage_query_count"] == 1
        assert report["coverage"]["audited_capture_availability"]["denominator"] == 13
    for name in ("candidate_hit_at_50", "final_hit_at_3", "no_answer_false_positive_rate"):
        assert with_return["summary"]["metrics"][name] == no_return["summary"]["metrics"][name]


def test_false_positive_counts_returned_irrelevant_evidence(dataset):
    results = change_result(metadata_baseline(dataset), "q-negative", ["lamp"])
    metric = evaluate(dataset, results)["summary"]["metrics"]["no_answer_false_positive_rate"]
    assert metric["numerator"] == 1 and metric["denominator"] == 4 and metric["estimate"] == .25


def test_latest_event_requires_top_one_and_not_any_top_three(dataset):
    results = change_result(metadata_baseline(dataset), "q-key-latest", ["key-old", "key-new-wide"])
    metrics = evaluate(dataset, results)["groups"]["intent"]["latest"]["metrics"]
    assert metrics["final_hit_at_3"]["estimate"] == 1
    assert metrics["latest_event_hit_at_1"]["estimate"] == 0


@pytest.mark.parametrize("record_id,query_id,reason", [
    ("missing-id", "q-key-name", "unknown_record"),
    ("lamp-other-owner", "q-lamp-alias", "owner_violation"),
    ("cup-dev", "q-lamp-alias", "split_violation"),
    ("key-new-wide", "q-key-time", "time_violation"),
])
def test_scope_violations_fail_constraints_and_cannot_count_as_hits(dataset, record_id, query_id, reason):
    result = change_result(metadata_baseline(dataset), query_id, [record_id])
    report = evaluate(dataset, result)
    assert not report["constraints"]["passed"]
    assert report["constraints"]["counts_by_reason"][reason] == 1
    row = next(r for r in report["per_query"] if r["query_id"] == query_id)
    assert row["final_relevant_events_found"] == 0


def test_invalid_candidate_does_not_promote_following_rank(dataset):
    result = change_result(metadata_baseline(dataset), "q-key-name", ["missing-id", "key-new-wide"])
    row = next(r for r in evaluate(dataset, result, ks=(1, 2))["per_query"] if r["query_id"] == "q-key-name")
    assert row["candidate"]["1"]["hit"] is False
    assert row["candidate"]["2"]["hit"] is True


def test_errors_are_reported_and_not_removed_from_positive_denominator(dataset):
    result = change_result(metadata_baseline(dataset), "q-key-name", [], error="embedding_timeout")
    metrics = evaluate(dataset, result)["summary"]["metrics"]
    assert metrics["error_rate"]["numerator"] == 1
    assert metrics["candidate_hit_at_50"]["denominator"] == 11
    assert metrics["final_hit_at_3"]["numerator"] == 8


def test_wilson_interval_is_bounded_and_exposes_small_sample_uncertainty():
    interval = proportion(1, 1)["wilson_95"]
    assert interval["lower"] == pytest.approx(.2065493)
    assert interval["upper"] == 1
    assert proportion(0, 0)["estimate"] is None
    assert proportion(0, 0)["wilson_95"] is None
    assert proportion(0, 10)["wilson_95"]["upper"] > 0
    with pytest.raises(ValueError):
        proportion(2, 1)


@pytest.mark.parametrize("changes", [
    {"unexpected": "field"}, {"captured_at_ms": "123"}, {"captured_at_ms": True},
    {"image_path": "../secret.jpg"}, {"image_path": "/private/photo.jpg"},
    {"image_path": "https://example.test/photo.jpg"}, {"image_sha256": "invalid"},
])
def test_record_schema_rejects_implicit_coercion_and_unsafe_paths(dataset, changes):
    with pytest.raises(ValidationError):
        Record.model_validate(dataset.records[0].model_dump() | changes)


@pytest.mark.parametrize("changes", [
    {"from_ts_ms": 10, "to_ts_ms": 10}, {"tags": ["same", "same"]},
    {"current_image_path": "../photo.jpg"}, {"intent": "maybe"},
])
def test_query_schema_is_strict(dataset, changes):
    with pytest.raises(ValidationError):
        Query.model_validate(dataset.queries[0].model_dump() | changes)


@pytest.mark.parametrize("changes", [
    {"candidate_record_ids": ["a", "a"]}, {"candidate_record_ids": [], "final_record_ids": ["a"]},
    {"abstained": True}, {"final_record_ids": ["a", "a"]}, {"error": "timeout"},
])
def test_result_schema_rejects_inconsistent_outputs(changes):
    base = dict(query_id="q", candidate_record_ids=["a"], final_record_ids=["a"], abstained=False)
    with pytest.raises(ValidationError):
        RankedResult.model_validate(base | changes)


def test_gold_cannot_claim_relevance_for_unknown_coverage(dataset):
    with pytest.raises(ValidationError):
        Gold.model_validate(dataset.gold[0].model_dump() | {"coverage": "unknown"})


def test_missing_results_or_gold_cannot_silently_reduce_denominator(dataset):
    with pytest.raises(ValueError, match="cover all queries"):
        evaluate(dataset, metadata_baseline(dataset)[:-1])
    with pytest.raises(ValueError, match="exactly one gold"):
        replace(dataset, gold=dataset.gold[:-1]).validate()


def test_duplicate_ids_rejected(dataset):
    with pytest.raises(ValueError, match="duplicate record_id"):
        replace(dataset, records=dataset.records + [dataset.records[0]]).validate()
    results = metadata_baseline(dataset)
    with pytest.raises(ValueError, match="duplicate query_id"):
        evaluate(dataset, results + [results[0]])


@pytest.mark.parametrize("kind", ["group", "image", "query_family"])
def test_split_leakage_detected(dataset, kind):
    records, queries = list(dataset.records), list(dataset.queries)
    if kind == "group":
        records[-1] = records[-1].model_copy(update={"leakage_group_id": records[0].leakage_group_id})
    elif kind == "image":
        records[0] = records[0].model_copy(update={"image_sha256": "a" * 64})
        queries[-1] = queries[-1].model_copy(update={"current_image_sha256": "a" * 64})
    else:
        queries[-1] = queries[-1].model_copy(update={"query_family_id": queries[0].query_family_id})
    with pytest.raises(ValueError, match="split leakage"):
        replace(dataset, records=records, queries=queries).validate()


def test_gold_cannot_refer_to_another_owner_or_time(dataset):
    gold = list(dataset.gold)
    gold[0] = gold[0].model_copy(update={"relevant_event_ids": ["other-lamp-event"]})
    with pytest.raises(ValueError, match="outside owner/split/time"):
        replace(dataset, gold=gold).validate()


def test_latest_label_must_really_be_latest(dataset):
    gold = [g.model_copy(update={"latest_event_id": "key-event-old"}) if g.query_id == "q-key-latest" else g
            for g in dataset.gold]
    with pytest.raises(ValueError, match="not the newest"):
        replace(dataset, gold=gold).validate()


def test_jsonl_errors_include_source_line(tmp_path):
    path = tmp_path / "broken.jsonl"
    path.write_text('\n{"unexpected":true}\n', encoding="utf-8")
    with pytest.raises(ValueError, match="broken.jsonl:2"):
        read_jsonl(path, RankedResult)


def test_cli_metadata_and_external_adapter_have_identical_metrics(tmp_path):
    baseline, external, ranked = tmp_path / "baseline.json", tmp_path / "external.json", tmp_path / "ranked.jsonl"
    assert main(["--dataset", str(FIXTURE), "--adapter", "metadata", "--output", str(baseline),
                 "--save-ranked-results", str(ranked)]) == 0
    assert main(["--dataset", str(FIXTURE), "--ranked-results", str(ranked), "--output", str(external)]) == 0
    a, b = [json.loads(path.read_text()) for path in (baseline, external)]
    assert a["summary"] == b["summary"]
    assert a["per_query"] == b["per_query"]
    assert a["dataset"]["sha256"] == b["dataset"]["sha256"]


def test_cli_returns_failure_for_privacy_constraint_violation(dataset, tmp_path):
    result = change_result(metadata_baseline(dataset), "q-lamp-alias", ["lamp-other-owner"])
    path = tmp_path / "ranked.jsonl"
    write_jsonl(path, result)
    assert main(["--dataset", str(FIXTURE), "--ranked-results", str(path),
                 "--output", str(tmp_path / "report.json")]) == 2


def test_baseline_filters_owner_time_split_and_keeps_stable_rank(dataset):
    results = {r.query_id: r for r in metadata_baseline(dataset)}
    assert results["q-key-time"].candidate_record_ids == ["key-old"]
    assert results["q-lamp-alias"].candidate_record_ids == ["lamp"]
    assert results["q-key-name"].candidate_record_ids == ["key-new-close", "key-new-wide", "key-old"]


def test_all_abstention_is_not_mistaken_for_good_recall(dataset):
    results = [RankedResult(query_id=q.query_id, candidate_record_ids=[], final_record_ids=[], abstained=True)
               for q in dataset.queries]
    metrics = evaluate(dataset, results)["summary"]["metrics"]
    assert metrics["no_answer_false_positive_rate"]["estimate"] == 0
    assert metrics["candidate_hit_at_50"]["estimate"] == 0
    assert metrics["abstention_rate"]["estimate"] == 1


def test_empty_real_template_is_private_never_overwrites_and_is_not_a_benchmark(tmp_path):
    target = tmp_path / "private-data"
    create_template(target)
    assert (target.stat().st_mode & 0o777) == 0o700
    assert ((target / "records.jsonl").stat().st_mode & 0o777) == 0o600
    assert (target / "records.jsonl").read_text() == ""
    with pytest.raises(ValueError, match="empty real-data template"):
        Dataset.load(target)
    with pytest.raises(FileExistsError):
        create_template(target)


def test_real_label_cannot_be_added_to_metadata_only_fixture(dataset):
    manifest = Manifest.model_validate(dataset.manifest.model_dump() | {"kind": "real_labeled"})
    with pytest.raises(ValueError, match="real_labeled visual records require"):
        replace(dataset, manifest=manifest).validate()


def test_declared_media_verification_checks_hash_and_no_other_files(dataset, tmp_path):
    contents = b"evaluation-integrity-test-only"
    (tmp_path / "image.jpg").write_bytes(contents)
    records = list(dataset.records)
    records[0] = records[0].model_copy(update={"image_path": "image.jpg", "image_sha256": hashlib.sha256(contents).hexdigest()})
    with_media = replace(dataset, records=records)
    assert with_media.verify_media(tmp_path)["file_count"] == 1
    (tmp_path / "image.jpg").write_bytes(b"changed")
    with pytest.raises(ValueError, match="checksum mismatch"):
        with_media.verify_media(tmp_path)


def test_declared_media_cannot_read_through_external_symlink(dataset, tmp_path):
    root = tmp_path / "dataset"
    root.mkdir()
    outside = tmp_path / "outside.jpg"
    outside.write_bytes(b"private")
    (root / "image.jpg").symlink_to(outside)
    records = list(dataset.records)
    records[0] = records[0].model_copy(update={"image_path": "image.jpg", "image_sha256": "a" * 64})
    with pytest.raises(ValueError, match="symlink resolves outside"):
        replace(dataset, records=records).verify_media(root)
