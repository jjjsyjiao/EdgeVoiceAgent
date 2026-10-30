"""端到端接口测试：使用 Chroma 临时内存实例 + 哈希嵌入 + 规则提炼，零模型下载。"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from app.distill.rule import RuleDistillProvider
from app.embedding.hashing import HashingEmbeddingProvider
from app.main import create_app
from app.service import MemoryService
from app.session_store import SessionFrameStore
from app.store import ChromaMemoryStore


@pytest.fixture
def client(tmp_path) -> TestClient:
    # path=None -> Chroma 内存实例；EphemeralClient 进程内共享，故用唯一集合名隔离用例
    service = MemoryService(
        ChromaMemoryStore(None, f"user_memory_{uuid.uuid4().hex}"),
        HashingEmbeddingProvider(),
        RuleDistillProvider(),
    )
    # V3：帧留存注入临时目录，避免真实 ./data/frames 落盘
    store = SessionFrameStore(str(tmp_path / "frames"))
    return TestClient(create_app(service=service, session_store=store))


def test_healthz_reports_backends(client: TestClient) -> None:
    body = client.get("/healthz").json()
    assert body["status"] == "ok"
    assert body["embedding"] == "hashing"
    assert body["distill"] == "rule"


def test_add_then_search_returns_hit(client: TestClient) -> None:
    added = client.post("/memory/add", json={"text": "我喜欢喝拿铁咖啡"}).json()
    assert added["ok"] is True and added["id"]

    hits = client.post("/memory/search", json={"query": "我喜欢喝拿铁咖啡", "top_k": 3}).json()["hits"]
    assert len(hits) == 1
    assert hits[0]["text"] == "我喜欢喝拿铁咖啡"
    assert 0.0 <= hits[0]["score"] <= 1.0


def test_add_is_idempotent_for_duplicates(client: TestClient) -> None:
    first = client.post("/memory/add", json={"text": "我偏好深色主题"}).json()
    second = client.post("/memory/add", json={"text": "我偏好深色主题"}).json()
    assert first["id"] == second["id"]
    assert client.get("/healthz").json()["memories"] == 1


def test_distill_extracts_preference_sentences(client: TestClient) -> None:
    body = client.post(
        "/memory/distill",
        json={"turns": ["今天天气不错。记住我喜欢在下雨天喝热茶。", "好的，已了解。"]},
    ).json()
    assert body["preferences"] == ["记住我喜欢在下雨天喝热茶"]


def test_add_rejects_empty_text(client: TestClient) -> None:
    assert client.post("/memory/add", json={"text": ""}).status_code == 422


@pytest.mark.parametrize("blank", ["   ", "\t", "\n", " \t \n "])
def test_add_rejects_whitespace_only_text(client: TestClient, blank: str) -> None:
    # 长度合法但 strip 后为空：必须 422，不能让脏文本落库
    assert client.post("/memory/add", json={"text": blank}).status_code == 422


def test_add_trims_surrounding_whitespace(client: TestClient) -> None:
    # 合法文本的首尾空白应被规范化去除
    resp = client.post(
        "/memory/add",
        json={"text": "  我习惯九点开会  ", "kind": "preference", "source": "user"},
    )
    assert resp.status_code == 200
    hit = client.post("/memory/search", json={"query": "几点开会", "top_k": 1}).json()["hits"][0]
    assert hit["text"] == "我习惯九点开会"


@pytest.mark.parametrize("blank", ["   ", "\t\n"])
def test_search_rejects_blank_query(client: TestClient, blank: str) -> None:
    assert client.post("/memory/search", json={"query": blank}).status_code == 422


def test_search_rejects_out_of_range_top_k(client: TestClient) -> None:
    assert client.post("/memory/search", json={"query": "x", "top_k": 99}).status_code == 422
