"""Strict on-disk evaluation contracts (schema version 1).

Only identifiers, labels and optional relative media paths are read.  Evaluation
does not enumerate the user's production memory store or upload any media.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Identifier = Annotated[str, Field(min_length=1, max_length=160, pattern=r"^[A-Za-z0-9_.:-]+$")]
ShortText = Annotated[str, Field(max_length=2048)]
Split = Literal["train", "dev", "test"]
Sha256 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Manifest(StrictModel):
    schema_version: Literal[1]
    dataset_id: Identifier
    kind: Literal["synthetic_contract", "real_labeled"]
    description: Annotated[str, Field(min_length=1, max_length=4096)]
    annotation_protocol: Annotated[str, Field(min_length=1, max_length=4096)]


def _relative_media_path(value: str | None) -> str | None:
    if value is None:
        return value
    path = PurePosixPath(value)
    if (not value or path.is_absolute() or ".." in path.parts or "\\" in value
            or ":" in value or "\x00" in value):
        raise ValueError("media path must be relative to the dataset directory, without parent traversal or URL")
    return value


class Record(StrictModel):
    record_id: Identifier
    event_id: Identifier
    owner_id: Identifier
    captured_at_ms: Annotated[int, Field(ge=0)]
    object_name: Annotated[str, Field(max_length=160)]
    aliases: Annotated[list[Annotated[str, Field(min_length=1, max_length=160)]], Field(max_length=32)]
    location: ShortText
    note: ShortText
    split: Split
    leakage_group_id: Identifier
    image_path: Annotated[str, Field(max_length=1024)] | None = None
    image_sha256: Sha256 | None = None

    _media_path = field_validator("image_path")(_relative_media_path)

    @model_validator(mode="after")
    def media_has_hash(self):
        if self.image_path is not None and self.image_sha256 is None:
            raise ValueError("image_path requires image_sha256 for integrity and split leakage checks")
        return self


class Query(StrictModel):
    query_id: Identifier
    owner_id: Identifier
    text: Annotated[str, Field(min_length=1, max_length=2048)]
    intent: Literal["lookup", "latest", "compare", "recall"]
    split: Split
    leakage_group_id: Identifier
    query_family_id: Identifier
    tags: Annotated[list[Identifier], Field(min_length=1, max_length=32)]
    from_ts_ms: Annotated[int, Field(ge=0)] | None = None
    to_ts_ms: Annotated[int, Field(ge=0)] | None = None
    current_image_path: Annotated[str, Field(max_length=1024)] | None = None
    current_image_sha256: Sha256 | None = None

    _media_path = field_validator("current_image_path")(_relative_media_path)

    @model_validator(mode="after")
    def valid_range(self):
        if self.from_ts_ms is not None and self.to_ts_ms is not None and self.from_ts_ms >= self.to_ts_ms:
            raise ValueError("time range must have from_ts_ms < to_ts_ms")
        if len(set(self.tags)) != len(self.tags):
            raise ValueError("query tags must be unique")
        if self.current_image_path is not None and self.current_image_sha256 is None:
            raise ValueError("current_image_path requires current_image_sha256")
        return self


class Gold(StrictModel):
    query_id: Identifier
    # Unknown coverage is never silently scored as a retrieval miss or a negative.
    coverage: Literal["available", "verified_absent", "not_captured", "expired", "unknown"]
    relevant_event_ids: list[Identifier]
    latest_event_id: Identifier | None = None
    annotation_note: Annotated[str, Field(min_length=1, max_length=4096)]

    @model_validator(mode="after")
    def consistent_labels(self):
        if len(set(self.relevant_event_ids)) != len(self.relevant_event_ids):
            raise ValueError("relevant_event_ids must be unique")
        if (self.coverage == "available") != bool(self.relevant_event_ids):
            raise ValueError("only available coverage must contain relevant events")
        if self.latest_event_id is not None and self.latest_event_id not in self.relevant_event_ids:
            raise ValueError("latest_event_id must be a relevant event")
        return self


class RankedResult(StrictModel):
    query_id: Identifier
    candidate_record_ids: list[Identifier]
    final_record_ids: Annotated[list[Identifier], Field(max_length=3)]
    abstained: bool
    error: Annotated[str, Field(max_length=1024)] | None = None

    @model_validator(mode="after")
    def consistent_ranking(self):
        for name in ("candidate_record_ids", "final_record_ids"):
            values = getattr(self, name)
            if len(values) != len(set(values)):
                raise ValueError(f"{name} must not repeat record ids")
        if not set(self.final_record_ids).issubset(self.candidate_record_ids):
            raise ValueError("final records must occur in candidate_record_ids")
        if self.abstained != (len(self.final_record_ids) == 0):
            raise ValueError("abstained must be true exactly when final_record_ids is empty")
        if self.error is not None and self.final_record_ids:
            raise ValueError("failed queries cannot claim a final answer")
        return self


def read_jsonl(path: Path, model: type[StrictModel]) -> list:
    result = []
    with path.open(encoding="utf-8") as file:
        for number, line in enumerate(file, 1):
            if not line.strip():
                continue
            try:
                result.append(model.model_validate(json.loads(line)))
            except (ValueError, TypeError) as exc:
                raise ValueError(f"{path.name}:{number}: {exc}") from exc
    return result


def _unique(rows: list, field: str) -> dict:
    result = {}
    for row in rows:
        key = getattr(row, field)
        if key in result:
            raise ValueError(f"duplicate {field}: {key}")
        result[key] = row
    return result


def in_scope(record: Record, query: Query) -> bool:
    return (record.owner_id == query.owner_id and record.split == query.split
            and (query.from_ts_ms is None or record.captured_at_ms >= query.from_ts_ms)
            and (query.to_ts_ms is None or record.captured_at_ms < query.to_ts_ms))


@dataclass(frozen=True)
class Dataset:
    manifest: Manifest
    records: list[Record]
    queries: list[Query]
    gold: list[Gold]
    fingerprint: str

    @classmethod
    def load(cls, directory: str | Path) -> "Dataset":
        directory = Path(directory)
        paths = [directory / name for name in ("manifest.json", "records.jsonl", "queries.jsonl", "gold.jsonl")]
        manifest = Manifest.model_validate(json.loads(paths[0].read_text(encoding="utf-8")))
        records = read_jsonl(paths[1], Record)
        queries = read_jsonl(paths[2], Query)
        gold = read_jsonl(paths[3], Gold)
        digest = hashlib.sha256()
        for path in paths:
            digest.update(path.name.encode())
            digest.update(b"\0")
            digest.update(path.read_bytes())
        dataset = cls(manifest, records, queries, gold, digest.hexdigest())
        dataset.validate()
        return dataset

    def validate(self) -> None:
        if not self.records or not self.queries:
            raise ValueError("dataset must contain records and queries; an empty real-data template is not a benchmark")
        records = _unique(self.records, "record_id")
        queries = _unique(self.queries, "query_id")
        gold = _unique(self.gold, "query_id")
        if queries.keys() != gold.keys():
            raise ValueError("every query requires exactly one gold row and no extra gold rows")
        if self.manifest.kind == "real_labeled" and any(not r.image_path or not r.image_sha256 for r in self.records):
            raise ValueError("real_labeled visual records require image_path and image_sha256; use an empty private template until collected")

        # Same capture/subject/room episode, duplicated media and paraphrases must
        # stay in one split. Hash checks supplement, not replace manual grouping.
        leak_groups: dict[tuple[str, str], set[str]] = defaultdict(set)
        events: dict[str, list[Record]] = defaultdict(list)
        for record in records.values():
            events[record.event_id].append(record)
            leak_groups[("leakage_group", record.leakage_group_id)].add(record.split)
            if record.image_sha256:
                leak_groups[("image_sha256", record.image_sha256)].add(record.split)
        for query in queries.values():
            leak_groups[("leakage_group", query.leakage_group_id)].add(query.split)
            leak_groups[("query_family", query.query_family_id)].add(query.split)
            if query.current_image_sha256:
                leak_groups[("image_sha256", query.current_image_sha256)].add(query.split)
        for (kind, key), splits in leak_groups.items():
            if len(splits) > 1:
                raise ValueError(f"split leakage: {kind} {key} occurs in {sorted(splits)}")
        for event_id, frames in events.items():
            identities = {(r.owner_id, r.split, r.leakage_group_id) for r in frames}
            if len(identities) != 1:
                raise ValueError(f"event {event_id} crosses owners, splits or leakage groups")
        for query_id, label in gold.items():
            query = queries[query_id]
            for event_id in label.relevant_event_ids:
                if event_id not in events:
                    raise ValueError(f"gold {query_id} refers to unknown event {event_id}")
                if not any(in_scope(record, query) for record in events[event_id]):
                    raise ValueError(f"gold {query_id} event {event_id} is outside owner/split/time scope")
            if query.intent == "latest" and label.coverage == "available":
                if label.latest_event_id is None:
                    raise ValueError(f"latest query {query_id} requires latest_event_id")
                event_times = {event: max(r.captured_at_ms for r in events[event] if in_scope(r, query))
                               for event in label.relevant_event_ids}
                if event_times[label.latest_event_id] != max(event_times.values()):
                    raise ValueError(f"latest label for {query_id} is not the newest relevant event")
            elif label.latest_event_id is not None:
                raise ValueError("latest_event_id is only allowed for an available latest query")

    def validate_results(self, results: list[RankedResult]) -> None:
        indexed = _unique(results, "query_id")
        expected = {q.query_id for q in self.queries}
        if indexed.keys() != expected:
            raise ValueError(f"results must cover all queries exactly; missing={sorted(expected - indexed.keys())}, "
                             f"extra={sorted(indexed.keys() - expected)}")

    def verify_media(self, directory: str | Path) -> dict:
        """Opt-in reads of declared files only; no discovery or network access."""
        root = Path(directory).resolve()
        pairs = [(r.image_path, r.image_sha256) for r in self.records if r.image_path]
        pairs += [(q.current_image_path, q.current_image_sha256) for q in self.queries if q.current_image_path]
        checked = set()
        for relative, expected_hash in pairs:
            path = (root / relative).resolve()
            if not path.is_relative_to(root):
                raise ValueError("media symlink resolves outside dataset directory")
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            if actual != expected_hash:
                raise ValueError(f"media checksum mismatch: {relative}")
            checked.add(relative)
        return {"verified": True, "file_count": len(checked),
                "note": "Exact hashes verified. Perceptual duplicates, shared physical objects and correlated queries still require manual grouping."}
