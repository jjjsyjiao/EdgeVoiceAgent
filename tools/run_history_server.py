#!/usr/bin/env python3
"""Local USB development server; credentials stay in ignored local.properties.

Run with the server virtualenv Python. The server binds loopback only; Android
reaches it through `adb reverse tcp:8000 tcp:8000`.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    values = {}
    for line in (ROOT / "local.properties").read_text().splitlines():
        if line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    token = values.get("history.api.token", "")
    if len(token) < 16:
        raise SystemExit("Configure history.api.token (at least 16 characters) in local.properties first.")
    test_token = values.get("history.test.api.token", "")
    if test_token and (len(test_token) < 16 or test_token == token):
        raise SystemExit("history.test.api.token must be distinct and at least 16 characters.")
    environment = dict(os.environ)
    token_owners = {token: "local-device"}
    if test_token:
        token_owners[test_token] = "local-instrumentation"
    environment["VISUAL_MEMORY_AUTH_TOKENS"] = json.dumps(token_owners)
    environment.setdefault("EMBEDDING_PROVIDER", "hashing")
    environment["CHROMA_DIR"] = str(ROOT / "server/data/chroma-local-history")
    environment["SESSION_FRAME_DIR"] = str(ROOT / "server/data/frames-local-history")
    environment["VISUAL_MEMORY_DIR"] = str(ROOT / "server/data/visual-memory-local")
    # Dedicated REST adapters. The Realtime WebSocket URL is never reused here.
    mappings = {
        "history.retrieval.enabled": "VISUAL_RETRIEVAL_ENABLED",
        "history.retrieval.embedding.url": "VISUAL_EMBEDDING_ENDPOINT",
        "history.retrieval.embedding.model": "VISUAL_EMBEDDING_MODEL",
        "history.retrieval.embedding.dimension": "VISUAL_EMBEDDING_DIMENSION",
        "history.retrieval.rerank.enabled": "VISUAL_RERANK_ENABLED",
        "history.retrieval.rerank.url": "VISUAL_RERANK_ENDPOINT",
        "history.retrieval.rerank.model": "VISUAL_RERANK_MODEL",
        "history.retention.days": "VISUAL_MEMORY_RETENTION_DAYS",
    }
    for key, env_key in mappings.items():
        if values.get(key):
            environment[env_key] = values[key]
    if environment.get("VISUAL_RETRIEVAL_ENABLED", "false").lower() == "true":
        environment["VISUAL_EMBEDDING_API_KEY"] = values.get("history.retrieval.api.key") or values.get("openai.api.key", "")
    os.chdir(ROOT / "server")
    os.execve(sys.executable, [sys.executable, "-m", "uvicorn", "app.main:app",
              "--host", "127.0.0.1", "--port", "8000", "--no-access-log"], environment)


if __name__ == "__main__":
    main()
