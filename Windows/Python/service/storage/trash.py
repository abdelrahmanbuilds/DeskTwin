"""سلة DeskTwin القابلة للاستعادة بدل الحذف المباشر."""
import json
import os
import shutil
import time
import uuid
import subprocess

PROJECT_ROOT = os.path.abspath(os.environ.get("DESKTWIN_ROOT") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(__file__)))))
TRASH_DIR = os.path.join(PROJECT_ROOT, "data", "trash")


def move_to_trash(path: str) -> str:
    if not os.path.exists(path):
        raise FileNotFoundError(path)
    os.makedirs(TRASH_DIR, exist_ok=True)
    item_id = f"{int(time.time())}_{uuid.uuid4().hex[:8]}"
    # Windows Shell هو المسؤول عن النقل إلى Recycle Bin الحقيقية.
    # نستخدم Microsoft.VisualBasic FileIO لأنها تدعم الملفات والمجلدات
    # وتترك الاستعادة تحت إدارة Windows نفسها.
    env = os.environ.copy()
    env["DT_PATH"] = os.path.abspath(path)
    script = """
$p=$env:DT_PATH
Add-Type -AssemblyName Microsoft.VisualBasic
if ([IO.Directory]::Exists($p)) {
  [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($p,'OnlyErrorDialogs','SendToRecycleBin')
} else {
  [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($p,'OnlyErrorDialogs','SendToRecycleBin')
}
"""
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                            env=env, capture_output=True, text=True,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode != 0:
        raise OSError(result.stderr.strip() or "تعذر نقل الملف إلى Recycle Bin")
    stored = os.path.abspath(path)
    with open(os.path.join(TRASH_DIR, item_id + ".json"), "w", encoding="utf-8") as handle:
        json.dump({"item_id": item_id, "original_path": os.path.abspath(path),
                   "stored_path": stored, "deleted_at": time.time()}, handle)
    return stored


def restore_from_recycle_bin(original_path: str) -> bool:
    """يحاول استعادة عنصر Windows المطابق لمساره الأصلي."""
    env = os.environ.copy()
    env["DT_ORIGINAL"] = os.path.abspath(original_path)
    script = """
$target=$env:DT_ORIGINAL
$bin=(New-Object -ComObject Shell.Application).Namespace(10)
$item=$bin.Items() | Where-Object { $_.ExtendedProperty('System.Recycle.DeletedFrom') -eq $target }
if ($null -eq $item) { exit 2 }
$item.InvokeVerb('Restore')
"""
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                            env=env, capture_output=True,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    return result.returncode == 0


def permanently_delete_from_recycle_bin(original_path: str) -> bool:
    """حذف نهائي بعد تأكيد صريح من شاشة سلة المحذوفات في الهاتف."""
    env = os.environ.copy()
    env["DT_ORIGINAL"] = os.path.abspath(original_path)
    script = """
$target=$env:DT_ORIGINAL
$bin=(New-Object -ComObject Shell.Application).Namespace(10)
$item=$bin.Items() | Where-Object { $_.ExtendedProperty('System.Recycle.DeletedFrom') -eq $target }
if ($null -eq $item) { exit 2 }
$item.InvokeVerb('delete')
"""
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script], env=env,
                            capture_output=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode != 0:
        return False
    for name in os.listdir(TRASH_DIR) if os.path.isdir(TRASH_DIR) else []:
        if not name.endswith(".json"):
            continue
        record = os.path.join(TRASH_DIR, name)
        try:
            with open(record, encoding="utf-8") as handle:
                if json.load(handle).get("original_path") == os.path.abspath(original_path):
                    os.remove(record)
        except (OSError, ValueError):
            continue
    return True
