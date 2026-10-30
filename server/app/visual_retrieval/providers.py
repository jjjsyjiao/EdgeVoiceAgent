"""Bounded DashScope independent image/text embeddings, without an SDK dependency.

Official contract: https://help.aliyun.com/zh/model-studio/multimodal-embedding-api-reference
In qwen3 independent mode every result can have type=vl; only index maps an
embedding back to the corresponding input. Never use type for this mapping.
"""
from __future__ import annotations

import base64
import hashlib
import json
import math
import time
from typing import Protocol
from urllib.parse import urlsplit

import httpx

from app.visual_memory_store import validate_jpeg

DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding"


class EmbeddingError(RuntimeError):
    """Contains a fixed, safe code only: never retain a request/body/API key."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class MultimodalEmbeddingProvider(Protocol):
    namespace: str
    dimension: int

    def encode_texts(self, texts: list[str]) -> list[list[float]]: ...
    def encode_images(self, images: list[bytes]) -> list[list[float]]: ...


def normalise_vector(vector: list[float], dimension: int) -> list[float]:
    if not isinstance(vector, list) or len(vector) != dimension:
        raise EmbeddingError("embedding_invalid_dimension")
    if any(isinstance(x, bool) or not isinstance(x, (int, float)) for x in vector):
        raise EmbeddingError("embedding_invalid_vector")
    try:
        values = [float(x) for x in vector]
        if not all(math.isfinite(x) for x in values):
            raise EmbeddingError("embedding_invalid_vector")
        # Scaling before norm prevents overflow/underflow for finite inputs.
        scale = max(abs(x) for x in values)
        if scale == 0:
            raise EmbeddingError("embedding_zero_vector")
        values = [x / scale for x in values]
        norm = math.sqrt(math.fsum(x * x for x in values))
        return [x / norm for x in values]
    except (OverflowError, ValueError) as exc:
        raise EmbeddingError("embedding_invalid_vector") from None


class DashscopeMultimodalEmbeddingProvider:
    def __init__(
        self,
        api_key: str,
        endpoint: str = DEFAULT_ENDPOINT,
        model: str = "qwen3-vl-embedding",
        dimension: int = 2560,
        timeout_seconds: float = 12.0,
        batch_size: int = 10,
        instruct: str = "",
        *,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        parsed = urlsplit(endpoint)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment or parsed.query:
            raise ValueError("embedding endpoint must be an HTTPS URL without credentials/query")
        if not api_key or not api_key.strip() or any(c in api_key for c in "\r\n"):
            raise ValueError("embedding API key is required")
        if not model or not 1 <= dimension <= 8192 or not 0 < timeout_seconds <= 60 or not 1 <= batch_size <= 10:
            raise ValueError("invalid embedding configuration")
        if model == "qwen3-vl-embedding" and dimension not in {2560, 2048, 1536, 1024, 768, 512, 256}:
            raise ValueError("unsupported qwen3-vl-embedding dimension")
        if len(instruct) > 2048:
            raise ValueError("embedding instruction is too long")
        self.dimension = dimension
        self.model = model
        self.endpoint = endpoint
        self.batch_size = batch_size
        self.instruct = instruct
        identity = {
            "provider": "dashscope", "model": model, "dimension": dimension,
            "preprocess": "jpeg-original-user-fields-v1", "instruct_version": "v1",
            "instruct_sha256": hashlib.sha256(instruct.encode()).hexdigest(),
            "endpoint_sha256": hashlib.sha256(endpoint.encode()).hexdigest(),
            "independent": True,
        }
        self.namespace = "dashscope:" + model + ":" + str(dimension) + ":" + hashlib.sha256(
            json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()[:24]
        self._client = httpx.Client(
            headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
            timeout=httpx.Timeout(timeout_seconds), follow_redirects=False,
            transport=transport, trust_env=False,
        )

    def close(self) -> None:
        self._client.close()

    def encode_texts(self, texts: list[str]) -> list[list[float]]:
        if not isinstance(texts, list) or len(texts) > 100:
            raise EmbeddingError("embedding_invalid_input")
        if any(not isinstance(t, str) or not t.strip() or len(t) > 16000 for t in texts):
            raise EmbeddingError("embedding_invalid_input")
        return self._encode([{"text": t} for t in texts])

    def encode_images(self, images: list[bytes]) -> list[list[float]]:
        if not isinstance(images, list) or len(images) > 100:
            raise EmbeddingError("embedding_invalid_input")
        for raw in images:
            if not isinstance(raw, bytes):
                raise EmbeddingError("embedding_invalid_input")
            try:
                validate_jpeg(raw)
            except ValueError:
                raise EmbeddingError("embedding_invalid_image") from None
        return self._encode([{"image": "data:image/jpeg;base64," + base64.b64encode(raw).decode("ascii")} for raw in images])

    def _encode(self, contents: list[dict]) -> list[list[float]]:
        vectors = []
        for offset in range(0, len(contents), self.batch_size):
            batch = contents[offset:offset + self.batch_size]
            for attempt in range(2):
                try:
                    batch_vectors = self._encode_batch(batch)
                    break
                except EmbeddingError as error:
                    if attempt or error.code not in {"embedding_rate_limited", "embedding_upstream_error", "embedding_transport_error"}:
                        raise
                    # A single bounded retry helps when indexing and query calls briefly overlap.
                    time.sleep(0.4)
            vectors.extend(batch_vectors)
        return vectors

    def _encode_batch(self, batch: list[dict]) -> list[list[float]]:
        parameters: dict = {"dimension": self.dimension, "enable_fusion": False, "output_type": "dense"}
        if self.instruct:
            parameters["instruct"] = self.instruct
        payload = {"model": self.model, "input": {"contents": batch}, "parameters": parameters}
        try:
            # Bound the response before parsing; never retain a failed body.
            with self._client.stream("POST", self.endpoint, json=payload) as response:
                if response.status_code != 200:
                    code = "embedding_auth_failed" if response.status_code in {401, 403} else (
                        "embedding_rate_limited" if response.status_code == 429 else (
                            "embedding_upstream_error" if response.status_code >= 500 else "embedding_http_error")
                    )
                    raise EmbeddingError(code)
                body = bytearray()
                max_bytes = len(batch) * self.dimension * 40 + 65536
                for chunk in response.iter_bytes():
                    body.extend(chunk)
                    if len(body) > max_bytes:
                        raise EmbeddingError("embedding_response_too_large")
            result = json.loads(body)
        except EmbeddingError:
            raise
        except httpx.TimeoutException:
            raise EmbeddingError("embedding_timeout") from None
        except httpx.HTTPError:
            raise EmbeddingError("embedding_transport_error") from None
        except (ValueError, TypeError):
            raise EmbeddingError("embedding_invalid_response") from None
        embeddings = result.get("output", {}).get("embeddings") if isinstance(result, dict) and isinstance(result.get("output"), dict) else None
        if not isinstance(embeddings, list) or len(embeddings) != len(batch):
            raise EmbeddingError("embedding_result_count")
        by_index: dict[int, list[float]] = {}
        for entry in embeddings:
            if not isinstance(entry, dict):
                raise EmbeddingError("embedding_invalid_response")
            index = entry.get("index")
            if type(index) is not int or not 0 <= index < len(batch) or index in by_index:
                raise EmbeddingError("embedding_invalid_index")
            if entry.get("type") not in {"vl", next(iter(batch[index]))}:
                raise EmbeddingError("embedding_invalid_type")
            by_index[index] = normalise_vector(entry.get("embedding"), self.dimension)
        return [by_index[i] for i in range(len(batch))]
