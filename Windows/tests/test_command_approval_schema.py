import os
import sqlite3
import tempfile
import unittest
from contextlib import closing

from Python.service.database.db import DeskTwinDB


class CommandApprovalSchemaTests(unittest.TestCase):
    def test_new_database_accepts_command_execution_approval(self):
        with tempfile.TemporaryDirectory(dir=os.path.dirname(__file__)) as folder:
            db = DeskTwinDB(os.path.join(folder, "desk-twin.db"))

            approval_id = db.create_approval_request(
                device_token="trusted-phone",
                action_type="command_execute",
                file_path="Get-Date",
                new_value="PowerShell",
            )

            approval = db.get_approval(approval_id)
            self.assertEqual(approval["action_type"], "command_execute")
            self.assertEqual(approval["status"], "pending")

    def test_existing_database_migrates_and_preserves_approvals(self):
        with tempfile.TemporaryDirectory(dir=os.path.dirname(__file__)) as folder:
            db_path = os.path.join(folder, "desk-twin.db")
            with closing(sqlite3.connect(db_path)) as conn, conn:
                conn.execute("""
                    CREATE TABLE pending_approvals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        device_token TEXT NOT NULL,
                        action_type TEXT NOT NULL CHECK (action_type IN (
                            'delete_file', 'rename_file', 'create_file',
                            'make_linked', 'overwrite_file', 'command_draft'
                        )),
                        file_id INTEGER,
                        file_path TEXT,
                        new_value TEXT,
                        status TEXT NOT NULL DEFAULT 'pending'
                            CHECK (status IN ('pending', 'approved', 'rejected', 'expired')),
                        requested_at TEXT NOT NULL,
                        resolved_at TEXT
                    )
                """)
                conn.execute("""
                    INSERT INTO pending_approvals
                        (device_token, action_type, file_path, requested_at)
                    VALUES ('trusted-phone', 'command_draft', 'Get-Date', '2026-10-18T00:00:00+00:00')
                """)

            db = DeskTwinDB(db_path)

            old_approval = db.get_approval(1)
            new_id = db.create_approval_request(
                device_token="trusted-phone",
                action_type="command_execute",
                file_path="Get-Date",
                new_value="PowerShell",
            )
            new_approval = db.get_approval(new_id)

            self.assertEqual(old_approval["action_type"], "command_draft")
            self.assertEqual(old_approval["status"], "pending")
            self.assertEqual(new_approval["action_type"], "command_execute")
            self.assertEqual(db.count_pending_approvals(), 2)


if __name__ == "__main__":
    unittest.main()
