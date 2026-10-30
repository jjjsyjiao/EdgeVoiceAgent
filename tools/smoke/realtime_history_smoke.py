#!/usr/bin/env python3
"""Same-model Realtime history proof: actual tool call, fixture retrieval, image comparison.

Only --live calls the configured Realtime endpoint. No second model or real personal
media is used. The local HTTP fixture service retrieves JPEGs without interpreting
them. Random card numbers exist only in image pixels, never in tool results/prompts.
"""
from __future__ import annotations

import argparse
import array
import base64
from collections import Counter
from datetime import datetime, timedelta
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import math
from pathlib import Path
import queue
import re
import secrets
import sys
import tempfile
import threading
import time
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen
import uuid
import wave
from zoneinfo import ZoneInfo

import websocket
from PIL import Image, ImageDraw, ImageFont

from necessary_chain_smoke import PROJECT_ROOT, properties, speech

TZ = ZoneInfo("Asia/Shanghai")
RATE = 24000
QUESTION = "这张卡片和我昨天看的是一样的吗？请先查历史，再比较编号。"
TOOL_NAME = "retrieve_visual_memory"
INSTRUCTIONS = (
    "这是图像输入协议测试。用户询问昨天的画面时，必须先调用retrieve_visual_memory查询。"
    "工具只检索，不识别编号或比较。历史原图由客户端通过图像通道提供。"
    "图片上C表示本次提问的当前画面，H1表示昨天的历史画面，时间也印在图中。"
    "只能根据实际可见的图片读取编号，不能从记录ID、时间或工具参数推测编号。"
    "拿到C和H1图像后只回答一句：当前编号是XXXX，历史编号是YYYY，结论是相同或不同。"
    "XXXX和YYYY必须是对应图片中的四位阿拉伯数字，不要把占位符念出来。"
    "如果没有看到H1图像，明确回答：历史图缺失，无法比较。禁止猜测历史编号。"
    "这里只判断卡片编号是否相同，不据此推断物理身份。"
)
TOOLS = [{"type": "function", "function": {
    "name": TOOL_NAME,
    "description": "检索用户昨天看到的卡片历史画面；返回记录与时间，图片稍后由客户端传入。",
    "parameters": {"type": "object", "properties": {
        "object_query": {"type": "string", "enum": ["卡片"]},
        "time_expression": {"type": "string", "enum": ["昨天"]},
    }, "required": ["object_query", "time_expression"], "additionalProperties": False},
}}]


class ProbeError(RuntimeError):
    pass


def card(code: str, label: str, captured_at: str) -> bytes:
    image = Image.new("RGB", (960, 640), "white")
    draw = ImageDraw.Draw(image)
    font = "/System/Library/Fonts/Supplemental/Arial.ttf"
    draw.text((40, 28), label, font=ImageFont.truetype(font, 64), fill="black")
    draw.text((40, 108), captured_at, font=ImageFont.truetype(font, 27), fill="black")
    draw.rounded_rectangle((45, 200, 915, 570), radius=20, outline="black", width=5)
    draw.text((480, 385), code, anchor="mm", font=ImageFont.truetype(font, 240), fill="black")
    output = io.BytesIO()
    image.save(output, "JPEG", quality=85)
    result = output.getvalue()
    if len(base64.b64encode(result)) > 256 * 1024:
        raise ProbeError("fixture_image_too_large")
    return result


def collage(current: bytes, history: bytes | None) -> bytes:
    image = Image.new("RGB", (1280, 480), "white")
    image.paste(Image.open(io.BytesIO(current)).resize((640, 426)), (0, 0))
    if history is not None:
        image.paste(Image.open(io.BytesIO(history)).resize((640, 426)), (640, 0))
    output = io.BytesIO()
    image.save(output, "JPEG", quality=85)
    result = output.getvalue()
    if len(base64.b64encode(result)) > 256 * 1024:
        raise ProbeError("collage_too_large")
    return result


