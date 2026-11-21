import customtkinter as ctk

class IntegrationHealthView(ctk.CTkFrame):
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.service = main_window.service
        self.colors = main_window.colors
        ctk.CTkLabel(self, text="فحص توافق DeskTwin", font=ctk.CTkFont(size=22, weight="bold"), text_color=self.colors.text_primary).pack(anchor="w", pady=(0, 16))
        self.result = ctk.CTkTextbox(self, height=360, fg_color=self.colors.bg_card, text_color=self.colors.text_primary)
        self.result.pack(fill="both", expand=True)
        self.result.configure(state="disabled")
        ctk.CTkButton(self, text="إعادة الفحص", command=self.refresh).pack(anchor="e", pady=(10, 0))

    def refresh(self):
        devices = []
        if self.service.db and self.service.pairing:
            devices = self.service.list_trusted_devices()
        active = sum(1 for d in devices if not d.get("revoked"))
        try:
            status = self.service.get_security_status()
            connections = status.get("active_connections", 0)
        except Exception:
            connections = 0
        lines = [
            f"الخدمة: {'Online' if self.service.is_running else 'Offline'}",
            f"الأجهزة الموثوقة: {active}",
            f"جلسات WebSocket: {connections}",
            "Chat: متاح عبر /integration/health",
            "البروتوكول: 1.0",
            "",
            "Hints:",
        ]
        if not self.service.is_running:
            lines.append("- شغّل الخدمة قبل اختبار التكامل.")
        if active == 0:
            lines.append("- اعمل Pairing من الهاتف أولًا.")
        if active and connections == 0:
            lines.append("- الجهاز موثوق لكن لا توجد قناة WebSocket؛ افتح Chat أو أعد الاتصال.")
        if any(d.get("trust_level") != "full" and not d.get("revoked") for d in devices):
            lines.append("- بعض الأجهزة ليست بمستوى Full؛ أوامر الجهاز ستُرفض حتى رفع الثقة.")
        self.result.configure(state="normal")
        self.result.delete("1.0", "end")
        self.result.insert("end", "\n".join(lines))
        self.result.configure(state="disabled")

    def on_show(self):
        self.refresh()

    def on_refresh_tick(self):
        self.refresh()
