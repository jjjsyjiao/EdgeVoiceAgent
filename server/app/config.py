"""服务端配置：全部经环境变量注入，便于 Docker / 本地共用同一份启动入口。"""

from __future__ import annotations

import os
from dataclasses import dataclass

# 默认嵌入模型：多语 MiniLM，约 120MB，中英文皆可，可离线运行。
DEFAULT_EMBEDDING_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"


@dataclass(frozen=True)
class Settings:
    """环境变量 → 运行时配置。字段名与 env 的映射见 [from_env]。"""

    chroma_dir: str
    collection: str
    embedding_provider: str  # st | hashing
    embedding_model: str
    llm_base_url: str  # OpenAI 兼容根地址，留空则禁用 LLM 提炼
    llm_api_key: str
    llm_model: str
    dedup_threshold: float  # 余弦相似度达到该值即视为同一条记忆
    # ---- V3 会话帧留存 ----
    session_frame_dir: str  # 留存根目录（按 session_id 分子目录）
    session_frame_ttl_hours: float  # 留存 TTL（小时）
    session_frame_max_bytes: int  # 全局总占用上限（字节）
    # 主动记忆的照片与元数据；和旧版 /session 原型独立，必须带 owner 身份。
    visual_memory_dir: str = "./data/visual-memory"
    visual_memory_auth_tokens: str = ""  # JSON: bearer token -> owner id
    visual_memory_retention_days: float = 7.0
    visual_memory_max_bytes: int = 512 * 1024 * 1024
    visual_memory_owner_max_bytes: int = 128 * 1024 * 1024
    visual_retrieval_enabled: bool = False
    visual_embedding_endpoint: str = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding"
    visual_embedding_api_key: str = ""
    visual_embedding_model: str = "qwen3-vl-embedding"
    visual_embedding_dimension: int = 2560
    visual_embedding_timeout: float = 6.0
    visual_rerank_enabled: bool = False
    visual_rerank_endpoint: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    visual_rerank_model: str = "qwen3-vl-rerank"

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            chroma_dir=os.getenv("CHROMA_DIR", "./data/chroma"),
            collection=os.getenv("CHROMA_COLLECTION", "user_memory"),
            embedding_provider=os.getenv("EMBEDDING_PROVIDER", "st").strip().lower(),
            embedding_model=os.getenv("EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL),
            llm_base_url=os.getenv("LLM_BASE_URL", "").strip(),
            llm_api_key=os.getenv("LLM_API_KEY", "").strip(),
            llm_model=os.getenv("LLM_MODEL", "gpt-4o-mini").strip(),
            dedup_threshold=float(os.getenv("DEDUP_THRESHOLD", "0.95")),
            session_frame_dir=os.getenv("SESSION_FRAME_DIR", "./data/frames"),
            session_frame_ttl_hours=float(os.getenv("SESSION_FRAME_TTL_HOURS", "24")),
            session_frame_max_bytes=int(
                os.getenv("SESSION_FRAME_MAX_BYTES", str(512 * 1024 * 1024))
            ),
            visual_memory_dir=os.getenv("VISUAL_MEMORY_DIR", "./data/visual-memory"),
            visual_memory_auth_tokens=os.getenv("VISUAL_MEMORY_AUTH_TOKENS", ""),
            visual_memory_retention_days=float(os.getenv("VISUAL_MEMORY_RETENTION_DAYS", "7")),
            visual_memory_max_bytes=int(os.getenv("VISUAL_MEMORY_MAX_BYTES", str(512 * 1024 * 1024))),
            visual_memory_owner_max_bytes=int(os.getenv("VISUAL_MEMORY_OWNER_MAX_BYTES", str(128 * 1024 * 1024))),
            visual_retrieval_enabled=os.getenv("VISUAL_RETRIEVAL_ENABLED", "false").lower() == "true",
            visual_embedding_endpoint=os.getenv("VISUAL_EMBEDDING_ENDPOINT", "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding"),
            visual_embedding_api_key=os.getenv("VISUAL_EMBEDDING_API_KEY", ""),
            visual_embedding_model=os.getenv("VISUAL_EMBEDDING_MODEL", "qwen3-vl-embedding"),
            visual_embedding_dimension=int(os.getenv("VISUAL_EMBEDDING_DIMENSION", "2560")),
            visual_embedding_timeout=float(os.getenv("VISUAL_EMBEDDING_TIMEOUT", "6")),
            visual_rerank_enabled=os.getenv("VISUAL_RERANK_ENABLED", "false").lower() == "true",
            visual_rerank_endpoint=os.getenv("VISUAL_RERANK_ENDPOINT", "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"),
            visual_rerank_model=os.getenv("VISUAL_RERANK_MODEL", "qwen3-vl-rerank"),
        )
