import hashlib
import os
import tempfile
import unittest

from transfer.resumable import (
    append_chunk,
    compute_fingerprint,
    get_resume_offset,
    verify_partial_matches_fingerprint,
)


class ResumableTransferTests(unittest.TestCase):
    def test_appends_only_at_actual_file_size(self):
        with tempfile.TemporaryDirectory(dir=os.path.dirname(os.path.dirname(__file__))) as directory:
            path = os.path.join(directory, "partial.bin")
            with open(path, "wb") as target:
                target.write(b"first")

            append_chunk(path, 5, b"-second")

            with open(path, "rb") as target:
                self.assertEqual(target.read(), b"first-second")
            self.assertEqual(get_resume_offset(path), 12)

    def test_rejects_stale_offset_without_changing_partial_file(self):
        with tempfile.TemporaryDirectory(dir=os.path.dirname(os.path.dirname(__file__))) as directory:
            path = os.path.join(directory, "partial.bin")
            with open(path, "wb") as target:
                target.write(b"existing")

            with self.assertRaisesRegex(ValueError, "عدم تطابق"):
                append_chunk(path, 3, b"wrong-offset")

            with open(path, "rb") as target:
                self.assertEqual(target.read(), b"existing")

    def test_fingerprint_accepts_same_prefix_and_rejects_changed_prefix(self):
        with tempfile.TemporaryDirectory(dir=os.path.dirname(os.path.dirname(__file__))) as directory:
            source = os.path.join(directory, "source.bin")
            matching = os.path.join(directory, "matching.bin")
            changed = os.path.join(directory, "changed.bin")
            sample = b"stable-prefix"
            with open(source, "wb") as target:
                target.write(sample + b"source-tail")
            with open(matching, "wb") as target:
                target.write(sample + b"different-tail")
            with open(changed, "wb") as target:
                target.write(b"changed-prefix")

            expected = hashlib.sha256(sample).hexdigest()
            self.assertEqual(compute_fingerprint(source, len(sample)), expected)
            self.assertTrue(verify_partial_matches_fingerprint(matching, expected, len(sample)))
            self.assertFalse(verify_partial_matches_fingerprint(changed, expected, len(sample)))

    def test_partial_smaller_than_fingerprint_sample_is_not_resumable(self):
        with tempfile.TemporaryDirectory(dir=os.path.dirname(os.path.dirname(__file__))) as directory:
            path = os.path.join(directory, "partial.bin")
            with open(path, "wb") as target:
                target.write(b"tiny")

            self.assertFalse(verify_partial_matches_fingerprint(path, "unused", sample_bytes=8))


if __name__ == "__main__":
    unittest.main()