def assess(text: str, case: str, current: str, historical: str) -> dict:
    normalized = text.translate(str.maketrans("零〇一二三四五六七八九", "00123456789"))
    normalized = re.sub(r"\s+", "", normalized)
    current_match = re.search(r"当前(?:卡片)?(?:的)?编号(?:是|为|[:：])?[\"“]?([0-9]{4})(?![0-9])", normalized)
    history_match = re.search(r"历史(?:卡片)?(?:的)?编号(?:是|为|[:：])?[\"“]?([0-9]{4})(?![0-9])", normalized)
    actual_current = current_match.group(1) if current_match else None
    actual_history = history_match.group(1) if history_match else None
    missing = bool(re.search(r"历史图(?:像)?(?:缺失|未提供|不可见|不存在|未收到)", normalized))
    different = bool(re.search(r"不同|不一致|不相同", normalized))
    same = bool(re.search(r"相同|一致", re.sub(r"不同|不一致|不相同", "", normalized)))
    verdict = "different" if different and not same else "same" if same and not different else None
    if case == "missing":
        passed = missing and "无法比较" in normalized and not re.search(r"[0-9]{4}", normalized)
        passed = passed and not different and not same
    else:
        passed = actual_current == current and actual_history == historical
        passed = passed and verdict == ("same" if current == historical else "different")
    return {"passed": passed, "current_read": actual_current, "history_read": actual_history,
            "verdict": verdict, "missing_history_acknowledged": missing}


class FixtureArchive:
    """Local-only retrieval service. It never reads image pixels or calculates a verdict."""

    def __init__(self, jpeg: bytes, captured_at: str):
        self.frame_id = "frame_" + uuid.uuid4().hex.translate(str.maketrans("0123456789", "abcdefghij"))
        self.jpeg, self.captured_at = jpeg, captured_at
        self.search_calls = self.downloads = 0
        archive = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                if self.path != "/search" or not 0 < length <= 4096:
                    self.send_error(400)
                    return
                args = json.loads(self.rfile.read(length))
                if args != {"object_query": "卡片", "time_expression": "昨天"}:
                    self.send_error(400)
                    return
                archive.search_calls += 1
                body = json.dumps({"status": "ok", "frames": [{
                    "frame_id": archive.frame_id, "label": "H1",
                    "captured_at": archive.captured_at,
                    "path": "/frames/" + archive.frame_id + ".jpg",
                }]}, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if self.path != "/frames/" + archive.frame_id + ".jpg":
                    self.send_error(404)
                    return
                archive.downloads += 1
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(archive.jpeg)))
                self.end_headers()
                self.wfile.write(archive.jpeg)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def retrieve(self, arguments: dict) -> tuple[dict, bytes]:
        origin = "http://127.0.0.1:" + str(self.server.server_port)
        request = Request(origin + "/search", json.dumps(arguments).encode(), {"Content-Type": "application/json"})
        with urlopen(request, timeout=5) as response:
            result = json.load(response)
        with urlopen(origin + result["frames"][0].pop("path"), timeout=5) as response:
            jpeg = response.read(200_000)
        if jpeg != self.jpeg:
            raise ProbeError("retrieved_jpeg_mismatch")
        return result, jpeg


