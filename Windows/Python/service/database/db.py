"""
DeskTwin Database Layer
------------------------
طبقة رفيعة فوق SQLite: فتح الاتصال، تطبيق الـ schema، ووظائف أساسية
لإضافة/تحديث/الاستعلام عن الملفات وتسجيل التغييرات.
"""

import os
import sqlite3
import json
from datetime import datetime, timezone, timedelta
from contextlib import closing, contextmanager


PROJECT_ROOT = os.path.abspath(os.environ.get("DESKTWIN_ROOT") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
SCHEMA_PATH = os.path.join(os.path.dirname(__file__), "schema.sql")
DEFAULT_DB_PATH = os.path.join(PROJECT_ROOT, "database", "desktwin.db")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class DeskTwinDB:
    def __init__(self, db_path: str = DEFAULT_DB_PATH):
        self.db_path = db_path
        self.activity_log_limit = 300
        self._init_schema()

    def _connect(self):
        # Requests use short-lived connections. WAL allows readers while a
        # writer commits; busy_timeout absorbs brief writer collisions.
        conn = sqlite3.connect(self.db_path, timeout=5.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA busy_timeout = 5000")
        conn.execute("PRAGMA foreign_keys = ON")
        return conn

    def _init_schema(self):
        with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
            schema = f.read()
        # sqlite3's connection context manager commits/rolls back but does not
        # close the connection; close it explicitly after the schema transaction.
        with closing(self._connect()) as conn, conn:
            conn.executescript(schema)
            # الإصدارات القديمة كانت تضع CHECK ثابتًا لأنواع النشاط،
            # وهذا يمنع إضافة عمليات جديدة مثل النسخ والنقل والتنزيل.
            # نعيد إنشاء الجدول مرة واحدة بدون CHECK مع الاحتفاظ بالسجل.
            row = conn.execute(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='activity_log'"
            ).fetchone()
            table_sql = (row[0] if row else "") or ""
            if "CHECK" in table_sql.upper() and "activity_type" in table_sql:
                conn.execute("DROP INDEX IF EXISTS idx_activity_log_time")
                conn.execute("ALTER TABLE activity_log RENAME TO activity_log_legacy")
                conn.execute("""
                    CREATE TABLE activity_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        device_token TEXT,
                        activity_type TEXT NOT NULL,
                        detail TEXT,
                        created_at TEXT NOT NULL
                    )
                """)
                conn.execute("""
                    INSERT INTO activity_log(id, device_token, activity_type, detail, created_at)
                    SELECT id, device_token, activity_type, detail, created_at
                    FROM activity_log_legacy
                """)
                conn.execute("DROP TABLE activity_log_legacy")
                conn.execute("CREATE INDEX IF NOT EXISTS idx_activity_log_time ON activity_log(created_at)")

            # Older databases have a fixed CHECK list that predates the
            # computer command-draft approval flow. CREATE TABLE IF NOT EXISTS
            # cannot alter that constraint, so rebuild this small table while
            # preserving all pending/history rows.
            approval_row = conn.execute(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='pending_approvals'"
            ).fetchone()
            approval_sql = (approval_row[0] if approval_row else "") or ""
            if "command_execute" not in approval_sql:
                conn.execute("DROP INDEX IF EXISTS idx_pending_approvals_status")
                conn.execute("ALTER TABLE pending_approvals RENAME TO pending_approvals_legacy")
                conn.execute("""
                    CREATE TABLE pending_approvals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        device_token TEXT NOT NULL,
                        action_type TEXT NOT NULL CHECK (action_type IN (
                            'delete_file', 'rename_file', 'create_file',
                            'make_linked', 'overwrite_file', 'command_draft',
                            'command_execute'
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
                        (id, device_token, action_type, file_id, file_path,
                         new_value, status, requested_at, resolved_at)
                    SELECT id, device_token, action_type, file_id, file_path,
                           new_value, status, requested_at, resolved_at
                    FROM pending_approvals_legacy
                """)
                conn.execute("DROP TABLE pending_approvals_legacy")
                conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_pending_approvals_status "
                    "ON pending_approvals(status)"
                )
            columns = {row[1] for row in conn.execute("PRAGMA table_info(trusted_devices)").fetchall()}
            if "trust_level" not in columns:
                conn.execute("ALTER TABLE trusted_devices ADD COLUMN trust_level TEXT NOT NULL DEFAULT 'standard'")
            if "session_expires_at" not in columns:
                conn.execute("ALTER TABLE trusted_devices ADD COLUMN session_expires_at TEXT")
            if "auto_reconnect_count" not in columns:
                conn.execute("ALTER TABLE trusted_devices ADD COLUMN auto_reconnect_count INTEGER NOT NULL DEFAULT 0")
            if "last_auto_reconnect_at" not in columns:
                conn.execute("ALTER TABLE trusted_devices ADD COLUMN last_auto_reconnect_at TEXT")
            manifest_columns = {row[1] for row in conn.execute("PRAGMA table_info(scan_manifests)").fetchall()}
            if "root_mtime" not in manifest_columns:
                conn.execute("ALTER TABLE scan_manifests ADD COLUMN root_mtime REAL")

    @contextmanager
    def cursor(self):
        conn = self._connect()
        try:
            cur = conn.cursor()
            yield cur
            conn.commit()
        finally:
            conn.close()

    # ---------- Files ----------

    def get_file_by_path(self, path: str):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM files WHERE path = ?", (path,))
            return cur.fetchone()

    def is_scan_clean(self, target_path: str, root_mtime=None) -> bool:
        with self.cursor() as cur:
            cur.execute("SELECT dirty, root_mtime FROM scan_manifests WHERE target_path = ?", (target_path,))
            row = cur.fetchone()
        if not row or int(row["dirty"]) != 0:
            return False
        return root_mtime is None or row["root_mtime"] == root_mtime

    def mark_scan_clean(self, target_path: str, root_mtime=None):
        with self.cursor() as cur:
            cur.execute(
                "INSERT INTO scan_manifests(target_path, last_scan_at, dirty, root_mtime) VALUES (?, ?, 0, ?) "
                "ON CONFLICT(target_path) DO UPDATE SET last_scan_at=excluded.last_scan_at, dirty=0, root_mtime=excluded.root_mtime",
                (target_path, now_iso(), root_mtime),
            )

    def mark_scan_dirty(self, target_path: str):
        with self.cursor() as cur:
            cur.execute(
                "INSERT INTO scan_manifests(target_path, last_scan_at, dirty) VALUES (?, ?, 1) "
                "ON CONFLICT(target_path) DO UPDATE SET dirty=1",
                (target_path, now_iso()),
            )

    def upsert_file(self, file_info: dict):
        """
        يضيف ملف جديد أو يحدّث ملف موجود.
        file_info لازم يحتوي: path, name, extension, parent_folder,
        size_bytes, created_at, modified_at, hash, project (اختياري)
        """
        existing = self.get_file_by_path(file_info["path"])
        ts = now_iso()

        if existing is None:
            with self.cursor() as cur:
                cur.execute("""
                    INSERT INTO files (
                        path, name, extension, parent_folder, size_bytes,
                        created_at, modified_at, hash, version, project, tags,
                        sync_state, last_seen_at, first_indexed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'NEW', ?, ?)
                """, (
                    file_info["path"], file_info["name"], file_info.get("extension"),
                    file_info.get("parent_folder"), file_info.get("size_bytes", 0),
                    file_info.get("created_at"), file_info.get("modified_at"),
                    file_info.get("hash"), file_info.get("project"),
                    json.dumps(file_info.get("tags", []), ensure_ascii=False),
                    ts, ts
                ))
            self.log_change(file_info["path"], "created", new_hash=file_info.get("hash"), detected_by=file_info.get("detected_by", "scanner"))
            return "created"

        # الملف موجود: هل اتغيّر؟
        hash_changed = existing["hash"] != file_info.get("hash")
        mtime_changed = existing["modified_at"] != file_info.get("modified_at")

        if hash_changed or mtime_changed:
            with self.cursor() as cur:
                cur.execute("""
                    UPDATE files SET
                        size_bytes = ?, modified_at = ?, hash = ?,
                        version = version + 1, sync_state = 'MODIFIED',
                        last_seen_at = ?
                    WHERE path = ?
                """, (
                    file_info.get("size_bytes", 0), file_info.get("modified_at"),
                    file_info.get("hash"), ts, file_info["path"]
                ))
            self.log_change(
                file_info["path"], "modified",
                old_hash=existing["hash"], new_hash=file_info.get("hash"),
                detected_by=file_info.get("detected_by", "scanner")
            )
            return "modified"
        else:
            # مفيش تغيير حقيقي، بس نحدّث last_seen عشان نعرف إنه لسه موجود
            with self.cursor() as cur:
                cur.execute("UPDATE files SET last_seen_at = ? WHERE path = ?", (ts, file_info["path"]))
            return "unchanged"

    def mark_missing_files(self, target_prefix: str, seen_paths: set):
        """أي ملف تحت المسار ده كان معروف قبل كده ومظهرش في السكان الحالي = MISSING/DELETED."""
        with self.cursor() as cur:
            cur.execute(
                "SELECT path FROM files WHERE path LIKE ? AND sync_state != 'DELETED'",
                (target_prefix + "%",)
            )
            known_paths = {row["path"] for row in cur.fetchall()}

        missing = known_paths - seen_paths
        ts = now_iso()
        for path in missing:
            with self.cursor() as cur:
                cur.execute("UPDATE files SET sync_state = 'DELETED', last_seen_at = ? WHERE path = ?", (ts, path))
            self.log_change(path, "deleted", detected_by="scanner")
        return missing

    def log_change(self, file_path, event_type, old_path=None, old_hash=None, new_hash=None, detected_by="scanner"):
        with self.cursor() as cur:
            cur.execute("""
                INSERT INTO change_log (file_path, event_type, old_path, old_hash, new_hash, detected_by, detected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (file_path, event_type, old_path, old_hash, new_hash, detected_by, now_iso()))

    # ---------- Activity Log (بند: "التليفون داخل على مجلد إيه دلوقتي") ----------

    def log_activity(self, device_token: str, activity_type: str, detail: str = "", limit: int = None):
        with self.cursor() as cur:
            cur.execute("""
                INSERT INTO activity_log (device_token, activity_type, detail, created_at)
                VALUES (?, ?, ?, ?)
            """, (device_token, activity_type, detail, now_iso()))
            cur.execute(
                "DELETE FROM activity_log WHERE id NOT IN "
                "(SELECT id FROM activity_log ORDER BY id DESC LIMIT ?)",
                (max(50, min(int(limit or self.activity_log_limit), 10000)),),
            )

    def get_recent_activity(self, limit: int = 30, device_token: str = None, offset: int = 0):
        limit = max(1, min(int(limit), 1000))
        offset = max(0, min(int(offset), 10000))
        with self.cursor() as cur:
            if device_token:
                cur.execute(
                    "SELECT * FROM activity_log WHERE device_token = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    (device_token, limit, offset),
                )
            else:
                cur.execute(
                    "SELECT * FROM activity_log ORDER BY id DESC LIMIT ? OFFSET ?", (limit, offset)
                )
            return [dict(row) for row in cur.fetchall()]

    def clear_activity(self):
        with self.cursor() as cur:
            cur.execute("DELETE FROM activity_log")

    # ---------- Pending Approvals (عمليات حساسة محتاجة موافقة من الكمبيوتر) ----------

    def create_approval_request(self, device_token: str, action_type: str,
                                  file_id=None, file_path=None, new_value=None) -> int:
        with self.cursor() as cur:
            cur.execute("""
                INSERT INTO pending_approvals
                    (device_token, action_type, file_id, file_path, new_value, status, requested_at)
                VALUES (?, ?, ?, ?, ?, 'pending', ?)
            """, (device_token, action_type, file_id, file_path, new_value, now_iso()))
            return cur.lastrowid

    def get_approval(self, approval_id: int):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM pending_approvals WHERE id = ?", (approval_id,))
            return cur.fetchone()

    def list_pending_approvals(self):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM pending_approvals WHERE status = 'pending' ORDER BY id")
            return [dict(row) for row in cur.fetchall()]

    def count_pending_approvals(self) -> int:
        """عدد سريع بدون جلب كل الصفوف - يُستخدم في الـ badge بالـ Sidebar."""
        with self.cursor() as cur:
            cur.execute("SELECT COUNT(*) as c FROM pending_approvals WHERE status = 'pending'")
            return cur.fetchone()["c"]

    def expire_stale_approvals(self, older_than_days: int) -> int:
        """
        أي طلب موافقة فضل 'pending' أكتر من المدة المحددة يتحول لـ 'expired'
        تلقائيًا - بدون ده، طلب نسيه المستخدم يفضل يزاحم شاشة الموافقات
        للأبد. بترجع عدد الطلبات اللي انتهت صلاحيتها فعليًا (للّوج).
        """
        cutoff = (datetime.now(timezone.utc) - timedelta(days=older_than_days)).isoformat()
        with self.cursor() as cur:
            cur.execute(
                "UPDATE pending_approvals SET status = 'expired', resolved_at = ? "
                "WHERE status = 'pending' AND requested_at < ?",
                (now_iso(), cutoff)
            )
            return cur.rowcount

    def resolve_approval(self, approval_id: int, approved: bool):
        status = "approved" if approved else "rejected"
        with self.cursor() as cur:
            cur.execute(
                "UPDATE pending_approvals SET status = ?, resolved_at = ? WHERE id = ?",
                (status, now_iso(), approval_id)
            )

    # ---------- Single-User Binding ----------

    def get_bound_user(self):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM bound_user WHERE id = 1")
            row = cur.fetchone()
        return dict(row) if row else None

    def bind_user(self, user_name: str):
        with self.cursor() as cur:
            cur.execute("""
                INSERT INTO bound_user (id, user_name, bound_at) VALUES (1, ?, ?)
                ON CONFLICT(id) DO UPDATE SET user_name = excluded.user_name, bound_at = excluded.bound_at
            """, (user_name, now_iso()))

    def delete_file_record(self, file_id: int):
        """بعد نجاح حذف الملف فعليًا من القرص، نحدّث حالته في الفهرس."""
        with self.cursor() as cur:
            cur.execute(
                "UPDATE files SET sync_state = 'DELETED', last_seen_at = ? WHERE id = ?",
                (now_iso(), file_id)
            )
        self.log_change_by_id(file_id, "deleted", detected_by="approval")

    def rename_file_record(self, file_id: int, new_path: str, new_name: str):
        """بعد نجاح تسمية الملف فعليًا على القرص، نحدّث مساره واسمه في الفهرس."""
        with self.cursor() as cur:
            cur.execute(
                "UPDATE files SET path = ?, name = ?, last_seen_at = ? WHERE id = ?",
                (new_path, new_name, now_iso(), file_id)
            )

    def log_change_by_id(self, file_id: int, event_type: str, detected_by="approval"):
        with self.cursor() as cur:
            cur.execute("SELECT path FROM files WHERE id = ?", (file_id,))
            row = cur.fetchone()
        if row:
            self.log_change(row["path"], event_type, detected_by=detected_by)

    def search_files(self, query: str = "", project: str = None, sync_state: str = None, limit: int = 100):
        sql = "SELECT * FROM files WHERE 1=1"
        params = []
        if query:
            sql += " AND (name LIKE ? OR path LIKE ?)"
            params += [f"%{query}%", f"%{query}%"]
        if project:
            sql += " AND project = ?"
            params.append(project)
        if sync_state:
            sql += " AND sync_state = ?"
            params.append(sync_state)
        sql += " ORDER BY modified_at DESC LIMIT ?"
        params.append(limit)

        with self.cursor() as cur:
            cur.execute(sql, params)
            return [dict(row) for row in cur.fetchall()]

    # ---------- Scan runs ----------

    def start_scan_run(self, target_path: str) -> int:
        with self.cursor() as cur:
            cur.execute(
                "INSERT INTO scan_runs (target_path, started_at, status) VALUES (?, ?, 'running')",
                (target_path, now_iso())
            )
            return cur.lastrowid

    def finish_scan_run(self, run_id: int, found=0, new=0, modified=0, deleted=0, status="completed"):
        with self.cursor() as cur:
            cur.execute("""
                UPDATE scan_runs SET finished_at=?, files_found=?, files_new=?,
                files_modified=?, files_deleted=?, status=?
                WHERE id=?
            """, (now_iso(), found, new, modified, deleted, status, run_id))

    # ---------- Version History ----------
    # جدول file_versions موجود في الـ schema من البداية، لكن مفيش أي كود
    # كان بيملأه أو يقرأ منه فعليًا - القسم ده هو أول تطبيق حقيقي له.

    def add_file_version(self, file_path: str, stored_copy_path: str, hash_value: str,
                          size_bytes: int, source_device: str, reason: str) -> int:
        """
        بيسجل نسخة قديمة من ملف (بعد نسخها فعليًا لمكان التخزين الاحتياطي).
        version_number بيتحسب تلقائيًا كـ "آخر رقم لنفس الملف + 1".
        """
        with self.cursor() as cur:
            cur.execute(
                "SELECT COALESCE(MAX(version_number), 0) as maxv FROM file_versions WHERE file_path = ?",
                (file_path,)
            )
            next_version = cur.fetchone()["maxv"] + 1

            cur.execute("""
                INSERT INTO file_versions
                    (file_path, version_number, stored_copy_path, hash, size_bytes,
                     source_device, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (file_path, next_version, stored_copy_path, hash_value, size_bytes,
                  source_device, reason, now_iso()))
            return cur.lastrowid

    def list_file_versions(self, file_path: str):
        """كل النسخ القديمة المحفوظة لملف معين، الأحدث أولًا."""
        with self.cursor() as cur:
            cur.execute(
                "SELECT * FROM file_versions WHERE file_path = ? ORDER BY version_number DESC",
                (file_path,)
            )
            return [dict(row) for row in cur.fetchall()]

    def get_file_version(self, version_id: int):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM file_versions WHERE id = ?", (version_id,))
            row = cur.fetchone()
        return dict(row) if row else None

    def delete_file_version(self, version_id: int) -> bool:
        """
        بيمسح صف النسخة من الجدول فقط - مسح الملف الفعلي من على القرص
        مسؤولية الكود اللي بينادي الدالة دي (الـ endpoint)، مش الـ DB layer.
        """
        with self.cursor() as cur:
            cur.execute("DELETE FROM file_versions WHERE id = ?", (version_id,))
            return cur.rowcount > 0

    def count_versions_for_file(self, file_path: str) -> int:
        with self.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) as c FROM file_versions WHERE file_path = ?",
                (file_path,)
            )
            return cur.fetchone()["c"]

    def get_version_retention_settings(self):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM version_retention_settings WHERE id = 1")
            row = cur.fetchone()
        return dict(row) if row else None

    def update_version_retention_settings(self, max_versions_per_file=None,
                                             auto_delete_after_days=None, cleanup_mode=None):
        current = self.get_version_retention_settings() or {
            "max_versions_per_file": "5", "auto_delete_after_days": None, "cleanup_mode": "manual"
        }
        new_max = max_versions_per_file if max_versions_per_file is not None else current["max_versions_per_file"]
        new_days = auto_delete_after_days if auto_delete_after_days is not None else current["auto_delete_after_days"]
        new_mode = cleanup_mode if cleanup_mode is not None else current["cleanup_mode"]
        with self.cursor() as cur:
            cur.execute("""
                UPDATE version_retention_settings
                SET max_versions_per_file = ?, auto_delete_after_days = ?, cleanup_mode = ?
                WHERE id = 1
            """, (new_max, new_days, new_mode))

    # ---------- Conflicts ----------
    # جدول conflicts موجود في الـ schema من البداية بردو، بدون أي كود
    # يملأه أو يقرأ منه - نفس وضع file_versions تمامًا.

    def create_conflict(self, file_path: str, pc_hash: str, pc_modified_at: str,
                         phone_hash: str, phone_modified_at: str,
                         pc_backup_version_id=None, phone_backup_version_id=None) -> int:
        with self.cursor() as cur:
            cur.execute("""
                INSERT INTO conflicts
                    (file_path, pc_hash, pc_modified_at, pc_backup_version_id,
                     phone_hash, phone_modified_at, phone_backup_version_id,
                     detected_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending')
            """, (file_path, pc_hash, pc_modified_at, pc_backup_version_id,
                  phone_hash, phone_modified_at, phone_backup_version_id, now_iso()))
            conflict_id = cur.lastrowid

        # الملف يفضل في حالة CONFLICT لحد ما المستخدم يحل - القاعدة
        # الرسمية في docs/architecture/SYNC_SPEC.md بند 2.2: "مفيش أي جهة تكسب تلقائيًا"
        with self.cursor() as cur:
            cur.execute("UPDATE files SET sync_state = 'CONFLICT' WHERE path = ?", (file_path,))

        return conflict_id

    def list_conflicts(self, status: str = "pending"):
        with self.cursor() as cur:
            if status:
                cur.execute("SELECT * FROM conflicts WHERE status = ? ORDER BY detected_at DESC", (status,))
            else:
                cur.execute("SELECT * FROM conflicts ORDER BY detected_at DESC")
            return [dict(row) for row in cur.fetchall()]

    def get_conflict(self, conflict_id: int):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM conflicts WHERE id = ?", (conflict_id,))
            row = cur.fetchone()
        return dict(row) if row else None

    def resolve_conflict(self, conflict_id: int, resolution: str):
        """
        resolution: 'resolved_keep_pc' | 'resolved_keep_phone' | 'resolved_manual'
        بعد الحل، الملف يرجع لحالة SYNCED - لكن **تطبيق القرار فعليًا على
        القرص (استبدال المحتوى) مسؤولية الكود اللي بينادي الدالة دي**،
        مش الـ DB layer (نفس مبدأ delete_file_version بالظبط).
        """
        valid = {"resolved_keep_pc", "resolved_keep_phone", "resolved_manual"}
        if resolution not in valid:
            raise ValueError(f"resolution غير صالحة: {resolution}")

        conflict = self.get_conflict(conflict_id)
        if conflict is None:
            return False

        with self.cursor() as cur:
            cur.execute(
                "UPDATE conflicts SET status = ?, resolved_at = ? WHERE id = ?",
                (resolution, now_iso(), conflict_id)
            )
            cur.execute(
                "UPDATE files SET sync_state = 'SYNCED' WHERE path = ?",
                (conflict["file_path"],)
            )
        return True

    # ---------- Resumable Transfers (نقل الملفات الكبيرة على أجزاء) ----------

    def create_resumable_transfer(self, file_path: str, direction: str, total_size_bytes: int,
                                    source_fingerprint: str, device_token: str, retention_hours: int = 24):
        with self.cursor() as cur:
            cur.execute("""
                INSERT INTO resumable_transfers
                    (file_path, direction, total_size_bytes, bytes_received, source_fingerprint,
                     device_token, status, created_at, last_progress_at, retention_hours)
                VALUES (?, ?, ?, 0, ?, ?, 'in_progress', ?, ?, ?)
            """, (file_path, direction, total_size_bytes, source_fingerprint, device_token,
                  now_iso(), now_iso(), retention_hours))
            return cur.lastrowid

    def get_resumable_transfer(self, file_path: str):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM resumable_transfers WHERE file_path = ?", (file_path,))
            row = cur.fetchone()
        return dict(row) if row else None

    def update_resumable_progress(self, file_path: str, bytes_received: int):
        with self.cursor() as cur:
            cur.execute("""
                UPDATE resumable_transfers
                SET bytes_received = ?, last_progress_at = ?, status = 'in_progress'
                WHERE file_path = ?
            """, (bytes_received, now_iso(), file_path))

    def mark_resumable_completed(self, file_path: str):
        with self.cursor() as cur:
            cur.execute(
                "UPDATE resumable_transfers SET status = 'completed' WHERE file_path = ?",
                (file_path,)
            )

    def delete_resumable_transfer(self, file_path: str):
        with self.cursor() as cur:
            cur.execute("DELETE FROM resumable_transfers WHERE file_path = ?", (file_path,))

    def list_incomplete_transfers(self):
        """كل الملفات الناقصة اللي لسه محتاجة تكمل - للتحذير في الـ GUI عند فتح البرنامج."""
        with self.cursor() as cur:
            cur.execute(
                "SELECT * FROM resumable_transfers WHERE status IN ('in_progress', 'stalled') "
                "ORDER BY last_progress_at DESC"
            )
            return [dict(row) for row in cur.fetchall()]

    def list_expired_transfers(self):
        """
        الملفات الناقصة اللي عدى وقت الاحتفاظ بتاعها (retention_hours) من غير
        أي تقدّم جديد - دول اللي المفروض يتمسحوا نهائيًا (بند: المستخدم حدد
        مدة من ساعة لأسبوع، وبعدها الملف الناقص يتمسح تلقائيًا).
        """
        with self.cursor() as cur:
            cur.execute("""
                SELECT * FROM resumable_transfers
                WHERE status IN ('in_progress', 'stalled')
                AND datetime(last_progress_at) <= datetime('now', '-' || retention_hours || ' hours')
            """)
            return [dict(row) for row in cur.fetchall()]

    # ---------- Offline Command Batches (طابور الأوامر المؤجلة) ----------

    def create_command_batch(self, device_token: str, commands: list[dict]) -> int:
        """
        بيسجل الدفعة كلها مرة واحدة - batch + كل الأوامر بترتيبها. الترتيب
        بيتحدد بـ enumerate() على القايمة زي ما وصلت من التليفون (المفروض
        تكون أصلاً مرتبة زمنيًا من ساعة ما اتعملت وهو أوفلاين).
        """
        with self.cursor() as cur:
            cur.execute("""
                INSERT INTO offline_command_batches (device_token, status, created_at)
                VALUES (?, 'processing', ?)
            """, (device_token, now_iso()))
            batch_id = cur.lastrowid

            for index, command in enumerate(commands):
                cur.execute("""
                    INSERT INTO offline_commands
                        (batch_id, sequence_index, command_type, file_path, new_value, status)
                    VALUES (?, ?, ?, ?, ?, 'pending')
                """, (batch_id, index, command["command_type"], command["file_path"], command.get("new_value")))

        return batch_id

    def get_batch(self, batch_id: int):
        with self.cursor() as cur:
            cur.execute("SELECT * FROM offline_command_batches WHERE id = ?", (batch_id,))
            return cur.fetchone()

    def list_batch_commands(self, batch_id: int):
        with self.cursor() as cur:
            cur.execute(
                "SELECT * FROM offline_commands WHERE batch_id = ? ORDER BY sequence_index",
                (batch_id,)
            )
            return [dict(row) for row in cur.fetchall()]

    def mark_command_executed(self, command_id: int):
        with self.cursor() as cur:
            cur.execute(
                "UPDATE offline_commands SET status = 'executed', executed_at = ? WHERE id = ?",
                (now_iso(), command_id)
            )

    def mark_command_conflict(self, command_id: int, reason: str):
        with self.cursor() as cur:
            cur.execute(
                "UPDATE offline_commands SET status = 'conflict', conflict_reason = ? WHERE id = ?",
                (reason, command_id)
            )

    def mark_command_skipped(self, command_id: int):
        with self.cursor() as cur:
            cur.execute("UPDATE offline_commands SET status = 'skipped' WHERE id = ?", (command_id,))

    def set_batch_status(self, batch_id: int, status: str):
        with self.cursor() as cur:
            completed_at = now_iso() if status in ("completed", "stopped_on_conflict", "cancelled") else None
            cur.execute(
                "UPDATE offline_command_batches SET status = ?, completed_at = ? WHERE id = ?",
                (status, completed_at, batch_id)
            )
