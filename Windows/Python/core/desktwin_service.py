"""
DeskTwin Service Controller
-----------------------------
طبقة تحكم واحدة تدير كل مكونات DeskTwin (Scanner, Watcher, Discovery, API)
جوه نفس البرنامج - مصممة عشان الواجهة الرسومية (gui.py) تقدر تشغّلها/توقفها
بزرار واحد، بدل ما المستخدم يفتح 3 نوافذ Terminal يدويًا.

كل نشاط بيتسجل عبر callback (on_log) عشان الواجهة تقدر تعرضه لايف.
"""

import os
import json
import sys
import socket
import ipaddress
import threading
import time
from urllib.parse import urlencode

PROJECT_ROOT = os.path.abspath(os.environ.get("DESKTWIN_ROOT") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, PROJECT_ROOT)
sys.path.insert(0, os.path.join(PROJECT_ROOT, "Python", "service"))

from config.settings import DeskTwinConfig
from database.db import DeskTwinDB
from scanner.scanner import scan_all
from watcher.watcher import start_watching
from discovery.discovery import DeskTwinAdvertiser
from auth.pairing import PairingManager

import uvicorn
from Python.core.diagnostics import operation_id as diagnostic_operation_id
from Python.core.diagnostics import record as diagnostic_record
from Python.core.diagnostics import record_exception as diagnostic_exception
from security.tls import ensure_certificate


