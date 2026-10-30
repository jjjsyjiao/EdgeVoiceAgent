"""零依赖确定性嵌入：字符 n-gram 哈希到固定维度后 L2 归一化。

用于 CI / 单测 / 无模型环境兜底：不需下载任何模型即可让链路端到端跑通，
但检索语义弱于神经模型，生产环境请使用 ``st`` 后端。
"""

from __future__ import annotations

import hashlib
import math

from . import EmbeddingProvider


class HashingEmbeddingProvider(EmbeddingProvider):
    def __init__(self, dim: int = 256, ngrams: tuple[int, ...] = (2, 3)):
        self._dim = dim
        self._ngrams = ngrams

    @property
    def name(self) -> str:
        return "hashing"

    @property
    def dim(self) -> int:
        return self._dim

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_one(t) for t in texts]

    def _embed_one(self, text: str) -> list[float]:
        vec = [0.0] * self._dim
        normalized = text.lower().strip()
        for n in self._ngrams:
            for i in range(len(normalized) - n + 1):
                gram = normalized[i : i + n]
                digest = hashlib.blake2b(gram.encode("utf-8"), digest_size=8).digest()
                vec[int.from_bytes(digest, "big") % self._dim] += 1.0
        norm = math.sqrt(sum(v * v for v in vec))
        return [v / norm for v in vec] if norm > 0 else vec
