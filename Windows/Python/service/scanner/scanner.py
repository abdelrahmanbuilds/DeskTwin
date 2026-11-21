"""
DeskTwin File Scanner
----------------------
بيفحص كل target معرّف في الـ config (ملف / فولدر / بارتشن)
ويبني/يحدّث خريطة الـ metadata في قاعدة البيانات.

الملف نفسه بيفضل مكانه - إحنا بس بنقرأ عنه.
"""

import os
import hashlib
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from datetime import datetime, timezone
from config.settings import DeskTwinConfig, ScanTarget
from database.db import DeskTwinDB


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()


def compute_hash(path: str, size_limit_mb: int, chunk_size: int = 65536) -> str | None:
    """SHA-256، إلا لو الملف أكبر من الحد المسموح (تكلفة عالية على ملفات ضخمة)."""
    try:
        size_mb = os.path.getsize(path) / (1024 * 1024)
        if size_limit_mb and size_mb > size_limit_mb:
            return None
        h = hashlib.sha256()
        with open(path, "rb") as f:
            while chunk := f.read(chunk_size):
                h.update(chunk)
        return h.hexdigest()
    except (PermissionError, FileNotFoundError, OSError):
        return None


def should_ignore_folder(folder_name: str, ignored_folders: list[str]) -> bool:
    return folder_name in ignored_folders


def should_ignore_file(filename: str, ignored_extensions: list[str]) -> bool:
    ext = os.path.splitext(filename)[1].lower()
    return ext in ignored_extensions


def iter_target_files(target: ScanTarget, ignored_folders: list[str], ignored_extensions: list[str]):
    """يرجع كل الملفات تحت الـ target - سواء ملف واحد أو فولدر (recursive أو لأ)."""
    path = target.normalized_path()

    if os.path.isfile(path):
        if not should_ignore_file(os.path.basename(path), ignored_extensions):
            yield path
        return

    if not os.path.isdir(path):
        return  # المسار مش موجود - نتجاهله بهدوء، الـ caller هيسجل تحذير

    if target.recursive:
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if not should_ignore_folder(d, ignored_folders)]
            for fname in files:
                if not should_ignore_file(fname, ignored_extensions):
                    yield os.path.join(root, fname)
    else:
        for entry in os.scandir(path):
            if entry.is_file() and not should_ignore_file(entry.name, ignored_extensions):
                yield entry.path


def scan_target(db: DeskTwinDB, target: ScanTarget, config: DeskTwinConfig, verbose: bool = True, on_progress=None) -> dict:
    """
    يفحص target واحد ويحدّث الـ DB. يرجع ملخص النتيجة.
    on_progress (اختياري): callback بياخد (target_label, files_scanned_so_far)
    وبيتنادى مع كل ملف - عشان الواجهة تقدر تعرض تقدّم حي بدل ما تتجمد
    من غير أي إشارة للمستخدم أثناء فحص مجلدات كبيرة.
    """
    root_path = target.normalized_path()

    if not os.path.exists(root_path):
        if verbose:
            print(f"[SKIP] المسار مش موجود: {root_path}")
        return {"error": "path_not_found", "target": root_path}

    try:
        root_mtime = os.stat(root_path).st_mtime if os.path.isdir(root_path) else os.stat(root_path).st_mtime
    except (PermissionError, FileNotFoundError, OSError):
        root_mtime = None

    # لو الـ Watcher لم يسجل أي تغيير منذ آخر دورة ناجحة، فقاعدة البيانات
    # بالفعل تحتوي على آخر صورة للـ target ونوفر المرور الكامل على الشجرة.
    if db.is_scan_clean(root_path, root_mtime):
        if verbose:
            print(f"[SKIP] لا تغييرات مسجلة: {root_path}")
        return {"found": 0, "new": 0, "modified": 0, "unchanged": 0,
                "deleted": 0, "skipped": True}

    run_id = db.start_scan_run(root_path)
    seen_paths = set()
    counts = {"found": 0, "new": 0, "modified": 0, "unchanged": 0}

    for file_path in iter_target_files(target, config.ignored_folders, config.ignored_extensions):
        try:
            stat = os.stat(file_path)
        except (PermissionError, FileNotFoundError, OSError):
            continue

        seen_paths.add(file_path)
        counts["found"] += 1

        # أغلب ملفات الفحص لم تتغير منذ آخر دورة. نستخدم size + mtime كـ
        # fingerprint سريع، ونحسب SHA-256 فقط عند وجود تغيير فعلي أو ملف جديد.
        existing = db.get_file_by_path(file_path)
        unchanged_metadata = (
            existing is not None
            and existing["size_bytes"] == stat.st_size
            and existing["modified_at"] == _iso(stat.st_mtime)
        )
        file_hash = existing["hash"] if unchanged_metadata else compute_hash(
            file_path, config.hash_size_limit_mb
        )

        file_info = {
            "path": file_path,
            "name": os.path.basename(file_path),
            "extension": os.path.splitext(file_path)[1].lower(),
            "parent_folder": os.path.dirname(file_path),
            "size_bytes": stat.st_size,
            "created_at": _iso(stat.st_ctime),
            "modified_at": _iso(stat.st_mtime),
            "hash": file_hash,
            "project": target.label or None,
            "detected_by": "scanner",
        }

        result = db.upsert_file(file_info)
        result_key = "new" if result == "created" else result
        if result_key in counts:
            counts[result_key] += 1

        if verbose and result != "unchanged":
            print(f"  [{result.upper()}] {file_path}")

        if on_progress:
            on_progress(target.label or root_path, counts["found"])

    deleted = db.mark_missing_files(root_path, seen_paths)
    counts["deleted"] = len(deleted)

    db.finish_scan_run(
        run_id,
        found=counts["found"], new=counts["new"],
        modified=counts["modified"], deleted=counts["deleted"],
        status="completed"
    )
    try:
        root_mtime = os.stat(root_path).st_mtime
    except (PermissionError, FileNotFoundError, OSError):
        root_mtime = None
    db.mark_scan_clean(root_path, root_mtime)

    if verbose:
        print(f"\n[DONE] {target.label or root_path}: "
              f"{counts['found']} ملف | {counts['new']} جديد | "
              f"{counts['modified']} معدّل | {counts['deleted']} محذوف")

    return counts


def scan_all(config: DeskTwinConfig, db: DeskTwinDB, verbose: bool = True, on_progress=None):
    """يفحص كل الـ targets المفعّلة في الكونفج. on_progress بينتقل لكل target (راجع scan_target)."""
    results = {}
    for target in config.targets:
        if not target.enabled:
            continue
        if verbose:
            print(f"\n=== Scanning: {target.label or target.path} ===")
        results[target.path] = scan_target(db, target, config, verbose=verbose, on_progress=on_progress)
    return results


if __name__ == "__main__":
    cfg = DeskTwinConfig.load()
    if not cfg.targets:
        print("مفيش targets متحددة في الكونفج. عدّل config/settings.py أو config.json وضيف مسار.")
        sys.exit(1)

    db = DeskTwinDB()
    scan_all(cfg, db)
