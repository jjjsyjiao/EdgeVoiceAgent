"""Single background worker: short database transactions, no network under locks."""
from __future__ import annotations

import hashlib
import json
import threading

from app.visual_memory_store import MAX_JPEG_BYTES, validate_jpeg
from .index import IndexJob, VisualVectorIndex
from .providers import EmbeddingError, MultimodalEmbeddingProvider


def user_record_text(record: dict) -> str:
    aliases = json.loads(record["aliases"]) if isinstance(record["aliases"], str) else record["aliases"]
    # Only stored user-provided fields. No model-generated visual description.
    return "\n".join(str(v).strip() for v in
                     [record["object_name"], *aliases, record["location"], record["note"]] if str(v).strip())


class VisualIndexWorker:
    def __init__(self, index: VisualVectorIndex, provider: MultimodalEmbeddingProvider,
                 poll_seconds: float = 0.5, lease_seconds: float = 60, max_attempts: int = 8) -> None:
        if index.namespace != provider.namespace or index.dimension != provider.dimension:
            raise ValueError("provider and vector index namespace must agree")
        if not 0 < poll_seconds <= 60 or not 1 <= lease_seconds <= 600 or not 1 <= max_attempts <= 100:
            raise ValueError("invalid worker configuration")
        self.index = index
        self.provider = provider
        self.poll_seconds = poll_seconds
        self.lease_seconds = lease_seconds
        self.max_attempts = max_attempts
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._run_lock = threading.Lock()
        self._lifecycle_lock = threading.Lock()

    def start(self) -> None:
        with self._lifecycle_lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(target=self._loop, name="visual-index-worker", daemon=True)
            self._thread.start()

    def stop(self, timeout: float = 2) -> bool:
        if not 0 <= timeout <= 10:
            raise ValueError("invalid worker stop timeout")
        # Do not wait for the lifecycle/SQLite completion lock before setting
        # this flag: shutdown must remain bounded even under DB contention.
        self._stop.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout)
        # A slow HTTP call can outlive this bounded wait. Its result is discarded
        # once it returns. A DB completion already begun may finish atomically.
        # The lease is released/recovered, and the thread is daemon.
        return thread is None or not thread.is_alive()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                worked = self.run_once()
            except Exception:
                # e.g. temporary SQLite contention. Never log user data or key.
                worked = False
            if not worked:
                self._stop.wait(self.poll_seconds)

    def _read_image(self, job: IndexJob) -> bytes:
        path = (self.index.root / "images" / hashlib.sha256(job.owner.encode()).hexdigest()
                / (hashlib.sha256(job.record_id.encode()).hexdigest() + ".jpg"))
        with path.open("rb") as image_file:
            raw = image_file.read(MAX_JPEG_BYTES + 1)
        if len(raw) != job.record["image_bytes"] or hashlib.sha256(raw).hexdigest() != job.record["image_sha256"]:
            raise ValueError("stored image integrity mismatch")
        validate_jpeg(raw)
        return raw

    def run_once(self) -> bool:
        # Explicit run_once is useful for migrations/testing but may not race
        # the background consumer on the same worker instance.
        with self._run_lock:
            job = self.index.claim(self.lease_seconds)
            if job is None:
                return False
            try:
                try:
                    jpeg = self._read_image(job)
                except (OSError, ValueError):
                    self.index.fail(job, "index_evidence_unavailable", permanent=True)
                    return True
                images = self.provider.encode_images([jpeg])
                if self._stop.is_set():
                    self.index.fail(job, "index_stopped", max_attempts=self.max_attempts)
                    return True
                texts = self.provider.encode_texts([user_record_text(job.record)])
                if len(images) != 1 or len(texts) != 1:
                    raise EmbeddingError("embedding_result_count")
                with self._lifecycle_lock:
                    if self._stop.is_set():
                        self.index.fail(job, "index_stopped", max_attempts=self.max_attempts)
                    else:
                        self.index.complete(job, images[0], texts[0])
            except EmbeddingError as exc:
                self.index.fail(job, exc.code, max_attempts=self.max_attempts)
            except Exception:
                self.index.fail(job, "index_internal_error", max_attempts=self.max_attempts)
            return True
