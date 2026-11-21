"""
DeskTwin Special Folders
--------------------------
بيكتشف اختصارات ويندوز القياسية (Desktop, Downloads, Documents, Pictures,
Videos, Music) وكل الـ Drives/Partitions المتاحة، عشان الموبايل يعرض
نفس شكل File Explorer بالظبط - نفس الاختصارات، نفس شجرة C:/Users/...
"""

import os
import sys
import string

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def get_special_folders() -> dict:
    """
    بيرجع dict فيه أسماء الاختصارات القياسية ومساراتها الفعلية.
    شغالة على Windows بشكل أساسي؛ على أنظمة تانية بترجع أقرب مكافئ.
    """
    home = os.path.expanduser("~")

    folders = {
        "Desktop": os.path.join(home, "Desktop"),
        "Downloads": os.path.join(home, "Downloads"),
        "Documents": os.path.join(home, "Documents"),
        "Pictures": os.path.join(home, "Pictures"),
        "Videos": os.path.join(home, "Videos"),
        "Music": os.path.join(home, "Music"),
    }

    # نرجّع بس اللي فعلاً موجود على الجهاز (بعض الأنظمة معندهاش كل المجلدات دي)
    return {name: path for name, path in folders.items() if os.path.isdir(path)}


def get_available_drives() -> list[dict]:
    """
    بيرجع كل الـ Drives/Partitions المتاحة (C:, D:, ...) على ويندوز.
    على أنظمة تانية (Linux/Mac) بيرجع الـ root بس كمكافئ.
    """
    drives = []

    if sys.platform == "win32":
        import ctypes
        bitmask = ctypes.windll.kernel32.GetLogicalDrives()
        for letter in string.ascii_uppercase:
            if bitmask & 1:
                drive_path = letter + ":" + os.sep
                if os.path.exists(drive_path):
                    drives.append({"name": "Local Disk (" + letter + ":)", "path": drive_path})
            bitmask >>= 1
    else:
        drives.append({"name": "Root", "path": "/"})

    return drives


def get_home_path() -> str:
    """المسار الرئيسي للمستخدم - نقطة البداية المنطقية لأي تصفح (زي C:\\Users\\username)."""
    return os.path.expanduser("~")


if __name__ == "__main__":
    print("=== Special Folders ===")
    for name, path in get_special_folders().items():
        print(f"  {name}: {path}")

    print("\n=== Drives ===")
    for drive in get_available_drives():
        print(f"  {drive['name']}: {drive['path']}")

    print("\n=== Home ===")
    print(f"  {get_home_path()}")
