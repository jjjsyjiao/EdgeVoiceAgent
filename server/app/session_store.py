"""服务端会话帧磁盘留存（V3）。

设计镜像端侧 V2 `DiskFrameRing` 的文件名索引 / 双限淘汰语义，但面向多会话：

- **按 session_id 分目录**：召回可不带 session_id，跨会话命中「昨天」类超窗回忆；
- **时间窗 / 排序用端侧时间戳**（与端侧 V2 同一时基），保证召回窗与端侧对齐；
- **TTL 用服务端落盘时间**（新写用 [clock]，冷启动重建用文件 mtime），
  避免端侧时钟漂移导致误删。

并发：FastAPI 同步端点在线程池并发调用本类，故全部公开方法在 [threading.Lock] 上串行；
落盘用 tmp + os.replace 原子写，崩溃只留 .tmp 残片（冷启动清理）。

纯标准库实现：单测用临时目录即可覆盖全部路径。
"""

from __future__ import annotations

import os
import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path

DEFAULT_TTL_SECONDS = 24 * 60 * 60                 # 24 小时
DEFAULT_MAX_TOTAL_BYTES = 512 * 1024 * 1024        # 全局总占用上限 512MB

# session_id 必须为安全字符：阻止 `..` / `/` 造成的目录穿越
_SAFE_SESSION = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


@dataclass(frozen=True)
class FrameEntry:
    """一条留存记录（按 ts_ms 升序索引）。

    @param ts_ms 端侧时间戳（epoch ms，召回窗对齐用）
    @param stored_at 服务端落盘时间（epoch s，TTL 判定用）
    """

    session_id: str
    ts_ms: int
    path: str
    size_bytes: int
    stored_at: float


@dataclass(frozen=True)
class FrameStoreStats:
    frame_count: int
    total_bytes: int
    session_count: int
    oldest_ts_ms: int | None
    newest_ts_ms: int | None


