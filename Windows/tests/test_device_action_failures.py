import asyncio
import os
import sys
import types
import unittest
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import Mock, patch

from fastapi import HTTPException


SERVICE_ROOT = Path(__file__).resolve().parents[1] / "Python" / "service"
sys.path.insert(0, str(SERVICE_ROOT))

# Import the API with an isolated temporary database; importing it must never
# migrate or otherwise touch the user's live DeskTwin database in this test.
from database.db import DeskTwinDB

_original_db_init = DeskTwinDB.__init__
DeskTwinDB.__init__ = lambda self: _original_db_init(self, ":memory:")
try:
    from api import server
finally:
    DeskTwinDB.__init__ = _original_db_init


class DeviceActionFailureTests(unittest.TestCase):
    def setUp(self):
        self.original_trust_check = server.pairing.get_trust_level
        self.original_run = server.subprocess.run
        self.original_log_activity = server.db.log_activity
        self.original_path_allowed = server.config.is_path_allowed
        self.original_stream_started = server._stream_started_at
        server._stream_started_at = {}
        server.pairing.get_trust_level = lambda _token: "full"
        server.db.log_activity = lambda *_args, **_kwargs: None

    def tearDown(self):
        server.pairing.get_trust_level = self.original_trust_check
        server.subprocess.run = self.original_run
        server.db.log_activity = self.original_log_activity
        server.config.is_path_allowed = self.original_path_allowed
        server._stream_started_at = self.original_stream_started

    def test_brightness_command_timeout_returns_clear_gateway_timeout(self):
        timeout = server.subprocess.TimeoutExpired(cmd="powershell.exe", timeout=8)
        server.subprocess.run = Mock(side_effect=timeout)

        with self.assertRaises(HTTPException) as raised:
            server.device_action(server.DeviceActionRequest(action="brightness_up"), token="test-token")

        self.assertEqual(raised.exception.status_code, 504)
        self.assertIn("انتهت مهلة", raised.exception.detail)
        self.assertEqual(server.subprocess.run.call_args.kwargs["timeout"], 8)

    def test_standard_pairing_can_use_non_destructive_quick_actions(self):
        server.pairing.get_trust_level = lambda _token: "standard"

        response = server.device_action(server.DeviceActionRequest(action="refresh"), token="test-token")

        self.assertTrue(response.success)
        self.assertEqual(response.action, "refresh")

    def test_standard_pairing_cannot_run_destructive_quick_actions(self):
        server.pairing.get_trust_level = lambda _token: "standard"

        with self.assertRaises(HTTPException) as raised:
            server.device_action(server.DeviceActionRequest(action="restart"), token="test-token")

        self.assertEqual(raised.exception.status_code, 403)

    def test_brightness_command_failure_returns_supported_hardware_hint(self):
        server.subprocess.run = Mock(side_effect=server.subprocess.CalledProcessError(
            1, "powershell.exe", stderr="No WMI brightness provider"
        ))

        with self.assertRaises(HTTPException) as raised:
            server.device_action(server.DeviceActionRequest(action="brightness_down"), token="test-token")

        self.assertEqual(raised.exception.status_code, 500)
        self.assertIn("قد لا تدعم الشاشة", raised.exception.detail)
        self.assertIn("No WMI brightness provider", raised.exception.detail)

    def test_live_path_stream_works_without_an_index_id_and_keeps_range_support(self):
        video_path = str(Path(__file__).resolve())
        server.config.is_path_allowed = lambda _path: True
        notifications = types.ModuleType("notifications.notifications")
        notifications.notify_operation = Mock()
        with patch.dict(sys.modules, {"notifications.notifications": notifications}), \
             patch.object(server.db, "cursor", side_effect=AssertionError("must not query the index")), \
             patch.object(server.db, "log_activity"):
            response = server.get_file_content(
                file_id=0,
                request=SimpleNamespace(headers={"range": "bytes=0-3"}),
                stream=True,
                path=video_path,
                token="trusted-device",
            )
            body = asyncio.run(self._read_body(response))

        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.headers["content-range"], f"bytes 0-3/{os.path.getsize(video_path)}")
        self.assertEqual(body, Path(__file__).read_bytes()[:4])

    def test_open_on_computer_uses_live_path_without_an_index_id(self):
        file_path = str(Path(__file__).resolve())
        server.config.is_path_allowed = lambda _path: True
        notifications = types.ModuleType("notifications.notifications")
        notifications.notify_operation = Mock()
        with patch.dict(sys.modules, {"notifications.notifications": notifications}), \
            patch.object(server.db, "cursor", side_effect=AssertionError("must not query the index")), \
            patch.object(server.db, "log_activity"), \
            patch.object(server.subprocess, "Popen") as popen:
            response = server.open_file_on_computer(file_id=0, path=file_path, token="trusted-device")

        self.assertTrue(response["success"])
        self.assertEqual(response["path"], file_path)
        self.assertEqual(response["operation"], "show_in_explorer")
        popen.assert_called_once_with(["explorer.exe", f"/select,{os.path.normpath(file_path)}"])

    def test_live_path_actions_still_require_allowed_scope(self):
        server.config.is_path_allowed = lambda _path: False
        with patch.object(server.db, "cursor", side_effect=AssertionError("must reject before index lookup")):
            with self.assertRaises(HTTPException) as raised:
                server.open_file_on_computer(file_id=0, path="outside.mp4", token="trusted-device")

        self.assertEqual(raised.exception.status_code, 403)

    def test_browser_origin_is_rejected_before_websocket_authentication(self):
        class BrowserSocket:
            headers = {"origin": "https://attacker.invalid"}

            def __init__(self):
                self.close_code = None

            async def close(self, code):
                self.close_code = code

        socket = BrowserSocket()
        asyncio.run(server.websocket_endpoint(socket))

        self.assertEqual(socket.close_code, 4403)

    def test_shell_execution_approval_request_is_rejected(self):
        with self.assertRaises(HTTPException) as raised:
            server.request_approval(
                server.ApprovalRequest(action_type="command_execute"),
                token="trusted-device",
            )

        self.assertEqual(raised.exception.status_code, 400)

    @staticmethod
    async def _read_body(response):
        chunks = []
        async for chunk in response.body_iterator:
            chunks.append(chunk)
        return b"".join(chunks)


if __name__ == "__main__":
    unittest.main()
