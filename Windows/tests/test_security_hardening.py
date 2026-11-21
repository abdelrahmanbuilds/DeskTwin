import os
import tempfile
import unittest

from Python.service.config.settings import DeskTwinConfig


class PathScopeHardeningTests(unittest.TestCase):
    def test_drive_root_target_allows_paths_on_that_drive(self):
        drive_root = os.path.splitdrive(os.path.abspath(os.getcwd()))[0] + os.sep
        config = DeskTwinConfig()
        config.add_target(drive_root)

        self.assertTrue(config.is_path_allowed(os.path.join(drive_root, "DeskTwin", "report.txt")))

    def test_drive_root_target_allows_its_children_without_matching_siblings(self):
        with tempfile.TemporaryDirectory() as folder:
            root = os.path.abspath(folder)
            child = os.path.join(root, "nested", "report.txt")
            sibling = root + "-other" + os.sep + "report.txt"
            config = DeskTwinConfig()
            config.add_target(root)

            self.assertTrue(config.is_path_allowed(child))
            self.assertFalse(config.is_path_allowed(sibling))


if __name__ == "__main__":
    unittest.main()
