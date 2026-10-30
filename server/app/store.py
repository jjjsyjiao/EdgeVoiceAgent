"""Chroma 单集合向量索引（cosine）。

索引策略完全收敛在服务端：端侧只按契约收发文本，不感知向量后端与库类型。
向量由 [EmbeddingProvider] 显式提供，故创建集合时关闭 Chroma 自带的 embedding_function，
避免其隐式加载内置模型。
"""

from __future__ import annotations

import time


class ChromaMemoryStore:
    """[path] 为 None 时使用内存实例（单测用），否则持久化到磁盘。"""

    def __init__(self, path: str | None, collection: str):
        import chromadb

        self._client = (
            chromadb.EphemeralClient() if path is None else chromadb.PersistentClient(path=path)
        )
        self._collection = self._client.get_or_create_collection(
            name=collection,
            metadata={"hnsw:space": "cosine"},
            embedding_function=None,
        )

    def count(self) -> int:
        return int(self._collection.count())

    def add(self, memory_id: str, text: str, vector: list[float], kind: str, source: str) -> None:
        self._collection.add(
            ids=[memory_id],
            embeddings=[vector],
            documents=[text],
            metadatas=[{"kind": kind, "source": source, "ts": time.time()}],
        )

    def nearest(self, vector: list[float], top_k: int) -> list[tuple[str, str, float]]:
        """返回 (id, text, 余弦相似度) 列表，按相似度降序。相似度 = 1 - cosine 距离。"""
        if self.count() == 0:
            return []
        result = self._collection.query(
            query_embeddings=[vector],
            n_results=top_k,
            include=["documents", "distances"],
        )
        ids = result["ids"][0]
        documents = result["documents"][0]
        distances = result["distances"][0]
        return [
            (memory_id, document, max(0.0, 1.0 - float(distance)))
            for memory_id, document, distance in zip(ids, documents, distances)
        ]

    def find_duplicate(self, vector: list[float], threshold: float) -> str | None:
        """近重复检测：相似度达到 [threshold] 即视为同一条记忆，返回其 id。"""
        hits = self.nearest(vector, top_k=1)
        if hits and hits[0][2] >= threshold:
            return hits[0][0]
        return None
