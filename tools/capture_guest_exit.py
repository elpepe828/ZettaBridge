#!/usr/bin/env python3
"""Collect one ZettaBridge guest run on a USB-debugging device, without root."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

PACKAGE = "com.zettabridge.launcher"
PROCESS = PACKAGE + ":guest"
REPORT = f"/sdcard/Android/data/{PACKAGE}/files/zb-runtime-report.txt"


def capture(adb, output, include_crash_buffer):
    def run(*args):
        return subprocess.run(
            [*adb, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False
        )

    def warn(message):
        print(message, file=sys.stderr)

    clock = run("shell", "date", "+%s")
    start = clock.stdout.strip().decode("ascii", errors="replace")
    if clock.returncode or not re.fullmatch(r"[0-9]{10,}", start):
        raise ValueError("Could not read device time. Check `adb devices` and USB authorization.")
    since = start + ".000"
    before = run("shell", "cat", REPORT)

    input("Launch the game and wait for its menu. Press Enter before triggering the exit: ")
    lookup = run("shell", "pidof", PROCESS)
    pid = lookup.stdout.strip().decode("ascii", errors="replace")
    if lookup.returncode or not re.fullmatch(r"[1-9][0-9]*", pid):
        raise ValueError("Could not find one running guest process. Retry with the game visible.")

    input(f"Guest PID {pid}. Reproduce the exit, then press Enter without reopening the game: ")
    if output is None:
        output = Path(tempfile.mkdtemp(prefix="zettabridge-exit-"))
    else:
        output.mkdir(mode=0o700)  # Refuse to overwrite or mix with an earlier capture.

    saved = 0
    report = run("shell", "cat", REPORT)
    if report.returncode or not report.stdout.strip():
        warn("No readable runtime report. Try the launcher's Last run report menu.")
    elif before.returncode == 0 and before.stdout == report.stdout:
        warn("The runtime report did not change; it may be from an earlier run. Not saved.")
    else:
        (output / "runtime-report.txt").write_bytes(report.stdout)
        saved += 1

    logs = run("logcat", "-b", "main,system,crash", "-t", since, "-v", "threadtime",
               f"--pid={pid}", "*:V")
    if logs.returncode or not any(
        re.match(rb"^\d\d-\d\d\s+\d\d:", line) for line in logs.stdout.splitlines()
    ):
        warn("No guest process log entries captured. The device may suppress application logs.")
    else:
        (output / "guest-logcat.txt").write_bytes(logs.stdout)
        saved += 1

    if include_crash_buffer:
        # Debuggerd can log under its own PID. Keep this device-wide buffer separate.
        crash = run("logcat", "-b", "crash", "-t", since, "-v", "threadtime", "*:V")
        if crash.returncode:
            warn("Could not read the Android crash buffer.")
        else:
            (output / "device-crash-private.txt").write_bytes(crash.stdout)
            warn("The device crash buffer can include other apps. Do not share it unreviewed.")

    print(f"Capture directory: {output.resolve()}")
    print("Review captured files before sharing; they may contain personal information.")
    return 0 if saved else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="device serial if more than one device is connected")
    parser.add_argument("--output", type=Path, help="new directory (default: private temp directory)")
    parser.add_argument("--include-device-crash-buffer", action="store_true",
                        help="also save the device-wide crash buffer for local review")
    args = parser.parse_args()
    adb = [os.environ.get("ADB", "adb")]
    if args.serial:
        adb += ["-s", args.serial]
    try:
        return capture(adb, args.output, args.include_device_crash_buffer)
    except (ValueError, OSError, EOFError, KeyboardInterrupt) as error:
        print(f"Capture stopped: {error or type(error).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