class DeskTwinService:
    """
    الحالة المركزية لكل حاجة شغالة. الواجهة (gui.py) بتتعامل مع الكلاس ده بس،
    مش محتاجة تعرف تفاصيل Scanner/Watcher/API الداخلية.
    """

    def __init__(self, on_log=None):
        self.on_log = on_log or (lambda msg: None)
        self.config: DeskTwinConfig = DeskTwinConfig.load()
        self.db: DeskTwinDB = None
        self.pairing: PairingManager = None

        self.observer = None
        self.advertiser = None
        self.api_server = None
        self.api_thread = None
        self.api_start_error = None

        self.is_running = False
        self.is_starting = False  # true أثناء الفحص الأولي - الواجهة تعتمد عليها لعرض شريط تقدّم بدل التجمّد
        self._verification_thread = None
        self._verification_stop = threading.Event()
        self._api_stop_requested = threading.Event()
        self._start_lock = threading.Lock()
        self._active_pairing_code = None
        self._active_pairing_expires_at = 0.0

    def _log(self, msg: str):
        self.on_log(msg)

    # ---------- إدارة الـ targets (المجلدات المراقبة) ----------

    def add_target(self, path: str, label: str = "", recursive: bool = True, is_file: bool = False):
        self.config.add_target(path, label=label, recursive=recursive, is_file=is_file)
        self.config.save()
        self._log(f"تمت إضافة {'ملف' if is_file else 'مجلد'}: {label or path}")

    def get_targets(self):
        return self.config.targets

    # ---------- تشغيل / إيقاف الخدمة كاملة ----------

    def start(self, on_progress=None, on_complete=None):
        """
        بتتنفذ بالكامل من Thread مستدعيها - الطبقة اللي فوق (الواجهة) هي
        المسؤولة تشغّل الدالة دي في Thread منفصل (مش على الـ UI thread)،
        عشان فحص مجلدات كبيرة ميجمّدش الواجهة. on_progress بيتنادى مع كل
        ملف بيتفحص (target_label, files_count) - يُستخدم لعرض تقدّم حي.
        on_complete بيتنادى مرة واحدة في الآخر (success: bool, message: str).
        """
        operation = diagnostic_operation_id()
        diagnostic_record("service", "start_requested", operation=operation)
        with self._start_lock:
            if self.is_running:
                start_state = "running"
            elif self.is_starting:
                start_state = "starting"
            else:
                start_state = "idle"
                self.is_starting = True

        if start_state == "running":
            self._log("الخدمة شغالة بالفعل.")
            if on_complete:
                on_complete(True, "الخدمة شغالة بالفعل.")
            return
        if start_state == "starting":
            if on_complete:
                on_complete(False, "جاري تشغيل الخدمة بالفعل.")
            return

        if not self.config.targets:
            with self._start_lock:
                self.is_starting = False
            diagnostic_record("service", "start_rejected", operation=operation, reason="no_targets")
            self._log("⚠ مفيش مجلدات متحددة. ضيف مجلد الأول.")
            if on_complete:
                on_complete(False, "مفيش مجلدات متحددة.")
            return

        completion_success = False
        completion_message = "فشل تشغيل الخدمة."
        try:
            # Fail before scanning folders or starting the watcher when another
            # Python/WinUI instance already owns the shared local API port.
            self._ensure_api_port_available(operation=operation)
            self.db = DeskTwinDB()
            from config.app_settings import AppSettings
            self.db.activity_log_limit = AppSettings.load().activity_log_limit
            self.pairing = PairingManager(self.db)

            self._log("جاري فحص المجلدات...")
            scan_all(self.config, self.db, verbose=False, on_progress=on_progress)
            self._log("✓ الفحص الأولي خلص.")

            self._log("جاري تشغيل المراقبة اللحظية...")
            self.observer = start_watching(self.config, self.db, verbose=False)
            self._log("✓ المراقبة شغالة.")

            self._log("جاري تشغيل الـ API...")
            self._start_api_server()
            diagnostic_record("api", "listening", operation=operation, port=8420)
            self._log("✓ الـ API شغال على البورت 8420.")

            # لا نعلن mDNS قبل نجاح bind واستماع الـ API؛ وإلا قد يظهر
            # الكمبيوتر للهاتف بينما الاتصال بالبورت لم يصبح جاهزًا بعد.
            self._log("جاري الإعلان عن الجهاز على الشبكة...")
            self.advertiser = DeskTwinAdvertiser(port=8420)
            self.advertiser.start()
            diagnostic_record(
                "discovery", "advertised", operation=operation,
                interface_count=len(self.advertiser.local_ips), port=8420,
            )
            self._log(
                f"✓ الجهاز يعلن على {', '.join(self.advertiser.local_ips)}:8420."
            )

            with self._start_lock:
                if (
                    self.api_server is None
                    or self.api_thread is None
                    or not self.api_thread.is_alive()
                    or not self.api_server.started
                ):
                    detail = str(self.api_start_error).strip() if self.api_start_error else ""
                    suffix = f": {detail}" if detail else ""
                    raise RuntimeError(f"توقف API قبل اكتمال تشغيل الخدمة{suffix}")
                self.is_running = True
                self._start_background_verification()
            diagnostic_record("service", "started", operation=operation)
            self._log("● DeskTwin Online — كل حاجة شغالة.")
            completion_success = True
            completion_message = "الخدمة شغالة."
        except Exception as e:
            # أوقف أي مكوّن بدأ جزئيًا؛ محاولة التشغيل التالية يجب ألا
            # تصطدم بمراقب أو API أو إعلان قديم من محاولة فاشلة.
            if self.advertiser:
                try:
                    self.advertiser.stop()
                except Exception:
                    pass
                self.advertiser = None
            if self.observer:
                try:
                    self.observer.stop()
                    self.observer.join(timeout=3)
                except Exception:
                    pass
                self.observer = None
            self._api_stop_requested.set()
            self._verification_stop.set()
            if self._verification_thread and self._verification_thread is not threading.current_thread():
                self._verification_thread.join(timeout=2)
            self._verification_thread = None
            if self.api_server:
                self.api_server.should_exit = True
            if self.api_thread and self.api_thread.is_alive():
                self.api_thread.join(timeout=5)
            self.api_server = None
            self.api_thread = None
            self.is_running = False
            self._active_pairing_code = None
            self._active_pairing_expires_at = 0.0
            diagnostic_exception("service_start", e, operation=operation)
            self._log(f"✗ فشل تشغيل الخدمة: {e}")
            completion_message = str(e)
        finally:
            with self._start_lock:
                self.is_starting = False
            if on_complete:
                on_complete(completion_success, completion_message)

    def stop(self):
        if not self.is_running:
            return

        operation = diagnostic_operation_id()
        diagnostic_record("service", "stop_requested", operation=operation)
        self._log("جاري إيقاف الخدمة...")

        if self.observer:
            self.observer.stop()
            self.observer.join(timeout=3)
            self.observer = None

        if self.advertiser:
            self.advertiser.stop()

        self._api_stop_requested.set()
        if self.api_server:
            self.api_server.should_exit = True
        if self.api_thread and self.api_thread is not threading.current_thread():
            self.api_thread.join(timeout=5)
        self.api_server = None
        self.api_thread = None

        self._verification_stop.set()

        self.is_running = False
        self._active_pairing_code = None
        self._active_pairing_expires_at = 0.0
        diagnostic_record("service", "stopped", operation=operation)
        self._log("○ DeskTwin Offline.")

    def _start_background_verification(self):
        """تحقق منخفض الأولوية دوري؛ يمنع الاعتماد الأبدي على Cache قديم."""
        self._verification_stop.clear()

        def verify_loop():
            while not self._verification_stop.wait(600):
                if not self.is_running or not self.db:
                    continue
                try:
                    # يفحص فقط targets التي علّمها الـ Watcher dirty،
                    # فلا يستهلك الجهاز في إعادة قراءة كل شيء بلا داعٍ.
                    scan_all(self.config, self.db, verbose=False)
                    self._log("✓ تم التحقق الخلفي من التغييرات.")
                except Exception as exc:
                    self._log(f"⚠ تعذر التحقق الخلفي: {exc}")

        self._verification_thread = threading.Thread(target=verify_loop, daemon=True)
        self._verification_thread.start()

    def _start_api_server(self):
        """بيشغل uvicorn جوه thread منفصل داخل نفس البرنامج (مش عملية Python تانية)."""
        import api.server as server_module
        from security.tls import ensure_certificate
        server_module.db = self.db
        server_module.pairing = self.pairing
        server_module.config = self.config

        cert_path, key_path, _ = ensure_certificate()
        config = uvicorn.Config(
            server_module.app, host="0.0.0.0", port=8420, log_level="warning",
            ssl_certfile=cert_path, ssl_keyfile=key_path,
            # Binary chunks stay compact and predictable; media bytes are not
            # worth compressing again by per-message deflate.
            ws_max_size=8 * 1024 * 1024,
            ws_max_queue=4,
            ws_per_message_deflate=False,
            # PyInstaller لا يحتاج إعداد logging الافتراضي الخاص بـ Uvicorn.
            # تركه مفعّلًا قد يفشل في النسخة المجمعة عند تحميل formatter.
            log_config=None,
            use_colors=False
        )
        self.api_server = uvicorn.Server(config)
        api_server = self.api_server

        self.api_start_error = None
        self._api_stop_requested.clear()

        def run_server():
            try:
                api_server.run()
            except BaseException as error:
                self.api_start_error = error
                diagnostic_exception("api_thread", error, port=8420)
            finally:
                if not self._api_stop_requested.is_set() and not api_server.should_exit:
                    self._handle_unexpected_api_exit(api_server)

        self.api_thread = threading.Thread(target=run_server, name="DeskTwinAPI", daemon=True)
        self.api_thread.start()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if api_server.started:
                return
            if not self.api_thread.is_alive():
                detail = str(self.api_start_error).strip() if self.api_start_error else ""
                suffix = f": {detail}" if detail else ""
                raise RuntimeError(f"توقف API قبل بدء الاستماع على البورت 8420{suffix}")
            time.sleep(0.05)
        self._api_stop_requested.set()
        api_server.should_exit = True
        self.api_thread.join(timeout=2)
        raise TimeoutError("لم يبدأ API الاستماع على البورت 8420 خلال 10 ثوانٍ")

    def _handle_unexpected_api_exit(self, api_server):
        """Drop service readiness and stop companion workers if Uvicorn dies."""
        with self._start_lock:
            if (
                self.api_server is not api_server
                or self._api_stop_requested.is_set()
                or api_server.should_exit
            ):
                return
            if self.api_start_error is None:
                self.api_start_error = RuntimeError("خيط API انتهى دون طلب إيقاف")
            self.is_running = False
            self._verification_stop.set()
            self._active_pairing_code = None
            self._active_pairing_expires_at = 0.0
            observer, advertiser = self.observer, self.advertiser
            self.observer = None
            self.advertiser = None

        if observer:
            try:
                observer.stop()
                if observer is not threading.current_thread():
                    observer.join(timeout=3)
            except Exception as error:
                self._log(f"⚠ تعذر إيقاف مراقبة الملفات بعد تعطل API: {error}")
        if advertiser:
            try:
                advertiser.stop()
            except Exception as error:
                self._log(f"⚠ تعذر إزالة إعلان DeskTwin بعد تعطل API: {error}")

        detail = str(self.api_start_error).strip()
        diagnostic_record("service", "api_unexpected_exit", port=8420, detail=detail)
        self._log(f"✗ توقف API بشكل غير متوقع؛ الخدمة لم تعد جاهزة للهاتف. {detail}")

    @staticmethod
    def _ensure_api_port_available(operation=None, host="0.0.0.0", port=8420):
        """Give a useful error instead of a later, opaque Uvicorn thread failure."""
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.bind((host, port))
        except OSError as error:
            if getattr(error, "winerror", None) == 10048 or error.errno in {48, 98, 10048}:
                diagnostic_record("api", "port_conflict", operation=operation, port=port)
                raise RuntimeError(
                    f"منفذ DeskTwin {port} مستخدم بالفعل. غالبًا توجد نسخة Python أو WinUI أخرى تعمل؛ "
                    "أوقف الخدمة من النسخة الأخرى ثم أعد المحاولة."
                ) from error
            diagnostic_exception("api_preflight", error, operation=operation, port=port)
            raise RuntimeError(f"تعذر فحص منفذ DeskTwin {port}: {error}") from error

    # ---------- Pairing ----------

    def generate_pairing_code(self) -> dict:
        if not self.pairing:
            return {"error": "الخدمة مش شغالة"}
        result = self.pairing.generate_pairing_code()
        self._active_pairing_code = result["code"]
        self._active_pairing_expires_at = time.monotonic() + int(result.get("valid_for_minutes", 0)) * 60
        result["qr_payload"] = self.pairing_qr_payload(result["code"])
        # Pairing codes are short-lived credentials. Keep them out of persistent
        # activity/diagnostic logs; the UI already displays the code to the user.
        self._log("تم إنشاء كود اقتران جديد")
        return result

    def pairing_qr_payload(self, code: str) -> str:
        """Create a local pairing QR bound to the TLS certificate identity."""
        if not isinstance(code, str) or len(code) != 6 or not code.isascii() or not code.isdigit():
            raise ValueError("كود الاقتران غير صالح")

        advertiser = self.advertiser
        addresses = getattr(advertiser, "local_ips", ()) if advertiser else ()
        hosts: list[str] = []
        for value in addresses:
            try:
                address = ipaddress.ip_address(str(value))
            except ValueError:
                continue
            if (address.version != 4 or address.is_loopback or address.is_link_local or address.is_unspecified
                    or address.is_multicast or address.is_reserved):
                continue
            hosts.append(str(address))

        # The QR is the out-of-band authentication channel for the first TLS
        # connection. Never let the phone learn the fingerprint from the
        # untrusted connection it is about to authorize.
        _, _, fingerprint = ensure_certificate()
        query = [("v", "2"), ("code", code), ("port", "8420"), ("fp", fingerprint)]
        query.extend(("host", host) for host in dict.fromkeys(hosts[:8]))
        return "desktwin://pair?" + urlencode(query)

    def get_active_pairing_code(self):
        if not self.is_running or time.monotonic() >= self._active_pairing_expires_at:
            self._active_pairing_code = None
            return None
        return self._active_pairing_code

    def get_active_pairing_seconds(self):
        if not self.get_active_pairing_code():
            return 0
        return max(1, int(self._active_pairing_expires_at - time.monotonic()))

    def list_trusted_devices(self):
        if not self.pairing:
            return []
        return self.pairing.list_trusted_devices()

    def revoke_device(self, device_id: int):
        if self.pairing:
            self.pairing.revoke_device(device_id)
            self._log(f"تم إلغاء ثقة الجهاز #{device_id}")

    def delete_revoked_devices(self):
        if not self.pairing:
            raise RuntimeError("الخدمة مش شغالة")
        count = self.pairing.delete_revoked_devices()
        self._log(f"تم حذف {count} سجل جهاز ملغى من السجل")
        return count

    def revoke_all_devices(self):
        """إجراء طوارئ: يلغي كل الأجهزة، ويترك الاقتران الجديد يدويًا."""
        if not self.pairing:
            raise RuntimeError("الخدمة مش شغالة")
        devices = self.pairing.list_trusted_devices()
        count = 0
        for device in devices:
            if not device.get("revoked"):
                self.pairing.revoke_device(device["id"])
                count += 1
        self._log(f"⚠ تم إلغاء ثقة {count} جهازًا من إجراء الأمان")
        return count

    def set_device_trust_level(self, device_id: int, trust_level: str):
        if not self.pairing:
            raise RuntimeError("الخدمة مش شغالة")
        self.pairing.set_trust_level(device_id, trust_level)
        self._log(f"تم تغيير مستوى ثقة الجهاز #{device_id} إلى {trust_level}")

    def get_security_status(self) -> dict:
        """لقطة موحدة للواجهة: الاتصالات ومحاولات الدخول والحظر المؤقت."""
        from security.monitor import monitor
        status = monitor.snapshot()
        status["active_connections"] = 0
        try:
            from api import server as server_module
            status["active_connections"] = server_module.ws_manager.active_count
        except Exception:
            pass
        if status["blocked_sources"]:
            status["severity"] = "critical"
        elif status["recent_failed_attempts"] or status["active_connections"] > 1:
            status["severity"] = "warning"
        else:
            status["severity"] = "normal"
        return status

    # ---------- إحصائيات للعرض ----------

    def get_file_count(self) -> int:
        if not self.db:
            return 0
        with self.db.cursor() as cur:
            cur.execute("SELECT COUNT(*) as c FROM files WHERE sync_state != 'DELETED'")
            return cur.fetchone()["c"]

    def get_recent_changes(self, limit: int = 10):
        if not self.db:
            return []
        with self.db.cursor() as cur:
            cur.execute(
                "SELECT file_path, event_type, detected_at FROM change_log "
                "ORDER BY id DESC LIMIT ?", (limit,)
            )
            return [dict(row) for row in cur.fetchall()]

    # ---------- Pending Approvals (طلبات حساسة من التليفون) ----------

    APPROVAL_EXPIRY_DAYS = 3  # طلب نُسي لأكتر من كده يتحول expired تلقائيًا

    def get_pending_approvals(self):
        if not self.db:
            return []
        # تنظيف الطلبات القديمة المنسية قبل كل عرض - بيمنع تراكمها للأبد
        expired_count = self.db.expire_stale_approvals(self.APPROVAL_EXPIRY_DAYS)
        if expired_count:
            self._log(f"⏱ {expired_count} طلب موافقة قديم انتهت صلاحيته تلقائيًا (نُسي أكتر من {self.APPROVAL_EXPIRY_DAYS} أيام)")
        return self.db.list_pending_approvals()

    def get_pending_approvals_count(self) -> int:
        """عدد سريع للـ badge في الـ Sidebar - بدون تنظيف (يحصل بالفعل عند get_pending_approvals)."""
        if not self.db:
            return 0
        return self.db.count_pending_approvals()

    def approve_request(self, approval_id: int) -> dict:
        """
        بينفذ العملية فعليًا على القرص بعد موافقة المستخدم من واجهة الكمبيوتر.
        بيرجع نتيجة واضحة (نجاح/فشل) عشان الواجهة تقدر تعرضها.
        """
        row = self.db.get_approval(approval_id)
        if row is None:
            return {"success": False, "error": "الطلب مش موجود"}
        if row["status"] != "pending":
            return {"success": False, "error": "الطلب اتحل بالفعل"}

        action = row["action_type"]
        if action == "command_execute":
            # Historical requests are rejected rather than executed. Native
            # controls use the explicit /device/action allowlist instead.
            self.db.resolve_approval(approval_id, approved=False)
            self._log("✗ رُفض طلب shell قديم؛ الأوامر النصية تُحفظ كمسودة فقط")
            return {"success": False, "error": "تنفيذ أوامر shell من الهاتف غير مدعوم"}
        file_path = row["file_path"]
        new_value = row["new_value"]

        # طلبات إضافة target (ADD_TARGET) هي الاستثناء الوحيد من فحص
        # النطاق الآمن: هدف الطلب نفسه هو توسيع النطاق، فمن الطبيعي إن
        # المسار المطلوب لسه مش مسموح وقت الفحص - ده مش خطأ، ده بالظبط
        # سبب وجود الطلب. كل الأنواع التانية (حذف/تسمية/استبدال/ربط)
        # لازم تكون على مسار مسموح بالفعل.
        is_add_target_request = action == "create_file" and new_value and new_value.startswith("ADD_TARGET|")
        is_command_draft = action == "command_draft"

        # فحص أمني حاسم: النطاق الآمن ممكن يتغيّر بين وقت الطلب ووقت
        # الموافقة (المستخدم يشيل target، أو يلغي share_entire_device)
        # - لو الموافقة اتأخرت، مينفعش الطلب القديم يتنفذ على مسار بقى
        # خارج النطاق الحالي. من غير الفحص ده، طلب استبدال/حذف قديم
        # فضل "pending" كان ممكن ينفّذ عملية فعلية على ملف المستخدم
        # مقصودًا استبعاده من المراقبة.
        if not is_add_target_request and not is_command_draft and file_path and not self.config.is_path_allowed(file_path):
            self.db.resolve_approval(approval_id, approved=False)
            self._log(f"✗ اترفض الطلب تلقائيًا: المسار بقى خارج النطاق المسموح ({file_path})")
            return {"success": False, "error": "المسار بقى خارج النطاق المسموح للموبايل - الطلب اترفض تلقائيًا"}

        try:
            if action == "delete_file":
                if file_path and os.path.exists(file_path):
                    from storage.trash import move_to_trash
                    trash_path = move_to_trash(file_path)
                    self._log(f"✓ اتنقل لسلة DeskTwin: {file_path} → {trash_path}")
                else:
                    self._log(f"⚠ الملف كان أصلاً مش موجود (اتمسح يدويًا قبل الموافقة؟): {file_path}")
                if row["file_id"]:
                    self.db.delete_file_record(row["file_id"])

            elif action == "rename_file":
                new_name = new_value
                if not new_name:
                    return {"success": False, "error": "اسم جديد غير محدد"}
                if not file_path or not os.path.exists(file_path):
                    # الملف اتمسح أو اتحرك بين وقت الطلب ووقت الموافقة -
                    # مفيش حاجة نسميها، والـ approval لازم يتحل (مش يفضل
                    # pending للأبد) عشان المستخدم يشوف إن الطلب انتهى.
                    self.db.resolve_approval(approval_id, approved=True)
                    return {"success": False, "error": "الملف مش موجود على الكمبيوتر (اتمسح أو اتحرك قبل الموافقة)"}
                new_path = os.path.join(os.path.dirname(file_path), new_name)
                os.rename(file_path, new_path)
                if row["file_id"]:
                    self.db.rename_file_record(row["file_id"], new_path, new_name)
                self._log(f"✓ اتوافق على تسمية: {file_path} → {new_name}")

            elif is_add_target_request:
                # صيغة new_value: "ADD_TARGET|<recursive:0 or 1>|<label>"
                _, recursive_flag, label = new_value.split("|", 2)
                if not os.path.exists(file_path):
                    return {"success": False, "error": "المسار المطلوب إضافته مش موجود على الكمبيوتر"}
                self.add_target(file_path, label=label, recursive=bool(int(recursive_flag)))
                self._log(f"✓ اتوافق على توسيع النطاق: {label or file_path}")

            elif action == "create_file":
                # الملف الفعلي (bytes) هيتبعت في request منفصل بعد الموافقة -
                # هنا بس بنسجل إن العملية مسموحة؛ التنفيذ الفعلي في upload endpoint.
                self._log(f"✓ اتوافق على إنشاء ملف جديد: {file_path}")

            elif is_command_draft:
                payload = json.loads(new_value or "{}")
                command = str(payload.get("command", "")).strip()
                shell = str(payload.get("shell", "cmd")).lower()
                if not command:
                    raise ValueError("الأمر فارغ")
                if shell not in {"cmd", "powershell"}:
                    raise ValueError("نوع الطرفية غير مدعوم")
                # المسار الذي اختاره المستخدم في الهاتف هو مكان حفظ المسودة
                # ومجلد عملها. يتم استعماله فقط بعد موافقة صريحة في واجهة
                # الكمبيوتر، فلا تظل الواجهة توحي بحفظ في مكان بينما الكود
                # يكتب سرًا على سطح المكتب.
                from config.app_settings import AppSettings
                configured_dir = AppSettings.load().command_draft_directory
                requested_dir = os.path.abspath(str(payload.get("working_directory", "")).strip())
                desktop = requested_dir or configured_dir or os.path.join(os.environ.get("USERPROFILE", os.path.expanduser("~")), "Desktop")
                if not os.path.isdir(desktop):
                    raise ValueError("مجلد حفظ المسودة غير موجود على الكمبيوتر")
                filename = f"DeskTwin-command-{int(time.time())}.txt"
                destination = os.path.join(desktop, filename)
                content = (
                    f"DeskTwin command draft\nShell: {shell}\n"
                    f"Working directory: {desktop}\n"
                    f"Requested by: {payload.get('user_name', 'unknown')}\n\n{command}\n"
                )
                with open(destination, "x", encoding="utf-8") as handle:
                    handle.write(content)
                self.db.log_activity(row["device_token"], "command_draft_created", detail=destination)
                from notifications.notifications import notify_operation
                notify_operation("إنشاء مسودة أمر من الهاتف", os.path.basename(destination))
                self._log(f"✓ تم إنشاء مسودة أمر نصية: {destination}")

            elif action == "overwrite_file":
                # نفس منطق create_file: الموافقة هنا بس بتفتح الباب. التنفيذ
                # الفعلي (كتابة الملف الجديد فوق القديم) بيحصل في POST /upload
                # بـ collision_mode=replace، اللي بيستهلك الـ approval ده
                # ويعمل savepoint للملف القديم قبل ما يكتب فوقه.
                self._log(f"✓ اتوافق على استبدال: {file_path}")

            elif action == "make_linked":
                # تحويل ملف من METADATA_ONLY لـ LINKED - مجرد تغيير sync_mode،
                # لسه معندناش مزامنة ثنائية فعلية (راجع docs/architecture/SYNC_SPEC.md قسم 5)
                # فالتنفيذ الحقيقي (مزامنة المحتوى) مش مبني بعد. نسجل الموافقة
                # ونحدّث sync_mode بس - القرار الأمن أفضل من رفض العملية بالكامل.
                if row["file_id"]:
                    with self.db.cursor() as cur:
                        cur.execute(
                            "UPDATE files SET sync_mode = 'LINKED' WHERE id = ?",
                            (row["file_id"],)
                        )
                self._log(f"✓ اتوافق على ربط الملف (Linked): {file_path}")

            else:
                self._log(f"⚠ نوع عملية غير مدعوم: {action}")
                return {"success": False, "error": f"نوع عملية غير مدعوم: {action}"}

            self.db.resolve_approval(approval_id, approved=True)
            return {"success": True}

        except (OSError, PermissionError) as e:
            self._log(f"✗ فشلت العملية: {e}")
            return {"success": False, "error": str(e)}

    def reject_request(self, approval_id: int):
        self.db.resolve_approval(approval_id, approved=False)
        self._log(f"✗ رُفض الطلب #{approval_id}")

