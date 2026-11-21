import gc
import tempfile
import unittest
from datetime import datetime, timezone

from Python.service.auth.pairing import PairingManager
from Python.service.database.db import DeskTwinDB


class PairingSessionTests(unittest.TestCase):
    def test_requested_temporary_session_duration_is_persisted(self):
        with tempfile.TemporaryDirectory() as folder:
            manager = PairingManager(DeskTwinDB(f"{folder}/desk-twin.db"))
            code = manager.generate_pairing_code()["code"]
            before = datetime.now(timezone.utc)

            result = manager.verify_code_and_issue_token(code, "test phone", session_minutes=30)

            self.assertTrue(result["success"])
            expires_at = datetime.fromisoformat(result["session_expires_at"])
            remaining = expires_at - before
            self.assertGreater(remaining.total_seconds(), 29 * 60 + 50)
            self.assertLess(remaining.total_seconds(), 30 * 60 + 10)
            del manager
            gc.collect()

    def test_permanent_pairing_remains_permanent_when_no_duration_is_requested(self):
        with tempfile.TemporaryDirectory() as folder:
            manager = PairingManager(DeskTwinDB(f"{folder}/desk-twin.db"))
            code = manager.generate_pairing_code()["code"]

            result = manager.verify_code_and_issue_token(code, "test phone", session_minutes=None)

            self.assertTrue(result["success"])
            self.assertIsNone(result["session_expires_at"])
            del manager
            gc.collect()

    def test_connected_device_name_resolves_without_returning_its_token(self):
        with tempfile.TemporaryDirectory() as folder:
            manager = PairingManager(DeskTwinDB(f"{folder}/desk-twin.db"))
            code = manager.generate_pairing_code()["code"]
            result = manager.verify_code_and_issue_token(code, "RMX2027")

            self.assertEqual(manager.get_device_name(result["device_token"]), "RMX2027")
            manager.revoke_token(result["device_token"])
            self.assertIsNone(manager.get_device_name(result["device_token"]))
            del manager
            gc.collect()


if __name__ == "__main__":
    unittest.main()
