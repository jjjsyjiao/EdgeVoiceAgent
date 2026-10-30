#!/usr/bin/env python3
"""Keep the local history server and one Android ADB reverse link available.

This is a foreground development helper. It never changes other devices or ADB
reverse mappings and prints only aggregate record/index counts.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
PORT = 8000
REVERSE = f"tcp:{PORT}"


def properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def validate_local_config(values: dict[str, str]) -> str:
    if values.get("history.enabled", "").lower() != "true":
        raise ValueError("local.properties 中需要 history.enabled=true")
    endpoint = urlparse(values.get("history.endpoint", ""))
    if (endpoint.scheme != "http" or endpoint.hostname not in {"localhost", "127.0.0.1"}
            or endpoint.port != PORT or endpoint.path not in {"", "/"}
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment):
        raise ValueError("此开发工具仅支持 history.endpoint=http://127.0.0.1:8000")
    token = values.get("history.api.token", "")
    if len(token) < 16:
        raise ValueError("local.properties 中需要有效的 history.api.token")
    return token


def adb_executable(values: dict[str, str]) -> str:
    for sdk_dir in (values.get("sdk.dir"), os.getenv("ANDROID_HOME"), os.getenv("ANDROID_SDK_ROOT")):
        if sdk_dir:
            path = Path(sdk_dir) / "platform-tools" / "adb"
            if path.is_file():
                return str(path)
    path = shutil.which("adb")
    if path:
        return path
    raise ValueError("找不到 Android SDK platform-tools/adb")


def has_reverse(listing: str) -> bool:
    return any(line.split()[-2:] == [REVERSE, REVERSE] for line in listing.splitlines())


def adb_command(adb: str, serial: str, *arguments: str) -> subprocess.CompletedProcess[str]:
    command = [adb, "-s", serial, *arguments]
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=12, check=False)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(command, 124, "", "")


def is_wireless_serial(serial: str) -> bool:
    try:
        host, port = serial.rsplit(":", 1)
        ipaddress.ip_address(host)
        return 1 <= int(port) <= 65535
    except ValueError:
        return False


def reconnect_wireless(adb: str, serial: str) -> None:
    if not is_wireless_serial(serial):
        return
    try:
        subprocess.run([adb, "connect", serial], capture_output=True, text=True, timeout=8, check=False)
    except subprocess.TimeoutExpired:
        pass


def ensure_reverse(adb: str, serial: str) -> bool:
    state = adb_command(adb, serial, "get-state")
    if state.returncode != 0 or state.stdout.strip() != "device":
        if not is_wireless_serial(serial):
            return False
        reconnect_wireless(adb, serial)
        state = adb_command(adb, serial, "get-state")
        if state.returncode != 0 or state.stdout.strip() != "device":
            return False
    listing = adb_command(adb, serial, "reverse", "--list")
    if listing.returncode != 0:
        return False
    if has_reverse(listing.stdout):
        return True
    added = adb_command(adb, serial, "reverse", REVERSE, REVERSE)
    if added.returncode != 0:
        return False
    verified = adb_command(adb, serial, "reverse", "--list")
    return verified.returncode == 0 and has_reverse(verified.stdout)


def port_listening() -> bool:
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=1):
            return True
    except OSError:
        return False


def server_status(token: str) -> tuple[str, int, int, int]:
    request = Request(
        f"http://127.0.0.1:{PORT}/visual-memory/status",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urlopen(request, timeout=5) as response:
        payload = json.load(response)
    coverage = payload.get("coverage") or {}
    index = payload.get("index") or {}
    return (
        str(payload.get("mode", "unknown")),
        int(coverage.get("records_count", 0)),
        int(index.get("ready", 0)),
        int(index.get("pending", 0)),
    )


def launch_server() -> subprocess.Popen[bytes]:
    python = ROOT / "server" / ".venv" / "bin" / "python"
    if not python.is_file():
        raise ValueError("缺少 server/.venv，请先安装服务端依赖")
    return subprocess.Popen(
        [str(python), str(ROOT / "tools" / "run_history_server.py")],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )


def wait_for_server(token: str, process: subprocess.Popen[bytes]) -> tuple[str, int, int, int]:
    for _ in range(30):
        if process.poll() is not None:
            break
        try:
            return server_status(token)
        except (OSError, URLError):
            time.sleep(0.5)
    raise RuntimeError("历史服务启动失败，请检查服务端配置和依赖")


def monitor(serial: str) -> None:
    values = properties(ROOT / "local.properties")
    token = validate_local_config(values)
    adb = adb_executable(values)
    owned: subprocess.Popen[bytes] | None = None
    last_state: tuple[object, ...] | None = None
    print(f"监视设备 {serial} 的历史服务连接；按 Ctrl+C 停止。", flush=True)
    try:
        while True:
            if not port_listening():
                owned = launch_server()
                status = wait_for_server(token, owned)
            else:
                try:
                    status = server_status(token)
                except HTTPError as error:
                    if error.code in (401, 403):
                        raise RuntimeError("历史服务鉴权失败，请检查当前服务与 local.properties 的 token 配置") from None
                    raise RuntimeError(f"历史服务状态接口返回 HTTP {error.code}") from None
                except (OSError, URLError):
                    status = None
            linked = ensure_reverse(adb, serial)
            state = (linked, status)
            if state != last_state:
                if status is None:
                    print("电脑历史服务暂时无响应。", flush=True)
                else:
                    mode, count, ready, pending = status
                    print(f"服务 {mode}；已确认保存 {count} 条，索引就绪 {ready} 条，待索引 {pending} 条。", flush=True)
                link_type = "无线 ADB" if is_wireless_serial(serial) else "USB"
                print(f"{link_type} 历史连接已就绪。" if linked else f"{link_type} 调试连接不可用；等待重连。", flush=True)
                last_state = state
            time.sleep(3)
    finally:
        if owned is not None and owned.poll() is None:
            owned.terminate()
            try:
                owned.wait(timeout=5)
            except subprocess.TimeoutExpired:
                owned.kill()
                owned.wait(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="adb devices 显示的目标设备序列号")
    arguments = parser.parse_args()
    try:
        monitor(arguments.serial)
    except KeyboardInterrupt:
        print("已停止监视。")
    except (ValueError, RuntimeError, OSError) as error:
        print(f"历史开发连接失败：{error}", file=sys.stderr)
        sys.exit(1)
