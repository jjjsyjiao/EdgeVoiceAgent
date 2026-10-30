"""带用户隔离的主动视觉记忆。

本模块保留元数据检索基线；可选的图文检索由 visual_retrieval 管线负责。
SQLite 保存元数据，图片以服务端生成的哈希路径保存；历史时间使用拍摄时刻，
保留期使用服务端入库时刻。coverage 是现存记录的边界，不代表连续录像。
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
import unicodedata
import uuid
import warnings
from collections.abc import Callable

from PIL import Image, UnidentifiedImageError

MAX_JPEG_BYTES = 256 * 1024
MAX_IMAGE_PIXELS = 4096 * 4096


def validate_jpeg(raw: bytes) -> None:
    """解码后才能作为视觉证据；拒绝截断图、伪 JPEG、过大图片。"""
    if not raw or len(raw) > MAX_JPEG_BYTES:
        raise ValueError("JPEG must contain 1..262144 bytes")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as image:
                if image.format != "JPEG" or image.width * image.height > MAX_IMAGE_PIXELS:
                    raise ValueError("image must be a JPEG of at most 16777216 pixels")
                image.verify()
            # verify() 不保证压缩数据完整，load() 再走一次真正解码。
            with Image.open(io.BytesIO(raw)) as image:
                image.load()
    except (OSError, UnidentifiedImageError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        raise ValueError("JPEG is not decodable") from exc


def _normalise(text: str) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]", "", unicodedata.normalize("NFKC", text).casefold())


_QUERY_FILLERS = (
    "帮我", "找一下", "回忆一下", "有没有", "看见", "看到", "昨天", "前天", "今天",
    "上次", "之前", "刚才", "这个", "那个", "是不是", "同一个", "在哪里", "在哪儿",
    "放哪里", "放哪儿", "在哪", "我的", "记得", "是否", "什么地方",
)


def metadata_score(query: str, record: dict) -> float:
    """确定性的中文名称/别名优先匹配，无法承诺任意画面的语义召回。"""
    normal_query = _normalise(query)
    labels = [_normalise(record["object_name"])] + [_normalise(a) for a in record["aliases"]]
    for label in labels:
        if label and label in normal_query:
            return 1.0 if label == normal_query else 0.95
    stripped = normal_query
    for word in _QUERY_FILLERS:
        stripped = stripped.replace(word, "")
    stripped = stripped.strip("的了呢吗么啊我")
    if not stripped:
        return 0.0
    if any(stripped in label for label in labels if label):
        return 0.9
    text = _normalise(" ".join([record["location"], record["note"]]))
    if len(stripped) >= 2 and stripped in text:
        return 0.75
    # 中文长查询中的二字词须覆盖至少一半；单个共同汉字不算命中。
    grams = {stripped[i:i + 2] for i in range(len(stripped) - 1)}
    if not grams:
        return 0.0
    target = " ".join(labels)
    matches = sum(gram in target for gram in grams)
    coverage = matches / len(grams)
    return round(0.4 + 0.3 * coverage, 4) if matches >= 1 and coverage >= 0.5 else 0.0


class RecordConflict(ValueError):
    pass


class VisualMemoryStore:
    def __init__(
        self,
        root: str,
        retention_days: float = 7,
        max_total_bytes: int = 512 * 1024 * 1024,
        max_owner_bytes: int = 128 * 1024 * 1024,
        clock: Callable[[], float] = time.time,
    ) -> None:
        if retention_days <= 0 or min(max_total_bytes, max_owner_bytes) <= 0:
            raise ValueError("visual memory limits must be positive")
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.retention_days = retention_days
        self.max_total_bytes = max_total_bytes
        self.max_owner_bytes = max_owner_bytes
        self.clock = clock
        self._lock = threading.RLock()
        self._db_path = self.root / "records.sqlite3"
        with self._connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS records (
                owner TEXT NOT NULL, record_id TEXT NOT NULL, session_id TEXT NOT NULL,
                captured_at_ms INTEGER NOT NULL, recorded_at_ms INTEGER NOT NULL,
                object_name TEXT NOT NULL, aliases TEXT NOT NULL, location TEXT NOT NULL,
                note TEXT NOT NULL, image_path TEXT NOT NULL, image_bytes INTEGER NOT NULL,
                image_sha256 TEXT NOT NULL, PRIMARY KEY (owner, record_id))""")
            db.execute("CREATE INDEX IF NOT EXISTS owner_time ON records(owner, captured_at_ms DESC)")
        os.chmod(self._db_path, 0o600)
        self._cleanup_orphans()
        self.prune()

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self._db_path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    @property
    def db_path(self) -> Path:
        return self._db_path

    def candidates(self, owner: str, from_ts_ms: int | None = None, to_ts_ms: int | None = None) -> list[dict]:
        """仅返回授权时间范围内的元数据；不读取图片，也不持锁调用模型。"""
        with self._lock:
            self.prune()
            sql, args = "SELECT * FROM records WHERE owner=?", [owner]
            if from_ts_ms is not None:
                sql += " AND captured_at_ms >= ?"
                args.append(from_ts_ms)
            if to_ts_ms is not None:
                sql += " AND captured_at_ms < ?"
                args.append(to_ts_ms)
            with self._connect() as db:
                return [{**self._metadata(row), "evidence_version": self._version(row)}
                        for row in db.execute(sql + " ORDER BY captured_at_ms DESC, record_id", args)]

    @staticmethod
    def _version(row) -> str:
        # Immutable-content identity, including JPEG, protects delete/recreate with the same ID.
        return hashlib.sha256(json.dumps(dict(row), sort_keys=True, ensure_ascii=False).encode()).hexdigest()

    def materialise(self, owner: str, record_id: str, score: float = 0.0,
                    from_ts_ms: int | None = None, to_ts_ms: int | None = None,
                    expected_version: str | None = None) -> dict | None:
        """交付证据前重新检查 owner、存在性、时间和校验和，防止旧索引复活删除记录。"""
        with self._lock, self._connect() as db:
            row = db.execute("SELECT * FROM records WHERE owner=? AND record_id=?", (owner, record_id)).fetchone()
            if row is None or (from_ts_ms is not None and row["captured_at_ms"] < from_ts_ms) or (
                to_ts_ms is not None and row["captured_at_ms"] >= to_ts_ms
            ) or row["recorded_at_ms"] < int((self.clock() - self.retention_days * 86400) * 1000):
                return None
            if expected_version is not None and self._version(row) != expected_version:
                return None
            return self._materialise(row, score)

    def coverage(self, owner: str) -> dict:
        with self._lock, self._connect() as db:
            row = db.execute("SELECT min(captured_at_ms), max(captured_at_ms), count(*) FROM records WHERE owner=?", (owner,)).fetchone()
            return {"from_ts_ms": row[0], "to_ts_ms": row[1], "retention_days": self.retention_days,
                    "records_count": row[2], "continuous": False}

    def _path(self, owner: str, record_id: str) -> Path:
        # 无客户端字符串参与路径拼接；即使 owner 配置里有斜杠也不会逃逸。
        owner_hash = hashlib.sha256(owner.encode()).hexdigest()
        record_hash = hashlib.sha256(record_id.encode()).hexdigest()
        return self.root / "images" / owner_hash / f"{record_hash}.jpg"

    def _cleanup_orphans(self) -> None:
        # 写入顺序为「临时 JPEG -> 原子改名 -> 提交元数据」，崩溃可能留下孤儿。
        # BEGIN IMMEDIATE 也阻止另一个服务进程正在写入时被清理。
        with self._lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            keep = {self._path(row["owner"], row["record_id"])
                    for row in db.execute("SELECT owner, record_id FROM records")}
            images = self.root / "images"
            for path in images.glob("*/*"):
                if path.is_file() and (path.suffix == ".tmp" or (path.suffix == ".jpg" and path not in keep)):
                    path.unlink(missing_ok=True)

    def _delete_rows(self, db: sqlite3.Connection, rows: list[sqlite3.Row]) -> None:
        for row in rows:
            db.execute("DELETE FROM records WHERE owner=? AND record_id=?", (row["owner"], row["record_id"]))
        # 数据库删除先提交，图片删除失败留下的孤儿不再能被检索。
        db.commit()
        for row in rows:
            self._path(row["owner"], row["record_id"]).unlink(missing_ok=True)

    def prune(self) -> None:
        with self._lock, self._connect() as db:
            cutoff = int((self.clock() - self.retention_days * 86400) * 1000)
            rows = db.execute("SELECT * FROM records WHERE recorded_at_ms < ?", (cutoff,)).fetchall()
            self._delete_rows(db, rows)

    @staticmethod
    def _metadata(row: sqlite3.Row) -> dict:
        return {
            "record_id": row["record_id"], "session_id": row["session_id"],
            "captured_at_ms": row["captured_at_ms"], "recorded_at_ms": row["recorded_at_ms"],
            "object_name": row["object_name"], "aliases": json.loads(row["aliases"]),
            "location": row["location"], "note": row["note"], "source": "user_recorded",
        }

    def _materialise(self, row: sqlite3.Row, score: float) -> dict:
        raw = self._path(row["owner"], row["record_id"]).read_bytes()
        if hashlib.sha256(raw).hexdigest() != row["image_sha256"]:
            raise ValueError("stored evidence checksum mismatch")
        validate_jpeg(raw)
        return {**self._metadata(row), "image_base64": base64.b64encode(raw).decode("ascii"), "score": score}

    def add(self, owner: str, values: dict, jpeg: bytes) -> dict:
        validate_jpeg(jpeg)
        if len(jpeg) > min(self.max_owner_bytes, self.max_total_bytes):
            raise OverflowError("image exceeds storage quota")
        digest = hashlib.sha256(jpeg).hexdigest()
        with self._lock:
            self.prune()
            with self._connect() as db:
                db.execute("BEGIN IMMEDIATE")
                previous = db.execute("SELECT * FROM records WHERE owner=? AND record_id=?", (owner, values["record_id"])).fetchone()
                if previous is not None:
                    comparable = self._metadata(previous)
                    if previous["image_sha256"] != digest or any(comparable[k] != values[k] for k in values):
                        raise RecordConflict("record_id already exists with different content")
                    return self._materialise(previous, 1.0)
                path = self._path(owner, values["record_id"])
                path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                temporary = path.with_suffix(f".{uuid.uuid4().hex}.tmp")
                try:
                    with temporary.open("xb") as file:
                        os.chmod(temporary, 0o600)
                        file.write(jpeg)
                        file.flush()
                        os.fsync(file.fileno())
                    os.replace(temporary, path)
                    db.execute("""INSERT INTO records VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", (
                        owner, values["record_id"], values["session_id"], values["captured_at_ms"], int(self.clock() * 1000),
                        values["object_name"], json.dumps(values["aliases"], ensure_ascii=False), values["location"], values["note"],
                        str(path.relative_to(self.root)), len(jpeg), digest,
                    ))
                    evicted = self._enforce_quota(db, owner, values["record_id"])
                    db.commit()
                except BaseException:
                    db.rollback()
                    path.unlink(missing_ok=True)
                    raise
                finally:
                    temporary.unlink(missing_ok=True)
                for old in evicted:
                    self._path(old["owner"], old["record_id"]).unlink(missing_ok=True)
                row = db.execute("SELECT * FROM records WHERE owner=? AND record_id=?", (owner, values["record_id"])).fetchone()
                if row is None:
                    raise OSError("record was evicted during save")
                return self._materialise(row, 1.0)

    def _enforce_quota(self, db: sqlite3.Connection, owner: str, preserve_id: str) -> list[sqlite3.Row]:
        evicted = []
        for clause, params, budget in (("WHERE owner=?", (owner,), self.max_owner_bytes), ("", (), self.max_total_bytes)):
            rows = db.execute(f"SELECT * FROM records {clause} ORDER BY recorded_at_ms, rowid", params).fetchall()
            total = sum(row["image_bytes"] for row in rows)
            remove = []
            for row in rows:
                if total <= budget:
                    break
                if row["owner"] == owner and row["record_id"] == preserve_id:
                    continue
                remove.append(row)
                total -= row["image_bytes"]
            for row in remove:
                db.execute("DELETE FROM records WHERE owner=? AND record_id=?", (row["owner"], row["record_id"]))
            evicted.extend(remove)
        return evicted

    def search(self, owner: str, query: str, from_ts_ms: int | None, to_ts_ms: int | None, limit: int) -> dict:
        with self._lock:
            self.prune()
            with self._connect() as db:
                coverage_row = db.execute("SELECT min(captured_at_ms), max(captured_at_ms), count(*) FROM records WHERE owner=?", (owner,)).fetchone()
                sql = "SELECT * FROM records WHERE owner=?"
                args: list = [owner]
                if from_ts_ms is not None:
                    sql += " AND captured_at_ms >= ?"
                    args.append(from_ts_ms)
                if to_ts_ms is not None:
                    sql += " AND captured_at_ms < ?"
                    args.append(to_ts_ms)
                sql += " ORDER BY captured_at_ms DESC, record_id"
                candidates = db.execute(sql, args).fetchall()
            scored = [(metadata_score(query, self._metadata(row)), row) for row in candidates]
            scored = sorted((pair for pair in scored if pair[0] > 0), key=lambda pair: (pair[0], pair[1]["captured_at_ms"]), reverse=True)
            records, unavailable = [], 0
            for score, row in scored:
                try:
                    record = self._materialise(row, score)
                except (OSError, ValueError):
                    unavailable += 1
                    continue
                if len(records) < limit:
                    records.append(record)
            return {
                "status": "matched" if records else ("evidence_unavailable" if unavailable else "no_match"),
                "records": records,
                "coverage": {
                    "from_ts_ms": coverage_row[0], "to_ts_ms": coverage_row[1],
                    "retention_days": self.retention_days, "records_count": coverage_row[2],
                    "continuous": False,
                },
                "unavailable_records_count": unavailable,
            }

    def delete(self, owner: str, record_id: str) -> bool:
        with self._lock, self._connect() as db:
            rows = db.execute("SELECT * FROM records WHERE owner=? AND record_id=?", (owner, record_id)).fetchall()
            self._delete_rows(db, rows)
            return bool(rows)
