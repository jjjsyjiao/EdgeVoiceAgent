"""本地多语神经嵌入（默认后端）。

模型默认 ``paraphrase-multilingual-MiniLM-L12-v2``：约 120MB，中英文均可，
完全本地推理、无需 API key。模型在首次构造时加载并常驻进程。
"""

from __future__ import annotations

from . import EmbeddingProvider


class SentenceTransformerEmbeddingProvider(EmbeddingProvider):
    def __init__(self, model_name: str):
        # lazy import：未安装 sentence-transformers 时仍可用 hashing 后端启动服务
        from sentence_transformers import SentenceTransformer

        self._model_name = model_name
        self._model = SentenceTransformer(model_name)
        self._dim = int(self._model.get_sentence_embedding_dimension())

    @property
    def name(self) -> str:
        return f"st:{self._model_name}"

    @property
    def dim(self) -> int:
        return self._dim

    def embed(self, texts: list[str]) -> list[list[float]]:
        vectors = self._model.encode(
            list(texts),
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        return [[float(x) for x in row] for row in vectors.tolist()]
