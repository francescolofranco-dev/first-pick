#!/usr/bin/env python3
"""Regression checks for selecting only FirstPick processes during updates."""
import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("production", Path(__file__).with_name("run-production.py"))
production = importlib.util.module_from_spec(spec)
spec.loader.exec_module(production)


class ProductionLaunchTest(unittest.TestCase):
    def test_only_matching_bundle_executables_are_stopped(self):
        with tempfile.TemporaryDirectory(prefix="firstpick launch ") as tmp:
            root = Path(tmp)
            for name, identifier in (("FirstPick", "com.firstpick.app"), ("Imposter", "com.other.app")):
                contents = root / f"{name}.app/Contents"
                contents.mkdir(parents=True)
                with (contents / "Info.plist").open("wb") as stream:
                    plistlib.dump({"CFBundleIdentifier": identifier}, stream)
            output = (
                f"100 {root}/FirstPick.app/Contents/MacOS/FirstPick\n"
                f"101 {root}/Imposter.app/Contents/MacOS/FirstPick\n"
                "102 /usr/bin/python3\n"
                f"103 {root}/FirstPick.app/Contents/Helpers/deck-reader\n"
                "104 /missing/FirstPick.app/Contents/MacOS/FirstPick\n"
            )
            with patch.object(production.subprocess, "check_output", return_value=output):
                self.assertEqual([100], production.running_instances())

    def test_invalid_source_never_stops_the_running_app(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(production.os, "kill") as kill:
                with self.assertRaisesRegex(RuntimeError, "not a FirstPick"):
                    production.install_and_launch(Path(tmp))
                kill.assert_not_called()


if __name__ == "__main__":
    unittest.main()
