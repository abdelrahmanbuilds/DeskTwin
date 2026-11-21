import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sync import phone_fs


class PhoneFileUploadErrorTests(unittest.TestCase):
    def test_phone_upload_rejection_is_returned_without_waiting_for_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "photo.jpg"
            source.write_bytes(b"photo")

            async def send_to_phone(token, message, channel="control"):
                if message["type"] == "phone_fs_upload_start":
                    phone_fs.resolve_upload_ack({
                        "type": "phone_fs_response",
                        "request_id": message["request_id"],
                        "success": False,
                        "error": "destination denied",
                    })
                return True

            with patch.object(phone_fs, "cleanup_transfers"), \
                 patch.object(phone_fs.manager, "active_tokens", return_value=["trusted-phone"]), \
                 patch.object(phone_fs.manager, "send_to_token", side_effect=send_to_phone):
                result = asyncio.run(phone_fs.upload_active(str(source), "", source.name))

        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "destination denied")
        self.assertTrue(result["transfer_id"])

    def test_upload_response_router_reports_if_it_consumed_a_waiter(self):
        loop = asyncio.new_event_loop()
        request_id = "test-upload-router"
        waiter = loop.create_future()
        try:
            with phone_fs._lock:
                phone_fs._upload_waiters[request_id] = waiter

            rejected = {"request_id": request_id, "success": False, "error": "denied"}
            self.assertTrue(phone_fs.resolve_upload_ack(rejected))
            self.assertEqual(waiter.result(), rejected)
            self.assertFalse(phone_fs.resolve_upload_ack(rejected))
        finally:
            with phone_fs._lock:
                phone_fs._upload_waiters.pop(request_id, None)
            loop.close()


if __name__ == "__main__":
    unittest.main()
