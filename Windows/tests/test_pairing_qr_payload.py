import unittest
from types import SimpleNamespace
from urllib.parse import parse_qs, urlparse
from unittest.mock import patch

from Python.core.desktwin_service import DeskTwinService


class PairingQrPayloadTests(unittest.TestCase):
    def make_service(self, addresses):
        service = DeskTwinService.__new__(DeskTwinService)
        service.advertiser = SimpleNamespace(local_ips=addresses)
        return service

    def test_omits_link_local_hosts_and_keeps_lan_hosts(self):
        service = self.make_service(["169.254.10.20", "192.168.1.8", "10.0.0.4"])

        with patch("Python.core.desktwin_service.ensure_certificate", return_value=("cert", "key", "a" * 64)):
            payload = service.pairing_qr_payload("123456")
        query = parse_qs(urlparse(payload).query)
        hosts = query["host"]

        self.assertEqual(hosts, ["192.168.1.8", "10.0.0.4"])
        self.assertEqual(query["v"], ["2"])
        self.assertEqual(query["fp"], ["a" * 64])

    def test_certificate_bound_qr_is_available_when_discovery_has_no_lan_host(self):
        service = self.make_service(["169.254.10.20"])

        with patch("Python.core.desktwin_service.ensure_certificate", return_value=("cert", "key", "b" * 64)):
            payload = service.pairing_qr_payload("123456")
        query = parse_qs(urlparse(payload).query)

        self.assertEqual(query["code"], ["123456"])
        self.assertEqual(query["fp"], ["b" * 64])
        self.assertNotIn("host", query)


if __name__ == "__main__":
    unittest.main()
