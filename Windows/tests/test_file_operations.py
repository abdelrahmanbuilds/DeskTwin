import os
import tempfile
import unittest

from Python.service.storage.file_operations import copy_without_overwrite


class SafeCopyTests(unittest.TestCase):
    def test_same_folder_copy_creates_numbered_siblings_without_changing_source(self):
        with tempfile.TemporaryDirectory() as folder:
            source = os.path.join(folder, "report.txt")
            with open(source, "wb") as stream:
                stream.write(b"original")

            first = copy_without_overwrite(source, source, duplicate_on_conflict=True)
            second = copy_without_overwrite(source, source, duplicate_on_conflict=True)

            self.assertEqual(os.path.basename(first), "report (copy).txt")
            self.assertEqual(os.path.basename(second), "report (copy 2).txt")
            for path in (source, first, second):
                with open(path, "rb") as stream:
                    self.assertEqual(stream.read(), b"original")

    def test_reject_mode_never_overwrites_existing_file(self):
        with tempfile.TemporaryDirectory() as folder:
            source = os.path.join(folder, "source.txt")
            destination = os.path.join(folder, "destination.txt")
            with open(source, "wb") as stream:
                stream.write(b"new")
            with open(destination, "wb") as stream:
                stream.write(b"keep")

            with self.assertRaises(FileExistsError):
                copy_without_overwrite(source, destination, duplicate_on_conflict=False)

            with open(destination, "rb") as stream:
                self.assertEqual(stream.read(), b"keep")

    def test_duplicate_mode_copies_directory_to_a_new_sibling(self):
        with tempfile.TemporaryDirectory() as folder:
            source = os.path.join(folder, "folder")
            os.mkdir(source)
            with open(os.path.join(source, "item.txt"), "w", encoding="utf-8") as stream:
                stream.write("content")

            actual = copy_without_overwrite(source, source, duplicate_on_conflict=True)

            self.assertEqual(os.path.basename(actual), "folder (copy)")
            with open(os.path.join(actual, "item.txt"), encoding="utf-8") as stream:
                self.assertEqual(stream.read(), "content")


if __name__ == "__main__":
    unittest.main()
