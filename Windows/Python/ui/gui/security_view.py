"""شاشة مراقبة أمنية واضحة بدون ادعاء اختراق غير مثبت."""

import customtkinter as ctk
from tkinter import messagebox


class SecurityView(ctk.CTkFrame):
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.service = main_window.service
        self.colors = main_window.colors
        self._build_ui()

    def _build_ui(self):
        ctk.CTkLabel(self, text="الأمان والمراقبة", font=ctk.CTkFont(size=22, weight="bold"),
                     text_color=self.colors.text_primary).pack(anchor="w", pady=(0, 16))
        self.alert = ctk.CTkFrame(self, corner_radius=12, fg_color=self.colors.bg_card)
        self.alert.pack(fill="x", pady=(0, 12))
        self.alert_title = ctk.CTkLabel(self.alert, text="جاري قراءة الحالة...",
                                        font=ctk.CTkFont(size=18, weight="bold"))
        self.alert_title.pack(anchor="w", padx=18, pady=(16, 4))
        self.alert_text = ctk.CTkLabel(self.alert, text="", justify="left", anchor="w")
        self.alert_text.pack(fill="x", padx=18, pady=(0, 16))

        self.stats = ctk.CTkLabel(self, text="", justify="left", anchor="w",
                                  text_color=self.colors.text_secondary)
        self.stats.pack(fill="x", pady=10)

        actions = ctk.CTkFrame(self, fg_color="transparent")
        actions.pack(fill="x", pady=10)
        ctk.CTkButton(actions, text="تحديث الحالة", command=self.refresh).pack(side="left", padx=(0, 8))
        ctk.CTkButton(actions, text="فتح إدارة الأجهزة", command=lambda: self.main_window._navigate_to("devices"),
                      fg_color=self.colors.warning, hover_color=self.colors.warning_hover).pack(side="left")
        ctk.CTkButton(actions, text="إلغاء ثقة كل الأجهزة", command=self.revoke_all,
                      fg_color=self.colors.error, hover_color=self.colors.error_hover).pack(side="left", padx=(8, 0))

        self.advice = ctk.CTkTextbox(self, height=170, corner_radius=10)
        self.advice.pack(fill="x", pady=(10, 0))
        self.advice.configure(state="disabled")

    def refresh(self):
        try:
            data = self.service.get_security_status()
        except Exception as exc:
            data = {"severity": "warning", "error": str(exc), "active_connections": 0,
                    "recent_failed_attempts": 0, "blocked_sources": 0}
        severity = data.get("severity", "normal")
        if severity == "critical":
            title = "⚠ اشتباه أمني مرتفع — تم حظر مصدر اتصال"
            color = self.colors.error
            text = "تم إيقاف قبول الطلبات من مصدر بسبب محاولات دخول فاشلة متكررة. لا تعتبر هذه الرسالة إثبات اختراق، لكنها تتطلب مراجعة فورية."
            advice = "الإجراءات المقترحة:\n1) أوقف الخدمة مؤقتًا.\n2) ألغِ ثقة أي جهاز غير معروف من شاشة الأجهزة.\n3) أعد الاقتران بعد التأكد من شبكة المنزل.\n4) لا توافق على طلبات غير متوقعة."
        elif severity == "warning":
            title = "تنبيه أمني — راجع الاتصالات"
            color = self.colors.warning
            text = "هناك جلسات متعددة أو محاولات فاشلة حديثة. الاتصال الطبيعي المتوقع هو اتصال هاتف واحد مع الكمبيوتر."
            advice = "راجع اسم الجهاز وآخر ظهور ومستوى الثقة. إذا لم تتعرف على الجهاز، ألغِ ثقته وأعد الاقتران."
        else:
            title = "✓ الاتصال الثنائي طبيعي"
            color = self.colors.success
            text = "لم تظهر مؤشرات غير طبيعية في المراقبة الحالية. لا يوجد دليل على دخول طرف ثالث الآن."
            advice = "استمر في استخدام الاقتران الموثوق، ولا تمنح مستوى ثقة كاملًا إلا لجهاز تملكه وتتحكم فيه."
        self.alert.configure(fg_color=color)
        self.alert_title.configure(text=title, text_color=self.colors.on_accent)
        self.alert_text.configure(text=text, text_color=self.colors.on_accent)
        self.stats.configure(text=(f"جلسات الاتصال الحالية: {data.get('active_connections', 0)}\n"
                                   f"محاولات فاشلة خلال آخر نافذة: {data.get('recent_failed_attempts', 0)}\n"
                                   f"مصادر محظورة مؤقتًا: {data.get('blocked_sources', 0)}"))
        self.advice.configure(state="normal")
        self.advice.delete("1.0", "end")
        self.advice.insert("1.0", advice)
        self.advice.configure(state="disabled")

    def revoke_all(self):
        if not messagebox.askyesno(
            "تأكيد إجراء الطوارئ",
            "سيتم إلغاء ثقة كل الأجهزة المتصلة، وستحتاج لإعادة الاقتران يدويًا. هل تريد المتابعة؟",
        ):
            return
        try:
            count = self.service.revoke_all_devices()
            messagebox.showinfo("تم", f"تم إلغاء ثقة {count} جهاز.")
            self.refresh()
        except Exception as exc:
            messagebox.showerror("تعذر تنفيذ الإجراء", str(exc))

    def on_show(self):
        self.refresh()

    def on_refresh_tick(self):
        self.refresh()
