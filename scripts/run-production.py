#!/usr/bin/env python3
"""Install and open one production instance at a stable per-user location."""
import fcntl
import os
from pathlib import Path
import plistlib
import signal
import subprocess
import sys
import tempfile
import time


def firstpick_bundle(path):
    try:
        with (path / "Contents/Info.plist").open("rb") as stream:
            return plistlib.load(stream).get("CFBundleIdentifier") == "com.firstpick.app"
    except (OSError, plistlib.InvalidFileException):
        return False


def running_instances():
    result = subprocess.check_output(["ps", "-U", str(os.getuid()), "-o", "pid=,comm="], text=True)
    instances = []
    for line in result.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) != 2:
            continue
        pid, command = fields
        executable = Path(command)
        if command.endswith(".app/Contents/MacOS/FirstPick") and firstpick_bundle(executable.parents[2]):
            instances.append(int(pid))
    return instances


def install_and_launch(source):
    if not firstpick_bundle(source):
        raise RuntimeError("Source is not a FirstPick app bundle.")
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(source)], check=True)
    support = Path.home() / "Library/Application Support/FirstPick"
    support.mkdir(parents=True, exist_ok=True)
    destination = Path.home() / "Applications/FirstPick.app"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with (support / "production-launch.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if destination.exists() and (destination.is_symlink() or not firstpick_bundle(destination)):
            raise RuntimeError(f"Refusing to replace an unrelated app at {destination}")
        with tempfile.TemporaryDirectory(prefix=".firstpick-install-", dir=destination.parent) as temporary:
            staged = Path(temporary) / "FirstPick.app"
            subprocess.run(["ditto", str(source), str(staged)], check=True)
            subprocess.run(["codesign", "--verify", "--deep", "--strict", str(staged)], check=True)
            for pid in running_instances():
                try:
                    os.kill(pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            deadline = time.monotonic() + 15
            while running_instances():
                if time.monotonic() >= deadline:
                    raise RuntimeError("FirstPick did not quit. Quit it manually and run runProduction again.")
                time.sleep(0.2)
            backup = Path(temporary) / "Previous.app"
            if destination.exists():
                destination.rename(backup)
            try:
                staged.rename(destination)
            except OSError:
                if backup.exists():
                    backup.rename(destination)
                raise
        arguments = ["open"]
        for name, value in {
            "FIRSTPICK_DEMO": "0", "FIRSTPICK_OVERLAY_TRACK": "0", "FIRSTPICK_FORCE_DECKS": "0",
            "FIRSTPICK_SMOKE": "0", "JAVA_TOOL_OPTIONS": "", "_JAVA_OPTIONS": "", "JDK_JAVA_OPTIONS": "",
        }.items():
            arguments.extend(["--env", f"{name}={value}"])
        subprocess.run([*arguments, str(destination)], check=True)
        print(f"Opened {destination}")


if __name__ == "__main__":
    try:
        install_and_launch(Path(sys.argv[1]).resolve())
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
