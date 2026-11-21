"""
DeskTwin Settings View
------------------------
إعدادات الواجهة (Dark/Light) + معلومات المستخدم المربوط بالجهاز +
سجل النظام الخام (نفس اللي كان معروض في gui.py القديم كـ "سجل النظام").
"""

import customtkinter as ctk
import os
from pathlib import Path
from tkinter import filedialog
from gui.i18n import t


class SettingsView(ctk.CTkFrame):
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.service = main_window.service
        self.colors = main_window.colors

        self._build_ui()

    def _build_ui(self):
        copy = lambda key: t(key, self.main_window.app_settings.language)
        ctk.CTkLabel(
            self, text=copy("settings"), font=ctk.CTkFont(size=22, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 20))

        # --- الشكل البصري ---
        theme_card = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        theme_card.pack(fill="x", pady=(0, 16))

        theme_inner = ctk.CTkFrame(theme_card, fg_color="transparent")
        theme_inner.pack(fill="x", padx=18, pady=16)

        ctk.CTkLabel(
            theme_inner, text=copy("appearance"), font=ctk.CTkFont(size=14, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 10))

        ctk.CTkLabel(theme_inner, text="مقدمة التشغيل", text_color=self.colors.text_secondary).pack(anchor="w")
        self.intro_mode_labels = {
            "تلقائي": "auto", "يدوي": "manual", "إيقاف": "off",
        } if self.main_window.app_settings.language == "ar" else {
            "Automatic": "auto", "Manual": "manual", "Off": "off",
        }
        current_intro_mode = self.main_window.app_settings.intro_mode
        self.intro_mode_var = ctk.StringVar(value=next(
            (label for label, mode in self.intro_mode_labels.items() if mode == current_intro_mode),
            next(iter(self.intro_mode_labels)),
        ))
        self.intro_mode_menu = ctk.CTkOptionMenu(
            theme_inner,
            values=list(self.intro_mode_labels),
            variable=self.intro_mode_var,
            command=self._on_intro_mode_change,
            dynamic_resizing=False,
        )
        self.intro_mode_menu.pack(anchor="w", pady=(4, 8))

        theme_row = ctk.CTkFrame(theme_inner, fg_color="transparent")
        theme_row.pack(anchor="w")

        current_mode = self.main_window.app_settings.theme_mode
        self.theme_var = ctk.StringVar(value=current_mode)

        ctk.CTkRadioButton(
            theme_row, text=copy("dark"), variable=self.theme_var, value="dark",
            command=self._on_theme_change, text_color=self.colors.text_primary
        ).pack(side="left", padx=(0, 20))

        ctk.CTkRadioButton(
            theme_row, text=copy("light"), variable=self.theme_var, value="light",
            command=self._on_theme_change, text_color=self.colors.text_primary
        ).pack(side="left")

        language_row = ctk.CTkFrame(theme_inner, fg_color="transparent")
        language_row.pack(anchor="w", pady=(14, 0))
        ctk.CTkLabel(language_row, text=copy("language"), text_color=self.colors.text_secondary).pack(side="left", padx=(0, 12))
        self.language_var = ctk.StringVar(value=self.main_window.app_settings.language)
        ctk.CTkOptionMenu(language_row, values=["ar", "en"], variable=self.language_var, command=self._on_language_change).pack(side="left")

        motion_row = ctk.CTkFrame(theme_inner, fg_color="transparent")
        motion_row.pack(fill="x", pady=(14, 0))
        ctk.CTkLabel(motion_row, text=copy("reduce_motion"), text_color=self.colors.text_primary).pack(side="left")
        self.reduce_motion_var = ctk.BooleanVar(value=self.main_window.app_settings.reduce_motion)
        ctk.CTkSwitch(motion_row, text="", variable=self.reduce_motion_var, command=self._on_reduce_motion_change).pack(side="right")
        self.play_intro_button = ctk.CTkButton(
            theme_inner, text=copy("play_intro"), command=self.main_window.play_intro,
            height=34, corner_radius=10, fg_color=self.colors.accent,
            hover_color=self.colors.accent_hover,
        )
        self.play_intro_button.pack(anchor="w", pady=(6, 0))
        intro_row = ctk.CTkFrame(theme_inner, fg_color="transparent")
        intro_row.pack(fill="x", pady=(10, 0))
        self.intro_path_label = ctk.CTkLabel(
            intro_row,
            text=Path(self.main_window.app_settings.custom_intro_video).name if self.main_window.app_settings.custom_intro_video else "لم يتم اختيار فيديو",
            text_color=self.colors.text_secondary,
        )
        self.intro_path_label.pack(side="left", padx=(0, 10))
        self.choose_intro_button = ctk.CTkButton(intro_row, text="اختيار فيديو", command=self._choose_intro_video, width=120)
        self.choose_intro_button.pack(side="left", padx=(0, 8))
        self.reset_intro_button = ctk.CTkButton(intro_row, text="إزالة الفيديو", command=self._reset_intro_video, width=140)
        self.reset_intro_button.pack(side="left")
        self._sync_intro_controls()

        # --- حجم سجل النشاط ---
        activity_card = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        activity_card.pack(fill="x", pady=(0, 16))
        activity_inner = ctk.CTkFrame(activity_card, fg_color="transparent")
        activity_inner.pack(fill="x", padx=18, pady=16)
        ctk.CTkLabel(
            activity_inner, text="حجم سجل النشاط", font=ctk.CTkFont(size=14, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 4))
        ctk.CTkLabel(
            activity_inner, text="حدد عدد عمليات الهاتف التي يحتفظ بها الكمبيوتر.",
            text_color=self.colors.text_secondary
        ).pack(anchor="w", pady=(0, 8))
        self._activity_log_options = {str(n): n for n in (50, 100, 200, 300, 1000)}
        current_limit = self.main_window.app_settings.activity_log_limit
        current_label = str(current_limit) if current_limit in self._activity_log_options.values() else "300"
        self.activity_log_var = ctk.StringVar(value=current_label)
        ctk.CTkOptionMenu(
            activity_inner, values=list(self._activity_log_options.keys()),
            variable=self.activity_log_var, command=self._on_activity_log_limit_change
        ).pack(anchor="w")

        command_card = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        command_card.pack(fill="x", pady=(0, 16))
        command_inner = ctk.CTkFrame(command_card, fg_color="transparent")
        command_inner.pack(fill="x", padx=18, pady=16)
        ctk.CTkLabel(command_inner, text="مسار ملفات أوامر CMD / PowerShell",
                     font=ctk.CTkFont(size=14, weight="bold"), text_color=self.colors.text_primary).pack(anchor="w")
        ctk.CTkLabel(command_inner, text="اختياري. لو لم تحدد مسارًا، تُحفظ الملفات على سطح المكتب.",
                     text_color=self.colors.text_secondary).pack(anchor="w", pady=(4, 8))
        self.command_dir_label = ctk.CTkLabel(command_inner, text=self.main_window.app_settings.command_draft_directory or "سطح المكتب (افتراضي)", anchor="w")
        self.command_dir_label.pack(fill="x", pady=(0, 8))
        ctk.CTkButton(command_inner, text="اختيار مجلد", command=self._choose_command_directory).pack(anchor="w")

        # --- الملفات الناقصة (نقل كبير اتقطع) ---
        transfer_card = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        transfer_card.pack(fill="x", pady=(0, 16))

        transfer_inner = ctk.CTkFrame(transfer_card, fg_color="transparent")
        transfer_inner.pack(fill="x", padx=18, pady=16)

        ctk.CTkLabel(
            transfer_inner, text="الملفات الكبيرة الناقصة", font=ctk.CTkFont(size=14, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 4))

        ctk.CTkLabel(
            transfer_inner,
            text="لو رفع ملف كبير (1GB أو أكتر) اتقطع، الجزء الناقص يفضل محفوظ على الكمبيوتر\nللمدة دي، وبعدها يتمسح تلقائيًا لو محدش كمّل الرفع.",
            justify="left", text_color=self.colors.text_secondary, font=ctk.CTkFont(size=12)
        ).pack(anchor="w", pady=(0, 10))

        # الخيارات: من ساعة لأسبوع بالظبط زي ما المستخدم حدد - مش أكتر
        # ولا أقل، عشان الخيار يبقى ضمن النطاق اللي هو قرره صراحة.
        self._retention_options = {
            "ساعة": 1, "6 ساعات": 6, "12 ساعة": 12,
            "يوم": 24, "3 أيام": 72, "أسبوع": 168,
        }
        current_hours = self.main_window.app_settings.incomplete_transfer_retention_hours
        current_label = next(
            (label for label, hours in self._retention_options.items() if hours == current_hours),
            "يوم"
        )
        self.retention_var = ctk.StringVar(value=current_label)

        ctk.CTkOptionMenu(
            transfer_inner, values=list(self._retention_options.keys()),
            variable=self.retention_var, command=self._on_retention_change
        ).pack(anchor="w")

        diagnostics_card = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        diagnostics_card.pack(fill="x", pady=(0, 16))
        diagnostics_inner = ctk.CTkFrame(diagnostics_card, fg_color="transparent")
        diagnostics_inner.pack(fill="x", padx=18, pady=16)
        ctk.CTkLabel(
            diagnostics_inner, text="سجل التشخيص", font=ctk.CTkFont(size=14, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 4))
        ctk.CTkLabel(
            diagnostics_inner, text="افتح ملفات الأحداث والتحذيرات الأخيرة عند حدوث مشكلة.",
            text_color=self.colors.text_secondary
        ).pack(anchor="w", pady=(0, 10))
        ctk.CTkButton(
            diagnostics_inner, text="فتح مجلد سجل التشخيص",
            command=self._open_diagnostics_folder
        ).pack(anchor="w")

        # --- المستخدم المربوط ---
        user_card = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=12)
        user_card.pack(fill="x", pady=(0, 16))

        user_inner = ctk.CTkFrame(user_card, fg_color="transparent")
        user_inner.pack(fill="x", padx=18, pady=16)

        ctk.CTkLabel(
            user_inner, text="المستخدم المرتبط بالكمبيوتر", font=ctk.CTkFont(size=14, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(anchor="w", pady=(0, 8))

        self.user_label = ctk.CTkLabel(
            user_inner, text="لسه محدش اتربط", text_color=self.colors.text_secondary
        )
        self.user_label.pack(anchor="w")


    def _on_theme_change(self):
        self.main_window.apply_theme(self.theme_var.get())

    def _on_intro_mode_change(self, label: str):
        mode = self.intro_mode_labels[label]
        self.main_window.app_settings.intro_mode = mode
        self.main_window.app_settings.save()
        self._sync_intro_controls()

    def _sync_intro_controls(self):
        selected_mode = self.intro_mode_labels.get(self.intro_mode_var.get(), "auto")
        can_play = selected_mode != "off" and bool(self.main_window.app_settings.custom_intro_video)
        self.play_intro_button.configure(state="normal" if can_play else "disabled")
        self.choose_intro_button.configure(state="normal")
        self.reset_intro_button.configure(state="normal" if self.main_window.app_settings.custom_intro_video else "disabled")

    def _on_language_change(self, language: str):
        self.main_window.apply_language(language)

    def _choose_intro_video(self):
        selected = filedialog.askopenfilename(
            title="اختيار فيديو المقدمة",
            filetypes=[("Video files", "*.mp4 *.mov *.mkv *.avi *.webm *.m4v"), ("All files", "*.*")],
        )
        if selected:
            self.main_window.app_settings.custom_intro_video = selected
            # Selecting a new video opts into the expected default. The user
            # can then switch to Manual or Off without the next launch auto-playing it.
            self.main_window.app_settings.intro_mode = "auto"
            self.main_window.app_settings.save()
            self.intro_mode_var.set(next(
                label for label, mode in self.intro_mode_labels.items() if mode == "auto"
            ))
            self.intro_path_label.configure(text=Path(selected).name)
            self._sync_intro_controls()

    def _reset_intro_video(self):
        self.main_window.app_settings.custom_intro_video = ""
        self.main_window.app_settings.save()
        self.intro_path_label.configure(text="لم يتم اختيار فيديو")
        self._sync_intro_controls()

    def _on_reduce_motion_change(self):
        self.main_window.app_settings.reduce_motion = self.reduce_motion_var.get()
        self.main_window.app_settings.save()

    def _on_retention_change(self, selected_label: str):
        hours = self._retention_options[selected_label]
        self.main_window.app_settings.incomplete_transfer_retention_hours = hours
        self.main_window.app_settings.save()

    def _on_activity_log_limit_change(self, selected_label: str):
        limit = self._activity_log_options[selected_label]
        self.main_window.app_settings.activity_log_limit = limit
        self.main_window.app_settings.save()
        if self.service.db:
            self.service.db.activity_log_limit = limit
            self.service.db.log_activity("system", "activity_log_limit_changed", str(limit), limit=limit)

    def _open_diagnostics_folder(self):
        folder = Path(__file__).resolve().parents[3] / "data" / "logs"
        folder.mkdir(parents=True, exist_ok=True)
        try:
            os.startfile(folder)
        except OSError:
            self.main_window._log(f"مجلد سجل التشخيص: {folder}")

    def _choose_command_directory(self):
        selected = filedialog.askdirectory(title="اختار مكان حفظ مسودات الأوامر")
        if selected:
            self.main_window.app_settings.command_draft_directory = selected
            self.main_window.app_settings.save()
            self.command_dir_label.configure(text=selected)

    def _refresh_user_info(self):
        if self.service.db:
            user = self.service.db.get_bound_user()
            if user:
                self.user_label.configure(text=user["user_name"] + " (من " + user["bound_at"][:10] + ")")
            else:
                self.user_label.configure(text="لسه محدش اتربط")

    def on_show(self):
        self._refresh_user_info()

    def on_refresh_tick(self):
        self._refresh_user_info()
