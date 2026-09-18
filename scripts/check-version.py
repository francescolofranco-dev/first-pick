#!/usr/bin/env python3
"""Reject app commits without an explicit, staged semantic version bump."""
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def version(ref):
    result = subprocess.run(["git", "show", ref], capture_output=True, text=True)
    if result.returncode:
        if ref == "HEAD:VERSION":
            return (1, 0, 0)  # Last packaged version before VERSION was introduced.
        raise ValueError("Stage VERSION with the app changes.")
    value = result.stdout.strip()
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
        raise ValueError("VERSION must contain MAJOR.MINOR.PATCH.")
    return tuple(map(int, value.split(".")))


def main():
    paths = git("diff", "--cached", "--name-only", "--diff-filter=ACDMRT").splitlines()
    app_changed = any(
        path.startswith(("src/main/", "native/", "packaging/", "gradle/"))
        or path in {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}
        for path in paths
    )
    if not app_changed and "VERSION" not in paths:
        return
    old = version("HEAD:VERSION")
    new = version(":VERSION")
    major, minor, patch = old
    if new not in {(major, minor, patch + 1), (major, minor + 1, 0), (major + 1, 0, 0)}:
        raise ValueError("Bump and stage VERSION: PATCH for fixes, MINOR for features, MAJOR for breaking changes.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"Version check: {error}", file=sys.stderr)
        sys.exit(1)