class Realtime:
    def __init__(self, config: dict, output: Path):
        self.model = config.get("openai.realtime.model", "")
        endpoint, key = config.get("openai.realtime.url", ""), config.get("openai.api.key", "")
        if config.get("realtime.provider") != "qwen" or not key or not self.model or not endpoint.startswith("wss://"):
            raise ProbeError("missing_qwen_configuration")
        parts = urlsplit(endpoint)
        query = dict(parse_qsl(parts.query))
        query["model"] = self.model
        url = urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), ""))
        self.trace = (output / "events.jsonl").open("w", encoding="utf-8")
        self.trace_lock = threading.Lock()
        self.events = queue.Queue(maxsize=2048)
        self.closed = False
        self.session_id = None
        self.counts = Counter()
        try:
            self.ws = websocket.create_connection(url, header=["Authorization: Bearer " + key], timeout=15)
        except Exception:
            self.trace.close()
            raise ProbeError("websocket_connection_failed") from None
        self.ws.settimeout(1)
        self.thread = threading.Thread(target=self._receive, daemon=True)
        self.thread.start()

    def log(self, direction: str, kind: str, **details):
        with self.trace_lock:
            self.trace.write(json.dumps({"at": datetime.now(TZ).isoformat(), "direction": direction,
                                         "type": kind, **details}, ensure_ascii=False) + "\n")
            self.trace.flush()

    def _receive(self):
        try:
            while not self.closed:
                try:
                    raw = self.ws.recv()
                except websocket.WebSocketTimeoutException:
                    continue
                if not raw:
                    break
                item = json.loads(raw)
                kind = item.get("type", "unknown")
                self.counts[kind] += 1
                details = {k: item[k] for k in ["response_id", "call_id", "name"] if k in item}
                if kind == "response.created":
                    details.update(response_id=item.get("response", {}).get("id"),
                                   conversation_id=item.get("response", {}).get("conversation_id"))
                if kind == "input_audio_buffer.committed":
                    details["item_id"] = item.get("item_id")
                if kind == "response.function_call_arguments.done":
                    details["arguments"] = item.get("arguments")
                if kind == "response.done":
                    details.update(response_id=item.get("response", {}).get("id"),
                                   status=item.get("response", {}).get("status"))
                if kind == "error":
                    details["code"] = re.sub(r"[^A-Za-z0-9_-]", "", str(item.get("error", {}).get("code", "unknown")))[:80]
                self.log("receive", kind, **details)
                self.events.put(item, timeout=2)
        except Exception:
            pass
        finally:
            try:
                self.events.put({"type": "probe.closed"}, timeout=2)
            except queue.Full:
                pass

    def send(self, item: dict, **details):
        self.log("send", item["type"], **details)
        self.ws.send(json.dumps(item, ensure_ascii=False))

    def next(self, deadline: float) -> dict:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ProbeError("event_timeout")
        try:
            item = self.events.get(timeout=remaining)
        except queue.Empty:
            raise ProbeError("event_timeout") from None
        if item.get("type") == "probe.closed":
            raise ProbeError("websocket_closed")
        if item.get("type") == "error":
            code = re.sub(r"[^A-Za-z0-9_-]", "", str(item.get("error", {}).get("code", "unknown")))[:80]
            raise ProbeError("service_error_" + code)
        return item

    def wait(self, kind: str, timeout=20) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            item = self.next(deadline)
            if item["type"] == kind:
                return item
            if item["type"] == "response.created":
                raise ProbeError("unexpected_automatic_response")

    def configure(self, tools: bool):
        session = {"model": self.model, "modalities": ["text", "audio"], "voice": "Tina",
                   "audio": {"input": {"format": {"type": "pcm", "sample_rate": RATE}},
                             "output": {"format": {"type": "pcm", "sample_rate": RATE}}},
                   "input_audio_transcription": {"model": "qwen3-asr-flash-realtime"},
                   "turn_detection": None, "instructions": INSTRUCTIONS,
                   "tools": TOOLS if tools else []}
        self.send({"type": "session.update", "session": session}, tools_enabled=tools)
        acknowledged = self.wait("session.updated").get("session", {})
        if self.session_id and acknowledged.get("id") not in (None, self.session_id):
            raise ProbeError("session_changed")
        if acknowledged.get("model") not in (None, self.model):
            raise ProbeError("model_changed")
        if not tools and acknowledged.get("tools") not in (None, []):
            raise ProbeError("tools_not_cleared")
        return {"acknowledged_model": acknowledged.get("model"),
                "tools_field_in_ack": "tools" in acknowledged,
                "tools_clear_acknowledged": acknowledged.get("tools") == [] if "tools" in acknowledged else None}

    def media(self, pcm: bytes, images: list[tuple[str, bytes]]):
        # Audio advances the timeline throughout both image insertions, >=1 s apart.
        positions = {24_000 + index * 60_000: (label, jpeg) for index, (label, jpeg) in enumerate(images)}
        audio = bytes(24_000) + pcm + bytes(72_000)
        if positions and max(positions) >= len(audio):
            audio += bytes(max(positions) - len(audio) + 48_000)
        for offset in range(0, len(audio), 960):
            self.send({"type": "input_audio_buffer.append",
                       "audio": base64.b64encode(audio[offset:offset + 960]).decode()}, pcm_bytes=min(960, len(audio)-offset))
            # Select the first 20 ms chunk at/after each requested insertion position.
            for at in list(positions):
                if offset >= at:
                    label, jpeg = positions.pop(at)
                    self.send({"type": "input_image_buffer.append", "image": base64.b64encode(jpeg).decode()},
                              image_label=label, jpeg_bytes=len(jpeg), sha256=hashlib.sha256(jpeg).hexdigest())
            time.sleep(0.02)
        self.send({"type": "input_audio_buffer.commit"})
        return self.wait("input_audio_buffer.committed").get("item_id")

    def response(self) -> dict:
        self.send({"type": "response.create"})
        deadline = time.monotonic() + 60
        response_id = None
        audio = bytearray()
        transcripts, texts, calls = [], [], []
        audio_done = False
        conversation_id = None
        first_audio = None
        started = time.monotonic()
        while True:
            item = self.next(deadline)
            kind = item["type"]
            if kind == "response.created":
                if response_id:
                    raise ProbeError("overlapping_responses")
                response_id = item.get("response", {}).get("id")
                conversation_id = item.get("response", {}).get("conversation_id")
            if kind.startswith("response.") and item.get("response_id"):
                if not response_id or item["response_id"] != response_id:
                    raise ProbeError("response_id_mismatch")
            if kind in ("response.audio.delta", "response.output_audio.delta"):
                if not response_id:
                    raise ProbeError("audio_before_response_created")
                if first_audio is None:
                    first_audio = round((time.monotonic() - started) * 1000)
                audio.extend(base64.b64decode(item["delta"], validate=True))
            elif kind in ("response.audio_transcript.delta", "response.output_audio_transcript.delta"):
                transcripts.append(item.get("delta", ""))
            elif kind in ("response.audio_transcript.done", "response.output_audio_transcript.done"):
                transcripts = [item.get("transcript", "")]
            elif kind in ("response.text.delta", "response.output_text.delta"):
                texts.append(item.get("delta", ""))
            elif kind in ("response.text.done", "response.output_text.done"):
                texts = [item.get("text", "")]
            elif kind in ("response.audio.done", "response.output_audio.done"):
                audio_done = True
            elif kind == "response.function_call_arguments.done":
                calls.append({"name": item.get("name"), "call_id": item.get("call_id"),
                              "arguments": json.loads(item.get("arguments", "{}"))})
            elif kind == "response.done":
                done = item.get("response", {})
                if done.get("id") != response_id:
                    raise ProbeError("done_response_id_mismatch")
                return {"response_id": response_id, "conversation_id": conversation_id,
                        "status": done.get("status"), "text": "".join(transcripts or texts),
                        "audio": bytes(audio), "audio_done": audio_done, "calls": calls,
                        "first_audio_ms": first_audio}

    def close(self):
        self.closed = True
        self.ws.close()
        self.thread.join(timeout=3)
        self.trace.close()


