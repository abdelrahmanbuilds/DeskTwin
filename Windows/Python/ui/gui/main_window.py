"""
DeskTwin Main Window
----------------------
الهيكل العام للواجهة الجديدة: Sidebar على اليسار للتنقل بين الشاشات،
ومنطقة محتوى على اليمين بتتغير حسب الشاشة المختارة.
"""

import os
import sys
import threading

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, PROJECT_ROOT)
sys.path.insert(0, os.path.join(PROJECT_ROOT, "Python", "service"))

import customtkinter as ctk
from tkinter import messagebox

from Python.core.desktwin_service import DeskTwinService
from config.app_settings import AppSettings
from gui.theme import get_theme
from gui.i18n import t

from gui.dashboard_view import DashboardView
from gui.folders_view import FoldersView
from gui.devices_view import DevicesView
from gui.approvals_view import ApprovalsView
from gui.activity_view import ActivityView
from gui.settings_view import SettingsView
from gui.security_view import SecurityView
from gui.chat_view import ChatView
from gui.integration_health_view import IntegrationHealthView
from gui.phone_files_view import PhoneFilesView
from gui.intro_overlay import DeskTwinIntroOverlay
from Python.core.diagnostics import initialize as initialize_diagnostics, record as diagnostic_record, record_exception as diagnostic_exception, run_preflight

REFRESH_INTERVAL_MS = 5000

NAV_ITEMS = [
    ("dashboard", "dashboard", "🏠"),
    ("folders", "folders", "📁"),
    ("devices", "devices", "📱"),
    ("approvals", "approvals", "✅"),
    ("activity", "activity", "📊"),
    ("settings", "settings", "⚙️"),
    ("security", "security", "🛡️"),
    ("chat", "chat", "💬"),
    ("integration", "integration", "🔧"),
    ("phone_files", "phone_files", "📱"),
]


