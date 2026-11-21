"""
DeskTwin Dashboard View
-------------------------
الشاشة الأولى اللي المستخدم بيشوفها: نظرة سريعة على كل حاجة - حالة الخدمة،
عدد الملفات المفهرسة، آخر نشاط، كود الاقتران السريع.
"""

import customtkinter as ctk
from gui.i18n import t


class DashboardView(ctk.CTkFrame):
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.service = main_window.service
        self.colors = main_window.colors

        self._pairing_countdown_job = None
        self._pairing_reveal_job = None
        self._service_refresh_job = None
        self._current_pairing_code = None

        self._build_ui()

    def _build_ui(self):
        copy = lambda key: t(key, self.main_window.app_settings.language)
        ctk.CTkLabel(
            self, text=copy("overview"), font=ctk.CTkFont(size=22, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 20))

        # بطاقة البداية: القرار الأهم للمستخدم (هل الهاتف يقدر يتصل؟)
        # يظهر مباشرة بدل ما يدور على الحالة بين الأزرار الصغيرة.
        self.service_card = ctk.CTkFrame(
            self, fg_color=self.colors.bg_card, corner_radius=18,
            border_width=1, border_color=self.colors.glass_border
        )
        self.service_card.pack(fill="x", pady=(0, 18))
        service_inner = ctk.CTkFrame(self.service_card, fg_color="transparent")
        service_inner.pack(fill="x", padx=20, pady=18)

        self.service_state_dot = ctk.CTkLabel(
            service_inner, text="●", font=ctk.CTkFont(size=22), text_color=self.colors.offline_dot
        )
        self.service_state_dot.pack(side="left", padx=(0, 12))
        service_copy = ctk.CTkFrame(service_inner, fg_color="transparent")
        service_copy.pack(side="left", fill="x", expand=True)
        self.service_title_label = ctk.CTkLabel(
            service_copy, text="", font=ctk.CTkFont(size=16, weight="bold"),
            text_color=self.colors.text_primary, anchor="w"
        )
        self.service_title_label.pack(fill="x")
        self.service_hint_label = ctk.CTkLabel(
            service_copy, text="", font=ctk.CTkFont(size=12),
            text_color=self.colors.text_secondary, anchor="w"
        )
        self.service_hint_label.pack(fill="x", pady=(4, 0))
        self.service_action_button = ctk.CTkButton(
            service_inner, text="", width=126, height=36,
            command=self._on_service_action
        )
        self.service_action_button.pack(side="right")
        self._refresh_service_card()

        startup_warnings = getattr(self.main_window, "startup_warnings", [])
        if startup_warnings:
            warning_card = ctk.CTkFrame(
                self, fg_color=self.colors.warning, corner_radius=12,
                border_width=1, border_color=self.colors.warning_hover
            )
            warning_card.pack(fill="x", pady=(0, 16))
            ctk.CTkLabel(
                warning_card,
                text=f"⚠ تم العثور على {len(startup_warnings)} تحذير عند بدء التشغيل",
                text_color=self.colors.text_primary,
                font=ctk.CTkFont(size=13, weight="bold")
            ).pack(anchor="w", padx=16, pady=(10, 2))
            ctk.CTkLabel(
                warning_card,
                text="راجع سجل التشخيص قبل تنفيذ عمليات نقل أو مزامنة.",
                text_color=self.colors.text_primary,
                font=ctk.CTkFont(size=11)
            ).pack(anchor="w", padx=16, pady=(0, 10))

        # صف بطاقات الإحصائيات
        stats_row = ctk.CTkFrame(self, fg_color="transparent")
        stats_row.pack(fill="x", pady=(0, 20))

        self.files_count_card = self._stat_card(stats_row, copy("indexed_files"), "0")
        self.files_count_card.pack(side="left", padx=(0, 12), fill="x", expand=True)

        self.folders_count_card = self._stat_card(stats_row, copy("watched_folders"), "0")
        self.folders_count_card.pack(side="left", padx=(0, 12), fill="x", expand=True)

        self.devices_count_card = self._stat_card(stats_row, copy("trusted_devices"), "0")
        self.devices_count_card.pack(side="left", fill="x", expand=True)

        # قسم كود الاقتران السريع
        ctk.CTkLabel(
            self, text=copy("quick_pairing"), font=ctk.CTkFont(size=15, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(10, 8))

        pairing_card = ctk.CTkFrame(
            self, fg_color=self.colors.bg_card, corner_radius=16,
            border_width=1, border_color=self.colors.glass_border
        )
        pairing_card.pack(fill="x", pady=(0, 20))

        pairing_inner = ctk.CTkFrame(pairing_card, fg_color="transparent")
        pairing_inner.pack(fill="x", padx=20, pady=18)

        qr_column = ctk.CTkFrame(pairing_inner, fg_color="transparent")
        qr_column.pack(side="left", padx=(0, 18))
        self.pairing_qr_label = ctk.CTkLabel(
            qr_column, text="QR", width=200, height=200,
            corner_radius=12, fg_color=self.colors.bg_secondary,
            text_color=self.colors.text_disabled, font=ctk.CTkFont(size=13, weight="bold")
        )
        self.pairing_qr_label.pack()
        ctk.CTkLabel(
            qr_column, text=copy("scan_pairing_qr"), text_color=self.colors.text_secondary,
            font=ctk.CTkFont(size=10)
        ).pack(pady=(6, 0))

        self.pairing_code_label = ctk.CTkLabel(
            pairing_inner, text="------", font=ctk.CTkFont(size=34, weight="bold"),
            text_color=self.colors.accent
        )
        self.pairing_code_label.pack(side="left")

        right_col = ctk.CTkFrame(pairing_inner, fg_color="transparent")
        right_col.pack(side="right")

        self.pairing_timer_label = ctk.CTkLabel(right_col, text="", text_color=self.colors.text_secondary)
        self.pairing_timer_label.pack()

        self.generate_code_button = ctk.CTkButton(
            right_col, text=copy("generate_code"), command=self._on_generate_code,
            height=32, width=150, fg_color=self.colors.accent, hover_color=self.colors.accent_hover
        )
        self.generate_code_button.pack(pady=(8, 0))

        ctk.CTkLabel(
            self, text=copy("details_hint"),
            text_color=self.colors.text_secondary
        ).pack(anchor="w", pady=(18, 8))

    def _stat_card(self, parent, label: str, value: str) -> ctk.CTkFrame:
        card = ctk.CTkFrame(
            parent, fg_color=self.colors.bg_card, corner_radius=16, height=90,
            border_width=1, border_color=self.colors.glass_border
        )
        card.pack_propagate(False)

        value_label = ctk.CTkLabel(card, text=value, font=ctk.CTkFont(size=28, weight="bold"), text_color=self.colors.accent)
        value_label.pack(anchor="w", padx=18, pady=(16, 0))

        ctk.CTkLabel(card, text=label, font=ctk.CTkFont(size=12), text_color=self.colors.text_secondary).pack(anchor="w", padx=18)

        card.value_label = value_label  # نخزن مرجع عشان نحدّثه لاحقًا
        return card

    def _on_generate_code(self):
        result = self.service.generate_pairing_code()
        if "error" in result:
            self._clear_pairing_credentials(t("qr_start_service", self.main_window.app_settings.language))
            self.pairing_code_label.configure(text=t("pairing_error", self.main_window.app_settings.language))
            return
        self._current_pairing_code = result["code"]
        self.pairing_code_label.configure(text=result["code"])
        self._render_pairing_qr(result["code"], result.get("qr_payload"))
        self._play_pairing_reveal()
        self._start_countdown(result["valid_for_minutes"] * 60)

    def _clear_pairing_credentials(self, qr_message: str | None = None):
        """يلغي بيانات اقتران صارت غير قابلة للاستعمال ولا يترك QR مضللاً."""
        if qr_message is None:
            qr_message = t("qr_expired", self.main_window.app_settings.language)
        if self._pairing_countdown_job:
            self.after_cancel(self._pairing_countdown_job)
            self._pairing_countdown_job = None
        if self._pairing_reveal_job:
            self.after_cancel(self._pairing_reveal_job)
            self._pairing_reveal_job = None
        self._current_pairing_code = None
        if not hasattr(self, "pairing_qr_label"):
            return
        self.pairing_code_label.configure(text="------")
        self.pairing_qr_label.configure(image=None, text=qr_message)
        self._pairing_qr_image = None
        self.pairing_timer_label.configure(text="")

    def _render_pairing_qr(self, code: str, payload: str | None = None):
        """يشفر QR الكود وعناوين الكمبيوتر المحلية لتجاوز اعتماد الاقتران على mDNS."""
        try:
            import qrcode
            qr_data = payload or self.service.pairing_qr_payload(code)
            image = qrcode.make(qr_data).convert("RGB").resize((216, 216))
            self._pairing_qr_image = ctk.CTkImage(light_image=image, dark_image=image, size=(200, 200))
            self.pairing_qr_label.configure(image=self._pairing_qr_image, text="")
        except Exception:
            # الاقتران بالكود لا يتأثر لو مكتبة QR لسه لم تُثبت.
            self.pairing_qr_label.configure(
                image=None,
                text=t("qr_unavailable", self.main_window.app_settings.language)
            )

    def _play_pairing_reveal(self):
        """إشارة قصيرة تؤكد أن الكود اتجدد، مع احترام إعداد تقليل الحركة."""
        if self.main_window.app_settings.reduce_motion:
            return
        if self._pairing_reveal_job:
            self.after_cancel(self._pairing_reveal_job)

        frames = (
            (38, self.colors.glow_cyan),
            (36, self.colors.accent),
            (35, self.colors.glow_cyan),
            (34, self.colors.accent),
        )

        def show_frame(index=0):
            if index >= len(frames):
                self.pairing_code_label.configure(
                    font=ctk.CTkFont(size=34, weight="bold"),
                    text_color=self.colors.accent,
                )
                self._pairing_reveal_job = None
                return
            size, color = frames[index]
            self.pairing_code_label.configure(
                font=ctk.CTkFont(size=size, weight="bold"),
                text_color=color,
            )
            self._pairing_reveal_job = self.after(55, lambda: show_frame(index + 1))

        show_frame()

    def _start_countdown(self, seconds_left: int):
        if self._pairing_countdown_job:
            self.after_cancel(self._pairing_countdown_job)

        def tick(remaining):
            if remaining <= 0:
                self.pairing_timer_label.configure(text=t("expired", self.main_window.app_settings.language))
                self._clear_pairing_credentials()
                self.pairing_timer_label.configure(text=t("expired", self.main_window.app_settings.language))
                return
            mins, secs = divmod(remaining, 60)
            self.pairing_timer_label.configure(text=t("valid_for", self.main_window.app_settings.language) + " " + str(mins) + ":" + str(secs).zfill(2))
            self._pairing_countdown_job = self.after(1000, tick, remaining - 1)

        tick(seconds_left)

    def _refresh_service_card(self):
        copy = lambda key: t(key, self.main_window.app_settings.language)
        running = self.service.is_running
        self.service_state_dot.configure(text_color=self.colors.online_dot if running else self.colors.offline_dot)
        self.service_title_label.configure(text=copy("service_ready" if running else "service_offline"))
        self.service_hint_label.configure(text=copy("service_ready_hint" if running else "service_offline_hint"))
        self.service_action_button.configure(
            text=copy("stop_service" if running else "start_service"),
            fg_color=self.colors.error if running else self.colors.accent,
            hover_color=self.colors.error_hover if running else self.colors.accent_hover,
            state="disabled" if self.service.is_starting else "normal",
        )
        if not running:
            self._clear_pairing_credentials(t("qr_service_stopped", self.main_window.app_settings.language))

    def _on_service_action(self):
        self.main_window._on_toggle_service()
        # الإيقاف فوري، أما التشغيل فقد يحتاج وقتًا للفهرسة؛ التحديث التالي
        # يعكس الحالتين من مصدر الخدمة بدل افتراض نجاح التشغيل.
        self._service_refresh_job = self.after(250, self._refresh_service_card)

    # ---------- Callbacks من النافذة الرئيسية ----------

    def on_show(self):
        self._restore_active_pairing_code()
        self.on_refresh_tick()

    def _restore_active_pairing_code(self):
        code = self.service.get_active_pairing_code()
        if not code or code == getattr(self, "_current_pairing_code", None):
            return
        self._current_pairing_code = code
        self.pairing_code_label.configure(text=code)
        self._render_pairing_qr(code)
        self._start_countdown(self.service.get_active_pairing_seconds())

    def destroy(self):
        for job_name in ("_pairing_countdown_job", "_pairing_reveal_job", "_service_refresh_job"):
            job = getattr(self, job_name, None)
            if job:
                try:
                    self.after_cancel(job)
                except Exception:
                    pass
                setattr(self, job_name, None)
        super().destroy()

    def on_refresh_tick(self):
        self._refresh_service_card()
        self.folders_count_card.value_label.configure(text=str(len(self.service.get_targets())))

        if self.service.db:
            self.files_count_card.value_label.configure(text=str(self.service.get_file_count()))
            devices = self.service.list_trusted_devices()
            active_devices = [d for d in devices if not d.get("revoked")]
            self.devices_count_card.value_label.configure(text=str(len(active_devices)))

