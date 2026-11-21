import json
import unittest

from WinUI.bridge import winui_host


class LegacyCodePagePipe:
    """Strictly model a redirected Windows pipe using a Western code page."""

    def __init__(self):
        self.text = ""

    def write(self, value):
        value.encode("cp1252")
        self.text += value
        return len(value)

    def flush(self):
        pass


def test_bridge_escapes_arabic_for_legacy_windows_pipe(monkeypatch):
    pipe = LegacyCodePagePipe()
    monkeypatch.setattr(winui_host, "_wire", pipe)

    winui_host.emit({"event": "log", "message": "جاري فحص المجلدات"})

    assert json.loads(pipe.text)["message"] == "جاري فحص المجلدات"


class PhoneFileListOptionsTests(unittest.TestCase):
    def test_legacy_request_defaults_to_unpaged_name_listing(self):
        self.assertEqual(
            winui_host.normalize_phone_file_list_options({"path": "Documents"}),
            {"offset": 0, "limit": 0, "sort": "name", "search": ""},
        )

    def test_paged_request_preserves_bounded_search_and_allowlisted_sort(self):
        self.assertEqual(
            winui_host.normalize_phone_file_list_options({
                "offset": 150,
                "limit": 150,
                "sort": "modified",
                "search": "report",
            }),
            {"offset": 150, "limit": 150, "sort": "modified", "search": "report"},
        )
        normalized = winui_host.normalize_phone_file_list_options({"sort": "unsupported"})
        self.assertEqual(normalized["sort"], "name")

    def test_rejects_unbounded_page_size_over_limit(self):
        with self.assertRaises(ValueError):
            winui_host.normalize_phone_file_list_options({"limit": 501})

    def test_storage_roots_use_existing_allowlisted_phone_action(self):
        self.assertEqual(winui_host.validate_phone_file_action("roots"), "roots")
        with self.assertRaises(ValueError):
            winui_host.validate_phone_file_action("arbitrary_operation")


class PhoneChannelStateTests(unittest.TestCase):
    class FakeManager:
        def __init__(self, channels):
            self.channels = channels
            self.has_active_connection = "control" in channels

        def active_tokens(self, channel):
            return ["paired-device"] if channel in self.channels else []

    class FakeApi:
        def __init__(self, manager):
            self.ws_manager = manager

    def test_control_and_file_channels_report_independently(self):
        api = self.FakeApi(self.FakeManager({"control"}))

        self.assertTrue(winui_host.phone_channel_connected(True, api, "control"))
        self.assertFalse(winui_host.phone_channel_connected(True, api, "phone_files"))

        api.ws_manager = self.FakeManager({"phone_files"})
        self.assertFalse(winui_host.phone_channel_connected(True, api, "control"))
        self.assertTrue(winui_host.phone_channel_connected(True, api, "phone_files"))

    def test_channels_are_disconnected_when_service_is_stopped(self):
        api = self.FakeApi(self.FakeManager({"control", "phone_files"}))

        self.assertFalse(winui_host.phone_channel_connected(False, api, "control"))
        self.assertFalse(winui_host.phone_channel_connected(False, api, "phone_files"))

    def test_connected_device_label_uses_file_channel_and_never_returns_token(self):
        class Manager:
            def active_tokens(self, channel):
                return {"phone_files": ["secret-file-token"], "control": ["secret-control-token"]}.get(channel, [])

        class Api:
            ws_manager = Manager()

        class Pairing:
            def __init__(self):
                self.looked_up = []

            def get_device_name(self, token):
                self.looked_up.append(token)
                return "RMX2027" if token == "secret-file-token" else None

        pairing = Pairing()
        name = winui_host.connected_phone_device_name(True, Api(), pairing)

        self.assertEqual(name, "RMX2027")
        self.assertEqual(pairing.looked_up, ["secret-file-token"])
        self.assertNotIn("secret-file-token", name)
