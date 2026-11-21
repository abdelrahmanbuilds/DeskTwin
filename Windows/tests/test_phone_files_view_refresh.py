import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Python" / "ui"))

from gui.phone_files_view import PhoneFilesView


def make_view():
    view = PhoneFilesView.__new__(PhoneFilesView)
    view._load_in_progress = False
    view._pending_load_path = None
    view._last_event_revision = 0
    view._render = MagicMock()
    view.status = MagicMock()
    view.main_window = SimpleNamespace(app_settings=SimpleNamespace(language="en"))
    return view


class PhoneFilesViewRefreshTests(unittest.TestCase):
    def test_active_upload_snapshot_is_isolated_from_internal_state(self):
        from sync import phone_fs

        with phone_fs._lock:
            original = dict(phone_fs._active_uploads)
            phone_fs._active_uploads.clear()
            phone_fs._active_uploads["transfer-1"] = {"transfer_id": "transfer-1", "bytes_sent": 12}
        try:
            snapshot = phone_fs.active_uploads()
            snapshot[0]["bytes_sent"] = 99
            with phone_fs._lock:
                self.assertEqual(phone_fs._active_uploads["transfer-1"]["bytes_sent"], 12)
        finally:
            with phone_fs._lock:
                phone_fs._active_uploads.clear()
                phone_fs._active_uploads.update(original)

    def test_loads_during_request_coalesce_to_latest_path(self):
        view = make_view()
        view._load_in_progress = True

        view.load("/first")
        view.load("/latest")

        self.assertEqual(view._pending_load_path, "/latest")

    def test_failed_listing_does_not_acknowledge_revision(self):
        view = make_view()
        view._load_in_progress = True
        view._last_event_revision = 7

        view._finish_request({"success": False, "error": "offline"}, "/", 11)

        self.assertEqual(view._last_event_revision, 7)
        self.assertFalse(view._load_in_progress)

    def test_success_acknowledges_snapshot_then_runs_one_queued_load(self):
        view = make_view()
        view._load_in_progress = True
        view._pending_load_path = "/latest"
        thread = MagicMock()

        with patch("sync.phone_fs.event_revision", return_value=12), patch(
            "gui.phone_files_view.threading.Thread", return_value=thread
        ) as thread_factory:
            view._finish_request({"success": True}, "/old", 10)

        self.assertEqual(view._last_event_revision, 10)
        self.assertTrue(view._load_in_progress)
        self.assertIsNone(view._pending_load_path)
        thread_factory.assert_called_once()
        self.assertEqual(thread_factory.call_args.kwargs["args"], ("/latest", 12))
        thread.start.assert_called_once()


if __name__ == "__main__":
    unittest.main()
