import customtkinter as ctk
from sync.manual_messages import get_messages
from gui.i18n import t


class ChatView(ctk.CTkFrame):
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window, self.colors, self._last_signature = main_window, main_window.colors, None
        copy = lambda key: t(key, self.main_window.app_settings.language)
        header = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=14)
        header.pack(fill="x", pady=(0, 12))
        ctk.CTkLabel(header, text="DeskTwin Chat", font=ctk.CTkFont(size=22, weight="bold"), text_color=self.colors.text_primary).pack(anchor="w", padx=16, pady=(12, 0))
        self.connection_label = ctk.CTkLabel(header, text="● " + copy("waiting_phone"), text_color=self.colors.text_secondary, font=ctk.CTkFont(size=12))
        self.connection_label.pack(anchor="w", padx=16, pady=(2, 12))
        self.messages = ctk.CTkScrollableFrame(self, fg_color=self.colors.bg_card, corner_radius=14)
        self.messages.pack(fill="both", expand=True)
        composer = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=14)
        composer.pack(fill="x", pady=(12, 0))
        self.input = ctk.CTkTextbox(composer, height=78, fg_color=self.colors.bg_secondary, text_color=self.colors.text_primary, border_width=1, border_color=self.colors.border)
        self.input.pack(side="left", fill="x", expand=True, padx=(12, 8), pady=12)
        self.input.bind("<Control-Return>", lambda _event: self.send_message())
        ctk.CTkButton(composer, text=copy("send"), command=self.send_message, width=105, height=42, fg_color=self.colors.accent, hover_color=self.colors.accent_hover).pack(side="right", padx=(0, 12), pady=12)
        self.feedback = ctk.CTkLabel(self, text=copy("message_hint"), text_color=self.colors.text_secondary, font=ctk.CTkFont(size=11))
        self.feedback.pack(anchor="w", pady=(6, 0))

    def send_message(self):
        text = self.input.get("1.0", "end-1c").strip()
        if not text:
            return
        from api.server import send_manual_text_to_devices
        if send_manual_text_to_devices(text):
            self.input.delete("1.0", "end")
            self.feedback.configure(text="تم الإرسال للهاتف — ننتظر تأكيد الاستلام", text_color=self.colors.success)
            self._last_signature = None
            self.refresh()
        else:
            self.feedback.configure(text="لم يصل الهاتف الآن. افتح DeskTwin على الهاتف وتأكد من الاتصال.", text_color=self.colors.error)

    def on_show(self):
        self._last_signature = None
        self.refresh()

    def on_refresh_tick(self):
        self.refresh()

    def refresh(self):
        items = list(reversed(get_messages()))
        signature = tuple((item.get("id"), item.get("status"), item.get("received_at")) for item in items)
        if signature == self._last_signature:
            return
        self._last_signature = signature
        connected = bool(getattr(self.main_window.service, "is_running", False))
        self.connection_label.configure(text="● " + t("computer_service_waiting" if connected else "computer_service_stopped", self.main_window.app_settings.language), text_color=self.colors.success if connected else self.colors.error)
        for widget in self.messages.winfo_children(): widget.destroy()
        if not items:
            ctk.CTkLabel(self.messages, text=t("start_message", self.main_window.app_settings.language), text_color=self.colors.text_secondary).pack(pady=30)
            return
        for item in items:
            outgoing = item.get("sender") == "computer"
            row = ctk.CTkFrame(self.messages, fg_color="transparent")
            row.pack(fill="x", padx=10, pady=5)
            bubble = ctk.CTkFrame(row, fg_color=self.colors.accent if outgoing else self.colors.bg_secondary, corner_radius=12)
            bubble.pack(side="right" if outgoing else "left", padx=4)
            ctk.CTkLabel(bubble, text=item.get("text", ""), justify="left", wraplength=420, text_color=self.colors.on_accent if outgoing else self.colors.text_primary).pack(anchor="w", padx=12, pady=(9, 2))
            status = {"sent": "تم الإرسال — انتظار الهاتف", "delivered": "تم الاستلام", "received": "تم الاستلام"}.get(item.get("status"), item.get("status", ""))
            ctk.CTkLabel(bubble, text=f"{item.get('received_at', '')}  {status}", text_color=self.colors.on_accent_muted if outgoing else self.colors.text_secondary, font=ctk.CTkFont(size=10)).pack(anchor="e", padx=12, pady=(0, 7))
