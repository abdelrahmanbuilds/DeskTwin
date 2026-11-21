"""
DeskTwin App Settings
-----------------------
تفضيلات خاصة بالواجهة الرسومية فقط (شكل، وضع، حجم النافذة) - منفصلة تمامًا
عن DeskTwinConfig (اللي هو إعدادات المزامنة: المجلدات المراقبة، النطاق، إلخ).
الفصل ده مهم: تغيير شكل الواجهة ميعملش أي حاجة بمنطق المزامنة أو العكس.
"""

import json
import os
from dataclasses import dataclass, asdict

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
APP_SETTINGS_PATH = os.path.join(PROJECT_ROOT, "config", "app_settings.json")


@dataclass
class AppSettings:
    theme_mode: str = "dark"           # "dark" أو "light"
    language: str = "ar"                # "ar" أو "en" - واجهة فقط
    reduce_motion: bool = False
    intro_mode: str = "auto"  # auto, manual, off
    custom_intro_video: str = ""
    window_width: int = 980
    window_height: int = 720
    sidebar_collapsed: bool = False
    last_active_view: str = "dashboard"
    auto_start_service: bool = True    # فتح البرنامج = تشغيل الخدمة تلقائيًا (بدون ضغطة زرار يدوية)
    incomplete_transfer_retention_hours: int = 24  # مدة الاحتفاظ بملف ناقص قبل مسحه نهائيًا (ساعة - أسبوع = 168)
    activity_log_limit: int = 300              # عدد عمليات الهاتف المحتفظ بها في سجل النشاط
    command_draft_directory: str = ""         # مجلد العمل الافتراضي لأوامر الهاتف؛ فارغ = Desktop
    command_request_export_directory: str = ""  # مجلد افتراضي لنسخ طلبات الأوامر كنص
    use_default_command_request_export_directory: bool = False  # استخدم المجلد دون نافذة اختيار
    phone_download_directory: str = ""  # مكان حفظ الملفات المنزلة من الهاتف على الكمبيوتر
    phone_upload_directory: str = ""  # مجلد الهاتف الافتراضي لرفع ملفات الكمبيوتر إليه

    def save(self, path: str = APP_SETTINGS_PATH):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(asdict(self), f, indent=2, ensure_ascii=False)

    @classmethod
    def load(cls, path: str = APP_SETTINGS_PATH) -> "AppSettings":
        if not os.path.exists(path):
            return cls()
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            settings = cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})
            if settings.intro_mode not in {"auto", "manual", "off"}:
                settings.intro_mode = "auto"
            return settings
        except (json.JSONDecodeError, TypeError):
            return cls()  # ملف تالف - نرجع للافتراضي بدل ما نكسر التطبيق
