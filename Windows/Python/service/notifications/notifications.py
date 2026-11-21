"""
DeskTwin Notifications (Windows)
---------------------------------
إشعار Toast حقيقي (زي واتساب أو ويندوز سكيوريتي) عند إضافة أو حذف ملف
- قابل للضغط وبيفتح المجلد الذي يحتوي على المسار المتأثر
وبيفضل موجود في تاريخ الإشعارات (Action Center) مش بيختفي فورًا.

رحلة اختيار المكتبة (مهم لو حد فتح الملف ده تاني بعدين):
    1. win10toast: فشلت بصمت تام - بتعتمد على win32gui.PumpMessages()
       اللي محتاج message loop على نفس الـ thread اللي نادى عليه، والـ
       API عندنا شغال جوه Thread فرعي (راجع Python/core/desktwin_service.py)، مش
       الـ main thread.
    2. win11toast: بديل بيستخدم winsdk، لكن محتاجة Visual Studio C++
       build tools عشان تتبني من المصدر لو مفيش wheel جاهزة للبيئة -
       فشلت عندنا.
    3. plyer: اشتغلت وعرضت الإشعار، لكن إشعار "fire and forget" بسيط -
       مفيهوش click handler ولا بيفضل في تاريخ الإشعارات (Action Center) -
       نفس محدودية win10toast من ناحية القدرات، مش من ناحية التشغيل.
    4. windows-toasts (الاختيار النهائي): بتستخدم نفس الـ Windows Runtime
       toast API الحقيقي زي win11toast، لكن الاعتماديات بتاعتها (حزم
       winrt-*) ليها wheels جاهزة مبنية مسبقًا لكل إصدار بايثون + ويندوز
       على PyPI - فمحتاجش Visual Studio ولا أي بناء محلي. وبتدعم:
       - launch: مسار المجلد الذي يحتوي على الملف ليفتحه Windows Explorer
       - الإشعار بيفضل في Action Center لحد ما المستخدم يقفله بنفسه أو
         يتعامل معاه - مش بيختفي من غير أثر زي المكتبتين التانيين.
"""

import os
import sys
import threading
import time

_notification_available = None  # None = لسه ما اتفحصش، True/False بعد أول محاولة
_file_toast_lock = threading.Lock()
_recent_file_toasts = {}


def _check_available():
    """
    فحص لمرة واحدة بس: هل windows-toasts متثبتة أصلاً؟ بيتسجل في الـ
    console مرة واحدة عشان المستخدم يعرف لو محتاج يثبت المكتبة، مش
    هيتكرر مع كل ملف.
    """
    global _notification_available
    if _notification_available is not None:
        return _notification_available
    try:
        from windows_toasts import WindowsToaster  # noqa: F401
        _notification_available = True
    except Exception as e:
        _notification_available = False
        print(f"[NOTIFICATIONS] إشعارات الويندوز مش متاحة (windows-toasts مش متثبتة أو حصل خطأ): {e}")
    return _notification_available


def _human_size(size_bytes: int) -> str:
    """بيحول حجم بالـ bytes لصيغة قريبة من اللي المستخدم متعود يشوفه في مستكشف الملفات."""
    size = float(size_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            if unit == "B":
                return f"{int(size)} {unit}"
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} GB"


def _file_type_label(extension: str) -> str:
    """اسم بسيط ومفهوم للامتداد بدل ما يظهر '.pdf' كما هي في الإشعار."""
    ext = (extension or "").lower().lstrip(".")
    if not ext:
        return "ملف"
    return f"ملف {ext.upper()}"


def notify_new_file(path: str, size_bytes: int, extension: str):
    """
    بتتنادى بعد ما /upload يخلص حفظ الملف بنجاح وفهرسته في الـ DB.

    شغالة في Thread منفصل عشان مستحاليش تعطل أو تأخر رد الـ API للموبايل -
    الموبايل مش لازم يستنى ظهور الإشعار على الشاشة عشان ياخد تأكيد الرفع.
    """

    notify_file_change("created", path, size_bytes=size_bytes, extension=extension)


