"""请求/响应模型：字段与端侧 [HttpLongTermMemory] 契约严格对齐，不可随意改名。

- POST /memory/add     {text, kind, source}          -> {ok, id}
- POST /memory/search  {query, top_k}                -> {hits: [{id, text, score}]}
- POST /memory/distill {turns}                       -> {preferences: [...]}

V3 会话帧留存（端侧 [HttpSessionFrameClient] 契约）：
- POST /session/frames {session_id, frames:[{ts, image}]}          -> {ok, accepted, dropped}
- POST /session/recall {from_ts, to_ts, max_frames, session_id?}   -> {frames:[{ts, image}]}

[image] 为**裸 base64（无 data: 前缀）JPEG**，与端侧实时上行同一编码产物。
"""

from __future__ import annotations

import base64
import binascii
import re

from pydantic import BaseModel, Field, field_validator, model_validator

# 端侧 V1 硬闸：Base64 ≤ 256KB → 二进制约 192KB；此处放宽到 256KB 二进制兜底
MAX_FRAME_JPEG_BYTES = 256 * 1024
_SAFE_SESSION = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _non_blank(value: str) -> str:
    """去首尾空白后必须非空——防止 "   " 这类长度合法但无内容的脏文本入库/检索。"""
    value = value.strip()
    if not value:
        raise ValueError("must not be blank")
    return value


class AddRequest(BaseModel):
    text: str = Field(min_length=1, max_length=512)
    kind: str = "preference"
    source: str = "explicit"

    @field_validator("text")
    @classmethod
    def text_must_not_be_blank(cls, v: str) -> str:
        return _non_blank(v)


class AddResponse(BaseModel):
    ok: bool
    id: str


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=512)
    top_k: int = Field(default=3, ge=1, le=20)

    @field_validator("query")
    @classmethod
    def query_must_not_be_blank(cls, v: str) -> str:
        return _non_blank(v)


class Hit(BaseModel):
    id: str
    text: str
    score: float


class SearchResponse(BaseModel):
    hits: list[Hit]


class DistillRequest(BaseModel):
    turns: list[str] = Field(default_factory=list, max_length=64)


class DistillResponse(BaseModel):
    preferences: list[str]


# ---------------- V3 会话帧留存 ----------------


def _safe_session(value: str) -> str:
    value = value.strip()
    if not _SAFE_SESSION.fullmatch(value):
        raise ValueError("must match [A-Za-z0-9_-]{1,64}")
    return value


def _validated_b64_image(value: str) -> str:
    """校验裸 base64 JPEG：可解码、非空、不超限。返回原串（端侧/服务端各解一次）。"""
    try:
        raw = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("image must be valid base64")
    if not raw:
        raise ValueError("image must not be empty")
    if len(raw) > MAX_FRAME_JPEG_BYTES:
        raise ValueError(f"image exceeds {MAX_FRAME_JPEG_BYTES} bytes")
    if not raw.startswith(b"\xff\xd8"):
        raise ValueError("image must be JPEG")  # SOI 魔数：挡住误传 PNG/裸字节
    return value


class FrameUpload(BaseModel):
    ts: int = Field(ge=0)  # 端侧 epoch 毫秒
    image: str

    @field_validator("image")
    @classmethod
    def image_must_be_jpeg_b64(cls, v: str) -> str:
        return _validated_b64_image(v)


class SessionFramesRequest(BaseModel):
    session_id: str
    frames: list[FrameUpload] = Field(min_length=1, max_length=64)

    @field_validator("session_id")
    @classmethod
    def session_must_be_safe(cls, v: str) -> str:
        return _safe_session(v)


class SessionFramesResponse(BaseModel):
    ok: bool
    accepted: int
    dropped: int


class SessionRecallRequest(BaseModel):
    from_ts: int = Field(ge=0)
    to_ts: int = Field(ge=0)
    max_frames: int = Field(default=12, ge=1, le=48)
    session_id: str | None = None  # 省略则跨会话（支持「昨天」类超窗回忆）

    @field_validator("session_id")
    @classmethod
    def session_must_be_safe(cls, v: str | None) -> str | None:
        return None if v is None else _safe_session(v)

    @model_validator(mode="after")
    def window_must_be_ordered(self) -> "SessionRecallRequest":
        if self.from_ts > self.to_ts:
            raise ValueError("from_ts must be <= to_ts")
        return self


class RecalledFrame(BaseModel):
    ts: int
    image: str


class SessionRecallResponse(BaseModel):
    frames: list[RecalledFrame]
    total_in_window: int  # 窗内总帧数（端侧据此判断服务端是否真覆盖到该窗口）
