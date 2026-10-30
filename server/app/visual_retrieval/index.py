"""SQLite durable indexing jobs and exact cosine search, scoped to a namespace.

Only one namespace is active for newly inserted records. Older vectors remain
isolated for rollback. Record deletion cascades, including while a provider call
is in flight; a fresh lease token prevents recreating evidence after deletion.
Deployment contract: one configured worker/namespace per SQLite database. Do
not run mixed-model application replicas against the same database; namespace
activation is a startup migration, not a per-request selection mechanism.
"""
from __future__ import annotations

from array import array
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import sys
import time
from typing import Callable, Iterator
import uuid

from .providers import EmbeddingError, normalise_vector


_SUMPROD = getattr(math, "sumprod", None)  # Python 3.12+; Docker 3.11 keeps the fallback.


def _dot(left, right) -> float:
    return _SUMPROD(left, right) if _SUMPROD is not None else math.fsum(a * b for a, b in zip(left, right))


def record_fingerprint(record: dict) -> str:
    fields = ("owner", "record_id", "session_id", "captured_at_ms", "recorded_at_ms",
              "object_name", "aliases", "location", "note", "image_sha256")
    return hashlib.sha256(json.dumps({key: record[key] for key in fields},
        sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


@dataclass(frozen=True)
class IndexJob:
    owner: str
    record_id: str
    namespace: str
    lease_token: str
    attempts: int
    record: dict
    fingerprint: str


class VisualVectorIndex:
    def __init__(self, root: str | Path, namespace: str, dimension: int,
                 clock: Callable[[], float] = time.time) -> None:
        self.root = Path(root).resolve()
        self.db_path = self.root / "records.sqlite3"
        if not self.db_path.is_file():
            raise ValueError("VisualMemoryStore must be initialized first")
        if not namespace or len(namespace) > 512 or type(dimension) is not int or not 1 <= dimension <= 8192:
            raise ValueError("invalid vector namespace")
        self.namespace = namespace
        self.dimension = dimension
        self.clock = clock
        with self._connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS visual_index_namespaces (
                    namespace TEXT PRIMARY KEY, dimension INTEGER NOT NULL,
                    active INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS visual_index_jobs (
                    owner TEXT NOT NULL, record_id TEXT NOT NULL, namespace TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
                    next_attempt_ms INTEGER NOT NULL DEFAULT 0, lease_until_ms INTEGER NOT NULL DEFAULT 0,
                    lease_token TEXT NOT NULL DEFAULT '', error_code TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (owner, record_id, namespace),
                    FOREIGN KEY (owner, record_id) REFERENCES records(owner, record_id) ON DELETE CASCADE,
                    FOREIGN KEY (namespace) REFERENCES visual_index_namespaces(namespace) ON DELETE CASCADE);
                CREATE INDEX IF NOT EXISTS visual_job_due ON visual_index_jobs(namespace,status,next_attempt_ms);
                CREATE TABLE IF NOT EXISTS visual_vectors (
                    owner TEXT NOT NULL, record_id TEXT NOT NULL, namespace TEXT NOT NULL,
                    modality TEXT NOT NULL CHECK(modality IN ('image','text')),
                    dimension INTEGER NOT NULL, vector BLOB NOT NULL, fingerprint TEXT NOT NULL,
                    indexed_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (owner, record_id, namespace, modality),
                    FOREIGN KEY (owner, record_id) REFERENCES records(owner, record_id) ON DELETE CASCADE,
                    FOREIGN KEY (namespace) REFERENCES visual_index_namespaces(namespace) ON DELETE CASCADE);
                CREATE TRIGGER IF NOT EXISTS visual_record_enqueue AFTER INSERT ON records BEGIN
                    INSERT OR IGNORE INTO visual_index_jobs(owner,record_id,namespace)
                    SELECT NEW.owner,NEW.record_id,namespace FROM visual_index_namespaces WHERE active=1;
                END;
                CREATE TRIGGER IF NOT EXISTS visual_record_remove AFTER DELETE ON records BEGIN
                    DELETE FROM visual_index_jobs WHERE owner=OLD.owner AND record_id=OLD.record_id;
                    DELETE FROM visual_vectors WHERE owner=OLD.owner AND record_id=OLD.record_id;
                END;
                CREATE TRIGGER IF NOT EXISTS visual_record_reindex AFTER UPDATE ON records
                WHEN OLD.image_sha256 IS NOT NEW.image_sha256 OR OLD.object_name IS NOT NEW.object_name
                  OR OLD.aliases IS NOT NEW.aliases OR OLD.location IS NOT NEW.location OR OLD.note IS NOT NEW.note
                  OR OLD.session_id IS NOT NEW.session_id OR OLD.captured_at_ms IS NOT NEW.captured_at_ms
                  OR OLD.recorded_at_ms IS NOT NEW.recorded_at_ms
                BEGIN
                    DELETE FROM visual_index_jobs WHERE owner=OLD.owner AND record_id=OLD.record_id;
                    DELETE FROM visual_vectors WHERE owner=OLD.owner AND record_id=OLD.record_id;
                    INSERT OR IGNORE INTO visual_index_jobs(owner,record_id,namespace)
                    SELECT NEW.owner,NEW.record_id,namespace FROM visual_index_namespaces WHERE active=1;
                END;
            """)
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT dimension FROM visual_index_namespaces WHERE namespace=?", (namespace,)).fetchone()
            if previous and previous[0] != dimension:
                raise ValueError("namespace dimension mismatch")
            db.execute("UPDATE visual_index_namespaces SET active=0")
            db.execute("INSERT INTO visual_index_namespaces(namespace,dimension,active) VALUES(?,?,1) "
                       "ON CONFLICT(namespace) DO UPDATE SET active=1", (namespace, dimension))
            # Also repairs missing modality rows after an interrupted external migration.
            db.execute("""INSERT OR IGNORE INTO visual_index_jobs(owner,record_id,namespace)
                          SELECT owner,record_id,? FROM records""", (namespace,))
            db.execute("""UPDATE visual_index_jobs SET status='pending',next_attempt_ms=0,lease_token=''
                          WHERE namespace=? AND status='ready' AND
                          (SELECT count(*) FROM visual_vectors v WHERE v.owner=visual_index_jobs.owner
                           AND v.record_id=visual_index_jobs.record_id AND v.namespace=?) != 2""", (namespace, namespace))

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.db_path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=5000")
        try:
            with db:
                yield db
        finally:
            db.close()

    def claim(self, lease_seconds: float = 60) -> IndexJob | None:
        if not 1 <= lease_seconds <= 600:
            raise ValueError("invalid index lease")
        now = int(self.clock() * 1000)
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("""SELECT j.*,r.session_id,r.captured_at_ms,r.recorded_at_ms,r.object_name,
                r.aliases,r.location,r.note,r.image_sha256,r.image_bytes
                FROM visual_index_jobs j JOIN records r ON r.owner=j.owner AND r.record_id=j.record_id
                WHERE j.namespace=? AND ((j.status IN ('pending','retry') AND j.next_attempt_ms<=?)
                  OR (j.status='processing' AND j.lease_until_ms<=?))
                ORDER BY j.next_attempt_ms,r.recorded_at_ms,r.record_id LIMIT 1""", (self.namespace, now, now)).fetchone()
            if row is None:
                return None
            token = uuid.uuid4().hex
            attempts = row["attempts"] + 1
            db.execute("""UPDATE visual_index_jobs SET status='processing',attempts=?,lease_until_ms=?,lease_token=?
                          WHERE owner=? AND record_id=? AND namespace=?""",
                       (attempts, now + int(lease_seconds * 1000), token, row["owner"], row["record_id"], self.namespace))
            record = dict(row)
            return IndexJob(row["owner"], row["record_id"], self.namespace, token, attempts,
                            record, record_fingerprint(record))

    def complete(self, job: IndexJob, image_vector: list[float], text_vector: list[float]) -> bool:
        if job.namespace != self.namespace:
            return False
        blobs = {kind: self._pack(vector) for kind, vector in (("image", image_vector), ("text", text_vector))}
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM records WHERE owner=? AND record_id=?", (job.owner, job.record_id)).fetchone()
            lease = db.execute("""SELECT 1 FROM visual_index_jobs WHERE owner=? AND record_id=? AND namespace=?
                                 AND lease_token=? AND status='processing'""",
                               (job.owner, job.record_id, self.namespace, job.lease_token)).fetchone()
            if row is None or lease is None or record_fingerprint(dict(row)) != job.fingerprint:
                return False
            now = int(self.clock() * 1000)
            for kind, blob in blobs.items():
                db.execute("""INSERT INTO visual_vectors VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(owner,record_id,namespace,modality) DO UPDATE SET
                    dimension=excluded.dimension,vector=excluded.vector,fingerprint=excluded.fingerprint,
                    indexed_at_ms=excluded.indexed_at_ms""",
                           (job.owner, job.record_id, self.namespace, kind, self.dimension, blob, job.fingerprint, now))
            db.execute("""UPDATE visual_index_jobs SET status='ready',lease_token='',lease_until_ms=0,error_code=''
                          WHERE owner=? AND record_id=? AND namespace=?""", (job.owner, job.record_id, self.namespace))
            return True

    def fail(self, job: IndexJob, error_code: str, *, permanent: bool = False, max_attempts: int = 8) -> bool:
        # Only codes from this allowlist persist. Never persist exception strings.
        allowed = {"embedding_auth_failed", "embedding_rate_limited", "embedding_http_error",
                   "embedding_timeout", "embedding_transport_error", "embedding_invalid_response",
                   "embedding_response_too_large", "embedding_result_count", "embedding_invalid_index",
                   "embedding_invalid_dimension", "embedding_invalid_vector", "embedding_zero_vector",
                   "embedding_invalid_type", "embedding_invalid_input", "embedding_invalid_image",
                   "index_evidence_unavailable", "index_internal_error", "index_stopped"}
        code = error_code if error_code in allowed else "index_internal_error"
        failed = permanent or job.attempts >= max_attempts
        delay = min(3600, 2 ** min(job.attempts, 12)) if code != "index_stopped" else 0
        with self._connect() as db:
            result = db.execute("""UPDATE visual_index_jobs SET status=?,next_attempt_ms=?,lease_until_ms=0,
                                lease_token='',error_code=? WHERE owner=? AND record_id=? AND namespace=?
                                AND lease_token=? AND status='processing'""",
                                ("failed" if failed else "retry", int((self.clock() + delay) * 1000), code,
                                 job.owner, job.record_id, self.namespace, job.lease_token))
            return result.rowcount == 1

    def retry_failed(self, owner: str | None = None) -> int:
        sql = "UPDATE visual_index_jobs SET status='pending',attempts=0,next_attempt_ms=0,error_code='' WHERE namespace=? AND status='failed'"
        args = [self.namespace]
        if owner is not None:
            sql += " AND owner=?"
            args.append(owner)
        with self._connect() as db:
            return db.execute(sql, args).rowcount

    @staticmethod
    def _filters(owner: str, from_ts_ms: int | None, to_ts_ms: int | None) -> tuple[str, list]:
        sql, args = "r.owner=?", [owner]
        if from_ts_ms is not None:
            sql += " AND r.captured_at_ms>=?"
            args.append(from_ts_ms)
        if to_ts_ms is not None:
            sql += " AND r.captured_at_ms<?"
            args.append(to_ts_ms)
        return sql, args

    def status(self, owner: str, from_ts_ms: int | None = None, to_ts_ms: int | None = None) -> dict:
        filters, args = self._filters(owner, from_ts_ms, to_ts_ms)
        with self._connect() as db:
            counts = db.execute("""SELECT coalesce(j.status,'pending'),count(*) FROM records r
                LEFT JOIN visual_index_jobs j ON j.owner=r.owner AND j.record_id=r.record_id AND j.namespace=?
                WHERE """ + filters + " GROUP BY coalesce(j.status,'pending')", [self.namespace] + args).fetchall()
        raw = {row[0]: row[1] for row in counts}
        return {"namespace": self.namespace, "total": sum(raw.values()), "ready": raw.get("ready", 0),
                "pending": sum(raw.get(s, 0) for s in ("pending", "processing", "retry")),
                "failed": raw.get("failed", 0), "retry": raw.get("retry", 0), "processing": raw.get("processing", 0)}

    def search(self, owner: str, query_vector: list[float], modality: str = "image",
               from_ts_ms: int | None = None, to_ts_ms: int | None = None, limit: int = 50) -> list[dict]:
        if modality not in {"image", "text"} or type(limit) is not int or not 1 <= limit <= 200:
            raise ValueError("invalid vector search")
        query = normalise_vector(query_vector, self.dimension)
        filters, args = self._filters(owner, from_ts_ms, to_ts_ms)
        with self._connect() as db:
            rows = db.execute("""SELECT v.record_id,v.vector,v.dimension,r.captured_at_ms FROM visual_vectors v
                JOIN records r ON v.owner=r.owner AND v.record_id=r.record_id
                JOIN visual_index_jobs j ON j.owner=v.owner AND j.record_id=v.record_id AND j.namespace=v.namespace
                WHERE v.namespace=? AND v.modality=? AND j.status='ready' AND """ + filters,
                [self.namespace, modality] + args).fetchall()
        matches = []
        for row in rows:
            vector = self._unpack(row["vector"], row["dimension"])
            score = max(-1.0, min(1.0, _dot(query, vector)))
            matches.append({"record_id": row["record_id"], "score": score, "captured_at_ms": row["captured_at_ms"]})
        matches.sort(key=lambda item: (-item["score"], -item["captured_at_ms"], item["record_id"]))
        return matches[:limit]

    def _pack(self, vector: list[float]) -> bytes:
        values = array("f", normalise_vector(vector, self.dimension))
        if sys.byteorder != "little":
            values.byteswap()
        return values.tobytes()

    def _unpack(self, blob: bytes, dimension: int) -> array:
        if dimension != self.dimension or len(blob) != self.dimension * 4:
            raise EmbeddingError("embedding_invalid_dimension")
        values = array("f")
        values.frombytes(blob)
        if sys.byteorder != "little":
            values.byteswap()
        # Writes already normalize. Re-normalizing every vector allocates and
        # scans it several times per query. Validate the stored unit norm once;
        # squared values also reject NaN/Inf and zero without a separate pass.
        norm_squared = _dot(values, values)
        if not math.isfinite(norm_squared) or abs(norm_squared - 1.0) > 1e-4:
            raise EmbeddingError("embedding_invalid_vector")
        return values