def run_case(config: dict, output: Path, case: str, pcm: bytes, mode: str, layout: str) -> dict:
    output.mkdir(parents=True, exist_ok=False)
    current = "".join(secrets.choice("123456789") for _ in range(4))
    historical = current
    while historical == current and case != "same":
        historical = "".join(secrets.choice("123456789") for _ in range(4))
    now = datetime.now(TZ).replace(microsecond=0)
    old = (now - timedelta(days=1)).replace(hour=19, minute=42, second=0)
    current_image = card(current, "C - CURRENT", now.isoformat())
    historical_image = card(historical, "H1 - HISTORY", old.isoformat())
    (output / "current.jpg").write_bytes(current_image)
    (output / "history.jpg").write_bytes(historical_image)
    result = {"case": case, "mode": mode, "layout": layout, "model": config.get("openai.realtime.model"),
              "expected_current": current, "expected_history": None if case == "missing" else historical,
              "passed": False, "secondary_multimodal_model_calls": 0,
              "fixture_scope": "Synthetic numbered cards; local HTTP fixture retrieval, no real camera/archive"}
    client = None
    try:
        with FixtureArchive(historical_image, old.isoformat()) as archive:
            client = Realtime(config, output)
            created = client.wait("session.created").get("session", {})
            client.session_id = created.get("id")
            if not client.session_id:
                raise ProbeError("missing_session_id")
            result["session_id_sha256"] = hashlib.sha256(client.session_id.encode()).hexdigest()
            result["initial_config"] = client.configure(True)
            first_item = client.media(pcm, [("C", current_image)])
            parent = client.response()
            result["tool_attempts"] = []
            result["tool_argument_repairs"] = 0
            tool_conversation = parent["conversation_id"]
            for attempt in range(2):
                if parent["status"] != "completed" or len(parent["calls"]) != 1 or parent["audio"]:
                    raise ProbeError("expected_exactly_one_real_tool_call_without_audio")
                if tool_conversation and parent["conversation_id"] != tool_conversation:
                    raise ProbeError("tool_repair_conversation_changed")
                call = parent["calls"][0]
                result["tool_attempts"].append({"response_id": parent["response_id"], **call})
                if call["name"] != TOOL_NAME or not call["call_id"]:
                    raise ProbeError("wrong_tool_call")
                if call["arguments"] == {"object_query": "卡片", "time_expression": "昨天"}:
                    break
                if attempt:
                    raise ProbeError("unexpected_tool_arguments_after_one_repair")
                # Report validation failure to the model, never silently rewrite its arguments.
                invalid = {"status": "invalid_arguments", "message": "请按以下两个字段和值重新调用工具，不添加其他字段。",
                           "required_arguments": {"object_query": "卡片", "time_expression": "昨天"}}
                client.send({"type": "conversation.item.create", "item": {
                    "type": "function_call_output", "call_id": call["call_id"],
                    "output": json.dumps(invalid, ensure_ascii=False),
                }}, call_id=call["call_id"], validation_error=True)
                result["tool_argument_repairs"] += 1
                parent = client.response()
            result["tool_parent_conversation_id"] = parent["conversation_id"]
            retrieved, jpeg = archive.retrieve(call["arguments"])
            serialized_metadata = json.dumps(retrieved, ensure_ascii=False)
            if current in serialized_metadata or historical in serialized_metadata:
                raise ProbeError("visual_answer_leaked_into_tool_metadata")
            # ID/time only. No answer, card number, description, image URL or encoded pixels in output.
            result["tool_result"] = retrieved
            client.send({"type": "conversation.item.create", "item": {
                "id": "result_" + uuid.uuid4().hex, "type": "function_call_output",
                "call_id": call["call_id"], "output": serialized_metadata,
            }}, call_id=call["call_id"], tool_output_fields="status,frames(frame_id,label,captured_at)")
            result["comparison_config"] = client.configure(False)
            shown_history = None if case == "missing" else jpeg
            if layout == "collage":
                joined = collage(current_image, shown_history)
                (output / "comparison.jpg").write_bytes(joined)
                images = [("C+H1" if shown_history else "C-only", joined)]
            else:
                images = [("C", current_image)] + ([("H1", jpeg)] if shown_history else [])
            if mode == "controlled":
                second_item = client.media(pcm, images)
                if not first_item or not second_item or first_item == second_item:
                    raise ProbeError("comparison_not_a_new_committed_media_item")
            else:
                second_item = None
                for index, (label, image) in enumerate(images):
                    if index:
                        time.sleep(1.25)
                    client.send({"type": "input_image_buffer.append", "image": base64.b64encode(image).decode()},
                                image_label=label, jpeg_bytes=len(image), sha256=hashlib.sha256(image).hexdigest())
            final = client.response()
            if final["calls"]:
                raise ProbeError("recursive_tool_call_in_final_response")
            if final["response_id"] == parent["response_id"]:
                raise ProbeError("response_id_reused")
            if parent["conversation_id"] and final["conversation_id"] != parent["conversation_id"]:
                raise ProbeError("conversation_changed")
            audio = final.pop("audio")
            samples = array.array("h")
            if len(audio) % 2:
                raise ProbeError("unaligned_pcm")
            samples.frombytes(audio)
            if sys.byteorder != "little":
                samples.byteswap()
            peak = max((abs(x) for x in samples), default=0)
            rms = math.sqrt(sum(x*x for x in samples) / max(1, len(samples)))
            with wave.open(str(output / "reply.wav"), "wb") as destination:
                destination.setnchannels(1)
                destination.setsampwidth(2)
                destination.setframerate(RATE)
                destination.writeframes(audio)
            semantic = assess(final["text"], case, current, historical)
            media_ok = final["status"] == "completed" and final["audio_done"]
            media_ok = media_ok and len(audio) >= RATE // 2 and peak > 100 and rms > 20
            result.update(passed=bool(semantic["passed"] and media_ok), semantic=semantic,
                          final_response=final, tool_call=call, parent_response_id=parent["response_id"],
                          first_committed_item=first_item, comparison_committed_item=second_item,
                          audio_bytes=len(audio), audio_seconds=round(len(audio) / (RATE * 2), 3),
                          audio_peak=peak, audio_rms=round(rms, 2), retrieval_http_calls=archive.search_calls,
                          image_downloads=archive.downloads, websocket_connections=1,
                          image_transport="independent_jpegs" if layout == "frames" else "labeled_composite_jpeg")
    except Exception as error:
        result["error"] = str(error) if isinstance(error, ProbeError) else type(error).__name__
    finally:
        if client:
            result["event_counts"] = dict(client.counts)
            client.close()
        (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return result


def offline_check():
    assert assess("当前编号是4826，历史编号是4826，结论是相同。", "same", "4826", "4826")["passed"]
    assert assess("当前编号是4826，历史编号是9153，结论是不同。", "different", "4826", "9153")["passed"]
    assert not assess("当前编号是4826，历史编号是4826，结论是相同。", "different", "4826", "9153")["passed"]
    assert assess("历史图缺失，无法比较。", "missing", "4826", "9153")["passed"]
    assert not assess("历史图缺失，无法比较，猜测是9153。", "missing", "4826", "9153")["passed"]
    assert not assess("历史图缺失，无法比较，结论是相同。", "missing", "4826", "9153")["passed"]
    assert not assess("历史图缺失，无法比较。", "different", "4826", "9153")["passed"]
    with FixtureArchive(card("9153", "H1 - HISTORY", "2026-10-26T19:42:00+08:00"), "2026-10-26T19:42:00+08:00") as fixture:
        result, image = fixture.retrieve({"object_query": "卡片", "time_expression": "昨天"})
        assert fixture.search_calls == fixture.downloads == 1
        assert set(result["frames"][0]) == {"frame_id", "label", "captured_at"}
        assert Image.open(io.BytesIO(image)).size == (960, 640)
    print("offline_checks=PASS", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--offline-check", action="store_true")
    parser.add_argument("--config", type=Path, default=PROJECT_ROOT / "local.properties")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--mode", choices=["controlled", "direct"], default="controlled")
    parser.add_argument("--layout", choices=["frames", "collage"], default="frames")
    parser.add_argument("--cases", default="same,different,missing")
    args = parser.parse_args()
    if args.offline_check:
        offline_check()
        return
    if not args.live:
        parser.error("Use --offline-check or explicitly --live to call the configured Realtime model")
    cases = args.cases.split(",")
    if not cases or len(set(cases)) != len(cases) or any(x not in ("same", "different", "missing") for x in cases):
        parser.error("--cases must be a unique subset of same,different,missing")
    output = args.output or Path(__file__).parent / "artifacts" / ("realtime-history-" + datetime.now(TZ).strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=False)
    (output / "executed_probe.py").write_bytes(Path(__file__).read_bytes())
    config = properties(args.config)
    report = {"completed": False, "passed": False, "mode": args.mode, "layout": args.layout,
              "executed_probe_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "cases": [], "question": QUESTION, "scope": "protocol-only synthetic fixture proof"}
    try:
        with tempfile.TemporaryDirectory(prefix="edgevoice-history-input-") as temporary:
            pcm = speech(QUESTION, Path(temporary))
            report["question_pcm_sha256"] = hashlib.sha256(pcm).hexdigest()
            report["question_pcm_bytes"] = len(pcm)
            for case in cases:
                print(json.dumps({"stage": "starting", "case": case, "mode": args.mode, "layout": args.layout}, ensure_ascii=False), flush=True)
                result = run_case(config, output / case, case, pcm, args.mode, args.layout)
                report["cases"].append(result)
                print(json.dumps({"case": case, "passed": result["passed"], "error": result.get("error"),
                                  "answer": result.get("final_response", {}).get("text"),
                                  "audio_bytes": result.get("audio_bytes"), "audio_done": result.get("final_response", {}).get("audio_done")}, ensure_ascii=False), flush=True)
                if not result["passed"]:
                    break
        report["completed"] = len(report["cases"]) == len(cases)
        report["passed"] = report["completed"] and all(x["passed"] for x in report["cases"])
    except Exception as error:
        report["error"] = type(error).__name__
    finally:
        report["finished_at"] = datetime.now(TZ).isoformat()
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "completed": report["completed"], "output": str(output)}, ensure_ascii=False), flush=True)
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
