"""Private export and isolated pipeline contracts, with zero cloud calls."""

from dataclasses import replace
import hashlib
import io
import json
from pathlib import Path

from PIL import Image
import pytest

from app.visual_memory_store import VisualMemoryStore
from .export_store import export_store
from .finalize_records import finalize_records
from .pipeline_adapter import main as pipeline_main, run_pipeline
from .schema import Dataset


def jpeg(colour="red"):
    buffer = io.BytesIO()
    Image.new("RGB", (24, 20), colour).save(buffer, format="JPEG")
    return buffer.getvalue()


def stored(tmp_path, records=1):
    root = tmp_path / "source"
    store = VisualMemoryStore(str(root))
    for index in range(records):
        store.add("chosen-owner", {"record_id": f"source-{index}", "session_id": "session-a",
                  "captured_at_ms": 1000 + index, "object_name": "钥匙", "aliases": [],
                  "location": "书桌", "note": "记一下钥匙放在书桌"}, jpeg())
    store.add("other-owner", {"record_id": "other", "session_id": "session-b",
              "captured_at_ms": 1000, "object_name": "护照", "aliases": [],
              "location": "另一家", "note": "other-owner-private-note"}, jpeg("blue"))
    return root, store


def load_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def test_export_readonly_scoped_and_unlabeled(tmp_path):
    root, store = stored(tmp_path)
    before = (root / "records.sqlite3").read_bytes()
    output = tmp_path / "private"
    receipt = export_store(root, "chosen-owner", output, owner_alias="p001")
    assert (root / "records.sqlite3").read_bytes() == before
    assert receipt["status"] == "exported_pending_annotation"
    assert receipt["source_record_count"] == receipt["exported_record_count"] == 1
    assert receipt["labels_created"] == receipt["queries_created"] == 0
    assert (output / "records.jsonl").read_bytes() == (output / "queries.jsonl").read_bytes() == (output / "gold.jsonl").read_bytes() == b""
    pending = load_lines(output / "records.pending.jsonl")[0]
    assert pending["owner_id"] == "p001"
    assert pending["split"] is None and pending["leakage_group_id"] is None
    assert pending["source"]["session_id"] == "session-a"
    assert "other-owner-private-note" not in (output / "records.pending.jsonl").read_text()
    assert (output.stat().st_mode & 0o777) == 0o700
    assert ((output / pending["image_path"]).stat().st_mode & 0o777) == 0o600
    with pytest.raises(FileExistsError):
        export_store(root, "chosen-owner", output)


def test_empty_owner_export_is_no_data_never_fake_record(tmp_path):
    root, _ = stored(tmp_path, records=0)
    output = tmp_path / "empty"
    receipt = export_store(root, "chosen-owner", output)
    assert receipt["status"] == "no_data"
    assert receipt["source_record_count"] == receipt["exported_record_count"] == 0
    assert (output / "records.pending.jsonl").read_bytes() == b""
    with pytest.raises(ValueError, match="no exported records"):
        finalize_records(output, output / "assignments.template.jsonl")


def test_export_retention_filters_without_pruning_source(tmp_path):
    root, store = stored(tmp_path)
    receipt = export_store(root, "chosen-owner", tmp_path / "expired", retention_days=1, now_seconds=9e9)
    assert receipt["status"] == "no_usable_evidence"
    assert receipt["skipped"][0]["reason"] == "outside_requested_retention"
    assert len(store.candidates("chosen-owner")) == 1


def test_export_skips_checksum_mismatch_and_wont_copy_invalid_jpeg(tmp_path):
    root, store = stored(tmp_path)
    store._path("chosen-owner", "source-0").write_bytes(b"wrong")
    receipt = export_store(root, "chosen-owner", tmp_path / "invalid")
    assert receipt["exported_record_count"] == 0
    assert receipt["skipped"][0]["reason"] == "evidence_unavailable_or_invalid"


def test_export_refuses_image_symlink_outside_source_and_nested_destination(tmp_path):
    root, store = stored(tmp_path)
    path = store._path("chosen-owner", "source-0")
    outside = tmp_path / "private.jpg"
    outside.write_bytes(path.read_bytes())
    path.unlink()
    path.symlink_to(outside)
    receipt = export_store(root, "chosen-owner", tmp_path / "symlink-export")
    assert receipt["skipped"][0]["reason"] == "image_path_outside_store"
    with pytest.raises(ValueError, match="inside the source"):
        export_store(root, "chosen-owner", root / "nested")


