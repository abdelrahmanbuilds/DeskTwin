"""
DeskTwin Approvals View
-------------------------
طلبات الموافقة المعلّقة (حذف، تسمية، استبدال، توسيع نطاق) - المستخدم
يوافق أو يرفض من هنا. التنفيذ الفعلي عبر service.approve_request/reject_request.

تحديثات هذه الجولة:
- عرض "من كام يوم" لكل طلب (شفافية عن عمره، خصوصًا مع انتهاء الصلاحية التلقائي)
- رسالة تأكيد صريحة بعد نجاح العملية (مش بس تحديث هادئ للقائمة)
- زرار "افتح مكان الملف" يفتح الفولدر في File Explorer مباشرة
"""

import os
import json
import subprocess
import sys
import threading
from datetime import datetime, timezone

import customtkinter as ctk
from tkinter import messagebox
from gui.i18n import t

ACTION_LABELS = {
    "delete_file": "🗑 حذف ملف",
    "rename_file": "✏️ تسمية ملف",
    "create_file": "📄 إنشاء ملف (ADD_TARGET أو رفع)",
    "make_linked": "🔗 مزامنة كاملة",
    "overwrite_file": "⚠️ استبدال ملف",
    "command_execute": "⛔ طلب shell قديم محظور",
}

SUCCESS_MESSAGES = {
    "delete_file": "تم حذف الملف فعليًا من الكمبيوتر.",
    "rename_file": "تم تغيير اسم الملف فعليًا على الكمبيوتر.",
    "create_file": "تم تنفيذ الطلب بنجاح.",
    "make_linked": "الملف بقى متاح للمزامنة الكاملة.",
    "overwrite_file": "الموافقة اتسجلت - الاستبدال هيحصل مع أول رفع من التليفون.",
    "command_execute": "تم حظر طلب shell القديم.",
}


