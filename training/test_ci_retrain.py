"""Release-to-model selection checks; no network or torch installation needed."""

import contextlib
import io
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

import ci_retrain


class NewSetSelectionTest(unittest.TestCase):
    def select(self, data_date, model_date):
        # Use the real manifest to verify that new sets join weekly selection.
        with (
            patch.object(ci_retrain, "data_last_modified", side_effect=lambda code: data_date if code == "FRA" else None),
            patch.object(ci_retrain, "model_committed", return_value=model_date),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            return ci_retrain.select_auto()

    def test_reality_fracture_is_registered_for_training(self):
        self.assertIn("FRA", ci_retrain.standard_sets())

    def test_unavailable_launch_data_is_skipped(self):
        self.assertEqual([], self.select(data_date=None, model_date=None))

    def test_published_data_selects_set_without_model(self):
        published = datetime(2026, 10, 13, tzinfo=timezone.utc)
        self.assertEqual(["FRA"], self.select(data_date=published, model_date=None))

    def test_new_data_retrains_an_existing_model(self):
        old = datetime(2026, 10, 13, tzinfo=timezone.utc)
        new = datetime(2026, 10, 20, tzinfo=timezone.utc)
        self.assertEqual(["FRA"], self.select(data_date=new, model_date=old))
        self.assertEqual([], self.select(data_date=old, model_date=new))


if __name__ == "__main__":
    unittest.main()
