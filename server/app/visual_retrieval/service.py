"""Hybrid candidate retrieval with bounded evidence delivery and explicit degradation.

Uncalibrated semantic scores are candidates, never a claim of physical identity.
The same Realtime session remains responsible for inspecting and answering.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import time
import uuid

from app.visual_memory_store import VisualMemoryStore, metadata_score
from .keywords import KeywordIndex
from .providers import EmbeddingError
from .query import rrf, variants, VERSION
from .rerank import RerankUnavailable


class VisualRetrievalService:
    def __init__(self, store: VisualMemoryStore, index, provider, reranker=None,
                 channel_limit=50, candidate_limit=100, rerank_limit=30):
        if not 1 <= channel_limit <= 100 or not 1 <= candidate_limit <= 200 or not 1 <= rerank_limit <= 30:
            raise ValueError("invalid retrieval budgets")
        self.store, self.index, self.provider, self.reranker = store, index, provider, reranker
        self.keywords = KeywordIndex(store.db_path)
        self.channel_limit, self.candidate_limit, self.rerank_limit = channel_limit, candidate_limit, rerank_limit

    def search(self, owner, query, from_ts_ms=None, to_ts_ms=None, limit=3,
               raw_query=None, query_image=None, intent="recall") -> dict:
        return self.search_with_trace(owner, query, from_ts_ms, to_ts_ms, limit, raw_query, query_image, intent)[0]

    def search_with_trace(self, owner, query, from_ts_ms=None, to_ts_ms=None, limit=3,
                          raw_query=None, query_image=None, intent="recall") -> tuple[dict, dict]:
        if not 1 <= limit <= 3:
            raise ValueError("invalid final evidence limit")
        started, trace_id = time.monotonic(), uuid.uuid4().hex
        rows = self.store.candidates(owner, from_ts_ms, to_ts_ms)
        by_id = {row["record_id"]: row for row in rows}
        expanded = variants(query, raw_query)
        self.keywords.sync(owner, rows)
        states = self.index.status(owner, from_ts_ms, to_ts_ms)
        warnings = []
        if states["pending"]:
            warnings.append("indexing_pending")
        if states["failed"]:
            warnings.append("indexing_failed")
        meta = {row["record_id"]: max(metadata_score(v, row) for v in expanded) for row in rows}
        exact = sorted((rid for rid, score in meta.items() if score > 0),
                       key=lambda rid: (-meta[rid], -by_id[rid]["captured_at_ms"], rid))
        channels = {"metadata": exact[:self.channel_limit]}
        keyword_lists = {str(i): self.keywords.search(owner, v, from_ts_ms, to_ts_ms, self.channel_limit)
                         for i, v in enumerate(expanded)}
        channels["keyword"] = [rid for rid, _ in rrf(keyword_lists, self.channel_limit)]
        model_failed, semantic = False, False
        # Empty or still-unindexed scopes need no paid query embedding call.
        if states["ready"]:
            try:
                if query_image:
                    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="visual-query") as pool:
                        text_future = pool.submit(self.provider.encode_texts, expanded)
                        image_future = pool.submit(self.provider.encode_images, [query_image])
                        text_vectors, image_vectors = text_future.result(), image_future.result()
                else:
                    text_vectors, image_vectors = self.provider.encode_texts(expanded), []
                for modality in ("text", "image"):
                    ranks = {}
                    for i, vector in enumerate(text_vectors):
                        hits = self.index.search(owner, vector, modality, from_ts_ms, to_ts_ms, self.channel_limit)
                        # Identical generic notes cannot distinguish candidates. Do not let
                        # their arbitrary ID tie-break overrule a discriminating image route.
                        if modality == "text" and len(hits) > 1 and abs(hits[0]["score"] - hits[-1]["score"]) < 1e-7:
                            continue
                        ranks[str(i)] = [hit["record_id"] for hit in hits]
                    channels["text_to_" + modality] = [rid for rid, _ in rrf(ranks, self.channel_limit)]
                if image_vectors:
                    channels["image_to_image"] = [hit["record_id"] for hit in self.index.search(
                        owner, image_vectors[0], "image", from_ts_ms, to_ts_ms, self.channel_limit)]
                semantic = True
            except EmbeddingError:
                warnings.append("embedding_unavailable")
                model_failed = True
        if not semantic and rows:
            warnings.append("keyword_only")
        # Snapshot also gates any index row inserted while this query is running.
        channels = {name: [rid for rid in ids if rid in by_id] for name, ids in channels.items()}
        fused = rrf(channels, self.candidate_limit)
        scores = dict(fused)
        candidate_ids = [rid for rid, _ in fused]
        # Rank-only vector/rerank channels return every indexed record. When the
        # object named in the question matches saved names/aliases, don't fill
        # the evidence budget with unrelated photos merely because they ranked
        # second or third. Prefer the tool's focused query over the raw sentence
        # (which may mention a negated object), then fall back to the raw query.
        # Unanchored image/semantic searches remain broad for recall.
        focused = variants(query)
        focused_meta = {rid: max(metadata_score(v, row) for v in focused) for rid, row in by_id.items()}
        anchors = {rid for rid, score in focused_meta.items() if score >= 0.9}
        if not anchors:
            anchors = {rid for rid, score in meta.items() if score >= 0.9}
        if intent == "find_last_location":
            # Reserve evidence budget before truncation, not after reranking old exact matches.
            recent_anchors = sorted((rid for rid, score in meta.items() if score >= 0.9),
                                    key=lambda rid: (-by_id[rid]["captured_at_ms"], rid))[:limit]
            for rid in recent_anchors:
                scores.setdefault(rid, 1.0 / 61)
            candidate_ids = (recent_anchors + [rid for rid in candidate_ids if rid not in recent_anchors])[:self.candidate_limit]
        if anchors:
            candidate_ids = [rid for rid in candidate_ids if rid in anchors]
            # A strong name match must survive the fused top-K budget even when
            # generic semantic channels outrank it across a large history.
            missing = sorted(anchors.difference(candidate_ids),
                             key=lambda rid: (-focused_meta[rid], -by_id[rid]["captured_at_ms"], rid))
            for rid in missing:
                scores.setdefault(rid, 1.0 / 61)
            candidate_ids = (candidate_ids + missing)[:self.candidate_limit]
        materialised, unavailable = {}, 0
        for rid in candidate_ids:
            try:
                record = self.store.materialise(owner, rid, scores[rid], from_ts_ms, to_ts_ms, by_id[rid]["evidence_version"])
                if record is None:
                    unavailable += 1
                    continue
            except (OSError, ValueError):
                unavailable += 1
                continue
            materialised[rid] = record
            if len(materialised) >= self.rerank_limit:
                break
        if unavailable:
            warnings.append("partial_evidence")
        ordered = list(materialised)
        reranked = []
        if self.reranker is not None and ordered:
            try:
                reranked = self.reranker.rank(query, list(materialised.values()), query_image)
                # Keep explicit lexical evidence as an independent vote, not an arbitrary score sum.
                ordering = {"fused": ordered, "rerank": [rid for rid, _ in reranked]}
                if exact:
                    ordering["explicit"] = [rid for rid in exact if rid in materialised and meta[rid] >= 0.9]
                ordered = [rid for rid, _ in rrf(ordering, self.rerank_limit)]
            except RerankUnavailable:
                warnings.append("rerank_unavailable")
        if semantic:
            warnings.append("uncalibrated_similarity")
        if intent == "find_last_location":
            # Recency is meaningful only after object relevance is established.
            recency_anchors = [rid for rid in ordered if meta.get(rid, 0) >= 0.9]
            if recency_anchors:
                ordered = sorted(recency_anchors, key=lambda rid: (-by_id[rid]["captured_at_ms"], rid)) + [rid for rid in ordered if rid not in recency_anchors]
            elif rows:
                warnings.append("time_scope_incomplete")
            if states["pending"] or states["failed"] or unavailable:
                warnings.append("time_scope_incomplete")
        records = []
        for rid in ordered:
            # Revalidate after remote reranking: delete/TTL may have happened in flight.
            try:
                record = self.store.materialise(owner, rid, scores[rid], from_ts_ms, to_ts_ms, by_id[rid]["evidence_version"])
            except (OSError, ValueError):
                record = None
            if record is None:
                unavailable += 1
                warnings.append("partial_evidence")
                continue
            records.append(record)
            if len(records) == limit:
                break
        if not records and model_failed:
            retrieval_status = "unavailable"
        elif not records and states["pending"]:
            retrieval_status = "indexing"
        elif any(w in warnings for w in ("embedding_unavailable", "rerank_unavailable", "indexing_pending", "indexing_failed", "partial_evidence")):
            retrieval_status = "degraded"
        else:
            retrieval_status = "ready"
        response = {
            "status": "matched" if records else ("evidence_unavailable" if unavailable else "no_match"),
            "records": records, "coverage": self.store.coverage(owner), "unavailable_records_count": unavailable,
            "retrieval": {"mode": "hybrid", "status": retrieval_status,
                "pending_records_count": states["pending"], "failed_records_count": states["failed"],
                "warnings": list(dict.fromkeys(warnings)), "trace_id": trace_id},
        }
        # Only explicit offline evaluation writes traces. No default photos, raw ASR or IDs in logs.
        trace = {"trace_id": trace_id, "query_version": VERSION, "namespace": self.provider.namespace,
                 "channels": channels, "candidate_record_ids": candidate_ids,
                 "reranked_record_ids": [rid for rid, _ in reranked],
                 "final_record_ids": [row["record_id"] for row in records],
                 "abstained": not bool(records), "error": None,
                 "elapsed_ms": round((time.monotonic() - started) * 1000), "retrieval": response["retrieval"]}
        return response, trace
