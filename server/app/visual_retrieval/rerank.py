"""DashScope multimodal ranking only; no generated comparison or answer."""
from __future__ import annotations

import base64
import math
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit

import httpx
from .query import rrf


class RerankUnavailable(RuntimeError):
    def __init__(self):
        super().__init__("rerank_unavailable")


class DashscopeReranker:
    def __init__(self, api_key: str, endpoint: str, model="qwen3-vl-rerank", timeout_seconds=8.0, client=None):
        parts = urlsplit(endpoint)
        if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
            raise ValueError("Rerank endpoint must be an HTTPS URL without credentials/query/fragment")
        if not api_key or "\n" in api_key or "\r" in api_key:
            raise ValueError("Rerank credential is required")
        self.endpoint, self.model, self._key = endpoint, model, api_key
        self.client = client or httpx.Client(timeout=timeout_seconds, follow_redirects=False)
        self._owns_client = client is None

    def close(self):
        if self._owns_client:
            self.client.close()

    def rank(self, query: str, records: list[dict], query_image: bytes | None = None) -> list[tuple[str, float]]:
        if query_image:
            with ThreadPoolExecutor(max_workers=2, thread_name_prefix="visual-rerank") as pool:
                textual = pool.submit(self._rank_one, query, records, None)
                visual = pool.submit(self._rank_one, query, records, query_image)
                # Rank fusion preserves both constraints without mixing incomparable scores.
                return rrf({"text": [rid for rid, _ in textual.result()],
                            "image": [rid for rid, _ in visual.result()]}, len(records))
        return self._rank_one(query, records, None)

    def _rank_one(self, query: str, records: list[dict], query_image: bytes | None) -> list[tuple[str, float]]:
        if not records:
            return []
        if len(records) > 30:
            raise ValueError("rerank batch is limited to 30 evidence images")
        # Separate text/image queries follow the official one-modality query contract.
        value = {"image": "data:image/jpeg;base64," + base64.b64encode(query_image).decode()} if query_image else {"text": query}
        documents = [{"image": "data:image/jpeg;base64," + row["image_base64"]} for row in records]
        body = {"model": self.model, "input": {"query": value, "documents": documents},
                "parameters": {"top_n": len(records), "return_documents": False,
                               "instruct": "Retrieve images containing the queried object or scene. Visual similarity does not establish physical identity."}}
        try:
            with self.client.stream("POST", self.endpoint, headers={"Authorization": "Bearer " + self._key}, json=body,
                                    follow_redirects=False) as response:
                if response.status_code != 200:
                    raise RerankUnavailable()
                chunks, size = [], 0
                for chunk in response.iter_bytes():
                    size += len(chunk)
                    if size > 256 * 1024:
                        raise RerankUnavailable()
                    chunks.append(chunk)
                import json
                data = json.loads(b"".join(chunks))
            results = data["output"]["results"]
            if not isinstance(results, list) or len(results) != len(records):
                raise RerankUnavailable()
            seen, ranked = set(), []
            for result in results:
                index, score = result["index"], result["relevance_score"]
                if type(index) is not int or index not in range(len(records)) or index in seen or type(score) not in (int, float) or not math.isfinite(score):
                    raise RerankUnavailable()
                seen.add(index)
                ranked.append((records[index]["record_id"], float(score)))
            return sorted(ranked, key=lambda item: (-item[1], item[0]))
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise RerankUnavailable() from None