class SessionFrameStore:
    def __init__(
        self,
        root_dir: str,
        ttl_seconds: float = DEFAULT_TTL_SECONDS,
        max_total_bytes: int = DEFAULT_MAX_TOTAL_BYTES,
        clock=time.time,
    ):
        self._root = Path(root_dir)
        self._root.mkdir(parents=True, exist_ok=True)
        self._ttl = float(ttl_seconds)
        self._max_total = int(max_total_bytes)
        self._clock = clock
        self._lock = threading.Lock()
        self._entries: list[FrameEntry] = []  # 始终按 (ts_ms, 文件名) 升序
        self._total = 0
        self._seq = 0
        self._scan()

    # ---------------- 公开 API ----------------

    @staticmethod
    def is_valid_session(session_id: str) -> bool:
        return bool(_SAFE_SESSION.fullmatch(session_id))

    def append_many(self, session_id: str, items: list[tuple[int, bytes]]) -> int:
        """批量落盘 [(ts_ms, jpeg)]，返回成功写入条数。空/非法/IO 失败逐条跳过。

        一次持锁写完一批，避免批次里逐帧抢锁（端侧按批上传，天然成批）。
        """
        if not session_id or not items or not self.is_valid_session(session_id):
            return 0
        session_dir = self._root / session_id
        written = 0
        with self._lock:
            session_dir.mkdir(parents=True, exist_ok=True)
            for ts_ms, jpeg in items:
                if not jpeg:
                    continue
                target = self._unique_path(session_dir, ts_ms)
                tmp = target.with_name(target.name + ".tmp")
                try:
                    tmp.write_bytes(jpeg)
                    os.replace(tmp, target)  # 原子：崩溃只留 .tmp 残片
                except OSError:
                    tmp.unlink(missing_ok=True)
                    continue
                self._entries.append(
                    FrameEntry(session_id, ts_ms, str(target), target.stat().st_size, self._clock())
                )
                written += 1
            if written:
                self._entries.sort(key=lambda e: (e.ts_ms, e.path))
                self._recount()
                self._prune_locked()
        return written

    def frames_in_window(
        self, from_ms: int, to_ms: int, session_id: str | None = None
    ) -> list[FrameEntry]:
        """时间窗（闭区间）内的记录；[session_id] 为空则跨会话。"""
        with self._lock:
            return [
                e
                for e in self._entries
                if from_ms <= e.ts_ms <= to_ms
                and (session_id is None or e.session_id == session_id)
            ]

    def sample(
        self, from_ms: int, to_ms: int, max_count: int, session_id: str | None = None
    ) -> list[tuple[int, bytes]]:
        """时间窗内**均匀抽帧**（控制 video_tokens 成本）：返回 [(ts_ms, jpeg)]，顺序按时间。

        不足 [max_count] 全取；超出时首/尾必中的等距下标（与端侧 DiskFrameRing 同口径）。
        """
        if max_count <= 0:
            return []
        windowed = self.frames_in_window(from_ms, to_ms, session_id)
        picked = _equidistant(windowed, max_count)
        out: list[tuple[int, bytes]] = []
        for entry in picked:
            try:
                with open(entry.path, "rb") as fh:
                    out.append((entry.ts_ms, fh.read()))
            except OSError:
                continue  # 文件丢失：召回是增强能力，跳过即可
        return out

    def stats(self) -> FrameStoreStats:
        with self._lock:
            sessions = {e.session_id for e in self._entries}
            return FrameStoreStats(
                frame_count=len(self._entries),
                total_bytes=self._total,
                session_count=len(sessions),
                oldest_ts_ms=self._entries[0].ts_ms if self._entries else None,
                newest_ts_ms=self._entries[-1].ts_ms if self._entries else None,
            )

    def prune(self) -> None:
        with self._lock:
            self._prune_locked()

    # ---------------- 内部 ----------------

    def _prune_locked(self) -> None:
        cutoff = self._clock() - self._ttl
        # 1) TTL 淘汰（按服务端落盘时间）
        kept = [e for e in self._entries if e.stored_at >= cutoff]
        if len(kept) != len(self._entries):
            for e in self._entries:
                if e.stored_at < cutoff:
                    _unlink(e.path)
            self._entries = kept
            self._total = sum(e.size_bytes for e in kept)
        # 2) 容量淘汰（从最旧 ts 起删，逐条递减总额）
        while self._entries and self._total > self._max_total:
            removed = self._entries.pop(0)
            _unlink(removed.path)
            self._total -= removed.size_bytes
        if self._total < 0:
            self._total = 0
        self._drop_empty_dirs()

    def _recount(self) -> None:
        self._total = sum(e.size_bytes for e in self._entries)

    def _drop_empty_dirs(self) -> None:
        try:
            for child in self._root.iterdir():
                if child.is_dir() and not any(child.iterdir()):
                    child.rmdir()
        except OSError:
            pass  # 目录清理失败不影响留存主链路

    def _unique_path(self, session_dir: Path, ts_ms: int) -> Path:
        while True:
            name = "%013d-%04d.jpg" % (ts_ms, self._seq & 0xFFFF)
            self._seq += 1
            candidate = session_dir / name
            if not candidate.exists():
                return candidate

    def _scan(self) -> None:
        """冷启动重建：扫描全部会话目录 → 清 .tmp 残片 → 排序 → 立即淘汰一次。"""
        with self._lock:
            now = self._clock()
            for session_dir in sorted(self._root.iterdir()):
                if not session_dir.is_dir() or not self.is_valid_session(session_dir.name):
                    _rm_tree(session_dir)
                    continue
                for f in sorted(session_dir.iterdir()):
                    if f.name.endswith(".tmp"):
                        _unlink(str(f))
                        continue
                    ts = _parse_ts(f.name)
                    if ts is None:
                        _unlink(str(f))
                        continue
                    try:
                        mtime = f.stat().st_mtime
                    except OSError:
                        continue
                    # 冷启动无内存时间：用文件 mtime 近似服务端落盘时间；顺带夹到 now 防未来时钟
                    self._entries.append(
                        FrameEntry(
                            session_dir.name, ts, str(f), f.stat().st_size, min(mtime, now)
                        )
                    )
            self._entries.sort(key=lambda e: (e.ts_ms, e.path))
            self._recount()
            self._prune_locked()


def _parse_ts(name: str) -> int | None:
    head = name.split("-", 1)[0]
    try:
        return int(head)
    except ValueError:
        return None


def _equidistant(entries: list[FrameEntry], max_count: int) -> list[FrameEntry]:
    n = len(entries)
    if n <= max_count:
        return list(entries)
    if max_count == 1:
        return [entries[-1]]
    idxs = dict.fromkeys(i * (n - 1) // (max_count - 1) for i in range(max_count))
    return [entries[i] for i in idxs]


def _unlink(path: str) -> None:
    try:
        os.unlink(path)
    except OSError:
        pass


def _rm_tree(path: Path) -> None:
    try:
        if path.is_dir():
            for child in path.iterdir():
                _unlink(str(child))
            path.rmdir()
        else:
            _unlink(str(path))
    except OSError:
        pass
