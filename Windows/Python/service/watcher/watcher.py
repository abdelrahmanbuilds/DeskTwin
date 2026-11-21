"""
DeskTwin File Watcher
----------------------
مراقبة Real-Time للتغييرات (بند 7 في التصميم): إنشاء / تعديل / حذف / نقل.
بيشتغل جنب الـ Scanner - مش بديل عنه:
- الـ Scanner: بيبني/يحدّث الصورة الكاملة (باتش، وقت التشغيل الأول، أو عند الطلب).
- الـ Watcher: بيمسك اللحظة اللي بيحصل فيها تغيير وهو شغال.
"""

import os
import sys
import time
import threading

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler

from config.settings import DeskTwinConfig, ScanTarget
from database.db import DeskTwinDB
from scanner.scanner import compute_hash, should_ignore_file, should_ignore_folder, _iso


class DeskTwinEventHandler(FileSystemEventHandler):
    """
    بيستقبل أحداث الـ filesystem الخام من watchdog ويحوّلها لتحديثات في DB.
    فيه debounce بسيط: لو ملف بيتعدل عدة مرات في وقت قصير جدًا (زي حفظ متكرر
    من محرر نصوص)، بنستنى شوية قبل ما نحسب الـ hash عشان منقراش ملف نص مكتوب.
    """

    DEBOUNCE_SECONDS = 0.7

    def __init__(self, db: DeskTwinDB, config: DeskTwinConfig, target: ScanTarget):
        self.db = db
        self.config = config
        self.target = target
        self._pending_paths = {}  # path -> (timer, event type, generation)
        self._pending_generation = 0
        self._lock = threading.Lock()

    def _should_ignore(self, path: str) -> bool:
        if should_ignore_file(os.path.basename(path), self.config.ignored_extensions):
            return True
        parts = path.split(os.sep)
        return any(should_ignore_folder(p, self.config.ignored_folders) for p in parts)

    def _schedule_process(self, path: str, event_type: str):
        """بيلغي أي timer قديم لنفس الملف ويحط واحد جديد - ده الـ debounce."""
        with self._lock:
            pending = self._pending_paths.get(path)
            if pending is not None:
                pending[0].cancel()
            # Watchdog often emits modified events while a newly copied file is
            # still being written. Preserve the create event through debounce
            # so the addition notification is not lost.
            effective_type = "created" if event_type == "created" or (pending and pending[1] == "created") else event_type
            self._pending_generation += 1
            generation = self._pending_generation
            timer = threading.Timer(
                self.DEBOUNCE_SECONDS,
                self._process_file,
                args=(path, effective_type, generation),
            )
            self._pending_paths[path] = (timer, effective_type, generation)
            timer.start()

    def _process_file(self, path: str, event_type: str, generation: int):
        with self._lock:
            pending = self._pending_paths.get(path)
            if pending is None or pending[2] != generation:
                return
            self._pending_paths.pop(path, None)
        self.db.mark_scan_dirty(self.target.normalized_path())

        if event_type == "deleted":
            existing = self.db.get_file_by_path(path)
            if existing:
                with self.db.cursor() as cur:
                    cur.execute(
                        "UPDATE files SET sync_state='DELETED', last_seen_at=? WHERE path=?",
                        (_iso(time.time()), path)
                    )
                self.db.log_change(path, "deleted", detected_by="watcher")
                try:
                    from notifications.notifications import notify_file_change
                    notify_file_change("deleted", path)
                except Exception as exc:
                    print(f"  [WATCHER][NOTIFICATION] تعذر إشعار حذف الملف: {exc}")
                print(f"  [WATCHER][DELETED] {path}")
            return

        # created / modified: لازم نقرأ الملف من القرص
        try:
            stat = os.stat(path)
        except (FileNotFoundError, PermissionError, OSError):
            return  # اتحذف فعليًا قبل ما نوصله، أو مش قادرين نقراه

        # أحداث watchdog قد تتكرر لنفس الملف. لو الـ metadata لم تتغير،
        # لا نعيد قراءة الملف كاملًا لحساب hash بلا داعٍ.
        existing = self.db.get_file_by_path(path)
        is_new_or_restored = existing is None or existing["sync_state"] == "DELETED"
        modified_at = _iso(stat.st_mtime)
        if (event_type != "created" and existing is not None and existing["sync_state"] != "DELETED"
                and existing["size_bytes"] == stat.st_size
                and existing["modified_at"] == modified_at):
            return

        try:
            file_hash = compute_hash(path, self.config.hash_size_limit_mb)
        except (FileNotFoundError, PermissionError, OSError) as exc:
            # الملف قد يكون ما زال قيد النسخ أو مقفولًا مؤقتًا؛ لا نسقط
            # Thread المراقبة كله، وننتظر حدثًا لاحقًا أو Scanner قادمًا.
            print(f"  [WATCHER][SKIP] تعذر قراءة الملف مؤقتًا: {path} ({exc})")
            return
        file_info = {
            "path": path,
            "name": os.path.basename(path),
            "extension": os.path.splitext(path)[1].lower(),
            "parent_folder": os.path.dirname(path),
            "size_bytes": stat.st_size,
            "created_at": _iso(stat.st_ctime),
            "modified_at": modified_at,
            "hash": file_hash,
            "project": self.target.label or None,
            "detected_by": "watcher",
        }
        result = self.db.upsert_file(file_info)
        if result != "unchanged":
            print(f"  [WATCHER][{result.upper()}] {path}")
        if is_new_or_restored or event_type == "created":
            try:
                from notifications.notifications import notify_file_change
                notify_file_change("created", path, size_bytes=stat.st_size, extension=file_info["extension"])
            except Exception as exc:
                print(f"  [WATCHER][NOTIFICATION] تعذر إشعار إضافة الملف: {exc}")

    # ---- watchdog hooks ----

    def on_created(self, event):
        if event.is_directory or self._should_ignore(event.src_path):
            return
        self._schedule_process(event.src_path, "created")

    def on_modified(self, event):
        if event.is_directory or self._should_ignore(event.src_path):
            return
        self._schedule_process(event.src_path, "modified")

    def on_deleted(self, event):
        if self._should_ignore(event.src_path):
            return
        if event.is_directory:
            prefix = event.src_path.rstrip(os.sep) + os.sep + "%"
            with self.db.cursor() as cur:
                cur.execute(
                    "UPDATE files SET sync_state='DELETED', last_seen_at=? "
                    "WHERE path LIKE ? AND sync_state != 'DELETED'",
                    (_iso(time.time()), prefix),
                )
            self.db.mark_scan_dirty(self.target.normalized_path())
            self.db.log_change(event.src_path, "deleted", detected_by="watcher")
            return
        self._schedule_process(event.src_path, "deleted")

    def on_moved(self, event):
        if event.is_directory:
            self.db.mark_scan_dirty(self.target.normalized_path())
            return
        # نعامله كحذف للمسار القديم + إنشاء للمسار الجديد
        if not self._should_ignore(event.src_path):
            self._schedule_process(event.src_path, "deleted")
        if not self._should_ignore(event.dest_path):
            self._schedule_process(event.dest_path, "created")
        self.db.log_change(event.dest_path, "moved", old_path=event.src_path, detected_by="watcher")


