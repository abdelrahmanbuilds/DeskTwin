import unittest
from types import SimpleNamespace
from unittest.mock import patch

from discovery import discovery
from Python.core.diagnostics import _safe, _safe_fields


def interface_address(ip, is_ipv4=True):
    return SimpleNamespace(ip=ip, is_IPv4=is_ipv4)


class LocalAddressTests(unittest.TestCase):
    @patch("discovery.discovery.ifaddr.get_adapters")
    def test_returns_unique_routable_ipv4_addresses(self, get_adapters):
        get_adapters.return_value = [
            SimpleNamespace(ips=[
                interface_address("192.168.1.25"),
                interface_address("192.168.1.25"),
                interface_address("127.0.0.1"),
                interface_address("169.254.3.161"),
                interface_address("192.168.1.25", is_ipv4=False),
            ]),
            SimpleNamespace(ips=[interface_address("10.0.0.4")]),
        ]

        self.assertEqual(discovery._get_local_ips(), ["10.0.0.4", "192.168.1.25"])

    @patch("discovery.discovery.ifaddr.get_adapters", return_value=[])
    def test_fails_instead_of_advertising_loopback_when_no_interface_exists(self, _get_adapters):
        with self.assertRaisesRegex(RuntimeError, "لم يتم العثور"):
            discovery._get_local_ips()

    @patch("discovery.discovery.ifaddr.get_adapters", side_effect=OSError("unavailable"))
    def test_reports_interface_enumeration_failure(self, _get_adapters):
        with self.assertRaisesRegex(RuntimeError, "تعذر قراءة"):
            discovery._get_local_ips()


class AdvertiserTests(unittest.TestCase):
    @patch("discovery.discovery.Zeroconf")
    @patch("discovery.discovery.ServiceInfo")
    @patch("discovery.discovery._get_local_ips", return_value=["192.168.1.25", "10.0.0.4"])
    def test_advertises_all_available_ipv4_addresses(self, _get_ips, service_info, zeroconf):
        advertiser = discovery.DeskTwinAdvertiser(port=8420, computer_name="DeskTwin-PC")

        advertiser.start()

        advertised = service_info.call_args.kwargs["addresses"]
        self.assertEqual(advertised, [b"\xc0\xa8\x01\x19", b"\x0a\x00\x00\x04"])
        zeroconf.return_value.register_service.assert_called_once_with(service_info.return_value)


class DiagnosticsRedactionTests(unittest.TestCase):
    def test_redacts_bearer_authorization_values(self):
        self.assertEqual(
            _safe("Authorization: Bearer sensitive-value"),
            "Authorization: Bearer <redacted>",
        )

    def test_redacts_sensitive_structured_fields_by_name(self):
        self.assertEqual(
            _safe_fields({"device_token": "sensitive-value", "phase": "pairing"}),
            {"device_token": "<redacted>", "phase": "pairing"},
        )


if __name__ == "__main__":
    unittest.main()
