"""
DeskTwin Activity View
------------------------
السجل الكامل لكل نشاط - تصفح، فتح ملفات، رفع، طلبات حذف/تسمية.
نسخة موسّعة من المعاينة اللي في الـ Dashboard.
"""

import customtkinter as ctk
from tkinter import messagebox
from gui.i18n import t

ACTIVITY_LABELS = {
    "browsed_folder": "📂 اتصفح مجلد",
    "opened_file": "📄 اتفتح ملف",
    "opened_location": "📍 اتفتح مكان الملف",
    "searched": "🔍 بحث",
    "downloaded_file": "⬇️ اتحمّل ملف",
    "uploaded_file": "⬆️ اترفع ملف",
    "copy": "📋 اتنسخ ملف",
    "move": "📦 اتنقل ملف",
    "delete": "🗑 اتمسح ملف",
    "rename": "✏️ اتغيّر اسم ملف",
    "mkdir": "📁 اتعمل مجلد",
    "open": "▶️ اتفتح ملف",
    "requested_delete": "🗑 طلب حذف",
    "requested_rename": "✏️ طلب تسمية",
    "connected": "🟢 اتصل",
    "disconnected": "🔴 قطع الاتصال",
}


class ActivityView(ctk.CTkFrame):
    PAGE_SIZE = 15

    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.service = main_window.service
        self.colors = main_window.colors
        self._rows_by_id = {}
        self._empty_label = None
        self._activity_limit = self.PAGE_SIZE
        self._load_more_button = None

        self._build_ui()

    def _build_ui(self):
        copy = lambda key: t(key, self.main_window.app_settings.language)
        ctk.CTkLabel(
            self, text=copy("activity_log"), font=ctk.CTkFont(size=22, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 20))

        self.activity_list = ctk.CTkScrollableFrame(
            self, fg_color=self.colors.bg_card, corner_radius=12
        )
        self.activity_list.pack(fill="both", expand=True)
        self._load_more_button = ctk.CTkButton(
            self.activity_list,
            text=copy("load_more_activity"),
            command=self._load_more,
            fg_color="transparent",
            text_color=self.colors.text_primary,
            border_width=1,
        )
        ctk.CTkButton(self, text=copy("clear_activity"), command=self._clear_activity).pack(anchor="e", pady=(8, 0))

    def _load_more(self):
        self._activity_limit += self.PAGE_SIZE
        self._refresh()

    def _refresh(self):
        if not self.service.db:
            return

        activity = self.service.db.get_recent_activity(limit=self._activity_limit)
        visible_ids = {item.get("id") for item in activity}
        for item_id in tuple(self._rows_by_id):
            if item_id not in visible_ids:
                self._rows_by_id.pop(item_id).destroy()
        if not activity:
            if self._empty_label is None or not self._empty_label.winfo_exists():
                self._empty_label = ctk.CTkLabel(
                    self.activity_list, text=t("no_activity", self.main_window.app_settings.language),
                    text_color=self.colors.text_secondary
                )
                self._empty_label.pack(anchor="w", padx=16, pady=20)
            self._load_more_button.pack_forget()
            return
        if self._empty_label is not None and self._empty_label.winfo_exists():
            self._empty_label.destroy()
            self._empty_label = None
        for item in activity:
            item_id = item.get("id")
            if item_id in self._rows_by_id:
                continue
            label = ACTIVITY_LABELS.get(item["activity_type"], item["activity_type"])
            detail = item.get("detail", "") or ""
            timestamp = item["created_at"][:19].replace("T", " ") if item["created_at"] else ""
            row = ctk.CTkFrame(self.activity_list, fg_color=self.colors.bg_secondary, corner_radius=8)
            row.pack(fill="x", padx=8, pady=4)
            main = ctk.CTkFrame(row, fg_color="transparent")
            main.pack(fill="x", padx=14, pady=10)
            ctk.CTkLabel(
                main, text=label, anchor="w",
                font=ctk.CTkFont(size=13, weight="bold"),
                text_color=self.colors.text_primary
            ).pack(side="left")
            ctk.CTkLabel(
                main, text=timestamp, anchor="e",
                font=ctk.CTkFont(size=10), text_color=self.colors.text_secondary
            ).pack(side="right")
            if detail:
                ctk.CTkLabel(
                    row, text=detail, anchor="w", justify="left",
                    font=ctk.CTkFont(size=11), text_color=self.colors.text_secondary
                ).pack(fill="x", padx=14, pady=(0, 10))
            self._rows_by_id[item_id] = row
            first_row = self.activity_list.winfo_children()[0] if self.activity_list.winfo_children() else None
            if first_row is not row:
                row.pack_configure(before=first_row)
        if len(activity) >= self._activity_limit:
            self._load_more_button.pack(fill="x", padx=8, pady=(8, 12))
        else:
            self._load_more_button.pack_forget()

    def on_show(self):
        self._refresh()

    def on_refresh_tick(self):
        self._refresh()

    def _clear_activity(self):
        if not messagebox.askyesno("تأكيد المسح", "هل تريد مسح سجل النشاط بالكامل؟ لا يمكن التراجع عن هذا الإجراء."):
            return
        self.service.db.clear_activity()
        self._activity_limit = self.PAGE_SIZE
        for row in self._rows_by_id.values():
            row.destroy()
        self._rows_by_id.clear()
        self._refresh()