def start_watching(config: DeskTwinConfig, db: DeskTwinDB, verbose: bool = True):
    """
    بيبدأ Observer واحد لكل target (فولدر أو بارتشن).
    لو target ملف مفرد، بنراقب الفولدر اللي هو جواه بس بنفلتر أي حاجة غيره.
    بيرجع الـ observer عشان الـ caller يقدر يوقفه (.stop() / .join()).
    """
    observer = Observer()

    for target in config.targets:
        if not target.enabled:
            continue

        watch_path = target.normalized_path()
        if os.path.isfile(watch_path):
            watch_path = os.path.dirname(watch_path)

        if not os.path.isdir(watch_path):
            if verbose:
                print(f"[WATCHER][SKIP] المسار مش موجود: {watch_path}")
            continue

        handler = DeskTwinEventHandler(db, config, target)
        observer.schedule(handler, watch_path, recursive=target.recursive)
        if verbose:
            print(f"[WATCHER] بيراقب: {watch_path} (recursive={target.recursive})")

    observer.start()
    return observer


if __name__ == "__main__":
    cfg = DeskTwinConfig.load()
    if not cfg.targets:
        print("مفيش targets. عدّل config/settings.py الأول.")
        sys.exit(1)

    db = DeskTwinDB()
    obs = start_watching(cfg, db)
    print("Watcher شغال. اضغط Ctrl+C للإيقاف.")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        obs.stop()
        obs.join()
        print("\nWatcher وقف.")
