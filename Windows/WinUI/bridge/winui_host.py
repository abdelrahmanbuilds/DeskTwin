"""Local-only JSON-lines bridge between the WinUI client and DeskTwinService.

The bridge is a child process of the desktop UI. Its stdin/stdout pipes are the
control channel; it does not open a network listener or bypass phone pairing.
"""

from __future__ import annotations

import base64
import asyncio
import json
import os
import sys
import threading
import traceback
from io import BytesIO

ROOT = os.path.abspath(os.environ.get("DESKTWIN_ROOT") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "Python", "service"))
PHONE_FILE_ACTIONS = frozenset({"roots", "refresh", "catalog", "media_info", "open", "thumbnail", "rename", "delete", "copy", "trash_list", "restore", "trash_delete"})

# Keep stdout exclusively for the JSON-lines protocol. Existing service code
# and optional libraries may print diagnostics; those belong on stderr.
_wire = sys.stdout
_wire_lock = threading.Lock()


def emit(payload: dict) -> None:
    with _wire_lock:
        # Keep the control pipe ASCII-only. Windows may start Python with a
        # legacy code page that cannot encode Arabic service messages.
        _wire.write(json.dumps(payload, ensure_ascii=True, separators=(",", ":")) + "\n")
        _wire.flush()


def phone_channel_connected(service_running: bool, api_module: object | None, channel: str) -> bool:
    if not service_running or api_module is None:
        return False
    manager = getattr(api_module, "ws_manager", None)
    if manager is None:
        return False
    if channel == "control":
        return bool(manager.has_active_connection)
    return bool(manager.active_tokens(channel))


def connected_phone_device_name(service_running: bool, api_module: object | None, pairing: object | None) -> str | None:
    if not service_running or api_module is None or pairing is None:
        return None
    manager = getattr(api_module, "ws_manager", None)
    if manager is None:
        return None
    active_tokens = manager.active_tokens("phone_files") or manager.active_tokens("control")
    for token in active_tokens:
        name = pairing.get_device_name(token)
        if name:
            return name
    return None


def normalize_phone_file_list_options(args: dict) -> dict:
    offset = max(0, int(args.get("offset", 0) or 0))
    limit = int(args.get("limit", 0) or 0)
    if limit < 0 or limit > 500:
        raise ValueError("حجم صفحة ملفات الهاتف غير صالح")
    sort = str(args.get("sort", "name"))
    if sort not in {"name", "modified", "size"}:
        sort = "name"
    return {
        "offset": offset,
        "limit": limit,
        "sort": sort,
        "search": str(args.get("search", ""))[:512],
        "recursive": bool(args.get("recursive", False)),
        "category": str(args.get("category", "")) if str(args.get("category", "")) in {"photos", "videos", "music", "docs", "apps"} else "",
    }


def validate_phone_file_action(operation: str) -> str:
    """Validate the small allowlist of phone-file operations exposed to WinUI."""
    if operation not in PHONE_FILE_ACTIONS:
        raise ValueError("عملية ملفات الهاتف غير مدعومة")
    return operation


def attach_pairing_qr(result: dict, payload: str | None = None) -> dict:
    try:
        import qrcode
        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            border=4,
        )
        qr.add_data(payload or result["code"], optimize=0)
        qr.make(fit=True)
        result["qr_matrix"] = [
            [bool(module) for module in row]
            for row in qr.get_matrix()
        ]
        image = qr.make_image(fill_color="#000000", back_color="#FFFFFF")
        buffer = BytesIO()
        image.save(buffer, format="PNG")
        result["qr_png"] = base64.b64encode(buffer.getvalue()).decode("ascii")
    except Exception as exc:
        result["qr_error"] = str(exc)
    return result