def test_explicit_assignments_finalize_records_but_not_queries_or_gold(tmp_path):
    root, _ = stored(tmp_path)
    output = tmp_path / "private"
    export_store(root, "chosen-owner", output)
    assignments = load_lines(output / "assignments.template.jsonl")
    assignments[0].update(split="test", leakage_group_id="household001")
    path = output / "assignments.jsonl"
    path.write_text("".join(json.dumps(row) + "\n" for row in assignments))
    assert finalize_records(output, path) == 1
    records = load_lines(output / "records.jsonl")
    assert records[0]["split"] == "test"
    assert "source" not in records[0] and "annotation_status" not in records[0]
    assert (output / "queries.jsonl").read_bytes() == (output / "gold.jsonl").read_bytes() == b""
    with pytest.raises(ValueError, match="existing records are never overwritten"):
        finalize_records(output, path)


def test_assignment_null_split_is_not_silently_replaced(tmp_path):
    root, _ = stored(tmp_path)
    output = tmp_path / "private"
    export_store(root, "chosen-owner", output)
    with pytest.raises(ValueError):
        finalize_records(output, output / "assignments.template.jsonl")
    assert (output / "records.jsonl").read_bytes() == b""


def media_dataset(tmp_path):
    fixture = Path(__file__).parent / "fixtures/synthetic_contract"
    dataset = Dataset.load(fixture)
    root = tmp_path / "dataset"
    root.mkdir()
    records = []
    # Explicitly synthetic generated media, used only to exercise service wiring.
    for index, record in enumerate(dataset.records):
        raw = jpeg((index * 20, 40, 100))
        path = f"image-{index}.jpg"
        (root / path).write_bytes(raw)
        records.append(record.model_copy(update={"image_path": path, "image_sha256": hashlib.sha256(raw).hexdigest()}))
    dataset = replace(dataset, records=records)
    dataset.validate()
    for name, rows in (("records.jsonl", dataset.records), ("queries.jsonl", dataset.queries), ("gold.jsonl", dataset.gold)):
        (root / name).write_text("".join(json.dumps(row.model_dump(), ensure_ascii=False) + "\n" for row in rows))
    (root / "manifest.json").write_text(dataset.manifest.model_dump_json())
    return root, Dataset.load(root)


def test_keyword_service_adapter_is_scoped_local_and_trace_compatible(tmp_path):
    root, dataset = media_dataset(tmp_path)
    results, traces, details = run_pipeline(dataset, root, tmp_path / "workspace")
    assert len(results) == len(traces) == len(dataset.queries)
    assert details["cloud_enabled"] is False
    assert details["embedding_namespace"] == "evaluation-keyword-only-no-embedding"
    assert details["index_attempts_per_record"] == 0
    assert all(row.error is None for row in results)
    found = {result.query_id: result for result in results}
    assert found["q-key-time"].candidate_record_ids == ["key-old"]
    assert "lamp-other-owner" not in found["q-lamp-alias"].candidate_record_ids
    assert found["q-semantic-synonym"].final_record_ids == ["charger"]


def test_pipeline_cli_defaults_local_and_writes_private_trace(tmp_path):
    root, _ = media_dataset(tmp_path)
    output = tmp_path / "report"
    assert pipeline_main(["--dataset", str(root), "--output", str(output)]) == 0
    report = json.loads((output / "report.json").read_text())
    assert report["adapter"] == "production_pipeline_keyword"
    assert report["adapter_details"]["cloud_enabled"] is False
    assert report["media_integrity"]["verified"] is True
    assert len(load_lines(output / "ranked_results.jsonl")) == 16
    assert ((output / "traces.jsonl").stat().st_mode & 0o777) == 0o600
    assert not list(output.glob("isolated-store-*"))


def test_cloud_requires_opt_in_before_creating_artifacts_or_reading_credentials(tmp_path):
    output = tmp_path / "report"
    with pytest.raises(SystemExit) as caught:
        pipeline_main(["--dataset", str(tmp_path / "missing"), "--output", str(output), "--mode", "cloud"])
    assert caught.value.code == 2
    assert not output.exists()


def test_metadata_only_fixture_cannot_be_mistaken_for_service_image_evaluation(tmp_path):
    fixture = Path(__file__).parent / "fixtures/synthetic_contract"
    with pytest.raises(ValueError, match="requires actual images"):
        run_pipeline(Dataset.load(fixture), fixture, tmp_path / "workspace")