class DeskTwinMainWindow(ctk.CTk):
    def __init__(self, initial_view=None):
        super().__init__()
        initialize_diagnostics()
        diagnostic_record("ui", "window_opened")
        self.startup_warnings = run_preflight()
        self._initial_view = initial_view

        self.app_settings = AppSettings.load()
        ctk.set_appearance_mode(self.app_settings.theme_mode)
        self.colors = get_theme(self.app_settings.theme_mode)
        self.t = lambda key: t(key, self.app_settings.language)

        self.service = DeskTwinService(on_log=self._on_log)

        self.title("DeskTwin")
        self.geometry(str(self.app_settings.window_width) + "x" + str(self.app_settings.window_height))
        self.minsize(880, 600)

        self._refresh_job = None
        self._service_start_requested = False
        self._log_subscribers = []

        # Navigation Stack: "dashboard" هي نقطة الجذر دايمًا. أي شاشة تانية
        # بتتفتح بترصّ فوقها هنا، وزرار الرجوع بيرجع خطوة واحدة في المكدس
        # ده - مش بيقفل التطبيق ولا يودي لمكان عشوائي.
        self._nav_stack = ["dashboard"]

        self._build_layout()
        self._restore_last_view()
        self._intro_overlay = None
        if self.app_settings.intro_mode == "auto" and self.app_settings.custom_intro_video:
            self.after(80, self._show_intro_overlay)

        self.protocol("WM_DELETE_WINDOW", self._on_close)

        # تشغيل تلقائي: فتح البرنامج لوحده (بدون ضغطة زرار يدوية) لازم يشغّل
        # الخدمة فعليًا - وإلا الموبايل بيشوف الكمبيوتر "أوف لاين" رغم إن
        # المستخدم فاتح البرنامج فعلاً، لأن الـ API ومعلن الشبكة (mDNS) كانوا
        # بيفضلوا واقفين لحد ما حد يضغط "ابدأ التشغيل" بإيده كل مرة.
        # الشرط self.service.get_targets() عشان مفيش معنى نحاول نبدأ خدمة
        # مفيهاش أي مجلد متابع أصلاً - هيفضل زي ما كان (المستخدم لازم يضيف
        # مجلد الأول من شاشة المجلدات).
        if self.app_settings.auto_start_service and self.service.get_targets():
            self.after(150, self._start_service_flow)

    def _show_intro_overlay(self, force=False):
        if (self._intro_overlay is not None or self.app_settings.intro_mode == "off"
                or not self.app_settings.custom_intro_video):
            return
        if not force and self.app_settings.intro_mode != "auto":
            return
        try:
            custom_video = self.app_settings.custom_intro_video
            self._intro_overlay = DeskTwinIntroOverlay(self, self._finish_intro_overlay, custom_video or None)
            self._intro_overlay.place(relx=0, rely=0, relwidth=1, relheight=1)
            self._intro_overlay.lift()
        except Exception as error:
            self._intro_overlay = None
            diagnostic_exception("ui", error, action="play_intro_video")
            if force:
                if isinstance(error, ModuleNotFoundError) and error.name == "imageio_ffmpeg":
                    details = "ثبّت متطلبات Python GUI من Python/dependencies/requirements-python-gui.txt ثم أعد تشغيل التطبيق."
                else:
                    details = str(error)
                messagebox.showerror("تعذر تشغيل فيديو المقدمة", details)

    def play_intro(self):
        self._show_intro_overlay(force=True)

    def _finish_intro_overlay(self):
        overlay = self._intro_overlay
        self._intro_overlay = None
        if overlay is not None:
            try:
                overlay.destroy()
            except Exception:
                pass

    def _restore_last_view(self):
        """
        أول ما البرنامج يفتح، نرجع لآخر شاشة كان فيها المستخدم - لكن
        الجذر يفضل dashboard دايمًا.

        لو القيمة المحفوظة في app_settings.json بقت مش موجودة ضمن شاشات
        NAV_ITEMS الحالية (مثلاً اسم شاشة اتشال أو اتغيّر في إصدار جديد)
        - بنتجاهلها ونرجع للـ dashboard بدل ما نحاول نعرض شاشة مش موجودة
        أصلاً في self.views (اللي كان هيسيب النافذة فاضية بدون أي خطأ واضح).
        """
        last = getattr(self, "_initial_view", None) or self.app_settings.last_active_view
        if last and last != "dashboard" and last in self.views:
            self._nav_stack = ["dashboard", last]
        self._render_current_view()

    def _build_layout(self):
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)

        self.sidebar = ctk.CTkFrame(self, width=200, fg_color=self.colors.bg_sidebar, corner_radius=0)
        self.sidebar.grid(row=0, column=0, sticky="nsw")
        self.sidebar.grid_propagate(False)

        self._build_sidebar_content()

        self.content_area = ctk.CTkFrame(self, fg_color=self.colors.bg_primary, corner_radius=0)
        self.content_area.grid(row=0, column=1, sticky="nsew")

        self.views = {
            "dashboard": DashboardView(self.content_area, self),
            "folders": FoldersView(self.content_area, self),
            "devices": DevicesView(self.content_area, self),
            "approvals": ApprovalsView(self.content_area, self),
            "activity": ActivityView(self.content_area, self),
            "settings": SettingsView(self.content_area, self),
            "security": SecurityView(self.content_area, self),
            "chat": ChatView(self.content_area, self),
            "integration": IntegrationHealthView(self.content_area, self),
            "phone_files": PhoneFilesView(self.content_area, self),
        }

    def _build_sidebar_content(self):
        logo_frame = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        logo_frame.pack(fill="x", padx=20, pady=(24, 16))

        ctk.CTkLabel(
            logo_frame, text="DeskTwin", font=ctk.CTkFont(size=20, weight="bold"),
            text_color=self.colors.sidebar_text
        ).pack(anchor="w")

        # حالة الخدمة داخل شارة صغيرة بدل نص عائم؛ أوضح أثناء نظرة سريعة.
        self.status_pill = ctk.CTkFrame(
            logo_frame,
            fg_color=self.colors.sidebar_hover,
            corner_radius=10,
            border_width=1,
            border_color=self.colors.glass_border,
        )
        self.status_pill.pack(anchor="w", pady=(6, 0), ipadx=8, ipady=1)
        self.status_dot_label = ctk.CTkLabel(self.status_pill, text="●", text_color=self.colors.offline_dot, font=ctk.CTkFont(size=12))
        self.status_dot_label.pack(side="left")
        self.status_text_label = ctk.CTkLabel(self.status_pill, text=" " + self.t("stopped"), text_color=self.colors.sidebar_muted, font=ctk.CTkFont(size=11))
        self.status_text_label.pack(side="left")

        # زرار الرجوع - بيظهر بس لما يكون فيه شاشة سابقة نرجعلها في المكدس
        self.back_button = ctk.CTkButton(
            self.sidebar, text="←  " + self.t("back"), anchor="w",
            fg_color="transparent", hover_color=self.colors.sidebar_hover,
            text_color=self.colors.sidebar_muted, font=ctk.CTkFont(size=13),
            height=34, corner_radius=8,
            command=self._go_back
        )
        # مش بنعمل pack هنا فورًا - هيتحدد ظهوره/اختفاؤه في _update_back_button_visibility

        self.nav_buttons = {}
        self.nav_badges = {}  # view_id -> CTkLabel، بيتعرض بس لما يكون فيه عدد > 0
        for view_id, label, icon in NAV_ITEMS:
            btn_row = ctk.CTkFrame(self.sidebar, fg_color="transparent")
            btn_row.pack(fill="x", padx=12, pady=2)

            btn = ctk.CTkButton(
                btn_row, text=icon + "  " + self.t(label), anchor="w",
                fg_color="transparent", hover_color=self.colors.sidebar_hover,
                text_color=self.colors.sidebar_text, font=ctk.CTkFont(size=13),
                height=40, corner_radius=12,
                command=lambda v=view_id: self._navigate_to(v)
            )
            btn.pack(side="left", fill="x", expand=True)
            self.nav_buttons[view_id] = btn

            if view_id == "approvals":
                badge = ctk.CTkLabel(
                    btn_row, text="", width=22, height=22, corner_radius=11,
                    fg_color=self.colors.error, text_color=self.colors.on_accent,
                    font=ctk.CTkFont(size=11, weight="bold")
                )
                # مش بنعمل pack هنا - هيظهر بس لما العدد > 0 (في update_approvals_badge)
                self.nav_badges["approvals"] = badge

        bottom_frame = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        bottom_frame.pack(side="bottom", fill="x", padx=12, pady=20)

        self.start_stop_button = ctk.CTkButton(
            bottom_frame, text="▶  " + self.t("start_service"), command=self._on_toggle_service,
            height=42, corner_radius=12, border_width=1,
            border_color=self.colors.glass_border,
            fg_color=self.colors.accent, hover_color=self.colors.accent_hover
        )
        self.start_stop_button.pack(fill="x")

        self._last_known_approvals_count = 0

    def _navigate_to(self, view_id: str):
        """
        بيتنادى من زراير الـ Sidebar. لو المستخدم دوس على شاشة هو أصلاً
        فيها، مفيش تكرار في المكدس. لو دوس على "الرئيسية"، المكدس بيرجع
        للجذر بالكامل (بداية جديدة للتصفح)، بدل ما يتراكم فوق نفسه.

        لو الشاشة المطلوبة موجودة بالفعل في مكان تاني وسط المكدس (مش في
        القمة) - بنشيل كل اللي فوقها وبنرفعها هي لقمة المكدس، بدل ما
        نضيف نسخة جديدة منها. من غير كده، زرار "رجوع" كان ممكن يرجّع
        لنفس الشاشة أكتر من مرة (المكدس بيلف في حلقة بدل ما يوصل
        للجذر مباشرة).
        """
        current = self._nav_stack[-1]
        if view_id == current:
            return

        if view_id == "dashboard":
            self._nav_stack = ["dashboard"]
        elif view_id in self._nav_stack:
            idx = self._nav_stack.index(view_id)
            self._nav_stack = self._nav_stack[:idx + 1]
        else:
            self._nav_stack.append(view_id)

        self._render_current_view()

    def _go_back(self):
        """زرار الرجوع: يرجع خطوة واحدة بس في المكدس. الجذر (dashboard) مفيش رجوع منه."""
        if len(self._nav_stack) > 1:
            self._nav_stack.pop()
            self._render_current_view()

    def _render_current_view(self):
        """
        يعرض الشاشة الحالية في أعلى المكدس، ويحدّث حالة الزراير وزرار الرجوع.

        بنستخدم pack_forget على كل الشاشات الأول (بشكل صريح ومضمون)، وبعدين
        نعرض الشاشة المطلوبة بس - الترتيب ده مهم: لو عكسنا الترتيب (نعرض
        الجديدة الأول ثم نخفي القديمة)، أي استثناء يحصل في on_show() للشاشة
        الجديدة (زي خطأ في استعلام DB) هيوقف التنفيذ قبل ما نوصل لسطر إخفاء
        الشاشة القديمة - فتفضل الاتنين ظاهرين مع بعض (بالظبط عرض "الشاشة
        اتقسمت نصين" اللي ممكن يحصل من غير الحماية دي).
        """
        view_id = self._nav_stack[-1]

        for vid, btn in self.nav_buttons.items():
            active = vid == view_id
            btn.configure(
                fg_color=self.colors.accent if active else "transparent",
                border_width=1 if active else 0,
                # CustomTkinter لا يقبل الشفافية كلون للحدود؛ نستخدم لون
                # خلفية الـsidebar للحالة غير النشطة حتى يظل الحد غير مرئي.
                border_color=self.colors.glass_border if active else self.colors.bg_sidebar
            )

        # الخطوة 1: نخفي كل الشاشات صراحة، بشكل مضمون ومنفصل عن أي منطق تاني
        for view in self.views.values():
            view.pack_forget()

        # الخطوة 2: نعرض الشاشة المطلوبة بس
        target_view = self.views.get(view_id)
        if target_view is not None:
            target_view.pack(fill="both", expand=True, padx=28, pady=24)

        self._update_back_button_visibility()

        self.app_settings.last_active_view = view_id
        self.app_settings.save()

        # الخطوة 3: on_show تتنادى في الآخر وجوه try/except - لو فيها خطأ
        # (زي استعلام DB فشل)، الشاشة تفضل ظاهرة بشكل صحيح على الأقل،
        # حتى لو المحتوى الداخلي بتاعها ماتحدثش
        if target_view is not None and hasattr(target_view, "on_show"):
            try:
                target_view.on_show()
            except Exception as e:
                self._log(f"⚠ خطأ أثناء تحديث شاشة {view_id}: {e}")

    def _update_back_button_visibility(self):
        if len(self._nav_stack) > 1:
            self.back_button.pack(fill="x", padx=12, pady=(0, 8), before=list(self.nav_buttons.values())[0])
        else:
            self.back_button.pack_forget()

    def update_approvals_badge(self, count: int):
        """
        بتتنادى من ApprovalsView كل مرة تتحدث، وكمان بشكل مستقل من
        _poll_approvals_globally عشان الـ badge يفضل صح حتى لو المستخدم
        واقف في شاشة تانية خالص ومفتحش شاشة الموافقات بنفسه.
        """
        badge = self.nav_badges.get("approvals")
        if not badge:
            return

        if count > 0:
            badge.configure(text=str(count) if count < 100 else "99+")
            badge.pack(side="right", padx=(4, 4))
        else:
            badge.pack_forget()

        # صوت تنبيه بس لو العدد زاد عن آخر مرة عرفنا بيها (مش كل refresh)
        if count > self._last_known_approvals_count:
            self.bell()
        self._last_known_approvals_count = count

    def _on_toggle_service(self):
        if self.service.is_running:
            self.service.stop()
            self._update_status_indicator(False)
            self.start_stop_button.configure(text="▶  " + self.t("start_service"))
            self._stop_auto_refresh()
            return

        if not self.service.get_targets():
            messagebox.showwarning("تنبيه", "ضيف مجلد واحد على الأقل من شاشة المجلدات قبل ما تبدأ.")
            self._navigate_to("folders")
            return

        self._start_service_flow()

    def _start_service_flow(self):
        """
        منطق التشغيل الفعلي - منفصل عن _on_toggle_service عشان يتنادى من
        مكانين: ضغطة المستخدم اليدوية على الزرار، أو التشغيل التلقائي عند
        فتح البرنامج (راجع __init__). نفس الفحوصات ونفس شريط التقدّم في
        الحالتين، فمفيش فرق في السلوك غير إن التلقائي مبيستناش ضغطة زرار.
        """
        if self.service.is_running or self.service.is_starting or self._service_start_requested:
            return
        if not self.service.get_targets():
            # التشغيل التلقائي بيتجاهل بصمت لو مفيش targets (اتفحص فعلاً
            # قبل النداء في __init__)، لكن الفحص هنا تاني كحماية لو الدالة
            # دي اتنادت من مكان تاني في المستقبل.
            return

        # الفحص الأولي (scan_all) بيقرا من القرص وبيحسب hash لكل ملف -
        # عملية ممكن تاخد وقت طويل مع مجلدات كبيرة. لو نفّذناها مباشرة هنا
        # على الـ UI thread، الواجهة كاملة هتتجمد لحد ما تخلص من غير أي
        # إشارة للمستخدم. الحل: تشغيلها في Thread منفصل + شريط تقدّم حي.
        self._service_start_requested = True
        self.start_stop_button.configure(state="disabled")
        dashboard = self.views.get("dashboard")
        if dashboard is not None:
            dashboard.service_action_button.configure(state="disabled")
        self._show_startup_progress()

        def on_progress(target_label: str, files_count: int):
            # بتتنادى من Thread تاني (مش الـ UI thread) - لازم self.after()
            # عشان أي تحديث لواجهة tkinter يحصل بأمان على الـ UI thread الأصلي
            self.after(0, lambda: self._update_startup_progress(target_label, files_count))

        def on_complete(success: bool, message: str):
            self.after(0, lambda: self._on_service_start_complete(success, message))

        def run_start():
            self.service.start(on_progress=on_progress, on_complete=on_complete)

        threading.Thread(target=run_start, daemon=True).start()

    def _show_startup_progress(self):
        """
        شاشة تقدّم بسيطة بتظهر فوق كل حاجة أثناء الفحص الأولي - بدل ما
        الواجهة تتجمد من غير أي رسالة، المستخدم بيشوف بالظبط عدد الملفات
        اللي اتفحصت لحد دلوقتي واسم المجلد الحالي.
        """
        self._progress_overlay = ctk.CTkFrame(self, fg_color=self.colors.bg_primary, corner_radius=0)
        self._progress_overlay.place(relx=0, rely=0, relwidth=1, relheight=1)

        inner = ctk.CTkFrame(self._progress_overlay, fg_color="transparent")
        inner.place(relx=0.5, rely=0.5, anchor="center")

        ctk.CTkLabel(
            inner, text=self.t("scan_files"), font=ctk.CTkFont(size=18, weight="bold"),
            text_color=self.colors.text_primary
        ).pack(pady=(0, 12))

        self._progress_status_label = ctk.CTkLabel(
            inner, text=self.t("starting"), text_color=self.colors.text_secondary
        )
        self._progress_status_label.pack(pady=(0, 16))

        progress_bar = ctk.CTkProgressBar(inner, mode="indeterminate", width=280)
        progress_bar.pack()
        progress_bar.start()
        self._progress_bar_widget = progress_bar

    def _update_startup_progress(self, target_label: str, files_count: int):
        if hasattr(self, "_progress_status_label"):
            self._progress_status_label.configure(
                text=f"{target_label} — {files_count} ملف اتفحص لحد دلوقتي"
            )

    def _on_service_start_complete(self, success: bool, message: str):
        self._service_start_requested = False
        if hasattr(self, "_progress_bar_widget"):
            self._progress_bar_widget.stop()
        if hasattr(self, "_progress_overlay"):
            self._progress_overlay.destroy()
            del self._progress_overlay

        self.start_stop_button.configure(state="normal")
        dashboard = self.views.get("dashboard")
        if dashboard is not None:
            dashboard.service_action_button.configure(state="normal")
            dashboard._refresh_service_card()

        if success:
            self._update_status_indicator(True)
            self.start_stop_button.configure(text="■  " + self.t("stop_service"))
            self._start_auto_refresh()
            self._check_incomplete_transfers()
        else:
            messagebox.showerror("فشل التشغيل", message)

    def _update_status_indicator(self, running: bool):
        self.status_dot_label.configure(text_color=self.colors.online_dot if running else self.colors.offline_dot)
        self.status_text_label.configure(
            text=" " + self.t("running" if running else "stopped"),
            text_color=self.colors.sidebar_text if running else self.colors.sidebar_muted,
        )
        self.status_pill.configure(
            border_color=self.colors.online_dot if running else self.colors.glass_border,
        )

    def _check_incomplete_transfers(self):
        """
        بتتنادى مرة واحدة كل ما الخدمة تشتغل بنجاح (يعني كل ما البرنامج
        يتفتح، طالما التشغيل التلقائي شغال) - بتفحص لو فيه ملفات كبيرة
        ناقصة (رفع اتقطع) وتنبّه المستخدم، بالظبط زي ما هو طلب: تحذير
        كل ما يفتح التطبيق لحد ما الملف يكتمل.
        """
        if not self.service.db:
            return
        incomplete = self.service.db.list_incomplete_transfers()
        if not incomplete:
            return

        lines = []
        for t in incomplete[:5]:  # أول 5 بس في الرسالة عشان متطولش لو فيه عدد كبير
            name = os.path.basename(t["file_path"])
            percent = int((t["bytes_received"] / t["total_size_bytes"]) * 100) if t["total_size_bytes"] else 0
            lines.append(f"• {name} — {percent}%")

        extra = f"\n\n(+{len(incomplete) - 5} ملف تاني)" if len(incomplete) > 5 else ""
        messagebox.showwarning(
            "فيه ملفات كبيرة ناقصة",
            "فيه رفع ملفات كبيرة اتقطع قبل ما يكمل:\n\n" + "\n".join(lines) + extra +
            "\n\nافتح التطبيق على التليفون وهو متصل بنفس الشبكة عشان يكمل الرفع تلقائيًا."
        )

    def _start_auto_refresh(self):
        def tick():
            # تحديث الشاشة الظاهرة فقط؛ تحديث كل الشاشات كل ثانيتين كان
            # يكرر استعلامات SQLite وقائمة الأجهزة والأمان بلا داعٍ ويجمد UI.
            view_id = self._nav_stack[-1] if self._nav_stack else "dashboard"
            view = self.views.get(view_id)
            if view is not None and hasattr(view, "on_refresh_tick"):
                try:
                    view.on_refresh_tick()
                except Exception as exc:
                    self._log(f"⚠ فشل تحديث شاشة {view_id}: {exc}")
            self._refresh_job = self.after(REFRESH_INTERVAL_MS, tick)
        tick()

    def _stop_auto_refresh(self):
        if self._refresh_job:
            self.after_cancel(self._refresh_job)
            self._refresh_job = None

    def apply_theme(self, mode: str):
        self.app_settings.theme_mode = mode
        self.app_settings.save()
        ctk.set_appearance_mode(mode)
        self.colors = get_theme(mode)
        self._rebuild_ui()

    def apply_language(self, language: str):
        self.app_settings.language = language
        self.app_settings.save()
        self.t = lambda key: t(key, language)
        self._rebuild_ui()

    def _rebuild_ui(self):
        """Apply appearance-only preferences without restarting the service layer."""
        active_view = self._nav_stack[-1]
        self.sidebar.destroy()
        self.content_area.destroy()
        self._build_layout()
        self._nav_stack = ["dashboard", active_view] if active_view != "dashboard" else ["dashboard"]
        self._render_current_view()

    def _on_log(self, message: str):
        diagnostic_record("service", "log", message=message)
        for callback in self._log_subscribers:
            callback(message)

    def _log(self, message: str):
        """Record UI-side warnings without routing them through service callbacks."""
        diagnostic_record("ui", "log", message=message)

    def subscribe_to_logs(self, callback):
        self._log_subscribers.append(callback)

    def _on_close(self):
        if self._intro_overlay is not None:
            self._intro_overlay.finish()
        if self.service.is_running:
            self.service.stop()
        self.app_settings.window_width = self.winfo_width()
        self.app_settings.window_height = self.winfo_height()
        self.app_settings.save()
        self.destroy()


if __name__ == "__main__":
    app = DeskTwinMainWindow()
    app.mainloop()

