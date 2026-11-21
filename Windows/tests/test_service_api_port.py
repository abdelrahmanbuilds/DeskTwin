import socket
import unittest

from Python.core.desktwin_service import DeskTwinService


class ApiPortPreflightTests(unittest.TestCase):
    def test_reports_a_port_already_in_use_before_service_start(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            with self.assertRaisesRegex(RuntimeError, "مستخدم بالفعل"):
                DeskTwinService._ensure_api_port_available(host="127.0.0.1", port=port)

    def test_accepts_an_available_port(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        self.assertIsNone(DeskTwinService._ensure_api_port_available(host="127.0.0.1", port=port))


if __name__ == "__main__":
    unittest.main()
