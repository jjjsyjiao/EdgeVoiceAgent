#!/usr/bin/env python3
"""Live Qwen audio timeline contract using synthetic speech only; no images or second model.

Only --live connects. Output contains numeric offsets, hashed item IDs and status;
no credentials, endpoint, transcript content, synthesized audio or raw server payload.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path
import queue
import re
import tempfile
import threading
import time
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
import websocket
from necessary_chain_smoke import PROJECT_ROOT, properties, speech

RATE = 24_000
BYTES_PER_MS = RATE * 2 / 1000
RELEVANT = {"session.updated", "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped",
            "input_audio_buffer.committed", "input_audio_buffer.cleared",
            "conversation.item.input_audio_transcription.completed", "response.done", "error"}

def item_hash(value):
    return hashlib.sha256(value.encode()).hexdigest()[:16] if value else None

class Timeline:
    def __init__(self, config):
        parts = urlsplit(config["openai.realtime.url"])
        query = dict(parse_qsl(parts.query)); query["model"] = config["openai.realtime.model"]
        self.ws = websocket.create_connection(urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), "")),
            header=["Authorization: Bearer " + config["openai.api.key"]], timeout=15)
        self.ws.settimeout(1)
        self.events = queue.Queue()
        self.total = 0
        self.vad_total = 0
        self.vad = False
        self.closed = False
        self.records = []
        self.model = config["openai.realtime.model"]
        self.thread = threading.Thread(target=self.receive, daemon=True); self.thread.start()

    def receive(self):
        try:
            while not self.closed:
                try: raw = self.ws.recv()
                except websocket.WebSocketTimeoutException: continue
                if not raw: break
                event = json.loads(raw)
                if event.get("type") in RELEVANT: self.events.put(event)
        except Exception:
            if not self.closed: self.events.put({"type": "probe.closed"})

    def send(self, event): self.ws.send(json.dumps(event, ensure_ascii=False))

    def next(self, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise RuntimeError("event_timeout")
        try: event = self.events.get(timeout=remaining)
        except queue.Empty: raise RuntimeError("event_timeout") from None
        kind = event.get("type")
        if kind == "error":
            code = re.sub(r"[^a-zA-Z0-9_-]", "", str(event.get("error", {}).get("code", "unknown")))[:80]
            raise RuntimeError("service_error_" + code)
        if kind == "probe.closed": raise RuntimeError("connection_closed")
        if kind not in {"session.updated", "response.done"}:
            safe = {"type": kind, "item_hash": item_hash(event.get("item_id")), "total_appended_ms_at_receive": self.total / BYTES_PER_MS}
            for key in ("audio_start_ms", "audio_end_ms"):
                if key in event: safe[key] = event[key]
            if kind.endswith("transcription.completed"): safe["transcript_length"] = len(event.get("transcript", ""))
            self.records.append(safe)
        return event

    def wait(self, kind, timeout=20):
        deadline = time.monotonic() + timeout
        while True:
            event = self.next(deadline)
            if event["type"] == kind: return event

    def configure(self, vad):
        session = {"model": self.model, "modalities": ["text", "audio"], "voice": "Tina",
            "audio": {"input": {"format": {"type": "pcm", "sample_rate": RATE}}, "output": {"format": {"type": "pcm", "sample_rate": RATE}}},
            "input_audio_transcription": {"model": "qwen3-asr-flash-realtime"},
            "instructions": "这是合成音频时序测试，每轮只回复收到。", "tools": [],
            "turn_detection": {"type": "server_vad", "threshold": 0.5, "prefix_padding_ms": 300, "silence_duration_ms": 500} if vad else None}
        self.send({"type": "session.update", "session": session}); self.wait("session.updated")
        self.vad = vad

    def clear(self):
        self.send({"type": "input_audio_buffer.clear"}); self.wait("input_audio_buffer.cleared")

    def append(self, pcm):
        for offset in range(0, len(pcm), 960):
            chunk = pcm[offset:offset+960]
            self.send({"type": "input_audio_buffer.append", "audio": base64.b64encode(chunk).decode()})
            self.total += len(chunk)
            if self.vad: self.vad_total += len(chunk)
            time.sleep(0.02)

    def vad_round(self, label, pcm, prefix_ms):
        start_total = self.total
        start_vad_total = self.vad_total
        record_start = len(self.records)
        self.append(bytes(int(prefix_ms * BYTES_PER_MS)) + pcm + bytes(RATE * 2))
        deadline = time.monotonic() + 30
        kinds = set()
        while not {"input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped", "input_audio_buffer.committed", "conversation.item.input_audio_transcription.completed", "response.done"}.issubset(kinds):
            event = self.next(deadline); kinds.add(event["type"])
        records = self.records[record_start:]
        onset = next(r for r in records if r["type"] == "input_audio_buffer.speech_started")
        end = next(r for r in records if r["type"] == "input_audio_buffer.speech_stopped")
        commit = next(r for r in records if r["type"] == "input_audio_buffer.committed")
        asr = next(r for r in records if r["type"].endswith("transcription.completed"))
        expected_cumulative_ms = start_total / BYTES_PER_MS + prefix_ms
        expected_vad_ms = start_vad_total / BYTES_PER_MS + prefix_ms
        start_ms = onset.get("audio_start_ms")
        return {"label": label, "appended_before_ms": start_total / BYTES_PER_MS, "prefix_silence_ms": prefix_ms,
            "pcm_speech_ms": len(pcm) / BYTES_PER_MS, "appended_after_ms": self.total / BYTES_PER_MS,
            "audio_start_ms": start_ms, "audio_end_ms": end.get("audio_end_ms"),
            "vad_appended_before_ms": start_vad_total / BYTES_PER_MS,
            "vad_appended_after_ms": self.vad_total / BYTES_PER_MS,
            "start_matches_vad_only_cumulative": start_ms is not None and abs(start_ms-expected_vad_ms) <= 750,
            "start_matches_cumulative": start_ms is not None and abs(start_ms-expected_cumulative_ms) <= 750,
            "start_matches_reset": start_ms is not None and abs(start_ms-prefix_ms) <= 750,
            "end_within_appended": isinstance(end.get("audio_end_ms"), (int, float)) and end["audio_end_ms"] <= self.total / BYTES_PER_MS,
            "all_item_ids_match": len({onset["item_hash"], end["item_hash"], commit["item_hash"], asr["item_hash"]}) == 1,
            "events": records}

    def close(self):
        self.closed = True
        self.ws.close(); self.thread.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--config", type=Path, default=PROJECT_ROOT / "local.properties")
    parser.add_argument("--output", type=Path, default=PROJECT_ROOT / "tools/smoke/artifacts/realtime-timeline-20261028-r2.json")
    args = parser.parse_args()
    if not args.live: parser.error("--live is required for this paid synthetic protocol check")
    result = {"passed": False, "connections": 0, "synthetic_speech_only": True, "rounds": []}
    client = None
    try:
        config = properties(args.config)
        if config.get("realtime.provider") != "qwen": raise RuntimeError("qwen_required")
        with tempfile.TemporaryDirectory(prefix="edgevoice-timeline-") as temp:
            pcm = speech("请你回答一加一等于几。", Path(temp))
            client = Timeline(config); result["connections"] = 1
            client.configure(True)
            result["rounds"].append(client.vad_round("initial_vad", pcm, 2000))
            client.clear()
            result["rounds"].append(client.vad_round("vad_after_clear", pcm, 1000))
            client.configure(False)
            manual_before = client.total
            client.append(bytes(RATE) + pcm + bytes(RATE))
            client.send({"type": "input_audio_buffer.commit"})
            commit = client.wait("input_audio_buffer.committed")
            asr = client.wait("conversation.item.input_audio_transcription.completed")
            result["manual_item"] = {"item_hash": item_hash(commit.get("item_id")), "asr_item_hash": item_hash(asr.get("item_id")),
                "appended_before_ms": manual_before / BYTES_PER_MS, "appended_after_ms": client.total / BYTES_PER_MS,
                "asr_item_matches_commit": commit.get("item_id") == asr.get("item_id")}
            client.clear(); client.configure(True)
            result["rounds"].append(client.vad_round("vad_after_manual_clear_restore", pcm, 1000))
            result["passed"] = all(r["start_matches_vad_only_cumulative"] and r["end_within_appended"] and r["all_item_ids_match"] for r in result["rounds"]) and result["manual_item"]["asr_item_matches_commit"]
    except Exception as failure:
        result["error"] = re.sub(r"[^A-Za-z0-9_-]", "_", str(failure))[:120]
    finally:
        if client: client.close()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["passed"] else 1

if __name__ == "__main__": raise SystemExit(main())