def main() -> None:
    global _wire
    _wire = sys.stdout
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.stdout = sys.stderr

    from Python.core.desktwin_service import DeskTwinService
    from config.app_settings import AppSettings

    service = DeskTwinService(on_log=lambda message: emit({"event": "log", "message": message}))
    closing = threading.Event()

    def state() -> dict:
        settings = AppSettings.load()
        targets = service.get_targets()
        api_module = sys.modules.get("api.server")
        phone_connected = phone_channel_connected(service.is_running, api_module, "control")
        phone_files_connected = phone_channel_connected(service.is_running, api_module, "phone_files")
        device_name = connected_phone_device_name(service.is_running, api_module, getattr(service, "pairing", None))
        return {
            "running": service.is_running,
            "starting": service.is_starting,
            "phone_connected": phone_connected,
            "phone_files_connected": phone_files_connected,
            "phone_device_name": device_name,
            "computer_name": os.environ.get("COMPUTERNAME", "DeskTwin-PC"),
            "files_indexed": service.get_file_count(),
            "targets_count": len(targets),
            "trusted_devices_count": len([
                device for device in service.list_trusted_devices()
                if not device.get("revoked")
            ]) if service.db else 0,
            "pending_approvals_count": service.get_pending_approvals_count(),
            "auto_start": settings.auto_start_service,
        }

    def dispatch(command: str, args: dict) -> object:
        if command == "get_state":
            return state()
        if command == "start_service":
            if service.is_running or service.is_starting:
                return {"accepted": True, "already_active": True}

            def started(success: bool, message: str) -> None:
                emit({"event": "service_started", "success": success, "message": message})

            threading.Thread(
                target=lambda: service.start(on_complete=started),
                name="DeskTwinWinUIServiceStart", daemon=True,
            ).start()
            return {"accepted": True}
        if command == "stop_service":
            service.stop()
            return {"stopped": True}
        if command == "get_targets":
            return [{
                "path": target.path,
                "label": target.label or target.path,
                "is_file": bool(getattr(target, "is_file", False)),
                "recursive": bool(getattr(target, "recursive", True)),
            } for target in service.get_targets()]
        if command == "get_share_scope":
            return {"share_entire_device": bool(service.config.share_entire_device)}
        if command == "set_share_entire_device":
            value = args.get("enabled")
            if not isinstance(value, bool):
                raise ValueError("اختيار مشاركة الجهاز غير صالح")
            service.config.share_entire_device = value
            service.config.save()
            return {"share_entire_device": value}
        if command == "add_target":
            path = os.path.abspath(str(args.get("path", "")))
            if not path or not os.path.exists(path):
                raise ValueError("المسار غير موجود")
            is_file = os.path.isfile(path)
            service.add_target(
                path,
                label=os.path.basename(path.rstrip("/\\")) or path,
                recursive=not is_file,
                is_file=is_file,
            )
            return {"added": True}
        if command == "remove_target":
            path = str(args.get("path", ""))
            before = len(service.config.targets)
            service.config.targets = [target for target in service.config.targets if target.path != path]
            if len(service.config.targets) == before:
                raise ValueError("المجلد لم يعد ضمن مجلدات DeskTwin")
            service.config.save()
            return {"removed": True}
        if command == "generate_pairing_code":
            result = service.generate_pairing_code()
            if "error" in result:
                return result
            return attach_pairing_qr(result, result.get("qr_payload"))
        if command == "get_active_pairing":
            code = service.get_active_pairing_code()
            if not code:
                return {"code": None, "seconds_left": 0}
            result = {
                "code": code,
                "seconds_left": service.get_active_pairing_seconds(),
            }
            return attach_pairing_qr(result, service.pairing_qr_payload(code))
        if command == "get_settings":
            from dataclasses import asdict
            return asdict(AppSettings.load())
        if command == "set_setting":
            from dataclasses import fields
            settings = AppSettings.load()
            key = str(args.get("key", ""))
            allowed = {field.name for field in fields(settings)}
            if key not in allowed:
                raise ValueError("الإعداد غير مدعوم من واجهة WinUI")
            value = args.get("value")
            if key == "theme_mode" and value not in {"light", "dark"}:
                raise ValueError("اختيار المظهر غير صالح")
            if key == "language" and value not in {"ar", "en"}:
                raise ValueError("اختيار اللغة غير صالح")
            if key == "intro_mode" and value not in {"auto", "manual", "off"}:
                raise ValueError("اختيار المقدمة غير صالح")
            if key in {"auto_start_service", "reduce_motion"} and not isinstance(value, bool):
                raise ValueError("قيمة الإعداد يجب أن تكون تشغيلًا أو إيقافًا")
            if key in {"window_width", "window_height"} and (type(value) is not int or not 880 <= value <= 5000):
                raise ValueError("أبعاد نافذة WinUI غير صالحة")
            if key == "sidebar_collapsed" and not isinstance(value, bool):
                raise ValueError("حالة الشريط الجانبي غير صالحة")
            if key == "last_active_view" and value not in {
                "dashboard", "folders", "devices", "approvals", "activity",
                "settings", "security", "chat", "transfers", "integration", "phone_files",
            }:
                raise ValueError("الشاشة المحفوظة غير مدعومة")
            if key == "activity_log_limit" and (type(value) is not int or value not in {50, 100, 200, 300, 1000}):
                raise ValueError("حجم سجل النشاط غير صالح")
            if key == "incomplete_transfer_retention_hours" and (type(value) is not int or value not in {1, 6, 12, 24, 72, 168}):
                raise ValueError("مدة الاحتفاظ بالملفات الناقصة غير صالحة")
            if key == "command_draft_directory":
                if not isinstance(value, str):
                    raise ValueError("مسار مجلد الأوامر غير صالح")
                if value and not os.path.isdir(value):
                    raise ValueError("مجلد حفظ مسودات الأوامر غير موجود")
            if key == "command_request_export_directory":
                if not isinstance(value, str):
                    raise ValueError("مسار حفظ طلبات الأوامر غير صالح")
                if value and not os.path.isdir(value):
                    raise ValueError("مجلد حفظ طلبات الأوامر غير موجود")
            if key == "use_default_command_request_export_directory" and not isinstance(value, bool):
                raise ValueError("اختيار مسار الحفظ التلقائي غير صالح")
            if key == "use_default_command_request_export_directory" and value and not settings.command_request_export_directory:
                raise ValueError("اختر مجلد حفظ طلبات الأوامر أولًا")
            if key in {"phone_download_directory", "phone_upload_directory"} and not isinstance(value, str):
                raise ValueError("مسار ملفات الهاتف غير صالح")
            if key == "phone_download_directory" and value and not os.path.isdir(value):
                raise ValueError("مجلد حفظ تنزيلات الهاتف غير موجود")
            setattr(settings, key, value)
            settings.save()
            if key == "activity_log_limit" and service.db:
                service.db.activity_log_limit = value
                service.db.log_activity("system", "activity_log_limit_changed", str(value), limit=value)
            return {"saved": True, "key": key, "value": value}
        if command == "get_chat_messages":
            from sync.manual_messages import get_messages
            return get_messages()
        if command == "send_chat_message":
            text = str(args.get("text", "")).strip()
            if not text or len(text) > 250_000:
                raise ValueError("الرسالة فارغة أو أطول من الحد المسموح")
            from api.server import send_manual_text_to_devices
            if not send_manual_text_to_devices(text):
                raise RuntimeError("لا يوجد هاتف متصل بالمحادثة الآن")
            return {"sent": True}
        if command in {"phone_files_list", "phone_files_download", "phone_files_read_range", "phone_files_upload", "phone_files_action"}:
            if not service.is_running:
                raise RuntimeError("ابدأ خدمة DeskTwin أولًا")
            from api import server as api_server_module
            from sync import phone_fs
            loop = api_server_module._event_loop
            if loop is None or loop.is_closed():
                raise RuntimeError("قناة الهاتف غير جاهزة؛ أعد تشغيل الخدمة")
            if command == "phone_files_list":
                coroutine = phone_fs.request_active(
                    "list",
                    str(args.get("path", "")),
                    **normalize_phone_file_list_options(args),
                )
                timeout = 90 if args.get("recursive") else 35
            elif command == "phone_files_download":
                coroutine = phone_fs.request_active(
                    "download",
                    str(args.get("path", "")),
                    expected_size_bytes=args.get("size_bytes", 0),
                )
                timeout = phone_fs.download_timeout_seconds(args.get("size_bytes", 0)) + 15
            elif command == "phone_files_read_range":
                coroutine = phone_fs.request_active(
                    "read_range",
                    str(args.get("path", "")),
                    offset=args.get("offset", -1),
                    length=args.get("length", 0),
                )
                timeout = 50
            elif command == "phone_files_upload":
                local_path = os.path.abspath(str(args.get("local_path", "")))
                if not os.path.isfile(local_path):
                    raise ValueError("الملف المحدد غير موجود")
                coroutine = phone_fs.upload_active(
                    local_path,
                    str(args.get("destination", "")),
                    os.path.basename(local_path),
                )
                timeout = 300
            else:
                operation = str(args.get("operation", ""))
                validate_phone_file_action(operation)
                coroutine = phone_fs.request_active(
                    operation,
                    str(args.get("path", "")),
                    **{key: value for key, value in args.items() if key not in {"operation", "path"}},
                )
                timeout = 120 if operation == "catalog" and args.get("recursive") else 30 if operation == "media_info" else 45 if operation == "thumbnail" else 35
            future = asyncio.run_coroutine_threadsafe(coroutine, loop)
            result = future.result(timeout=timeout)
            if command == "phone_files_download" and result.get("content_base64"):
                from pathlib import Path
                transfer_dir = Path(ROOT) / "data" / "phone_transfers"
                transfer_dir.mkdir(parents=True, exist_ok=True)
                request_id = str(result.get("request_id") or "download")
                local_path = transfer_dir / f"{request_id}-{os.path.basename(str(args.get('path', 'phone_file')))}"
                local_path.write_bytes(base64.b64decode(result["content_base64"]))
                result["local_path"] = str(local_path)
            return result
        if command == "get_active_phone_uploads":
            from sync.phone_fs import active_uploads
            return active_uploads()
        if command == "get_phone_file_upload_history":
            from sync.phone_fs import recent_events
            return [event for event in recent_events() if event.get("event") == "uploaded"]
        if command == "cancel_phone_upload":
            from api import server as api_server_module
            from sync.phone_fs import cancel_active
            transfer_id = str(args.get("transfer_id", ""))
            if not transfer_id:
                raise ValueError("معرّف عملية النقل غير موجود")
            loop = api_server_module._event_loop
            if loop is None or loop.is_closed():
                raise RuntimeError("قناة الهاتف غير جاهزة؛ أعد تشغيل الخدمة")
            cancelled = asyncio.run_coroutine_threadsafe(cancel_active(transfer_id), loop).result(timeout=10)
            return {"cancelled": bool(cancelled)}
        if command == "get_phone_file_event_count":
            from sync.phone_fs import recent_events
            return len(recent_events())
        if command == "get_phone_file_event_revision":
            from sync.phone_fs import event_revision
            return event_revision()
        if command == "get_devices":
            devices = service.list_trusted_devices() if service.db else []
            return [
                {
                    **device,
                    "id": int(device.get("id", 0) or 0),
                    "revoked": bool(device.get("revoked", False)),
                }
                for device in devices
            ]
        if command == "get_bound_user":
            return service.db.get_bound_user() if service.db else None
        if command == "get_pending_approvals":
            return service.get_pending_approvals() if service.db else []
        if command == "get_recent_changes":
            return service.get_recent_changes(limit=min(max(int(args.get("limit", 30)), 1), 100))
        if command == "get_recent_activity":
            if not service.db:
                return []
            limit = min(max(int(args.get("limit", 15)), 1), 1000)
            offset = min(max(int(args.get("offset", 0)), 0), 10000)
            return service.db.get_recent_activity(limit=limit, offset=offset)
        if command == "get_incomplete_transfers":
            if not service.db:
                return {"available": False, "total": 0, "items": []}
            limit = min(max(int(args.get("limit", 5)), 1), 100)
            transfers = service.db.list_incomplete_transfers()
            return {
                "available": True,
                "total": len(transfers),
                "items": [
                    {
                        "file_path": item.get("file_path", ""),
                        "direction": item.get("direction", "upload"),
                        "status": item.get("status", "in_progress"),
                        "bytes_received": int(item.get("bytes_received", 0) or 0),
                        "total_size_bytes": int(item.get("total_size_bytes", 0) or 0),
                        "last_progress_at": item.get("last_progress_at", ""),
                        "retention_hours": int(item.get("retention_hours", 24) or 24),
                    }
                    for item in transfers[:limit]
                ],
            }
        if command == "clear_activity":
            if not service.db:
                raise RuntimeError("ابدأ الخدمة أولًا لمسح سجل النشاط")
            service.db.clear_activity()
            return {"cleared": True}
        if command == "get_security_status":
            return service.get_security_status()
        if command == "get_integration_health":
            from api import server as api_server_module
            devices = service.list_trusted_devices() if service.db else []
            active_devices = [device for device in devices if not device.get("revoked")]
            security = service.get_security_status()
            connections = int(security.get("active_connections", 0) or 0)
            websocket_online = bool(api_server_module.ws_manager.has_active_connection)
            trust_limited = any(device.get("trust_level") != "full" for device in active_devices)
            hints = []
            if not service.is_running:
                hints.append("شغّل الخدمة قبل اختبار التكامل.")
            if not active_devices:
                hints.append("اقرن الهاتف أولًا.")
            if active_devices and not websocket_online:
                hints.append("لا توجد قناة WebSocket نشطة؛ افتح المحادثة في الهاتف أو أعد الاتصال.")
            if trust_limited:
                hints.append("بعض الأجهزة ليست بمستوى Full؛ قد تُرفض أوامر الجهاز.")
            return {
                "status": "healthy" if service.is_running and active_devices and websocket_online else "needs_attention",
                "running": service.is_running,
                "protocol_version": api_server_module.DESKTWIN_PROTOCOL_VERSION,
                "server_version": api_server_module.app.version,
                "trusted_devices": len(active_devices),
                "active_connections": connections,
                "websocket": "connected" if websocket_online else "offline",
                "chat": "connected" if websocket_online else "offline",
                "hints": hints,
            }
        if command == "revoke_device":
            service.revoke_device(int(args["device_id"]))
            return {"revoked": True}
        if command == "revoke_all_devices":
            return {"revoked_count": service.revoke_all_devices()}
        if command == "delete_revoked_devices":
            return {"deleted_count": service.delete_revoked_devices()}
        if command == "set_device_trust":
            service.set_device_trust_level(int(args["device_id"]), str(args["trust_level"]))
            return {"updated": True}
        if command == "approve_request":
            return service.approve_request(int(args["approval_id"]))
        if command == "reject_request":
            service.reject_request(int(args["approval_id"]))
            return {"rejected": True}
        if command == "shutdown":
            closing.set()
            return {"closing": True}
        raise ValueError(f"أمر غير معروف: {command}")

    settings = AppSettings.load()
    if settings.auto_start_service and service.get_targets():
        threading.Thread(
            target=lambda: service.start(on_complete=lambda ok, message: emit({
                "event": "service_started", "success": ok, "message": message,
            })),
            name="DeskTwinWinUIAutoStart", daemon=True,
        ).start()

    emit({"event": "ready"})
    # Phone file requests can wait for user/device work for minutes. Keep the
    # JSON-lines reader free so status/stop commands and other replies continue.
    phone_file_commands = {
        "phone_files_list", "phone_files_action", "phone_files_download", "phone_files_read_range", "phone_files_upload",
    }
    phone_file_slots = threading.BoundedSemaphore(4)

    def handle_request(request: dict) -> None:
        request_id = request.get("id")
        try:
            result = dispatch(str(request.get("command", "")), request.get("args") or {})
            emit({"id": request_id, "ok": True, "result": result})
        except Exception as exc:
            emit({
                "id": request_id,
                "ok": False,
                "error": str(exc),
                "trace": traceback.format_exc(limit=4),
            })

    try:
        for line in sys.stdin:
            try:
                request = json.loads(line)
            except Exception as exc:
                emit({
                    "id": None,
                    "ok": False,
                    "error": f"طلب JSON غير صالح: {exc}",
                })
                continue
            if not isinstance(request, dict):
                emit({"id": None, "ok": False, "error": "يجب أن يكون طلب الجسر كائن JSON."})
                continue
            command = str(request.get("command", ""))
            if command in phone_file_commands:
                if not phone_file_slots.acquire(blocking=False):
                    emit({"id": request.get("id"), "ok": False, "error": "توجد أربع عمليات ملفات قيد التنفيذ؛ أعد المحاولة بعد انتهائها."})
                    continue

                def run_phone_request(item=request) -> None:
                    try:
                        handle_request(item)
                    finally:
                        phone_file_slots.release()

                worker = threading.Thread(
                    target=run_phone_request,
                    name="DeskTwinWinUIPhoneFiles",
                    daemon=True,
                )
                try:
                    worker.start()
                except RuntimeError as exc:
                    phone_file_slots.release()
                    emit({"id": request.get("id"), "ok": False, "error": f"تعذر بدء عملية ملفات الهاتف: {exc}"})
            else:
                handle_request(request)
            if closing.is_set():
                break
    finally:
        if service.is_running:
            service.stop()


if __name__ == "__main__":
    main()