def notify_file_change(event_type: str, path: str, size_bytes: int | None = None, extension: str | None = None):
    """Show a file-change toast whose click opens the containing folder."""
    if event_type not in {"created", "deleted", "modified"} or not path:
        return

    absolute_path = os.path.abspath(path)
    dedupe_key = (event_type, os.path.normcase(absolute_path))
    now = time.monotonic()
    with _file_toast_lock:
        previous = _recent_file_toasts.get(dedupe_key)
        if previous is not None and now - previous < 5:
            return
        _recent_file_toasts[dedupe_key] = now
        if len(_recent_file_toasts) > 256:
            expired = [key for key, seen_at in _recent_file_toasts.items() if now - seen_at >= 5]
            for key in expired:
                _recent_file_toasts.pop(key, None)
            if len(_recent_file_toasts) > 256:
                oldest = sorted(_recent_file_toasts, key=_recent_file_toasts.get)[:-256]
                for key in oldest:
                    _recent_file_toasts.pop(key, None)

    def _show():
        if not _check_available():
            return

        from windows_toasts import WindowsToaster, Toast

        name = os.path.basename(absolute_path)
        folder = os.path.dirname(absolute_path)
        event_text = {
            "created": "تمت إضافة ملف",
            "deleted": "تم حذف ملف",
            "modified": "تم تحديث ملف",
        }[event_type]
        details = folder
        if event_type == "created":
            try:
                actual_size = size_bytes if size_bytes is not None else os.path.getsize(absolute_path)
            except OSError:
                actual_size = size_bytes
            if actual_size is not None:
                details = f"{_file_type_label(extension or os.path.splitext(name)[1])} - {_human_size(actual_size)} - {folder}"

        try:
            toaster = WindowsToaster("DeskTwin")
            new_toast = Toast()
            new_toast.text_fields = [
                event_text,
                name,
                details,
            ]
            # Open the containing folder so deletions and moved files still have
            # a useful destination after the original path no longer exists.
            if os.path.isdir(folder):
                new_toast.launch_action = folder
            toaster.show_toast(new_toast)
        except Exception as e:
            # فشل عرض إشعار واحد (نادر) - نسجله بس مش لازم يوقف أي حاجة.
            print(f"[NOTIFICATIONS] فشل عرض إشعار الملف '{name}': {e}")

    threading.Thread(target=_show, daemon=True).start()


def _clip_preview(text: str, max_length: int = 80) -> str:
    """
    نبذة قصيرة من النص المنسوخ للعرض في الإشعار - مش النص كامل، عشان لو
    كان طويل (فقرة كاملة مثلًا) الإشعار يفضل قابل للقراءة وميطولش.
    السطور الجديدة بتتحول لمسافة عشان الإشعار يفضل سطر أو سطرين بس.
    """
    single_line = " ".join(text.split())
    if len(single_line) <= max_length:
        return single_line
    return single_line[:max_length].rstrip() + "…"


def notify_clipboard_received(text: str):
    """
    بتتنادى لما نص جديد يوصل من التليفون عن طريق مزامنة الـ clipboard
    ويتحط في clipboard الكمبيوتر - نفس فكرة إشعار الملف، بس هنا بيعرض
    نبذة من النص المنسوخ بدل تفاصيل ملف.
    """

    def _show():
        if not _check_available():
            return

        from windows_toasts import WindowsToaster, Toast

        preview = _clip_preview(text)

        try:
            toaster = WindowsToaster("DeskTwin")
            new_toast = Toast()
            new_toast.text_fields = [
                "تم نسخ نص من التليفون",
                preview,
            ]
            toaster.show_toast(new_toast)
        except Exception as e:
            print(f"[NOTIFICATIONS] فشل عرض إشعار الـ clipboard: {e}")

    threading.Thread(target=_show, daemon=True).start()


def notify_transfer_started(path: str, direction: str):
    """إشعار مختصر عند بدء نقل ملف بين الكمبيوتر والهاتف."""
    def _show():
        if not _check_available():
            return
        from windows_toasts import WindowsToaster, Toast
        try:
            title = "إرسال ملف إلى الهاتف" if direction == "to_phone" else "استقبال ملف من الهاتف"
            toast = Toast()
            toast.text_fields = [title, os.path.basename(path)]
            WindowsToaster("DeskTwin").show_toast(toast)
        except Exception as e:
            print(f"[NOTIFICATIONS] فشل إشعار النقل: {e}")
    threading.Thread(target=_show, daemon=True).start()


def notify_operation(title: str, detail: str = ""):
    """إشعار موحد لأي أمر أكّده الكمبيوتر بالفعل."""
    def _show():
        if not _check_available():
            return
        from windows_toasts import WindowsToaster, Toast
        try:
            toast = Toast()
            toast.text_fields = [title, detail or "تم التنفيذ"]
            WindowsToaster("DeskTwin").show_toast(toast)
        except Exception as e:
            print(f"[NOTIFICATIONS] فشل إشعار العملية: {e}")
    threading.Thread(target=_show, daemon=True).start()


def notify_approval_request(action: str, path: str | None = None):
    """إشعار فوري بطلب موافقة جديد بدل الاعتماد على فتح الواجهة يدويًا."""
    def _show():
        if not _check_available():
            return
        from windows_toasts import WindowsToaster, Toast
        try:
            toast = Toast()
            toast.text_fields = ["طلب موافقة جديد", action, os.path.basename(path) if path else "راجع الطلب في DeskTwin"]
            # الضغط على الإشعار يعيد فتح واجهة DeskTwin بدل ترك الطلب مخفيًا.
            launch_target = sys.executable if getattr(sys, "frozen", False) else os.path.abspath(
                os.path.join(os.path.dirname(__file__), "..", "..", "..", "Python", "ui", "gui.py")
            )
            if os.path.exists(launch_target):
                toast.launch_action = launch_target + " --open-folders"
            WindowsToaster("DeskTwin").show_toast(toast)
        except Exception as e:
            print(f"[NOTIFICATIONS] فشل إشعار الموافقة: {e}")
    threading.Thread(target=_show, daemon=True).start()