class ApprovalsView(ctk.CTkFrame):
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.service = main_window.service
        self.colors = main_window.colors
        self._last_signature = None

        self._build_ui()

    def _build_ui(self):
        copy = lambda key: t(key, self.main_window.app_settings.language)
        ctk.CTkLabel(
            self, text=copy("approvals_title"), font=ctk.CTkFont(size=22, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 4))

        ctk.CTkLabel(
            self, text=copy("approvals_hint"),
            font=ctk.CTkFont(size=11), text_color=self.colors.text_secondary
        ).pack(anchor="w", pady=(0, 16))

        self.list_frame = ctk.CTkScrollableFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        self.list_frame.pack(fill="both", expand=True)

    def _refresh_list(self):
        if not self.service.db:
            if self._last_signature == ("service-stopped",):
                return
            self._last_signature = ("service-stopped",)
            for widget in self.list_frame.winfo_children():
                widget.destroy()
            ctk.CTkLabel(
                self.list_frame, text="شغّل الخدمة الأول",
                text_color=self.colors.text_secondary
            ).pack(anchor="w", pady=20, padx=10)
            return

        pending = self.service.get_pending_approvals()
        signature = tuple((item.get("id"), item.get("status"), item.get("action_type"), item.get("file_path"), item.get("requested_at")) for item in pending)
        if signature == self._last_signature:
            return
        self._last_signature = signature
        for widget in self.list_frame.winfo_children():
            widget.destroy()

        if hasattr(self.main_window, "update_approvals_badge"):
            self.main_window.update_approvals_badge(len(pending))

        if not pending:
            ctk.CTkLabel(
                self.list_frame, text=t("no_approvals", self.main_window.app_settings.language),
                text_color=self.colors.text_secondary
            ).pack(anchor="w", pady=20, padx=10)
            return

        for approval in pending:
            self._render_approval_row(approval)

    def _render_approval_row(self, approval: dict):
        row = ctk.CTkFrame(self.list_frame, fg_color=self.colors.bg_secondary, corner_radius=8)
        row.pack(fill="x", pady=4, padx=6)

        info_col = ctk.CTkFrame(row, fg_color="transparent")
        info_col.pack(side="left", fill="x", expand=True, padx=14, pady=10)

        header_row = ctk.CTkFrame(info_col, fg_color="transparent")
        header_row.pack(anchor="w", fill="x")

        ctk.CTkLabel(
            header_row, text=ACTION_LABELS.get(approval["action_type"], approval["action_type"]),
            font=ctk.CTkFont(weight="bold"), text_color=self.colors.text_primary, anchor="w"
        ).pack(side="left")

        age_text = self._format_age(approval.get("requested_at"))
        ctk.CTkLabel(
            header_row, text="  •  " + age_text,
            font=ctk.CTkFont(size=11), text_color=self.colors.warning, anchor="w"
        ).pack(side="left")

        file_path = approval.get("file_path", "") or ""
        ctk.CTkLabel(
            info_col, text=file_path,
            text_color=self.colors.text_secondary, font=ctk.CTkFont(size=11), anchor="w"
        ).pack(anchor="w")

        if approval.get("action_type") == "command_execute":
            try:
                payload = json.loads(approval.get("new_value") or "{}")
                command_preview = f"{payload.get('shell', 'cmd')}: {payload.get('command', '')}"
            except (TypeError, ValueError):
                command_preview = "تعذر قراءة الأمر المطلوب"
            ctk.CTkLabel(
                info_col, text=command_preview, text_color=self.colors.warning,
                font=ctk.CTkFont(size=11), anchor="w", justify="left", wraplength=620
            ).pack(anchor="w", fill="x", pady=(5, 0))

        btn_col = ctk.CTkFrame(row, fg_color="transparent")
        btn_col.pack(side="right", padx=14, pady=10)

        ctk.CTkButton(
            btn_col, text=t("reject", self.main_window.app_settings.language), width=64, height=28,
            fg_color=self.colors.error, hover_color=self.colors.error_hover,
            command=lambda a=approval["id"], t=ACTION_LABELS.get(approval["action_type"], ""): self._on_reject(a, t)
        ).pack(side="right", padx=(6, 0))

        ctk.CTkButton(
            btn_col, text=t("approve", self.main_window.app_settings.language), width=74, height=28,
            fg_color=self.colors.success, hover_color=self.colors.success_hover,
            command=lambda a=approval["id"], act=approval["action_type"], item=approval: self._on_approve(a, act, item)
        ).pack(side="right", padx=(6, 0))

        if file_path and os.path.exists(os.path.dirname(file_path) or file_path):
            ctk.CTkButton(
                btn_col, text="📂", width=32, height=28,
                fg_color="transparent", border_width=1, border_color=self.colors.border,
                text_color=self.colors.text_secondary, hover_color=self.colors.bg_card,
                command=lambda p=file_path: self._open_containing_folder(p)
            ).pack(side="right")

    def _format_age(self, requested_at: str) -> str:
        if not requested_at:
            return ""
        try:
            requested = datetime.fromisoformat(requested_at)
            now = datetime.now(timezone.utc)
            delta = now - requested
            if delta.days >= 1:
                return "من " + str(delta.days) + " يوم"
            hours = delta.seconds // 3600
            if hours >= 1:
                return "من " + str(hours) + " ساعة"
            minutes = max(delta.seconds // 60, 1)
            return "من " + str(minutes) + " دقيقة"
        except (ValueError, TypeError):
            return ""

    def _open_containing_folder(self, file_path: str):
        """بيفتح الفولدر اللي الملف جواه في File Explorer - مش الملف نفسه."""
        folder = file_path if os.path.isdir(file_path) else os.path.dirname(file_path)
        if not os.path.isdir(folder):
            messagebox.showwarning("تنبيه", "المجلد ده مش موجود على القرص دلوقتي.")
            return
        try:
            if sys.platform == "win32":
                os.startfile(folder)
            elif sys.platform == "darwin":
                subprocess.run(["open", folder], check=False)
            else:
                subprocess.run(["xdg-open", folder], check=False)
        except OSError as e:
            messagebox.showerror("خطأ", "مقدرناش نفتح المجلد: " + str(e))

    def _on_approve(self, approval_id: int, action_type: str, approval: dict | None = None):
        if action_type == "command_execute":
            try:
                payload = json.loads((approval or {}).get("new_value") or "{}")
                details = (
                    "هذا طلب shell قديم لم يعد DeskTwin ينفذه. "
                    "عند التأكيد سيُرفض الطلب فقط ولن يُشغَّل شيء:\n\n"
                    f"الطرفية: {payload.get('shell', 'cmd')}\n"
                    f"مجلد العمل: {payload.get('working_directory', '')}\n\n"
                    f"{payload.get('command', '')}\n\nهل تريد رفض الطلب الآن؟"
                )
            except (TypeError, ValueError):
                messagebox.showerror("طلب غير صالح", "تعذر قراءة الأمر المطلوب.")
                return
        else:
            details = "هل تريد تنفيذ هذا الطلب على الكمبيوتر؟"
        if not messagebox.askyesno("تأكيد الموافقة", details):
            return
        result = self.service.approve_request(approval_id)
        if result.get("success"):
            messagebox.showinfo("تم", SUCCESS_MESSAGES.get(action_type, "تم تنفيذ الطلب بنجاح."))
        else:
            messagebox.showerror("فشلت العملية", result.get("error", "خطأ غير معروف"))
        self._refresh_list()

    def _approve_command_in_background(self, approval_id: int, action_type: str):
        result = self.service.approve_request(approval_id)
        try:
            self.after(0, lambda: self._finish_command_approval(result, action_type))
        except Exception:
            pass

    def _finish_command_approval(self, result: dict, action_type: str):
        if result.get("success"):
            message = SUCCESS_MESSAGES.get(action_type, "تم تنفيذ الطلب بنجاح.")
            if result.get("output"):
                message += "\n\n" + result["output"][-1200:]
            messagebox.showinfo("تم", message)
        else:
            messagebox.showerror("فشل التنفيذ", result.get("error", "خطأ غير معروف"))
        self._refresh_list()

    def _on_reject(self, approval_id: int, action_label: str):
        if not messagebox.askyesno("تأكيد الرفض", "هل تريد رفض هذا الطلب؟"):
            return
        self.service.reject_request(approval_id)
        messagebox.showinfo("تم الرفض", "تم رفض الطلب: \"" + action_label + "\". هيوصل إشعار للتليفون.")
        self._refresh_list()

    def on_show(self):
        self._last_signature = None
        self._refresh_list()

    def on_refresh_tick(self):
        self._refresh_list()
