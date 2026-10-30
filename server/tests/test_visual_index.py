"""Durability/isolation/lifecycle contracts, not semantic retrieval metrics."""
import hashlib
import io
import sqlite3
import threading
import time

from PIL import Image
import pytest

from app.visual_memory_store import VisualMemoryStore
from app.visual_retrieval.index import VisualVectorIndex
from app.visual_retrieval import index as index_module
from app.visual_retrieval.providers import EmbeddingError
from app.visual_retrieval.worker import VisualIndexWorker, user_record_text


class Clock:
    now = 1800000000.0
    def __call__(self):
        return self.now


class FakeProvider:
    namespace = "contract-test-vectors-v1"
    dimension = 3
    def __init__(self):
        self.calls = []
    def encode_images(self, images):
        self.calls.append(("images", len(images)))
        return [[1., 0., 0.] for _ in images]
    def encode_texts(self, texts):
        self.calls.append(("texts", list(texts)))
        return [[0., 1., 0.] for _ in texts]


def jpeg(colour="red"):
    data = io.BytesIO()
    Image.new("RGB", (16, 16), colour).save(data, "JPEG")
    return data.getvalue()


def add(store, owner="a", record_id="r1", timestamp=1799999000000, **kwargs):
    values = {"record_id": record_id, "session_id": "s1", "captured_at_ms": timestamp,
              "object_name": "钥匙", "aliases": ["备用钥匙"], "location": "书桌右边", "note": "记一下，钥匙在书桌右边"}
    values.update(kwargs)
    return store.add(owner, values, jpeg())


@pytest.fixture
def parts(tmp_path):
    clock = Clock()
    store = VisualMemoryStore(str(tmp_path), clock=clock)
    provider = FakeProvider()
    index = VisualVectorIndex(tmp_path, provider.namespace, provider.dimension, clock=clock)
    worker = VisualIndexWorker(index, provider)
    return store, index, worker, provider, clock


def test_insert_trigger_indexes_images_and_only_user_text(parts):
    store, index, worker, provider, _ = parts
    add(store)
    assert index.status("a")["pending"] == 1
    assert worker.run_once()
    assert index.status("a")["ready"] == 1
    assert provider.calls == [("images", 1), ("texts", ["钥匙\n备用钥匙\n书桌右边\n记一下，钥匙在书桌右边"])]
    assert index.search("a", [1, 0, 0], "image")[0]["score"] == 1
    assert index.search("a", [1, 0, 0], "text")[0]["score"] == 0
    assert not worker.run_once()


def test_startup_backfill_and_idempotent_restart(tmp_path):
    clock = Clock()
    store = VisualMemoryStore(str(tmp_path), clock=clock)
    add(store)
    provider = FakeProvider()
    index = VisualVectorIndex(tmp_path, provider.namespace, 3, clock)
    assert index.status("a")["pending"] == 1
    VisualIndexWorker(index, provider).run_once()
    again = VisualVectorIndex(tmp_path, provider.namespace, 3, clock)
    assert again.status("a")["ready"] == 1
    assert again.claim() is None


def test_search_and_status_enforce_owner_half_open_time(parts):
    store, index, worker, _, _ = parts
    add(store, "a", "r1", 1000)
    add(store, "a", "r2", 2000)
    add(store, "b", "r1", 1000)
    while worker.run_once():
        pass
    assert [r["record_id"] for r in index.search("a", [1, 0, 0], from_ts_ms=1000, to_ts_ms=2000)] == ["r1"]
    assert [r["record_id"] for r in index.search("b", [1, 0, 0])] == ["r1"]
    assert index.search("nobody", [1, 0, 0]) == []
    assert index.status("a", 1000, 2000)["total"] == 1
    assert index.status("a", 2001, None)["total"] == 0


def test_delete_during_provider_call_cannot_resurrect_record(parts):
    store, index, worker, provider, _ = parts
    add(store)
    def delete_during_embedding(images):
        store.delete("a", "r1")
        return [[1, 0, 0]]
    provider.encode_images = delete_during_embedding
    worker.run_once()
    assert index.status("a")["total"] == 0
    with index._connect() as db:
        assert db.execute("SELECT count(*) FROM visual_vectors").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM visual_index_jobs").fetchone()[0] == 0


def test_delete_then_same_id_reinsert_rejects_old_lease(parts):
    store, index, _, _, clock = parts
    add(store)
    old = index.claim()
    store.delete("a", "r1")
    clock.now += 1
    add(store)
    assert not index.complete(old, [1, 0, 0], [0, 1, 0])
    assert index.status("a")["pending"] == 1
    fresh = index.claim()
    assert index.complete(fresh, [1, 0, 0], [0, 1, 0])


def test_ttl_prune_removes_vectors_and_jobs(parts):
    store, index, worker, _, clock = parts
    add(store)
    worker.run_once()
    clock.now += 8 * 86400
    store.prune()
    assert index.search("a", [1, 0, 0]) == []
    with index._connect() as db:
        assert db.execute("SELECT count(*) FROM visual_vectors").fetchone()[0] == 0


def test_restart_recovers_expired_lease_and_rejects_old_completion(parts):
    store, index, _, provider, clock = parts
    add(store)
    stale = index.claim(lease_seconds=10)
    restarted = VisualVectorIndex(store.root, provider.namespace, 3, clock)
    assert restarted.claim() is None
    clock.now += 11
    fresh = restarted.claim()
    assert fresh.lease_token != stale.lease_token
    assert not index.complete(stale, [1, 0, 0], [0, 1, 0])
    assert restarted.complete(fresh, [1, 0, 0], [0, 1, 0])


