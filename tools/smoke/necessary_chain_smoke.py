#!/usr/bin/env python3
"""百炼必要链路烟测：合成语音 + 人工数字卡，不采集麦克风或摄像头。

显式 --live 才调用模型（产生少量模型费用）；只读取本项目配置，不输出密钥。
输出仅包括测试回答、事件计数、音频时长与结果，不记录原始请求或鉴权头。
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import queue
import subprocess
import tempfile
import threading
import time
import wave
from collections import Counter
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import websocket
from PIL import Image, ImageDraw, ImageFont

PROJECT_ROOT = Path(__file__).resolve().parents[2]


def properties(path: Path) -> dict[str, str]:
    result = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#") or "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        result[key.strip()] = value.strip().replace(r"\:", ":").replace(r"\=", "=")
    return result


def speech(text: str, directory: Path) -> bytes:
    aiff, wav = directory / "question.aiff", directory / "question.wav"
    subprocess.run(["say", "-v", "Tingting", "-o", str(aiff), text], check=True)
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@24000", "-c", "1", str(aiff), str(wav)], check=True)
    with wave.open(str(wav), "rb") as source:
        if source.getframerate() != 24000 or source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise RuntimeError("测试音频必须为24kHz单声道PCM16")
        pcm = source.readframes(source.getnframes())
        if len(pcm) < 2400 or not any(pcm):
            raise RuntimeError("系统语音合成未产生有效语音，请在允许访问系统语音服务的环境运行")
        return pcm


def digit_card(digit: str) -> bytes:
    card = Image.new("RGB", (640, 480), "white")
    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 320)
    ImageDraw.Draw(card).text((320, 240), digit, anchor="mm", font=font, fill="black")
    buffer = io.BytesIO()
    card.save(buffer, "JPEG", quality=80)
    return buffer.getvalue()


def run(config: dict[str, str], output: Path) -> list[dict]:
    key = config.get("openai.api.key", "")
    model = config.get("openai.realtime.model", "")
    endpoint = config.get("openai.realtime.url", "")
    if config.get("realtime.provider") != "qwen" or not key or not model or not endpoint.startswith("wss://"):
        raise RuntimeError("需要配置百炼provider、模型、WSS地址和API Key")
    parts = urlsplit(endpoint)
    query = dict(parse_qsl(parts.query))
    query["model"] = model
    url = urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))
    ws = websocket.create_connection(url, header=[f"Authorization: Bearer {key}"], timeout=15)
    events: queue.Queue = queue.Queue()

    def receive():
        try:
            while True:
                raw = ws.recv()
                if not raw:
                    break
                events.put(json.loads(raw))
        except Exception:
            pass
        finally:
            events.put({"type": "probe.connection_closed"})

    threading.Thread(target=receive, daemon=True).start()

    def event(timeout=20):
        item = events.get(timeout=timeout)
        if item.get("type") == "error":
            error = item.get("error", {})
            raise RuntimeError("模型错误：" + str(error.get("code", "unknown")))
        if item.get("type") == "probe.connection_closed":
            raise RuntimeError("模型连接提前关闭")
        return item

    def wait(kind):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            item = event()
            if item.get("type") == kind:
                return item
        raise TimeoutError(kind)

    def send(item):
        ws.send(json.dumps(item))

    results = []
    try:
        wait("session.created")
        send({"type": "session.update", "session": {
            "model": model, "modalities": ["text", "audio"], "voice": "Tina",
            "audio": {
                "input": {"format": {"type": "pcm", "sample_rate": 24000}},
                "output": {"format": {"type": "pcm", "sample_rate": 24000}},
            },
            "input_audio_transcription": {"model": "qwen3-asr-flash-realtime"},
            "turn_detection": {"type": "server_vad", "threshold": 0.5, "silence_duration_ms": 500},
        }})
        wait("session.updated")
        print(json.dumps({"configured": True, "model": model}, ensure_ascii=False), flush=True)
        with tempfile.TemporaryDirectory(prefix="edgevoice-smoke-") as temp:
            for name, question, digit, expected in [
                ("audio", "一加一等于几，请简短回答。", None, ("2", "二", "两")),
                ("vision_7", "现在画面中的数字是几？请只回答数字。", "7", ("7", "七")),
                ("vision_3", "现在画面中的数字是几？请只回答数字。", "3", ("3", "三")),
            ]:
                pcm = bytes(24_000) + speech(question, Path(temp)) + bytes(72_000)
                image = digit_card(digit) if digit else None
                encoded_image = base64.b64encode(image).decode("ascii") if image else None
                if encoded_image and len(encoded_image) > 256 * 1024:
                    raise RuntimeError("测试图像超限")
                started = time.monotonic()
                # 20ms 帧，与 Android 上行一致；图像在音频之后按1fps交错。
                for offset in range(0, len(pcm), 960):
                    send({"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm[offset:offset + 960]).decode("ascii")})
                    if encoded_image and offset % 48_000 == 0:
                        send({"type": "input_image_buffer.append", "image": encoded_image})
                    time.sleep(0.02)
                audio = bytearray()
                text = ""
                user = ""
                counts = Counter()
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    item = event(timeout=15)
                    kind = item.get("type", "")
                    counts[kind] += 1
                    if kind in ("response.audio.delta", "response.output_audio.delta"):
                        audio.extend(base64.b64decode(item["delta"]))
                    elif kind in ("response.audio_transcript.delta", "response.output_audio_transcript.delta", "response.text.delta"):
                        text += item.get("delta", "")
                    elif kind in ("response.audio_transcript.done", "response.output_audio_transcript.done", "response.text.done"):
                        text = item.get("transcript", item.get("text", text))
                    elif kind == "conversation.item.input_audio_transcription.completed":
                        user = item.get("transcript", "")
                    elif kind == "response.done":
                        status = item.get("response", {}).get("status")
                        break
                else:
                    raise TimeoutError("模型回复超时")
                ok = bool(audio) and bool(user) and status == "completed" and any(value in text for value in expected)
                result = {"case": name, "passed": ok, "transcript": user, "answer": text,
                          "input_bytes": len(pcm), "output_bytes": len(audio),
                          "output_seconds": round(len(audio) / 48_000, 3),
                          "elapsed_seconds": round(time.monotonic() - started, 3), "events": dict(counts)}
                output.mkdir(parents=True, exist_ok=True)
                with wave.open(str(output / f"{name}_reply.wav"), "wb") as destination:
                    destination.setnchannels(1)
                    destination.setsampwidth(2)
                    destination.setframerate(24000)
                    destination.writeframes(audio)
                results.append(result)
                print(json.dumps(result, ensure_ascii=False), flush=True)
                if not ok:
                    break
    finally:
        ws.close()
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--config", type=Path, default=PROJECT_ROOT / "local.properties")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "artifacts" / "necessary_chain")
    args = parser.parse_args()
    if not args.live:
        parser.error("仅在明确验证时加 --live；此命令会调用已配置模型")
    results = run(properties(args.config), args.output)
    (args.output / "result.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    raise SystemExit(0 if len(results) == 3 and all(item["passed"] for item in results) else 1)


if __name__ == "__main__":
    main()
