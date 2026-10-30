"""主动记忆 API：只做存储/元数据检索，图片比较留给同一 Realtime 会话。"""

from __future__ import annotations

import base64
import binascii
import json
import re
import secrets
import sqlite3
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .config import Settings
from .visual_memory_store import MAX_JPEG_BYTES, RecordConflict, VisualMemoryStore, validate_jpeg

_SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,96}$")


class VisualRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")


class VisualRecordRequest(VisualRequest):
    record_id: str = Field(min_length=1, max_length=96)
    session_id: str = Field(min_length=1, max_length=96)
    captured_at_ms: int = Field(ge=0, le=253402300799999)
    object_name: str = Field(min_length=1, max_length=128)
    aliases: list[str] = Field(default_factory=list, max_length=16)
    location: str = Field(default="", max_length=256)
    note: str = Field(default="", max_length=1024)
    image_base64: str = Field(min_length=1, max_length=((MAX_JPEG_BYTES + 2) // 3) * 4)

    @field_validator("record_id", "session_id")
    @classmethod
    def safe_ids(cls, value: str) -> str:
        if not _SAFE_ID.fullmatch(value):
            raise ValueError("id must match [A-Za-z0-9_-]{1,96}")
        return value

    @field_validator("object_name")
    @classmethod
    def named_object(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("object_name must not be blank")
        return value

    @field_validator("aliases")
    @classmethod
    def bounded_aliases(cls, values: list[str]) -> list[str]:
        result = []
        for value in values:
            value = value.strip()
            if not value or len(value) > 128:
                raise ValueError("aliases must contain nonblank names of at most 128 characters")
            if value not in result:
                result.append(value)
        return result

    @field_validator("location", "note")
    @classmethod
    def trimmed_text(cls, value: str) -> str:
        return value.strip()

    @field_validator("image_base64")
    @classmethod
    def decodable_jpeg(cls, value: str) -> str:
        try:
            image = base64.b64decode(value, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ValueError("image_base64 must be bare base64") from exc
        validate_jpeg(image)
        return value


class VisualSearchRequest(VisualRequest):
    query: str = Field(min_length=1, max_length=512)
    from_ts_ms: int | None = Field(default=None, ge=0, le=253402300799999)
    to_ts_ms: int | None = Field(default=None, ge=0, le=253402300799999)
    limit: int = Field(default=3, ge=1, le=3)
    raw_query: str | None = Field(default=None, min_length=1, max_length=2048)
    query_image_base64: str | None = Field(default=None, min_length=1, max_length=((MAX_JPEG_BYTES + 2) // 3) * 4)
    intent: Literal["recall", "find_last_location", "compare"] = "recall"

    @field_validator("query_image_base64")
    @classmethod
    def valid_query_image(cls, value: str | None) -> str | None:
        return VisualRecordRequest.decodable_jpeg(value) if value is not None else None

    @field_validator("query")
    @classmethod
    def trimmed_query(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("query must not be blank")
        return value

    @model_validator(mode="after")
    def nonempty_window(self) -> "VisualSearchRequest":
        if self.from_ts_ms is not None and self.to_ts_ms is not None and self.from_ts_ms >= self.to_ts_ms:
            raise ValueError("from_ts_ms must be less than to_ts_ms (exclusive)")
        return self


class VisualRecord(BaseModel):
    record_id: str
    session_id: str
    captured_at_ms: int
    recorded_at_ms: int
    object_name: str
    aliases: list[str]
    location: str
    note: str
    image_base64: str
    source: Literal["user_recorded"] = "user_recorded"
    score: float


class VisualRecordResponse(BaseModel):
    ok: bool
    record: VisualRecord


class VisualCoverage(BaseModel):
    from_ts_ms: int | None
    to_ts_ms: int | None
    retention_days: float
    records_count: int
    continuous: bool = False


class VisualSearchResponse(BaseModel):
    status: Literal["matched", "no_match", "evidence_unavailable"]
    records: list[VisualRecord]
    coverage: VisualCoverage
    unavailable_records_count: int
    retrieval: dict | None = None


def _token_owners(raw: str) -> dict[str, str]:
    if not raw.strip():
        return {}
    try:
        entries = json.loads(raw)
    except (ValueError, TypeError) as exc:
        raise ValueError("VISUAL_MEMORY_AUTH_TOKENS must be a JSON object") from exc
    if not isinstance(entries, dict) or any(
        not isinstance(token, str) or not 16 <= len(token) <= 4096
        or not isinstance(owner, str) or not owner.strip() or len(owner) > 128
        for token, owner in entries.items()
    ):
        raise ValueError("VISUAL_MEMORY_AUTH_TOKENS requires token strings (16..4096 characters) and nonblank owner strings (1..128)")
    return entries


def create_visual_memory_router(settings: Settings, store: VisualMemoryStore | None = None, retrieval=None) -> APIRouter:
    tokens = _token_owners(settings.visual_memory_auth_tokens)
    if tokens and store is None:
        store = VisualMemoryStore(
            settings.visual_memory_dir, settings.visual_memory_retention_days,
            settings.visual_memory_max_bytes, settings.visual_memory_owner_max_bytes,
        )
    worker, provider, reranker = None, None, None
    if tokens and store is not None and settings.visual_retrieval_enabled and retrieval is None:
        from .visual_retrieval.providers import DashscopeMultimodalEmbeddingProvider
        from .visual_retrieval.index import VisualVectorIndex
        from .visual_retrieval.worker import VisualIndexWorker
        from .visual_retrieval.service import VisualRetrievalService
        from .visual_retrieval.rerank import DashscopeReranker
        provider = DashscopeMultimodalEmbeddingProvider(
            settings.visual_embedding_api_key, settings.visual_embedding_endpoint,
            settings.visual_embedding_model, settings.visual_embedding_dimension, settings.visual_embedding_timeout)
        index = VisualVectorIndex(store.root, provider.namespace, provider.dimension)
        worker = VisualIndexWorker(index, provider)
        if settings.visual_rerank_enabled:
            reranker = DashscopeReranker(settings.visual_embedding_api_key, settings.visual_rerank_endpoint,
                                       settings.visual_rerank_model, timeout_seconds=6)
        retrieval = VisualRetrievalService(store, index, provider, reranker)

    @asynccontextmanager
    async def lifespan(_app):
        if worker is not None:
            worker.start()
        try:
            yield
        finally:
            if worker is not None and worker.stop(timeout=2) and provider is not None:
                provider.close()
            if reranker is not None:
                reranker.close()

    router = APIRouter(prefix="/visual-memory", tags=["visual-memory"], lifespan=lifespan)
    bearer = HTTPBearer(auto_error=False)

    def owner(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)) -> str:
        if not tokens:
            raise HTTPException(status_code=503, detail="visual_memory_not_configured")
        if credentials is not None and credentials.scheme.lower() == "bearer" and len(credentials.credentials) <= 4096:
            for token, identity in tokens.items():
                if secrets.compare_digest(credentials.credentials.encode(), token.encode()):
                    return identity
        raise HTTPException(status_code=401, detail="invalid_visual_memory_credentials", headers={"WWW-Authenticate": "Bearer"})

    @router.post("/records", response_model=VisualRecordResponse)
    def remember(req: VisualRecordRequest, identity: str = Depends(owner)) -> dict:
        assert store is not None
        try:
            record = store.add(identity, req.model_dump(exclude={"image_base64"}), base64.b64decode(req.image_base64, validate=True))
            return {"ok": True, "record": record}
        except RecordConflict as exc:
            raise HTTPException(status_code=409, detail="record_id_conflict") from exc
        except OverflowError as exc:
            raise HTTPException(status_code=507, detail="visual_memory_storage_limit") from exc
        except (OSError, ValueError, sqlite3.Error) as exc:
            raise HTTPException(status_code=503, detail="visual_memory_storage_unavailable") from exc

    @router.post("/search", response_model=VisualSearchResponse)
    def search(req: VisualSearchRequest, identity: str = Depends(owner)) -> dict:
        assert store is not None
        try:
            if retrieval is not None:
                return retrieval.search(identity, req.query, req.from_ts_ms, req.to_ts_ms, req.limit,
                    req.raw_query, base64.b64decode(req.query_image_base64, validate=True) if req.query_image_base64 else None, req.intent)
            return store.search(identity, req.query, req.from_ts_ms, req.to_ts_ms, req.limit)
        except (OSError, sqlite3.Error) as exc:
            raise HTTPException(status_code=503, detail="visual_memory_storage_unavailable") from exc

    @router.get("/status")
    def indexing_status(identity: str = Depends(owner)) -> dict:
        assert store is not None
        store.prune()
        return {"mode": "hybrid" if retrieval is not None else "metadata", "coverage": store.coverage(identity),
                "index": retrieval.index.status(identity) if retrieval is not None else None}

    @router.post("/reindex")
    def retry_indexing(identity: str = Depends(owner)) -> dict:
        if retrieval is None:
            raise HTTPException(status_code=409, detail="visual_retrieval_not_enabled")
        return {"ok": True, "requeued": retrieval.index.retry_failed(identity)}

    @router.delete("/records/{record_id}")
    def delete(record_id: str, identity: str = Depends(owner)) -> dict:
        assert store is not None
        if not _SAFE_ID.fullmatch(record_id):
            raise HTTPException(status_code=422, detail="invalid_record_id")
        try:
            return {"ok": True, "deleted": store.delete(identity, record_id)}
        except (OSError, sqlite3.Error) as exc:
            raise HTTPException(status_code=503, detail="visual_memory_storage_unavailable") from exc

    return router