def test_failure_backoff_exhaustion_manual_retry_no_sensitive_details(parts):
    store, index, worker, provider, clock = parts
    add(store)
    worker.max_attempts = 2
    def broken(_):
        raise RuntimeError("sensitive key and personal note")
    provider.encode_images = broken
    worker.run_once()
    assert index.status("a")["retry"] == 1
    assert not worker.run_once()
    clock.now += 3
    worker.run_once()
    assert index.status("a")["failed"] == 1
    with index._connect() as db:
        assert db.execute("SELECT error_code FROM visual_index_jobs").fetchone()[0] == "index_internal_error"
    assert index.retry_failed("b") == 0
    assert index.retry_failed("a") == 1
    assert index.status("a")["pending"] == 1


def test_corrupt_image_is_not_sent_to_provider(parts):
    store, index, worker, provider, _ = parts
    add(store)
    store._path("a", "r1").write_bytes(jpeg("blue"))
    worker.run_once()
    assert provider.calls == []
    assert index.status("a")["failed"] == 1


def test_namespace_upgrade_reindexes_and_never_mixes_vectors(parts):
    store, old, worker, provider, clock = parts
    add(store)
    worker.run_once()
    new = VisualVectorIndex(store.root, "contract-test-v2", 2, clock)
    assert new.status("a")["pending"] == 1
    assert new.search("a", [1, 0]) == []
    job = new.claim()
    new.complete(job, [0, 1], [1, 0])
    assert new.search("a", [1, 0])[0]["score"] == 0
    assert old.search("a", [1, 0, 0])[0]["score"] == 1
    with pytest.raises(ValueError, match="dimension mismatch"):
        VisualVectorIndex(store.root, provider.namespace, 4, clock)


def test_both_modalities_commit_atomically(parts):
    store, index, _, _, _ = parts
    add(store)
    job = index.claim()
    with pytest.raises(EmbeddingError):
        index.complete(job, [1, 0, 0], [0, 0, 0])
    with index._connect() as db:
        assert db.execute("SELECT count(*) FROM visual_vectors").fetchone()[0] == 0
    assert index.status("a")["ready"] == 0


def test_network_phase_holds_no_write_transaction(parts):
    store, index, worker, provider, _ = parts
    add(store)
    def embed_while_writing(_):
        # A separate SQLite connection with no wait must acquire write lock.
        with sqlite3.connect(index.db_path, timeout=0) as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("UPDATE records SET location=location WHERE owner='a'")
        return [[1, 0, 0]]
    provider.encode_images = embed_while_writing
    worker.run_once()
    assert index.status("a")["ready"] == 1


def test_stop_is_bounded_and_inflight_result_cannot_commit(parts):
    store, index, worker, provider, _ = parts
    add(store)
    entered, release = threading.Event(), threading.Event()
    def blocked(_):
        entered.set()
        release.wait(3)
        return [[1, 0, 0]]
    provider.encode_images = blocked
    worker.start()
    assert entered.wait(1)
    before = time.monotonic()
    assert not worker.stop(timeout=.02)
    assert time.monotonic() - before < .5
    release.set()
    assert worker.stop(timeout=1)
    assert index.status("a")["ready"] == 0
    assert index.status("a")["retry"] == 1


def test_multiple_claimers_get_distinct_jobs_and_ties_are_stable(parts):
    store, index, _, _, _ = parts
    add(store, record_id="r2")
    add(store, record_id="r1")
    a, b = index.claim(), index.claim()
    assert a.record_id != b.record_id
    for job in (a, b):
        index.complete(job, [1, 0, 0], [1, 0, 0])
    assert [x["record_id"] for x in index.search("a", [1, 0, 0])] == ["r1", "r2"]


def test_ready_missing_vector_is_repaired_on_restart(parts):
    store, index, worker, provider, clock = parts
    add(store)
    worker.run_once()
    with index._connect() as db:
        db.execute("DELETE FROM visual_vectors WHERE modality='text'")
    restart = VisualVectorIndex(store.root, provider.namespace, 3, clock)
    assert restart.status("a")["pending"] == 1


def test_record_update_invalidates_vectors_and_outstanding_lease(parts):
    store, index, worker, _, _ = parts
    add(store)
    old = index.claim()
    with index._connect() as db:
        db.execute("UPDATE records SET note='用户更新了原话' WHERE owner='a' AND record_id='r1'")
    assert not index.complete(old, [1, 0, 0], [0, 1, 0])
    assert worker.run_once()
    assert index.status("a")["ready"] == 1
    with index._connect() as db:
        db.execute("UPDATE records SET note='再次更新' WHERE owner='a' AND record_id='r1'")
    assert index.search("a", [1, 0, 0]) == []
    assert index.status("a")["pending"] == 1


def test_stdlib_dot_fallback_preserves_cosine_ranking(parts, monkeypatch):
    store, index, _, _, _ = parts
    add(store, record_id="first")
    add(store, record_id="second")
    for vector in ([1, 2, 3], [3, -2, -1]):
        job = index.claim()
        index.complete(job, vector, vector)
    optimized = index.search("a", [1, 2, 3])
    monkeypatch.setattr(index_module, "_SUMPROD", None)
    fallback = index.search("a", [1, 2, 3])
    assert [x["record_id"] for x in optimized] == [x["record_id"] for x in fallback]
    assert [x["score"] for x in optimized] == pytest.approx([x["score"] for x in fallback])


def test_corrupt_vector_fails_closed(parts):
    from array import array
    store, index, worker, _, _ = parts
    add(store)
    worker.run_once()
    with index._connect() as db:
        db.execute("UPDATE visual_vectors SET vector=? WHERE modality='image'", (array('f', [float('nan'), 0, 0]).tobytes(),))
    with pytest.raises(EmbeddingError, match="embedding_invalid_vector"):
        index.search("a", [1, 0, 0])
