"""可插拔向量嵌入契约。

新增后端（BGE-M3、CLIP 多模态等）只需实现 [EmbeddingProvider]，
并在 [build_embedding_provider] 中注册，索引/接口层无需改动。
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class EmbeddingProvider(ABC):
    """文本 → 向量。实现须返回 **L2 归一化** 向量，cosine 距离才有意义。"""

    @property
    @abstractmethod
    def name(self) -> str:
        """后端标识，用于 /healthz 自检与日志。"""

    @property
    @abstractmethod
    def dim(self) -> int:
        """向量维度。"""

    @abstractmethod
    def embed(self, texts: list[str]) -> list[list[float]]:
        """批量编码，返回与 [texts] 等长的归一化向量列表。"""


def build_embedding_provider(provider: str, model_name: str) -> EmbeddingProvider:
    """按配置装配嵌入后端。

    - ``st``：本地神经模型（默认，语义效果好，需要 sentence-transformers）；
    - ``hashing``：零依赖确定性哈希（CI/单测与离线兜底）。
    """
    if provider == "hashing":
        from .hashing import HashingEmbeddingProvider

        return HashingEmbeddingProvider()
    if provider == "st":
        from .local_st import SentenceTransformerEmbeddingProvider

        return SentenceTransformerEmbeddingProvider(model_name)
    raise ValueError(f"未知 EMBEDDING_PROVIDER: {provider}（可选 st / hashing）")
