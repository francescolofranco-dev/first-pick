#!/usr/bin/env python3
import pathlib
import subprocess
import sys
import tempfile
import unittest

GUARD = pathlib.Path(__file__).with_name("check-version.py").resolve()


class VersionGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Version test")
        self.git("config", "user.email", "test@example.invalid")
        self.write("VERSION", "1.1.0\n")
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "Initial")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def write(self, path, value):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)

    def check(self, path, value=None, staged=True):
        self.write(path, "change\n")
        self.git("add", path)
        if value is not None:
            self.write("VERSION", value)
            if staged:
                self.git("add", "VERSION")
        return subprocess.run([sys.executable, str(GUARD)], cwd=self.root, capture_output=True).returncode

    def test_app_change_requires_bump(self):
        self.assertNotEqual(0, self.check("src/main/App.kt"))

    def test_unstaged_bump_does_not_count(self):
        self.assertNotEqual(0, self.check("src/main/App.kt", "1.1.1", staged=False))

    def test_patch_minor_and_major(self):
        for value in ["1.1.1", "1.2.0", "2.0.0"]:
            self.assertEqual(0, self.check("src/main/App.kt", value))

    def test_reject_invalid_or_nonincremental_versions(self):
        for value in ["1.1.0", "1.0.9", "01.2.0", "1.2.1", "garbage"]:
            self.assertNotEqual(0, self.check("native/helper.swift", value))

    def test_docs_and_tests_need_no_bump(self):
        self.assertEqual(0, self.check("README.md"))
        self.assertEqual(0, self.check("src/test/Example.kt"))

    def test_build_change_requires_bump(self):
        self.assertNotEqual(0, self.check("build.gradle.kts"))


if __name__ == "__main__":
    unittest.main()
