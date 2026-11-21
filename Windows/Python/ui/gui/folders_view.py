"""
DeskTwin Folders View
-----------------------
إدارة النطاق المسموح للموبايل: إضافة فولدر أو ملف منفرد، حذف، ومفتاح
"مشاركة الجهاز كاملًا" (معطّل افتراضيًا - القاعدة الذهبية للنطاق الآمن).
"""

import os
import customtkinter as ctk
from tkinter import filedialog, messagebox
from gui.i18n import t


class FoldersView(ctk.CTkFrame):
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.service = main_window.service
        self.colors = main_window.colors
        self._last_signature = None

        self._build_ui()
        self._refresh_list()

    def _build_ui(self):
        copy = lambda key: t(key, self.main_window.app_settings.language)
        ctk.CTkLabel(
            self, text=copy("watched_folders"), font=ctk.CTkFont(size=22, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 6))

        ctk.CTkLabel(
            self, text=copy("folder_scope"),
            font=ctk.CTkFont(size=12), text_color=self.colors.text_secondary
        ).pack(anchor="w", pady=(0, 20))

        # أزرار الإضافة
        buttons_row = ctk.CTkFrame(self, fg_color="transparent")
        buttons_row.pack(fill="x", pady=(0, 16))

        ctk.CTkButton(
            buttons_row, text="+ " + copy("add_folder"), command=self._on_add_folder,
            height=36, fg_color=self.colors.accent, hover_color=self.colors.accent_hover
        ).pack(side="left", padx=(0, 10))

        ctk.CTkButton(
            buttons_row, text="+ " + copy("add_file"), command=self._on_add_file,
            height=36, fg_color="transparent", border_width=1,
            border_color=self.colors.accent, text_color=self.colors.accent,
            hover_color=self.colors.bg_card
        ).pack(side="left")

        # قائمة المجلدات/الملفات
        self.list_frame = ctk.CTkScrollableFrame(self, fg_color=self.colors.bg_card, corner_radius=12, height=300)
        self.list_frame.pack(fill="both", expand=True, pady=(0, 20))

        # مفتاح مشاركة الجهاز كامل - بتأكيد صريح إجباري
        share_card = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        share_card.pack(fill="x")

        share_inner = ctk.CTkFrame(share_card, fg_color="transparent")
        share_inner.pack(fill="x", padx=18, pady=14)

        self.share_var = ctk.BooleanVar(value=self.service.config.share_entire_device)
        ctk.CTkSwitch(
            share_inner, text=copy("share_entire_device"),
            variable=self.share_var, command=self._on_toggle_share,
            font=ctk.CTkFont(size=13), text_color=self.colors.text_primary,
            fg_color=self.colors.border, progress_color=self.colors.error
        ).pack(anchor="w")

        ctk.CTkLabel(
            share_inner, text="⚠ " + copy("device_warning"),
            font=ctk.CTkFont(size=11), text_color=self.colors.warning
        ).pack(anchor="w", pady=(6, 0))

    def _on_add_folder(self):
        path = filedialog.askdirectory(title="اختار مجلد لمراقبته")
        if not path:
            return
        label = os.path.basename(path.rstrip("/\\")) or path
        self.service.add_target(path, label=label, recursive=True, is_file=False)
        self._refresh_list()

    def _on_add_file(self):
        path = filedialog.askopenfilename(title="اختار ملف لمشاركته")
        if not path:
            return
        label = os.path.basename(path)
        self.service.add_target(path, label=label, recursive=False, is_file=True)
        self._refresh_list()

    def _on_remove(self, path: str):
        confirm = messagebox.askyesno(
            "تأكيد", "هتشيل ده من المراقبة:\n" + path + "\n(الملف نفسه مش هيتمسح من الكمبيوتر)"
        )
        if not confirm:
            return
        self.service.config.targets = [t for t in self.service.config.targets if t.path != path]
        self.service.config.save()
        self._refresh_list()

    def _on_toggle_share(self):
        enabled = self.share_var.get()
        if enabled:
            confirm = messagebox.askyesno(
                "تأكيد مشاركة كاملة",
                "هتشارك كل أقراص وملفات الكمبيوتر مع الهاتف الموثوق. متأكد؟"
            )
            if not confirm:
                self.share_var.set(False)
                return
        self.service.config.share_entire_device = enabled
        self.service.config.save()

    def _refresh_list(self):
        targets = self.service.get_targets()
        signature = tuple(
            (t.path, t.label, getattr(t, "recursive", False), getattr(t, "is_file", False))
            for t in targets
        )
        if signature == self._last_signature:
            return
        self._last_signature = signature
        for widget in self.list_frame.winfo_children():
            widget.destroy()

        if not targets:
            ctk.CTkLabel(
                self.list_frame, text=t("no_targets", self.main_window.app_settings.language),
                text_color=self.colors.text_secondary
            ).pack(anchor="w", pady=20, padx=10)
            return

        for target in targets:
            row = ctk.CTkFrame(self.list_frame, fg_color="transparent")
            row.pack(fill="x", pady=4)

            icon = "📄" if getattr(target, "is_file", False) else "📁"
            ctk.CTkLabel(
                row, text=icon + " " + (target.label or target.path),
                text_color=self.colors.text_primary, anchor="w"
            ).pack(side="left")

            ctk.CTkButton(
                row, text="✕", width=28, height=24, fg_color="transparent",
                text_color=self.colors.error, hover_color=self.colors.bg_secondary,
                command=lambda p=target.path: self._on_remove(p)
            ).pack(side="right", padx=(4, 0))

            ctk.CTkLabel(
                row, text=target.path, text_color=self.colors.text_disabled, font=ctk.CTkFont(size=11)
            ).pack(side="right", padx=(0, 8))

    def on_show(self):
        self._last_signature = None
        self._refresh_list()

    def on_refresh_tick(self):
        # تحديث شاشة الكمبيوتر عند تغير اتصال/إعدادات الهاتف فقط؛ لا نعيد
        # بناء عناصر الواجهة في كل دورة بلا داعٍ.
        self._refresh_list()
