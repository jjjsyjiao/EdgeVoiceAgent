"""Persisted FTS5 index. Every result is joined to live owner/time-scoped rows."""
from __future__ import annotations

import hashlib
import sqlite3
from pathlib import Path

from .query import record_text, tokens


class KeywordIndex:
    def __init__(self, db_path: str | Path):
        self.path = str(db_path)
        with self.connect() as db:
            db.execute("CREATE VIRTUAL TABLE IF NOT EXISTS visual_fts USING fts5(owner UNINDEXED, record_id UNINDEXED, fingerprint UNINDEXED, body)")
            db.execute("""CREATE TRIGGER IF NOT EXISTS visual_fts_delete AFTER DELETE ON records BEGIN
                DELETE FROM visual_fts WHERE owner=OLD.owner AND record_id=OLD.record_id; END""")

    def connect(self):
        return sqlite3.connect(self.path, timeout=10)

    def sync(self, owner: str, rows: list[dict]):
        # Incremental updates are local; there is no cloud call while holding a DB lock.
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            cached = dict(db.execute("SELECT record_id,fingerprint FROM visual_fts WHERE owner=?", (owner,)))
            for row in rows:
                text = record_text(row)
                fingerprint = hashlib.sha256(text.encode()).hexdigest()
                if cached.get(row["record_id"]) == fingerprint:
                    continue
                # A delete between snapshot and sync must not recreate an index entry.
                if not db.execute("SELECT 1 FROM records WHERE owner=? AND record_id=?", (owner, row["record_id"])).fetchone():
                    continue
                db.execute("DELETE FROM visual_fts WHERE owner=? AND record_id=?", (owner, row["record_id"]))
                db.execute("INSERT INTO visual_fts VALUES (?,?,?,?)", (owner, row["record_id"], fingerprint, " ".join(tokens(text))))

    def search(self, owner: str, query: str, from_ts_ms=None, to_ts_ms=None, limit=50) -> list[str]:
        terms = tokens(query)
        if not terms:
            return []
        sql = """SELECT visual_fts.record_id FROM visual_fts JOIN records r
            ON r.owner=visual_fts.owner AND r.record_id=visual_fts.record_id
            WHERE visual_fts MATCH ? AND r.owner=?"""
        args = [" OR ".join('"' + term.replace('"', '""') + '"' for term in terms), owner]
        if from_ts_ms is not None:
            sql += " AND r.captured_at_ms>=?"
            args.append(from_ts_ms)
        if to_ts_ms is not None:
            sql += " AND r.captured_at_ms<?"
            args.append(to_ts_ms)
        sql += " ORDER BY bm25(visual_fts), r.captured_at_ms DESC, r.record_id LIMIT ?"
        args.append(limit)
        with self.connect() as db:
            return [row[0] for row in db.execute(sql, args)]
