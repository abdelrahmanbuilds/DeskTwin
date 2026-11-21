import asyncio
import base64
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from sync import phone_fs


class PhoneFileDownloadIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.transfer_dir_patch = patch.object(phone_fs, "_transfer_dir", self.temp_dir.name)
        self.transfer_dir_patch.start()
        self.request_id = uuid.uuid4().hex
        self.loop = asyncio.new_event_loop()
        self.future = self.loop.create_future()
        with phone_fs._lock:
            phone_fs._pending[self.request_id] = self.future
            phone_fs._downloads[self.request_id] = {"next_sequence": 0, "bytes_received": 0}

    def tearDown(self):
        with phone_fs._lock:
            phone_fs._pending.pop(self.request_id, None)
            phone_fs._downloads.pop(self.request_id, None)
        self.loop.close()
        self.transfer_dir_patch.stop()
        self.temp_dir.cleanup()

    def test_ordered_chunks_and_final_size_produce_verified_file(self):
        first = b"phone"
        second = b" files"
        phone_fs.accept_chunk({
            "request_id": self.request_id,
            "sequence": 0,
            "data": base64.b64encode(first).decode("ascii"),
        })
        phone_fs.accept_chunk({
            "request_id": self.request_id,
            "sequence": 1,
            "data": base64.b64encode(second).decode("ascii"),
        })

        phone_fs.resolve_response({
            "request_id": self.request_id,
            "type": "phone_fs_response",
            "success": True,
            "mode": "chunked",
            "size": len(first) + len(second),
        })

        result = self.future.result()
        self.assertTrue(result["success"])
        self.assertEqual(Path(result["local_path"]).read_bytes(), first + second)

    def test_out_of_order_chunk_fails_and_removes_partial_file(self):
        phone_fs.accept_chunk({
            "request_id": self.request_id,
            "sequence": 1,
            "data": base64.b64encode(b"wrong order").decode("ascii"),
        })

        self.assertFalse(self.future.result()["success"])
        self.assertFalse((Path(self.temp_dir.name) / f"{self.request_id}.part").exists())

    def test_final_size_mismatch_fails_and_removes_partial_file(self):
        phone_fs.accept_chunk({
            "request_id": self.request_id,
            "sequence": 0,
            "data": base64.b64encode(b"short").decode("ascii"),
        })

        phone_fs.resolve_response({
            "request_id": self.request_id,
            "type": "phone_fs_response",
            "success": True,
            "mode": "chunked",
            "size": 99,
        })

        self.assertFalse(self.future.result()["success"])
        self.assertFalse((Path(self.temp_dir.name) / f"{self.request_id}.part").exists())

    def test_empty_file_stream_returns_an_empty_download_file(self):
        phone_fs.resolve_response({
            "request_id": self.request_id,
            "type": "phone_fs_response",
            "success": True,
            "mode": "chunked",
            "size": 0,
        })

        result = self.future.result()
        self.assertTrue(result["success"])
        self.assertEqual(Path(result["local_path"]).read_bytes(), b"")

    def test_rejects_invalid_base64(self):
        phone_fs.accept_chunk({"request_id": self.request_id, "sequence": 0, "data": "%%%"})

        self.assertFalse(self.future.result()["success"])
        self.assertFalse((Path(self.temp_dir.name) / f"{self.request_id}.part").exists())


if __name__ == "__main__":
    unittest.main()
