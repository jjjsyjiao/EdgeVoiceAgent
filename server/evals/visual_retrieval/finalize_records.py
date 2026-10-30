"""Apply explicit human event/split/group assignments to an exported snapshot."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .export_store import _write_private
from .schema import Identifier, Record, Split, StrictModel, read_jsonl


class Assignment(StrictModel):
    record_id: Identifier
    event_id: Identifier
    split: Split
    leakage_group_id: Identifier


def finalize_records(directory: Path, assignments_path: Path) -> int:
    root = directory.resolve(strict=True)
    target = root / "records.jsonl"
    if target.is_symlink() or not target.is_file() or target.read_bytes():
        raise ValueError("records.jsonl must be the empty regular file from the private template; existing records are never overwritten")
    source = root / "records.pending.jsonl"
    if source.is_symlink() or not source.is_file():
        raise ValueError("records.pending.jsonl must be the regular export file")
    pending = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not pending:
        raise ValueError("no exported records to finalize; save real photos in the App first")
    assigned = read_jsonl(assignments_path, Assignment)
    assignments = {row.record_id: row for row in assigned}
    if len(assignments) != len(assigned) or set(assignments) != {row["record_id"] for row in pending}:
        raise ValueError("each exported record requires exactly one explicit assignment")
    records = []
    groups = {}
    for row in pending:
        values = {key: value for key, value in row.items() if key not in ("source", "annotation_status")}
        values.update(assignments[row["record_id"]].model_dump())
        record = Record.model_validate(values)
        for key in (("group", record.leakage_group_id), ("image", record.image_sha256), ("event", record.event_id)):
            if key in groups and groups[key] != record.split:
                raise ValueError("explicit assignments create split leakage")
            groups[key] = record.split
        image_path = (root / record.image_path).resolve(strict=True)
        if not image_path.is_relative_to(root):
            raise ValueError("declared media resolves outside dataset directory")
        records.append(record)
    # Preserve the exact unannotated export. Write reviewed records to a new
    # exclusive file, then atomically replace only the known empty template.
    # Another human writing the template concurrently must make this step fail.
    output = root / "records.reviewed.jsonl"
    _write_private(output, "".join(json.dumps(row.model_dump(), ensure_ascii=False) + "\n" for row in records))
    if target.read_bytes() or target.is_symlink():
        raise ValueError("records.jsonl changed during review; reviewed file retained, existing work not overwritten")
    output.replace(target)
    return len(records)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--assignments", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        count = finalize_records(args.dataset, args.assignments)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(json.dumps({"status": "records_reviewed_queries_and_gold_still_required", "record_count": count}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
