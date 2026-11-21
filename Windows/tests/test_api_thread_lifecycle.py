import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from Python.core.desktwin_service import DeskTwinService


class ApiThreadLifecycleTests(unittest.TestCase):
    def make_service(self):
        service = DeskTwinService.__new__(DeskTwinService)
        service._start_lock = threading.Lock()
        service._api_stop_requested = threading.Event()
        service._verification_stop = threading.Event()
        service._active_pairing_code = "123456"
        service._active_pairing_expires_at = 123.0
        service.api_start_error = RuntimeError("listener crashed")
        service.api_server = SimpleNamespace(should_exit=False)
        service.api_thread = None
        service.is_running = True
        service.is_starting = False
        service.observer = Mock()
        service.advertiser = Mock()
        service.on_log = Mock()
        return service

    def test_unexpected_api_exit_marks_service_unready_and_stops_companions(self):
        service = self.make_service()
        observer = service.observer
        advertiser = service.advertiser

        with patch("Python.core.desktwin_service.diagnostic_record"):
            service._handle_unexpected_api_exit(service.api_server)

        self.assertFalse(service.is_running)
        self.assertTrue(service._verification_stop.is_set())
        self.assertIsNone(service._active_pairing_code)
        self.assertIsNone(service.observer)
        self.assertIsNone(service.advertiser)
        observer.stop.assert_called_once()
        advertiser.stop.assert_called_once()
        service.on_log.assert_called_once()

    def test_requested_api_exit_does_not_mark_service_as_crashed(self):
        service = self.make_service()
        service._api_stop_requested.set()

        with patch("Python.core.desktwin_service.diagnostic_record"):
            service._handle_unexpected_api_exit(service.api_server)

        self.assertTrue(service.is_running)
        service.observer.stop.assert_not_called()
        service.advertiser.stop.assert_not_called()
        service.on_log.assert_not_called()


if __name__ == "__main__":
    unittest.main()
