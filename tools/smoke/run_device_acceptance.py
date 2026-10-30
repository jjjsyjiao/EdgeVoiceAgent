#!/usr/bin/env python3
"""Run opt-in device checks; restore network settings in finally.

Only instrumentation statistics are logged. No credentials or live media are read.
Network changes are limited to lifecycle's offline precondition or daily's explicit
recovery window. Do not use the phone for another task during these checks.
"""
import argparse
from datetime import datetime
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--suite", choices=("lifecycle", "daily"), required=True)
    parser.add_argument("--duration-seconds", type=int, default=1800)
    parser.add_argument("--rounds", type=int, default=20)
    parser.add_argument("--recovery-test", action="store_true")
    parser.add_argument("--detached", action="store_true",
                        help="daily only: keep remote instrumentation alive across brief ADB disconnects")
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()
    if args.detached and args.suite != "daily":
        parser.error("--detached is only supported with --suite daily")
    if args.duration_seconds <= 0 or args.rounds <= 0:
        parser.error("duration and rounds must be positive")
    args.log.parent.mkdir(parents=True, exist_ok=True)
    acceptance_deadline = None

    def adb(*parts, timeout=20):
        if acceptance_deadline is not None:
            remaining = acceptance_deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("Detached device acceptance exceeded duration plus 300 seconds")
            timeout = min(timeout, remaining)
        return subprocess.run([args.adb, *parts], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout).stdout.strip()

    initial = {key: adb("shell", "settings", "get", "global", key)
               for key in ("wifi_on", "mobile_data")}
    if initial["wifi_on"] not in ("0", "1") or initial["mobile_data"] not in ("0", "1"):
        raise RuntimeError("Unsupported initial network state; no settings changed")

    def network_off():
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")

    def restore_network(timeout=20, deadline=None):
        for service, setting in (("wifi", "wifi_on"), ("data", "mobile_data")):
            remaining = deadline - time.monotonic() if deadline is not None else timeout
            if remaining <= 0:
                raise subprocess.TimeoutExpired("network restoration", timeout)
            adb("shell", "svc", service, "enable" if initial[setting] == "1" else "disable", timeout=min(timeout, remaining))

    def interrupted(signum, _frame):
        raise KeyboardInterrupt(f"signal {signum}")

    signal.signal(signal.SIGTERM, interrupted)
    process = None
    passed = False
    restored = False
    with args.log.open("w", encoding="utf-8") as log:
        def emit(text):
            line = f"{datetime.now().astimezone().isoformat(timespec='seconds')} {text}"
            print(line, flush=True)
            log.write(line + "\n")
            log.flush()

        observed_stages = set()
        pending_stages = []
        result_pass = False

        def consume(line):
            nonlocal result_pass
            emit(line)
            for stage in ("daily_recovery_disable_network", "daily_recovery_restore_network", "lifecycle_restore_network"):
                if f"stage={stage}" in line and stage not in observed_stages:
                    observed_stages.add(stage)
                    pending_stages.append(stage)
            if line.startswith("INSTRUMENTATION_RESULT: status=PASS"):
                result_pass = True

        def apply_pending_stages():
            while pending_stages:
                stage = pending_stages[0]
                if stage == "daily_recovery_disable_network":
                    network_off()
                else:
                    restore_network()
                pending_stages.pop(0)
                emit(f"operator: applied stage={stage}; duplicate log lines cannot repeat this stage")

        def restore_with_retry():
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                try:
                    # Bound each command, including a stalled/offline ADB daemon.
                    restore_network(timeout=10, deadline=deadline)
                    matches = True
                    for setting, original in initial.items():
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            return False
                        matches &= adb("shell", "settings", "get", "global", setting,
                                       timeout=min(10, remaining)) == original
                    if matches:
                        return True
                    emit("cleanup: waiting for original network settings to settle")
                    time.sleep(min(5, max(0, deadline - time.monotonic())))
                except (subprocess.SubprocessError, OSError):
                    emit("cleanup: ADB unavailable while restoring network; retrying for at most 90 seconds")
                    time.sleep(min(5, max(0, deadline - time.monotonic())))
            return False

        try:
            emit(f"suite={args.suite}; original_wifi={initial['wifi_on']}; original_mobile={initial['mobile_data']}")
            if args.suite == "lifecycle":
                network_off()
                time.sleep(3)
                extra = ["-e", "restore_network", "true"]
            else:
                extra = ["-e", "duration_seconds", str(args.duration_seconds), "-e", "rounds", str(args.rounds)]
                if args.recovery_test:
                    extra += ["-e", "recovery_test", "true"]
            instrument = ["am", "instrument", "-w", "-e", "suite", args.suite, *extra,
                          "com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation"]
            if args.detached:
                remote_base = f"/data/local/tmp/edgevoice-acceptance-{uuid.uuid4().hex}"
                remote_log, remote_result = remote_base + ".log", remote_base + ".result"
                body = f"{shlex.join(instrument)} >{shlex.quote(remote_log)} 2>&1; echo DONE:$? >{shlex.quote(remote_result)}"
                launch = f"nohup setsid sh -c {shlex.quote(body)} </dev/null >/dev/null 2>&1 &"
                deadline = time.monotonic() + args.duration_seconds + 300
                acceptance_deadline = deadline
                emit(f"operator: detached device_log={remote_log}; device_result={remote_result}; deadline_seconds={args.duration_seconds + 300}")
                adb("shell", launch)
                read_lines = 0
                adb_offline = False

                def mirror(output, final=False):
                    nonlocal read_lines
                    lines = output.splitlines(keepends=True)
                    if lines and not final and not lines[-1].endswith(("\n", "\r")):
                        lines.pop()
                    if len(lines) < read_lines:
                        raise RuntimeError("Detached instrumentation log was truncated")
                    for item in lines[read_lines:]:
                        consume(item.rstrip("\r\n"))
                    read_lines = len(lines)

                while time.monotonic() < deadline:
                    try:
                        # Preserve the trailing newline so an unfinished line is not mirrored twice.
                        output = subprocess.run([args.adb, "shell", "cat", remote_log], check=True, text=True,
                                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                                timeout=min(20, max(0.1, deadline - time.monotonic()))).stdout
                        if adb_offline:
                            emit("operator: ADB connection restored; continuing same detached test (not a cloud recovery result)")
                            adb_offline = False
                        mirror(output)
                        apply_pending_stages()
                        marker = adb("shell", f"if test -f {shlex.quote(remote_result)}; then cat {shlex.quote(remote_result)}; fi",
                                     timeout=min(20, max(0.1, deadline - time.monotonic())))
                        if marker:
                            final_output = subprocess.run([args.adb, "shell", "cat", remote_log], check=True, text=True,
                                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                                          timeout=min(20, max(0.1, deadline - time.monotonic()))).stdout
                            mirror(final_output, final=True)
                            apply_pending_stages()
                            emit(f"operator: detached_result={marker}")
                            passed = marker == "DONE:0" and result_pass
                            if not passed:
                                raise RuntimeError("Detached device acceptance failed; inspect controlled statistics in log")
                            break
                    except (subprocess.SubprocessError, OSError):
                        if not adb_offline:
                            emit("operator: ADB unavailable; retrying every 5 seconds; detached test is not force-stopped")
                            adb_offline = True
                    time.sleep(min(5, max(0, deadline - time.monotonic())))
                else:
                    raise RuntimeError("Detached device acceptance exceeded duration plus 300 seconds")
            else:
                command = [args.adb, "shell", *instrument]
                process = subprocess.Popen(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1)
                for line in process.stdout:
                    consume(line.rstrip())
                    apply_pending_stages()
                passed = process.wait() == 0 and result_pass
                if not passed:
                    raise RuntimeError("Device acceptance failed; inspect controlled failure code in log")
        except RuntimeError as error:
            emit(f"operator: acceptance_failure={error}")
            raise
        finally:
            # The test deadline is strict; restoration has its own bounded cleanup budget.
            acceptance_deadline = None
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
            # An interrupted adb client does not necessarily stop remote instrumentation.
            stopped = False
            if not passed:
                try:
                    adb("shell", "am", "force-stop", "com.edgevoice.agent.app")
                    stopped = True
                except (subprocess.SubprocessError, OSError):
                    pass
            restored = restore_with_retry()
            if not passed and not stopped and restored:
                try:
                    adb("shell", "am", "force-stop", "com.edgevoice.agent.app")
                except (subprocess.SubprocessError, OSError):
                    emit("cleanup: could not force-stop failed remote instrumentation")
            emit(f"cleanup: network_restored={restored}")
            if not restored:
                raise RuntimeError("Network restoration failed after 90 seconds; reconnect ADB and restore original settings")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(f"acceptance stopped: {type(error).__name__}", file=sys.stderr)
        sys.exit(1)
