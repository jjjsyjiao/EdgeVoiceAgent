"""V3 会话帧留存：存储层单测 + 接口端到端测试。

存储层用临时目录 + 可注入时钟覆盖 TTL/容量淘汰与冷启动重建；
接口层用内存 Chroma + 哈希嵌入 + 临时帧目录，零模型下载。
"""

from __future__ import annotations

import base64
import uuid

import pytest
from fastapi.testclient import TestClient

from app.distill.rule import RuleDistillProvider
from app.embedding.hashing import HashingEmbeddingProvider
from app.main import create_app
from app.service import MemoryService
from app.session_store import SessionFrameStore
from app.store import ChromaMemoryStore

# 最小合法 JPEG：SOI + EOI（满足 FFD8 魔数校验）
JPEG = b"\xff\xd8\xff\xd9"


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


# ---------------- 存储层 ----------------


class Clock:
    def __init__(self, now: float = 1_000_000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


def test_append_and_read_back_in_order(tmp_path) -> None:
    store = SessionFrameStore(str(tmp_path / "f"))
    assert store.append_many("s1", [(2000, JPEG), (1000, JPEG), (3000, JPEG)]) == 3
    windowed = store.frames_in_window(0, 10_000)
    assert [e.ts_ms for e in windowed] == [1000, 2000, 3000]


def test_invalid_session_is_rejected_without_writing(tmp_path) -> None:
    store = SessionFrameStore(str(tmp_path / "f"))
    for bad in ["", "..", "a/b", "a b", "x" * 65, "a.b"]:
        assert store.append_many(bad, [(1, JPEG)]) == 0
    assert store.stats().frame_count == 0


def test_sample_is_equidistant_first_and_last_included(tmp_path) -> None:
    store = SessionFrameStore(str(tmp_path / "f"))
    store.append_many("s1", [(1000 * i, JPEG) for i in range(1, 13)])  # 12 帧
    picked = store.sample(500, 12_500, 5)
    assert [ts for ts, _ in picked] == [1000, 3000, 6000, 9000, 12_000]


def test_sample_returns_all_when_fewer_than_max(tmp_path) -> None:
    store = SessionFrameStore(str(tmp_path / "f"))
    store.append_many("s1", [(1000, JPEG), (2000, JPEG)])
    picked = store.sample(0, 10_000, 12)
    assert [ts for ts, _ in picked] == [1000, 2000]


def test_ttl_prune_removes_expired_by_server_clock(tmp_path) -> None:
    clock = Clock()
    store = SessionFrameStore(str(tmp_path / "f"), ttl_seconds=10, clock=clock)
    store.append_many("s1", [(1000, JPEG)])
    assert store.stats().frame_count == 1
    clock.now += 11  # 超过 TTL
    store.prune()
    assert store.stats().frame_count == 0
    assert not list((tmp_path / "f" / "s1").glob("*.jpg"))


def test_capacity_prune_evicts_oldest(tmp_path) -> None:
    store = SessionFrameStore(str(tmp_path / "f"), ttl_seconds=10**9, max_total_bytes=10)
    payload = b"\xff\xd8" + b"A" * 6  # 每帧 8 字节
    store.append_many("s1", [(1000, payload), (2000, payload), (3000, payload)])
    stats = store.stats()
    assert stats.total_bytes <= 10
    # 从最旧开始删：保留最新一帧
    remaining = store.frames_in_window(0, 10_000)
    assert [e.ts_ms for e in remaining] == [3000]


def test_cold_restart_rebuilds_index_and_cleans_tmp(tmp_path) -> None:
    root = str(tmp_path / "f")
    SessionFrameStore(root).append_many("s1", [(1000, JPEG), (2000, JPEG)])
    # 模拟崩溃残片：下次冷启动应清掉
    (tmp_path / "f" / "s1" / "9999999999999-0000.jpg.tmp").write_bytes(b"junk")
    # 脏文件（非时间戳命名）也应清掉
    (tmp_path / "f" / "s1" / "garbage.jpg").write_bytes(b"junk")

    rebuilt = SessionFrameStore(root)
    assert rebuilt.stats().frame_count == 2
    assert [e.ts_ms for e in rebuilt.frames_in_window(0, 10_000)] == [1000, 2000]
    assert not list((tmp_path / "f" / "s1").glob("*.tmp"))
    assert not (tmp_path / "f" / "s1" / "garbage.jpg").exists()


def test_window_can_be_scoped_to_one_session(tmp_path) -> None:
    store = SessionFrameStore(str(tmp_path / "f"))
    store.append_many("s1", [(1000, JPEG)])
    store.append_many("s2", [(1500, JPEG)])
    assert len(store.frames_in_window(0, 10_000)) == 2
    assert [e.session_id for e in store.frames_in_window(0, 10_000, "s1")] == ["s1"]


# ---------------- 接口层 ----------------


@pytest.fixture
def client(tmp_path) -> TestClient:
    service = MemoryService(
        ChromaMemoryStore(None, f"user_memory_{uuid.uuid4().hex}"),
        HashingEmbeddingProvider(),
        RuleDistillProvider(),
    )
    store = SessionFrameStore(str(tmp_path / "frames"))
    return TestClient(create_app(service=service, session_store=store))


def test_healthz_reports_frame_counts(client: TestClient) -> None:
    body = client.get("/healthz").json()
    assert body["frames"] == 0
    assert body["frame_sessions"] == 0


def test_upload_then_recall_roundtrip(client: TestClient) -> None:
    up = client.post(
        "/session/frames",
        json={"session_id": "s1", "frames": [{"ts": 1000, "image": b64(JPEG)}]},
    ).json()
    assert up == {"ok": True, "accepted": 1, "dropped": 0}

    rc = client.post(
        "/session/recall", json={"from_ts": 0, "to_ts": 5000, "max_frames": 5}
    ).json()
    assert rc["total_in_window"] == 1
    assert rc["frames"][0]["ts"] == 1000
    assert rc["frames"][0]["image"] == b64(JPEG)
    assert client.get("/healthz").json()["frames"] == 1


def test_upload_rejects_non_base64(client: TestClient) -> None:
    resp = client.post(
        "/session/frames",
        json={"session_id": "s1", "frames": [{"ts": 1, "image": "not-base64!!"}]},
    )
    assert resp.status_code == 422


def test_upload_rejects_non_jpeg_payload(client: TestClient) -> None:
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 8
    resp = client.post(
        "/session/frames",
        json={"session_id": "s1", "frames": [{"ts": 1, "image": b64(png)}]},
    )
    assert resp.status_code == 422


def test_upload_rejects_path_traversal_session(client: TestClient) -> None:
    resp = client.post(
        "/session/frames",
        json={"session_id": "../evil", "frames": [{"ts": 1, "image": b64(JPEG)}]},
    )
    assert resp.status_code == 422


def test_upload_rejects_empty_frame_list(client: TestClient) -> None:
    resp = client.post("/session/frames", json={"session_id": "s1", "frames": []})
    assert resp.status_code == 422


def test_recall_rejects_inverted_window(client: TestClient) -> None:
    resp = client.post("/session/recall", json={"from_ts": 5000, "to_ts": 1000})
    assert resp.status_code == 422


def test_recall_without_session_id_spans_all_sessions(client: TestClient) -> None:
    client.post("/session/frames", json={"session_id": "s1", "frames": [{"ts": 1000, "image": b64(JPEG)}]})
    client.post("/session/frames", json={"session_id": "s2", "frames": [{"ts": 2000, "image": b64(JPEG)}]})
    rc = client.post("/session/recall", json={"from_ts": 0, "to_ts": 5000, "max_frames": 5}).json()
    assert sorted(f["ts"] for f in rc["frames"]) == [1000, 2000]
