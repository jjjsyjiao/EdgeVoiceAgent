"""记忆业务编排：嵌入 → 去重/入库 → 检索 → 提炼。

本层不依赖 FastAPI，便于单元测试直接调用。
"""

from __future__ import annotations

import uuid

from .config import Settings
from .distill import DistillProvider, build_distill_provider
from .embedding import EmbeddingProvider, build_embedding_provider
from .store import ChromaMemoryStore


class MemoryService:
    def __init__(
        self,
        store: ChromaMemoryStore,
        embedder: EmbeddingProvider,
        distiller: DistillProvider,
        dedup_threshold: float = 0.95,
    ):
        self._store = store
        self._embedder = embedder
        self._distiller = distiller
        self._dedup_threshold = dedup_threshold

    @property
    def embedder_name(self) -> str:
        return self._embedder.name

    @property
    def distiller_name(self) -> str:
        return self._distiller.name

    def count(self) -> int:
        return self._store.count()

    def add(self, text: str, kind: str, source: str) -> tuple[bool, str]:
        """写入一条记忆；若与既有记忆近重复则幂等返回其 id（不重复入库）。"""
        text = text.strip()
        vector = self._embedder.embed([text])[0]
        duplicate = self._store.find_duplicate(vector, self._dedup_threshold)
        if duplicate is not None:
            return True, duplicate
        memory_id = uuid.uuid4().hex
        self._store.add(memory_id, text, vector, kind, source)
        return True, memory_id

    def search(self, query: str, top_k: int) -> list[tuple[str, str, float]]:
        vector = self._embedder.embed([query.strip()])[0]
        return self._store.nearest(vector, top_k)

    def distill(self, turns: list[str]) -> list[str]:
        return self._distiller.distill(turns)


def build_service(settings: Settings) -> MemoryService:
    return MemoryService(
        store=ChromaMemoryStore(settings.chroma_dir, settings.collection),
        embedder=build_embedding_provider(settings.embedding_provider, settings.embedding_model),
        distiller=build_distill_provider(
            settings.llm_base_url, settings.llm_api_key, settings.llm_model
        ),
        dedup_threshold=settings.dedup_threshold,
    )
