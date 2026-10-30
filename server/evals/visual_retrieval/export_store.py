"""Read-only owner-scoped export for later human annotation, never auto-gold."""

from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
import time
from urllib.parse import quote

from pydantic import TypeAdapter

from app.visual_memory_store import MAX_JPEG_BYTES, validate_jpeg
from .make_template import create_template
from .schema import Identifier


def _write_private(path: Path, content: str | bytes) -> None:
    # Exclusive creation makes retries safe: no previously exported evidence,
    # labels or annotation work may be silently replaced.
    with path.open("xb") as file:
        path.chmod(0o600)
        file.write(content.encode("utf-8") if isinstance(content, str) else content)


def export_store(store_directory: Path, owner: str, output: Path, *, owner_alias: str | None = None,
                 retention_days: float | None = None, now_seconds: float | None = None) -> dict:
    if not owner or not owner.strip() or len(owner) > 512 or any(char in owner for char in "\x00\r\n"):
        raise ValueError("owner must be an explicitly supplied non-empty identifier, not an API token")
    alias = owner_alias or "owner-" + hashlib.sha256(owner.encode()).hexdigest()[:16]
    TypeAdapter(Identifier).validate_python(alias, strict=True)
    if retention_days is not None and (not 0 < retention_days <= 36500):
        raise ValueError("retention_days must be positive and at most 36500")
    source = store_directory.resolve(strict=True)
    db_path = (source / "records.sqlite3").resolve(strict=True)
    if not source.is_dir() or not db_path.is_file() or not db_path.is_relative_to(source):
        raise ValueError("store database must be a regular file inside store_directory")
    destination = output.absolute()
    if destination.resolve().is_relative_to(source):
        raise ValueError("export directory must not be inside the source store")
    if destination.exists() or destination.is_symlink():
        raise FileExistsError("export directory already exists; select a new snapshot directory")
    # Never instantiate VisualMemoryStore here: __init__ performs cleanup/prune.
    # BEGIN pins the metadata snapshot; concurrent image deletion is reported as
    # skipped evidence, never silently turned into an available gold record.
    uri = "file:" + quote(str(db_path), safe="/") + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True, timeout=5)) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN")
        rows = db.execute("SELECT * FROM records WHERE owner=? ORDER BY captured_at_ms, record_id", (owner,)).fetchall()
    create_template(destination)
    captured = datetime.now(timezone.utc).isoformat()
    now_seconds = time.time() if now_seconds is None else now_seconds
    cutoff = int((now_seconds - retention_days * 86400) * 1000) if retention_days is not None else None
    pending, skipped = [], []
    for row in rows:
        digest = hashlib.sha256((owner + "\0" + row["record_id"]).encode()).hexdigest()[:24]
        record_id, event_id = "r-" + digest, "e-" + digest
        if cutoff is not None and row["recorded_at_ms"] < cutoff:
            skipped.append({"record_id": record_id, "reason": "outside_requested_retention"})
            continue
        # Derive the same owner/id hash path as the store. Never trust a stored
        # image_path to read arbitrary filesystem content.
        relative = Path("images") / hashlib.sha256(owner.encode()).hexdigest() / (hashlib.sha256(row["record_id"].encode()).hexdigest() + ".jpg")
        image_path = (source / relative).resolve()
        if not image_path.is_relative_to(source):
            skipped.append({"record_id": record_id, "reason": "image_path_outside_store"})
            continue
        try:
            with image_path.open("rb") as file:
                raw = file.read(MAX_JPEG_BYTES + 1)
            if len(raw) != row["image_bytes"] or hashlib.sha256(raw).hexdigest() != row["image_sha256"]:
                raise ValueError("integrity")
            validate_jpeg(raw)
            aliases = json.loads(row["aliases"])
            if not isinstance(aliases, list) or any(not isinstance(value, str) for value in aliases):
                raise ValueError("invalid aliases")
        except (OSError, ValueError):
            skipped.append({"record_id": record_id, "reason": "evidence_unavailable_or_invalid"})
            continue
        exported_path = "images/" + record_id + ".jpg"
        _write_private(destination / exported_path, raw)
        pending.append({
            "record_id": record_id, "event_id": event_id, "owner_id": alias,
            "captured_at_ms": row["captured_at_ms"], "object_name": row["object_name"],
            "aliases": aliases, "location": row["location"], "note": row["note"],
            "split": None, "leakage_group_id": None,
            "image_path": exported_path, "image_sha256": row["image_sha256"],
            "source": {"record_id": row["record_id"], "session_id": row["session_id"],
                       "recorded_at_ms": row["recorded_at_ms"]},
            "annotation_status": "pending_group_and_split_review",
        })
    _write_private(destination / "records.pending.jsonl", "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in pending))
    _write_private(destination / "assignments.template.jsonl", "".join(json.dumps({
        "record_id": row["record_id"], "event_id": row["event_id"],
        "split": None, "leakage_group_id": None,
    }, ensure_ascii=False) + "\n" for row in pending))
    status = "no_data" if not rows else ("exported_pending_annotation" if pending else "no_usable_evidence")
    receipt = {"schema_version": 1, "status": status, "exported_at": captured,
               "owner_alias": alias, "source_record_count": len(rows), "exported_record_count": len(pending),
               "skipped_record_count": len(skipped), "skipped": skipped,
               "retention_days_applied": retention_days, "source_access": "sqlite_read_only_no_store_constructor",
               "labels_created": 0, "queries_created": 0, "split_assigned": False,
               "note": "No relevant-event labels, real queries or split assignments were invented. Empty exports are not benchmarks."}
    _write_private(destination / "export_receipt.json", json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    _write_private(destination / "EXPORT_NEXT_STEPS.txt",
                   "records.pending.jsonl 为真实导出待审数据，正式 records/queries/gold 仍为空。\n"
                   "请复核源事件与会话、填写 assignments.template.jsonl 的 event_id/split/leakage_group_id，另存 assignments.jsonl。\n"
                   "运行 python -m evals.visual_retrieval.finalize_records --dataset 此目录 --assignments 此目录/assignments.jsonl。\n"
                   "然后人工采集真实问题、完整标注 gold，更新 manifest，运行 --verify-media。不要根据检索结果倒推标签。\n"
                   "未提供 --retention-days 时仅导出现存数据库行，不推断生产保留期或曾经拍到的内容。\n")
    return receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", type=Path, required=True, help="explicit VisualMemoryStore directory")
    parser.add_argument("--owner", required=True, help="explicit owner identifier, never a Bearer/API token")
    parser.add_argument("--owner-alias", help="optional anonymized owner id used in exported evaluation records")
    parser.add_argument("--output", type=Path, required=True, help="new private snapshot directory; no overwrites")
    parser.add_argument("--retention-days", type=float, help="optional explicit policy; filters metadata without deleting source")
    args = parser.parse_args(argv)
    try:
        receipt = export_store(args.store, args.owner, args.output, owner_alias=args.owner_alias,
                               retention_days=args.retention_days)
    except (OSError, ValueError, sqlite3.Error) as exc:
        parser.error(str(exc))
    print(json.dumps({"status": receipt["status"], "source_record_count": receipt["source_record_count"],
                      "exported_record_count": receipt["exported_record_count"],
                      "skipped_record_count": receipt["skipped_record_count"], "labels_created": 0,
                      "output": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
