"""
DeskTwin Devices View
-----------------------
عرض كل الأجهزة اللي عملت Pairing ناجح، وإمكانية إلغاء ثقة أي جهاز
(مثلاً لو المستخدم فقد التليفون). يستخدم PairingManager الموجود بالفعل
في auth/pairing.py (list_trusted_devices, revoke_device) - مفيش تغيير
مطلوب في الـ backend، الميزة كانت موجودة بس بلا واجهة.
"""

import customtkinter as ctk
from tkinter import messagebox
from gui.i18n import t


class DevicesView(ctk.CTkFrame):
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
            self, text=copy("trusted_devices"), font=ctk.CTkFont(size=22, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 20))

        actions = ctk.CTkFrame(self, fg_color="transparent")
        actions.pack(anchor="w", pady=(0, 10))
        ctk.CTkButton(actions, text=copy("refresh_devices"), width=150,
                      command=self._refresh_list).pack(side="left", padx=(0, 8))
        ctk.CTkButton(actions, text=copy("clear_revoked"), width=150,
                      fg_color="transparent", border_width=1,
                      border_color=self.colors.error, text_color=self.colors.error,
                      command=self._delete_revoked).pack(side="left")

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
                self.list_frame, text=t("service_required", self.main_window.app_settings.language),
                text_color=self.colors.text_secondary
            ).pack(anchor="w", pady=20, padx=10)
            return

        try:
            devices = self.service.list_trusted_devices()
        except Exception as exc:
            if self._last_signature == ("error", str(exc)):
                return
            self._last_signature = ("error", str(exc))
            for widget in self.list_frame.winfo_children():
                widget.destroy()
            ctk.CTkLabel(
                self.list_frame, text=f"تعذر تحميل الأجهزة: {exc}",
                text_color=self.colors.error
            ).pack(anchor="w", pady=20, padx=10)
            return
        signature = tuple(
            (d.get("id"), d.get("revoked"), d.get("trust_level"), d.get("last_seen_at"), d.get("auto_reconnect_count"))
            for d in devices
        )
        if signature == self._last_signature:
            return
        self._last_signature = signature
        # لا نمسح الواجهة إلا بعد تأكدنا أن البيانات تغيرت فعليًا؛ كان
        # المسح قبل المقارنة هو سبب تحول الشاشة إلى قائمة بيضاء فارغة.
        for widget in self.list_frame.winfo_children():
            widget.destroy()
        if not devices:
            ctk.CTkLabel(
                self.list_frame, text=t("no_devices", self.main_window.app_settings.language),
                text_color=self.colors.text_secondary
            ).pack(anchor="w", pady=20, padx=10)
            return

        # الموثوق دائمًا أولًا، ثم الأحدث نشاطًا؛ الأقدم ينزل لآخر السجل.
        devices = sorted(
            devices,
            key=lambda d: ((not d.get("revoked") and d.get("trust_level") in {"trusted", "full"}),
                           d.get("last_seen_at") or d.get("paired_at") or ""),
            reverse=True,
        )
        for device in devices:
            row = ctk.CTkFrame(self.list_frame, fg_color=self.colors.bg_secondary, corner_radius=8)
            row.pack(fill="x", pady=4, padx=6)

            info_col = ctk.CTkFrame(row, fg_color="transparent")
            info_col.pack(side="left", fill="x", expand=True, padx=14, pady=10)

            revoked = device.get("revoked")
            status_text = t("revoked" if revoked else "trusted", self.main_window.app_settings.language)
            status_color = self.colors.error if revoked else self.colors.success

            name_row = ctk.CTkFrame(info_col, fg_color="transparent")
            name_row.pack(anchor="w")
            ctk.CTkLabel(
                name_row, text=device.get("device_name") or "جهاز غير معروف",
                font=ctk.CTkFont(weight="bold"), text_color=self.colors.text_primary
            ).pack(side="left")
            ctk.CTkLabel(
                name_row, text="  ● " + status_text, text_color=status_color, font=ctk.CTkFont(size=11)
            ).pack(side="left")

            ctk.CTkLabel(
                info_col, text=t("last_seen", self.main_window.app_settings.language) + ": " + (device.get("last_seen_at", "") or "-")[:19].replace("T", " "),
                text_color=self.colors.text_secondary, font=ctk.CTkFont(size=11)
            ).pack(anchor="w")

            # دليل بصري إن "الثقة العمياء" فعليًا شغالة - الجهاز بيتصل
            # لوحده من غير أي كود، مش بس أول مرة اتعمل Pairing
            reconnect_count = device.get("auto_reconnect_count", 0) or 0
            if not revoked and reconnect_count > 0:
                ctk.CTkLabel(
                    info_col,
                    text=f"🔄 اتصل تلقائيًا {reconnect_count} مرة من غير كود",
                    text_color=self.colors.accent, font=ctk.CTkFont(size=11)
                ).pack(anchor="w", pady=(2, 0))

            if not revoked:
                trust_labels = {"عادي": "standard", "موثوق": "trusted", "كامل": "full"}
                current_level = device.get("trust_level", "standard")
                current_label = next((label for label, value in trust_labels.items() if value == current_level), "عادي")
                trust_var = ctk.StringVar(value=current_label)
                ctk.CTkOptionMenu(
                    row, values=list(trust_labels.keys()), variable=trust_var, width=95,
                    command=lambda label, d=device: self._on_trust_change(d, trust_labels[label])
                ).pack(side="right", padx=6)
                ctk.CTkButton(
                    row, text="إلغاء الثقة", width=110, height=30,
                    fg_color="transparent", border_width=1, border_color=self.colors.error,
                    text_color=self.colors.error, hover_color=self.colors.bg_card,
                    command=lambda d=device: self._on_revoke(d)
                ).pack(side="right", padx=14)

    def _on_revoke(self, device: dict):
        confirm = messagebox.askyesno(
            "تأكيد", "متأكد إنك عايز تلغي ثقة \"" + (device.get("device_name") or "الجهاز ده") + "\"؟\n"
            "هيحتاج يعمل اقتران جديد بكود عشان يتصل تاني."
        )
        if not confirm:
            return
        self.service.revoke_device(device["id"])
        self._refresh_list()

    def _on_trust_change(self, device: dict, trust_level: str):
        try:
            self.service.set_device_trust_level(device["id"], trust_level)
            self._refresh_list()
        except Exception as exc:
            messagebox.showerror("تعذر تغيير الثقة", str(exc))

    def _delete_revoked(self):
        devices = self.service.list_trusted_devices()
        targets = [d for d in devices if d.get("revoked")]
        if not targets:
            messagebox.showinfo("سجل الأجهزة", "لا توجد سجلات ملغاة للحذف.")
            return
        if not messagebox.askyesno("تأكيد", f"سيتم حذف {len(targets)} سجلًا ملغى من السجل فقط. الأجهزة الموثوقة لن تتأثر. متابعة؟"):
            return
        self.service.delete_revoked_devices()
        self._last_signature = None
        self._refresh_list()

    def on_show(self):
        self._refresh_list()

    def on_refresh_tick(self):
        # الاستعلام خفيف، وإعادة الرسم لا تحدث إلا إذا تغيّر توقيع البيانات.
        self._refresh_list()
