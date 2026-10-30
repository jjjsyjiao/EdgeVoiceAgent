"""Tier3 长期记忆 + V3 会话帧留存服务（FastAPI + Chroma 单文本索引 + 磁盘帧环）。

面向端侧：
  - `HttpLongTermMemory`：仅存用户偏好/关键事实（Tier3）；
  - `HttpSessionFrameClient`：会话视觉帧留存与超窗回忆召回（V3）。

启动：
    uvicorn app.main:app --host 0.0.0.0 --port 8000
或：
    docker compose up --build
"""

from __future__ import annotations

import base64
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import Settings
from .schemas import (
    AddRequest,
    AddResponse,
    DistillRequest,
    DistillResponse,
    Hit,
    RecalledFrame,
    SearchRequest,
    SearchResponse,
    SessionFramesRequest,
    SessionFramesResponse,
    SessionRecallRequest,
    SessionRecallResponse,
)
from .service import MemoryService, build_service
from .session_store import SessionFrameStore
from .visual_memory_api import create_visual_memory_router
from .visual_memory_store import VisualMemoryStore


def create_app(
    settings: Settings | None = None,
    service: MemoryService | None = None,
    session_store: SessionFrameStore | None = None,
    visual_store: VisualMemoryStore | None = None,
    visual_retrieval=None,
) -> FastAPI:
    """装配应用；[service]/[session_store] 用于测试注入（绕开 Chroma 目录与模型下载）。"""
    config = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # 生产环境启动时装配（懒加载嵌入模型 / 扫描帧留存目录）；测试注入的已就绪
        if service is None:
            app.state.service = build_service(config)
        if session_store is None:
            app.state.session_store = SessionFrameStore(
                config.session_frame_dir,
                ttl_seconds=config.session_frame_ttl_hours * 3600.0,
                max_total_bytes=config.session_frame_max_bytes,
            )
        yield

    app = FastAPI(title="EdgeVoiceAgent · 长期记忆 + 视觉记忆", version="0.3.0", lifespan=lifespan)
    app.include_router(create_visual_memory_router(config, visual_store, visual_retrieval))

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Pydantic 默认把输入图片写入 422 回应；证据/便签不应出现在错误或日志里。
        return JSONResponse(status_code=422, content={"detail": [
            {"type": error["type"], "loc": error["loc"], "msg": error["msg"]}
            for error in exc.errors()
        ]})
    if service is not None:
        app.state.service = service
    if session_store is not None:
        app.state.session_store = session_store

    @app.get("/healthz")
    def healthz(request: Request) -> dict:
        svc: MemoryService = request.app.state.service
        frames: SessionFrameStore = request.app.state.session_store
        fs = frames.stats()
        return {
            "status": "ok",
            "embedding": svc.embedder_name,
            "distill": svc.distiller_name,
            "memories": svc.count(),
            "frames": fs.frame_count,
            "frame_sessions": fs.session_count,
        }

    @app.post("/memory/add", response_model=AddResponse)
    def add_memory(req: AddRequest, request: Request) -> AddResponse:
        svc: MemoryService = request.app.state.service
        ok, memory_id = svc.add(req.text, req.kind, req.source)
        return AddResponse(ok=ok, id=memory_id)

    @app.post("/memory/search", response_model=SearchResponse)
    def search_memory(req: SearchRequest, request: Request) -> SearchResponse:
        svc: MemoryService = request.app.state.service
        hits = svc.search(req.query, req.top_k)
        return SearchResponse(hits=[Hit(id=i, text=t, score=s) for i, t, s in hits])

    @app.post("/memory/distill", response_model=DistillResponse)
    def distill_memory(req: DistillRequest, request: Request) -> DistillResponse:
        svc: MemoryService = request.app.state.service
        return DistillResponse(preferences=svc.distill(req.turns))

    @app.post("/session/frames", response_model=SessionFramesResponse)
    def upload_frames(req: SessionFramesRequest, request: Request) -> SessionFramesResponse:
        store: SessionFrameStore = request.app.state.session_store
        items = [(f.ts, base64.b64decode(f.image)) for f in req.frames]
        accepted = store.append_many(req.session_id, items)
        return SessionFramesResponse(
            ok=True, accepted=accepted, dropped=len(items) - accepted
        )

    @app.post("/session/recall", response_model=SessionRecallResponse)
    def recall_frames(req: SessionRecallRequest, request: Request) -> SessionRecallResponse:
        store: SessionFrameStore = request.app.state.session_store
        total = len(store.frames_in_window(req.from_ts, req.to_ts, req.session_id))
        picked = store.sample(req.from_ts, req.to_ts, req.max_frames, req.session_id)
        return SessionRecallResponse(
            frames=[
                RecalledFrame(ts=ts, image=base64.b64encode(jpeg).decode("ascii"))
                for ts, jpeg in picked
            ],
            total_in_window=total,
        )

    return app


app = create_app()
