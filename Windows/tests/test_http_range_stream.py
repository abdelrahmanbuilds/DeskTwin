import asyncio
import io
import unittest
from unittest.mock import mock_open, patch

from fastapi import HTTPException

from Python.service.transfer.http_range import file_range_response, parse_byte_range


class HttpRangeStreamingTests(unittest.TestCase):
    def test_parses_open_ended_and_suffix_ranges(self):
        self.assertEqual(parse_byte_range("bytes=3-", 10), (3, 9, True))
        self.assertEqual(parse_byte_range("bytes=-4", 10), (6, 9, True))
        self.assertEqual(parse_byte_range(None, 10), (0, 9, False))

    def test_rejects_unsatisfiable_and_multi_ranges(self):
        for header in ("bytes=10-", "bytes=7-3", "bytes=0-1,4-5"):
            with self.subTest(header=header), self.assertRaises(HTTPException) as raised:
                parse_byte_range(header, 10)
            self.assertEqual(raised.exception.status_code, 416)
            self.assertEqual(raised.exception.headers["Content-Range"], "bytes */10")

    def test_stream_response_sends_only_requested_bytes(self):
        path = "movie.mp4"
        with patch("Python.service.transfer.http_range.os.path.getsize", return_value=10), \
             patch("builtins.open", return_value=io.BytesIO(b"0123456789")):
            response = file_range_response(path, "movie.mp4", "bytes=4-7")
            body = asyncio.run(self._read_body(response))

            self.assertEqual(response.status_code, 206)
            self.assertEqual(response.headers["accept-ranges"], "bytes")
            self.assertEqual(response.headers["content-range"], "bytes 4-7/10")
            self.assertEqual(response.headers["content-length"], "4")
            self.assertEqual(response.headers["content-type"], "video/mp4")
            self.assertEqual(body, b"4567")

    @staticmethod
    async def _read_body(response):
        chunks = []
        async for chunk in response.body_iterator:
            chunks.append(chunk)
        return b"".join(chunks)


if __name__ == "__main__":
    unittest.main()
