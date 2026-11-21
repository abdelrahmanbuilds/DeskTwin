import asyncio
import threading
from datetime import datetime
import customtkinter as ctk
from tkinter import simpledialog
import base64
from tkinter import filedialog
from gui.i18n import t


class PhoneFilesView(ctk.CTkFrame):
    """Low-redraw desktop view backed by the phone SAF WebSocket bridge."""
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        self.main_window = main_window
        self.colors = main_window.colors
        self.path = ""
        self.entries = []
        self.clipboard = None
        self._showing_trash = False
        self._last_event_revision = 0
        self._load_in_progress = False
        self._pending_load_path = None
        self._last_listing_signature = None
        copy = lambda key: t(key, self.main_window.app_settings.language)
        header = ctk.CTkFrame(self, fg_color=self.colors.bg_card, corner_radius=14)
        header.pack(fill="x", pady=(0, 12))
        ctk.CTkLabel(header, text=copy("phone_files"), font=ctk.CTkFont(size=22, weight="bold"), text_color=self.colors.text_primary).pack(side="left", padx=16, pady=12)
        ctk.CTkButton(header, text=copy("refresh_folder"), width=150, command=lambda: self.load(self.path), fg_color=self.colors.accent, hover_color=self.colors.accent_hover).pack(side="right", padx=12)
        ctk.CTkButton(header, text=copy("paste"), width=65, command=self.paste).pack(side="right", padx=2)
        ctk.CTkButton(header, text=copy("phone_trash"), width=85, command=self.show_trash).pack(side="right", padx=2)
        ctk.CTkButton(header, text=copy("upload_phone"), width=90, command=self.upload_file).pack(side="right", padx=2)
        tools = ctk.CTkFrame(self, fg_color="transparent")
        tools.pack(fill="x", pady=(0, 8))
        self.search_var = ctk.StringVar(value="")
        self.search_var.trace_add("write", lambda *_: self._render_entries())
        self.search_entry = ctk.CTkEntry(
            tools,
            textvariable=self.search_var,
            placeholder_text=copy("search_phone_files"),
            height=36,
        )
        self.search_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.sort_var = ctk.StringVar(value=copy("sort_name"))
        self.sort_menu = ctk.CTkOptionMenu(
            tools,
            variable=self.sort_var,
            values=[copy("sort_name"), copy("sort_modified"), copy("sort_size")],
            command=lambda _value: self._render_entries(),
            width=150,
            height=36,
        )
        self.sort_menu.pack(side="right")
        self.status = ctk.CTkLabel(self, text=copy("choose_phone_folder"), text_color=self.colors.text_secondary)
        self.status.pack(anchor="w", pady=(0, 8))
        self.rows = ctk.CTkScrollableFrame(self, fg_color=self.colors.bg_card, corner_radius=14)
        self.rows.pack(fill="both", expand=True)

    def on_show(self): self.load(self.path)
    def on_refresh_tick(self):
        from sync.phone_fs import event_revision
        revision = event_revision()
        if revision != self._last_event_revision:
            if self.winfo_ismapped(): self.load(self.path)
    def load(self, path=""):
        if self._load_in_progress:
            # Coalesce bursts of filesystem events; the latest requested path wins.
            self._pending_load_path = path
            return
        from sync.phone_fs import event_revision
        listing_revision = event_revision()
        self._load_in_progress = True
        self.status.configure(text=t("reading_phone_files", self.main_window.app_settings.language))
        threading.Thread(target=self._request, args=(path, listing_revision), daemon=True).start()
    def _request(self, path, listing_revision):
        try:
            from sync.phone_fs import request_active
            result = asyncio.run(request_active("list", path))
        except Exception as exc:
            result = {"success": False, "error": str(exc)}
        try:
            self.after(0, lambda: self._finish_request(result, path, listing_revision))
        except RuntimeError:
            # The view was closed while the worker was waiting on the phone.
            pass
    def _finish_request(self, result, path, listing_revision):
        try:
            self._render(result, path)
            if result.get("success"):
                # Only acknowledge events covered by the request's starting snapshot.
                self._last_event_revision = listing_revision
        finally:
            self._load_in_progress = False
            pending_path = self._pending_load_path
            self._pending_load_path = None
            if pending_path is not None:
                self.load(pending_path)
    def _render(self, result, path):
        if not result.get("success"):
            self.status.configure(text=result.get("error", "فشل قراءة الهاتف")); return
        self._showing_trash = False
        self.search_entry.configure(state="normal")
        self.sort_menu.configure(state="normal")
        self.path = path; self.entries = result.get("entries", [])
        signature = (path, tuple((entry.get("path"), entry.get("name"), entry.get("directory"), entry.get("size"), entry.get("modified")) for entry in self.entries))
        self.status.configure(text=t("phone", self.main_window.app_settings.language) + " / " + (path or t("phone_root", self.main_window.app_settings.language)))
        if signature == self._last_listing_signature:
            return
        self._last_listing_signature = signature
        self._render_entries()

    def _render_entries(self):
        if not hasattr(self, "rows") or self._showing_trash:
            return
        for child in self.rows.winfo_children(): child.destroy()
        if self.path:
            ctk.CTkButton(self.rows, text="↑ " + t("up_folder", self.main_window.app_settings.language), anchor="w", command=lambda: self.load(self.path.rsplit('/', 1)[0] if '/' in self.path else "")).pack(fill="x", padx=8, pady=4)
        query = self.search_var.get().strip().casefold()
        def entry_name(item):
            return str(item.get("name") or "")

        entries = [entry for entry in self.entries if query in entry_name(entry).casefold()]
        sort_value = self.sort_var.get()
        if sort_value == t("sort_modified", self.main_window.app_settings.language):
            entries.sort(key=lambda item: (not item.get("directory", False), -(item.get("modified") or 0), entry_name(item).casefold()))
        elif sort_value == t("sort_size", self.main_window.app_settings.language):
            entries.sort(key=lambda item: (not item.get("directory", False), -(item.get("size") or 0), entry_name(item).casefold()))
        else:
            entries.sort(key=lambda item: (not item.get("directory", False), entry_name(item).casefold()))
        if not entries:
            ctk.CTkLabel(
                self.rows,
                text=t("no_matching_phone_files" if query else "empty_phone_folder", self.main_window.app_settings.language),
                text_color=self.colors.text_secondary,
            ).pack(anchor="center", pady=36)
        for entry in entries:
            row = ctk.CTkFrame(self.rows, fg_color=self.colors.bg_secondary, corner_radius=10)
            row.pack(fill="x", padx=8, pady=4)
            icon = "📁" if entry.get("directory") else self._file_icon(entry.get("name", ""))
            ctk.CTkLabel(
                row, text=icon, width=44, height=44, corner_radius=9,
                fg_color=self.colors.bg_card, font=ctk.CTkFont(size=22)
            ).pack(side="left", padx=(10, 8), pady=8)
            details = ctk.CTkFrame(row, fg_color="transparent")
            details.pack(side="left", fill="x", expand=True, padx=(0, 8), pady=7)
            ctk.CTkButton(
                details, text=entry.get("name", ""), anchor="w", height=25,
                fg_color="transparent", hover_color=self.colors.bg_card,
                text_color=self.colors.text_primary, font=ctk.CTkFont(size=13, weight="bold"),
                command=lambda e=entry: self._open(e)
            ).pack(fill="x")
            meta = t("folder", self.main_window.app_settings.language) if entry.get("directory") else self._format_size(entry.get("size", 0))
            if entry.get("modified"):
                meta += "  ·  " + datetime.fromtimestamp(entry["modified"] / 1000).strftime("%Y-%m-%d %H:%M")
            ctk.CTkLabel(
                details, text=meta, anchor="w", text_color=self.colors.text_secondary,
                font=ctk.CTkFont(size=10)
            ).pack(fill="x", padx=8)
            if not entry.get("directory"):
                ctk.CTkButton(row, text=t("open", self.main_window.app_settings.language), width=55, command=lambda e=entry: self.open_file(e)).pack(side="right", padx=2)
                ctk.CTkButton(row, text=t("delete", self.main_window.app_settings.language), width=60, fg_color=self.colors.error, command=lambda e=entry: self._delete(e)).pack(side="right")
            ctk.CTkButton(row, text=t("copy", self.main_window.app_settings.language), width=60, command=lambda e=entry: self.copy(e)).pack(side="right", padx=2)
            ctk.CTkButton(row, text=t("rename", self.main_window.app_settings.language), width=65, command=lambda e=entry: self.rename(e)).pack(side="right", padx=2)

    @staticmethod
    def _format_size(size):
        size = float(size or 0)
        for unit in ("B", "KB", "MB", "GB", "TB"):
            if size < 1024 or unit == "TB":
                return f"{int(size)} {unit}" if unit == "B" else f"{size:.1f} {unit}"
            size /= 1024

    @staticmethod
    def _file_icon(name):
        extension = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        if extension in {"jpg", "jpeg", "png", "gif", "webp", "heic"}: return "🖼️"
        if extension in {"mp4", "mkv", "mov", "avi", "webm"}: return "🎬"
        if extension in {"mp3", "wav", "m4a", "flac", "ogg"}: return "🎵"
        if extension in {"pdf"}: return "📕"
        if extension in {"doc", "docx", "odt", "rtf"}: return "📝"
        if extension in {"xls", "xlsx", "csv"}: return "📊"
        if extension in {"zip", "7z", "rar", "tar", "gz"}: return "🗜️"
        return "📄"
    def _open(self, entry):
        if entry.get("directory"): self.load(entry["path"])
        else: self.status.configure(text="فتح الملفات من الهاتف سيضاف حسب اختيار stream/cache")

    def open_file(self, entry):
        from sync.phone_fs import request_active
        threading.Thread(target=lambda: self._open_async(request_active, entry["path"]), daemon=True).start()

    def _open_async(self, request_fn, path):
        result = asyncio.run(request_fn("open", path))
        if not result.get("success"):
            self.after(0, lambda: self.status.configure(text=result.get("error", "فشل فتح الملف"))); return
        if result.get("mode") == "chunked_required":
            self.after(0, lambda: self.status.configure(text="جاري نقل الملف على أجزاء..."))
            result = asyncio.run(request_fn("download", path))
            if not result.get("success"):
                self.after(0, lambda: self.status.configure(text=result.get("error", "فشل النقل chunked"))); return
            local_path = result.get("local_path")
            if local_path:
                self.after(0, lambda: self._open_local_path(local_path))
            return
        self.after(0, lambda: self._save_open_result(result))

    def _open_local_path(self, path):
        import os
        try:
            os.startfile(path)
            self.status.configure(text="تم فتح نسخة مؤقتة من ملف الهاتف")
        except OSError as exc:
            self.status.configure(text=f"تعذر فتح الملف: {exc}")

    def _save_open_result(self, result):
        target = filedialog.asksaveasfilename(parent=self, initialfile=result.get("name", "phone_file"), title="حفظ نسخة مؤقتة من ملف الهاتف")
        if not target: return
        try:
            with open(target, "wb") as handle: handle.write(base64.b64decode(result["content_base64"]))
            import os; os.startfile(target)
            self.after(0, lambda: self.status.configure(text="تم فتح نسخة من ملف الهاتف"))
        except Exception as exc:
            self.after(0, lambda: self.status.configure(text=f"فشل حفظ الملف: {exc}"))
    def _delete(self, entry):
        from tkinter import messagebox
        if not messagebox.askyesno("تأكيد", "نقل الملف إلى سلة الهاتف؟"): return
        from sync.phone_fs import request_active
        threading.Thread(target=lambda: self._delete_async(request_active, entry["path"]), daemon=True).start()

    def copy(self, entry):
        self.clipboard = entry
        self.status.configure(text="تم نسخ العنصر — افتح المجلد الهدف واضغط لصق")

    def rename(self, entry):
        name = simpledialog.askstring("إعادة تسمية", "الاسم الجديد:", initialvalue=entry.get("name", ""), parent=self)
        if not name or name == entry.get("name"): return
        from sync.phone_fs import request_active
        threading.Thread(target=lambda: self._rename_async(request_active, entry["path"], name), daemon=True).start()

    def _rename_async(self, request_fn, path, name):
        result = asyncio.run(request_fn("rename", path, new_name=name))
        self.after(0, lambda: self.load(self.path) if result.get("success") else self.status.configure(text=result.get("error", "فشل إعادة التسمية")))

    def paste(self):
        if not self.clipboard: return
        from sync.phone_fs import request_active
        source = self.clipboard.get("path", "")
        threading.Thread(target=lambda: self._paste_async(request_active, source), daemon=True).start()

    def _paste_async(self, request_fn, source):
        result = asyncio.run(request_fn("copy", source, destination=self.path))
        self.after(0, lambda: self.load(self.path) if result.get("success") else self.status.configure(text=result.get("error", "فشل اللصق")))

    def upload_file(self):
        path = filedialog.askopenfilename(parent=self, title="اختيار ملف لإرساله للهاتف")
        if not path: return
        import os
        self.status.configure(text="جاري رفع الملف على أجزاء...")
        from sync.phone_fs import upload_active
        threading.Thread(target=lambda: self._upload_async(upload_active, path, os.path.basename(path)), daemon=True).start()

    def _upload_async(self, upload_fn, path, name):
        result = asyncio.run(upload_fn(path, self.path, name))
        self.after(0, lambda: self.load(self.path) if result.get("success") else self.status.configure(text=result.get("error", "فشل رفع الملف")))
    def _delete_async(self, request_fn, path):
        result = asyncio.run(request_fn("delete", path))
        self.after(0, lambda: self.load(self.path) if result.get("success") else self.status.configure(text=result.get("error", "فشل الحذف")))

    def show_trash(self):
        from sync.phone_fs import request_active
        threading.Thread(target=lambda: self._trash_async(request_active), daemon=True).start()

    def _trash_async(self, request_fn):
        result = asyncio.run(request_fn("trash_list"))
        self.after(0, lambda: self._render_trash(result))

    def _render_trash(self, result):
        if not result.get("success"):
            self.status.configure(text=result.get("error", "تعذر قراءة سلة الهاتف")); return
        self._showing_trash = True
        self.search_entry.configure(state="disabled")
        self.sort_menu.configure(state="disabled")
        self._last_listing_signature = None
        for child in self.rows.winfo_children(): child.destroy()
        self.status.configure(text="سلة محذوفات الهاتف")
        for item in result.get("items", []):
            row = ctk.CTkFrame(self.rows, fg_color="transparent"); row.pack(fill="x", padx=8, pady=3)
            ctk.CTkLabel(row, text=item.get("path", ""), text_color=self.colors.text_primary).pack(side="left", fill="x", expand=True)
            ctk.CTkButton(row, text="استعادة", width=70, command=lambda x=item: self._trash_action(x, "restore")).pack(side="right", padx=2)
            ctk.CTkButton(row, text="نهائي", width=60, fg_color=self.colors.error, command=lambda x=item: self._trash_action(x, "trash_delete")).pack(side="right")

    def _trash_action(self, item, operation):
        from sync.phone_fs import request_active
        threading.Thread(target=lambda: self._trash_action_async(request_active, item["trash_id"], operation), daemon=True).start()

    def _trash_action_async(self, request_fn, trash_id, operation):
        result = asyncio.run(request_fn(operation, trash_id=trash_id))
        self.after(0, lambda: self.show_trash() if result.get("success") else self.status.configure(text=result.get("error", "فشلت العملية")))
