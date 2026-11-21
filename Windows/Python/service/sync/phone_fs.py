"""Computer-side request/response bridge for the phone SAF file tree."""
import asyncio
import threading
import uuid
import os
import base64
import json
import struct

from sync.ws_manager import manager

_pending: dict[str, asyncio.Future] = {}
_downloads: dict[str, dict[str, int]] = {}
_range_reads: dict[str, dict[str, object]] = {}
_upload_waiters: dict[str, asyncio.Future] = {}
_active_uploads: dict[str, dict] = {}
_events: list[dict] = []
_event_revision = 0
_lock = threading.Lock()
PROJECT_ROOT = os.path.abspath(os.environ.get("DESKTWIN_ROOT") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(__file__)))))
_transfer_dir = os.path.join(PROJECT_ROOT, "data", "phone_transfers")
# Keep Android and service chunk sizes in sync. A 4 MiB payload cuts JSON and
# Base64 message overhead without requiring multi-frame buffering.
_UPLOAD_CHUNK_SIZE = 4 * 1024 * 1024
_DOWNLOAD_CHUNK_SIZE = 4 * 1024 * 1024
_DOWNLOAD_ENCODED_CHUNK_LIMIT = 4 * ((_DOWNLOAD_CHUNK_SIZE + 2) // 3)
_BINARY_CHUNK_SIZE = 1024 * 1024
_BINARY_FRAME_MAGIC = b"DTB1"
_BINARY_FRAME_HEADER_SIZE = 44  # magic, 32-byte hex request id, uint64 absolute offset
_events_file = os.path.join(_transfer_dir, "history.json")


def download_timeout_seconds(expected_size_bytes: object = 0) -> int:
    """Allow large media downloads enough time without making small requests wait."""
    try:
        size = max(0, int(expected_size_bytes or 0))
    except (TypeError, ValueError):
        size = 0
    if size == 0:
        return 240
    mebibyte = 1024 * 1024
    estimated = ((size + mebibyte - 1) // mebibyte) * 8 + 60
    return min(900, max(60, estimated))
try:
    with open(_events_file, "r", encoding="utf-8") as handle:
        _events = json.load(handle)[-200:]
except (OSError, ValueError, TypeError):
    _events = []


def publish_event(data: dict) -> None:
    global _event_revision
    with _lock:
        event = {**data, "timestamp_ms": int(__import__('time').time() * 1000)}
        _events.append(event)
        del _events[:-200]
        _event_revision += 1
        try:
            os.makedirs(_transfer_dir, exist_ok=True)
            temporary = _events_file + ".tmp"
            with open(temporary, "w", encoding="utf-8") as handle:
                json.dump(_events, handle, ensure_ascii=False)
            os.replace(temporary, _events_file)
        except OSError:
            pass


def recent_events() -> list[dict]:
    with _lock:
        return list(_events)


def event_revision() -> int:
    """Monotonic in-process change marker, unaffected by event-list trimming."""
    with _lock:
        return _event_revision


def active_uploads() -> list[dict]:
    """Return a copy of in-flight desktop-to-phone uploads for local UI status."""
    with _lock:
        return [dict(upload) for upload in _active_uploads.values()]


def _upload_cancelled(transfer_id: str) -> bool:
    with _lock:
        return bool(_active_uploads.get(transfer_id, {}).get("cancel_requested"))


def _update_upload(transfer_id: str, **values) -> None:
    with _lock:
        upload = _active_uploads.get(transfer_id)
        if upload is not None:
            upload.update(values)


async def resolve_response(data: dict) -> None:
    if data.get("event"):
        publish_event(data)
    request_id = str(data.get("request_id", ""))
    with _lock:
        future = _pending.pop(request_id, None)
        download = _downloads.pop(request_id, None)
        range_read = _range_reads.pop(request_id, None)
    if range_read is not None:
        if data.get("success") is True:
            received = int(range_read["bytes_received"])
            try:
                total_size = int(data.get("size", -1))
                expected = min(
                    int(range_read["max_bytes"]),
                    max(0, total_size - int(range_read["start_offset"])),
                )
            except (TypeError, ValueError):
                expected = -1
            if expected < 0 or received != expected:
                data = dict(data, success=False, error="عدد بايتات نطاق الفيديو المستلم غير مكتمل")
            data = dict(data)
            data["bytes_received"] = received
            if data.get("success") is True:
                range_path = os.path.join(_transfer_dir, request_id + ".range")
                try:
                    os.makedirs(_transfer_dir, exist_ok=True)
                    await asyncio.to_thread(_write_range_file, range_path, range_read["buffer"])
                    data["content_file"] = range_path
                except OSError as exc:
                    data = dict(data, success=False, error=f"تعذرت تهيئة نطاق الفيديو محليًا: {exc}")
        if data.get("success") is not True:
            try:
                os.remove(os.path.join(_transfer_dir, request_id + ".range"))
            except OSError:
                pass
        if future and not future.done():
            future.set_result(data)
        return
    transfer_path = os.path.join(_transfer_dir, request_id + ".part")
    if download is not None:
        if data.get("success") is True:
            try:
                expected_size = int(data.get("size", -1))
            except (TypeError, ValueError):
                expected_size = -1
            if expected_size >= 0 and download["bytes_received"] != expected_size:
                data = dict(data, success=False, error="حجم الملف المستلم لا يطابق الحجم المعلن")
            else:
                if expected_size == 0 and data.get("mode") == "chunked" and not os.path.exists(transfer_path):
                    os.makedirs(_transfer_dir, exist_ok=True)
                    with open(transfer_path, "wb"):
                        pass
                if os.path.exists(transfer_path):
                    data = dict(data)
                    data["local_path"] = transfer_path
        if data.get("success") is not True:
            try:
                os.remove(transfer_path)
            except OSError:
                pass
    elif os.path.exists(transfer_path):
        data = dict(data)
        data["local_path"] = transfer_path
    if future and not future.done():
        future.set_result(data)


def _write_range_file(path: str, buffer: bytearray) -> None:
    with open(path, "wb") as handle:
        handle.write(buffer)


def accept_chunk(data: dict) -> None:
    request_id = str(data.get("request_id", ""))
    encoded = data.get("data", "")
    if not request_id or not isinstance(encoded, str):
        return
    with _lock:
        download = _downloads.get(request_id)
        range_read = _range_reads.get(request_id)
        future = _pending.get(request_id)
        expected_sequence = download["next_sequence"] if download is not None else -1
        expected_range_sequence = range_read["next_sequence"] if range_read is not None else -1
    if (download is None and range_read is None) or future is None:
        return
    if len(encoded) > _DOWNLOAD_ENCODED_CHUNK_LIMIT:
        _reject_download(request_id, "حجم جزء النقل تجاوز الحد المسموح")
        return
    sequence = data.get("sequence")
    if sequence is not None:
        expected_sequence = expected_range_sequence if range_read is not None else expected_sequence
        sequence = data.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence != expected_sequence:
            _reject_download(request_id, "ترتيب أجزاء الملف غير صحيح")
            return
    try:
        chunk = base64.b64decode(encoded, validate=True)
    except (ValueError, base64.binascii.Error):
        _reject_download(request_id, "ترميز جزء الملف غير صالح")
        return
    if not chunk or len(chunk) > _DOWNLOAD_CHUNK_SIZE:
        _reject_download(request_id, "حجم جزء الملف غير صالح")
        return
    if range_read is not None:
        with _lock:
            current = _range_reads.get(request_id)
            if current is None:
                return
            if current["bytes_received"] + len(chunk) > current["max_bytes"]:
                overflow = True
            else:
                current["buffer"].extend(chunk)
                current["next_sequence"] += 1
                current["bytes_received"] += len(chunk)
                overflow = False
        if overflow:
            _reject_download(request_id, "تجاوزت بيانات القراءة نطاق البايتات المطلوب")
        return
    os.makedirs(_transfer_dir, exist_ok=True)
    target = os.path.join(_transfer_dir, request_id + ".part")
    try:
        with open(target, "ab") as handle:
            handle.write(chunk)
    except OSError:
        _reject_download(request_id, "تعذرت كتابة جزء الملف")
        return
    with _lock:
        current = _downloads.get(request_id)
        if current is not None:
            current["next_sequence"] += 1
            current["bytes_received"] += len(chunk)


def encode_binary_chunk(request_id: str, offset: int, data: bytes) -> bytes:
    """Pack a raw file chunk into the versioned binary WebSocket envelope."""
    try:
        request_id_bytes = request_id.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError("معرّف النقل الثنائي غير صالح") from exc
    if len(request_id_bytes) != 32 or any(char not in b"0123456789abcdef" for char in request_id_bytes):
        raise ValueError("معرّف النقل الثنائي غير صالح")
    if offset < 0 or len(data) < 1 or len(data) > _BINARY_CHUNK_SIZE:
        raise ValueError("حجم أو موضع كتلة النقل الثنائي غير صالح")
    return _BINARY_FRAME_MAGIC + request_id_bytes + struct.pack(">Q", offset) + data


def _append_binary_chunk(path: str, data: memoryview) -> None:
    with open(path, "ab") as handle:
        handle.write(data)


async def accept_binary_chunk(frame: bytes) -> None:
    """Accept a bounded raw file chunk without JSON or Base64 decoding."""
    if len(frame) <= _BINARY_FRAME_HEADER_SIZE or len(frame) > _BINARY_FRAME_HEADER_SIZE + _BINARY_CHUNK_SIZE:
        return
    if frame[:4] != _BINARY_FRAME_MAGIC:
        return
    try:
        request_id = frame[4:36].decode("ascii")
        offset = struct.unpack(">Q", frame[36:44])[0]
    except (UnicodeDecodeError, struct.error):
        return
    if len(request_id) != 32 or any(char not in "0123456789abcdef" for char in request_id):
        return
    chunk = memoryview(frame)[_BINARY_FRAME_HEADER_SIZE:]
    with _lock:
        download = _downloads.get(request_id)
        range_read = _range_reads.get(request_id)
        if download is None and range_read is None:
            return
        received = int(download["bytes_received"] if download is not None else range_read["bytes_received"])
        expected_offset = received if download is not None else int(range_read["start_offset"]) + received
        max_bytes = int(range_read["max_bytes"]) if range_read is not None else None
        if offset != expected_offset:
            error = "ترتيب كتل النقل الثنائي غير صحيح"
        elif max_bytes is not None and received + len(chunk) > max_bytes:
            error = "تجاوزت بيانات القراءة نطاق البايتات المطلوب"
        else:
            error = ""
            if range_read is not None:
                range_read["buffer"].extend(chunk)
                range_read["bytes_received"] = received + len(chunk)
                return

    if error:
        _reject_download(request_id, error)
        return

    target = os.path.join(_transfer_dir, request_id + ".part")
    try:
        await asyncio.to_thread(_append_binary_chunk, target, chunk)
    except OSError:
        _reject_download(request_id, "تعذرت كتابة كتلة النقل الثنائي")
        return
    with _lock:
        current = _downloads.get(request_id)
        if current is not None:
            current["bytes_received"] += len(chunk)


def _reject_download(request_id: str, error: str) -> None:
    with _lock:
        future = _pending.pop(request_id, None)
        download = _downloads.pop(request_id, None)
        _range_reads.pop(request_id, None)
    target = os.path.join(_transfer_dir, request_id + ".part")
    if download is not None:
        try:
            os.remove(target)
        except OSError:
            pass
    if future is not None and not future.done():
        future.set_result({"request_id": request_id, "success": False, "error": error})

def resolve_upload_ack(data: dict) -> bool:
    request_id = str(data.get("request_id", ""))
    with _lock: future = _upload_waiters.pop(request_id, None)
    if data.get("event"):
        publish_event(data)
    if future is None:
        return False
    if not future.done():
        future.set_result(data)
    return True

def cleanup_transfers(max_age_seconds: int = 86400) -> None:
    if not os.path.isdir(_transfer_dir): return
    cutoff = __import__('time').time() - max_age_seconds
    for name in os.listdir(_transfer_dir):
        if name == os.path.basename(_events_file):
            continue
        path = os.path.join(_transfer_dir, name)
        try:
            if os.path.isfile(path) and os.path.getmtime(path) < cutoff: os.remove(path)
        except OSError: pass

async def upload_active(local_path: str, destination: str, name: str, transfer_id: str | None = None) -> dict:
    cleanup_transfers(); tokens = manager.active_tokens("phone_files")
    if not tokens: return {"success": False, "error": "الهاتف غير متصل"}
    token = tokens[0]
    binary_transfer = manager.supports_binary_transfer(token, "phone_files")
    chunk_size = _BINARY_CHUNK_SIZE if binary_transfer else _UPLOAD_CHUNK_SIZE
    transfer_id = transfer_id or uuid.uuid4().hex; total = os.path.getsize(local_path)
    with _lock:
        _active_uploads[transfer_id] = {
            "transfer_id": transfer_id,
            "name": name,
            "destination": destination,
            "bytes_sent": 0,
            "total_bytes": total,
            "state": "starting",
            "cancel_requested": False,
        }
    try:
        loop = asyncio.get_running_loop(); start_waiter = loop.create_future()
        with _lock: _upload_waiters[transfer_id] = start_waiter
        if not await manager.send_to_token(token, {"type":"phone_fs_upload_start","request_id":transfer_id,"destination":destination,"name":name,"size":total}, channel="phone_files"):
            with _lock: _upload_waiters.pop(transfer_id, None)
            return {"success": False, "error": "تعذر بدء الرفع"}
        try: start_reply = await asyncio.wait_for(start_waiter, timeout=10)
        except asyncio.TimeoutError: return {"success": False, "error": "الهاتف لم يؤكد بداية الرفع", "transfer_id": transfer_id}
        if not start_reply.get("success", False):
            return {**start_reply, "transfer_id": transfer_id}
        if _upload_cancelled(transfer_id): return {"success": False, "cancelled": True, "error": "أُلغي الرفع", "transfer_id": transfer_id}
        offset = int(start_reply.get("offset", 0) or 0)
        _update_upload(transfer_id, bytes_sent=offset, state="sending")
        with open(local_path, "rb") as handle:
            handle.seek(offset)
            while chunk := await asyncio.to_thread(handle.read, chunk_size):
                if _upload_cancelled(transfer_id): return {"success": False, "cancelled": True, "error": "أُلغي الرفع", "transfer_id": transfer_id}
                if binary_transfer:
                    message_sent = await manager.send_binary_to_token(
                        token, encode_binary_chunk(transfer_id, offset, chunk), channel="phone_files"
                    )
                else:
                    message_sent = await manager.send_to_token(
                        token,
                        {"type":"phone_fs_upload_chunk","request_id":transfer_id,"offset":offset,"data":base64.b64encode(chunk).decode("ascii")},
                        channel="phone_files",
                    )
                if not message_sent:
                    return {"success":False,"error":"انقطع الاتصال","transfer_id":transfer_id}
                offset += len(chunk)
                _update_upload(transfer_id, bytes_sent=offset)
        if _upload_cancelled(transfer_id): return {"success": False, "cancelled": True, "error": "أُلغي الرفع", "transfer_id": transfer_id}
        finish_waiter = loop.create_future()
        with _lock: _upload_waiters[transfer_id] = finish_waiter
        _update_upload(transfer_id, state="confirming")
        await manager.send_to_token(token, {"type":"phone_fs_upload_finish","request_id":transfer_id}, channel="phone_files")
        try: finish_reply = await asyncio.wait_for(finish_waiter, timeout=30)
        except asyncio.TimeoutError: return {"success": False, "error": "الهاتف لم يؤكد اكتمال الرفع", "transfer_id": transfer_id}
        if not finish_reply.get("success", False): return finish_reply
        return {"success": True, "bytes": offset, "transfer_id": transfer_id}
    finally:
        with _lock:
            _upload_waiters.pop(transfer_id, None)
            _active_uploads.pop(transfer_id, None)

async def cancel_active(transfer_id: str) -> bool:
    with _lock:
        upload = _active_uploads.get(transfer_id)
        if upload is None:
            return False
        upload["cancel_requested"] = True
        upload["state"] = "cancelling"
    tokens = manager.active_tokens("phone_files")
    return bool(tokens and await manager.send_to_token(tokens[0], {"type":"phone_fs_upload_cancel","request_id":transfer_id}, channel="phone_files"))

async def handle_upload_message(data: dict, token: str) -> None:
    # Upload messages are acknowledged by the phone; this hook is reserved
    # for server-side auditing and future resumable offset tracking.
    return None


async def request(token: str, operation: str, path: str = "", **payload) -> dict:
    request_id = uuid.uuid4().hex
    loop = asyncio.get_running_loop()
    future = loop.create_future()
    if operation == "read_range":
        try:
            offset = int(payload.get("offset", -1))
            length = int(payload.get("length", 0))
        except (TypeError, ValueError):
            return {"request_id": request_id, "success": False, "error": "نطاق قراءة الفيديو غير صالح"}
        if offset < 0 or length < 1 or length > 2 * 1024 * 1024:
            return {"request_id": request_id, "success": False, "error": "نطاق قراءة الفيديو خارج الحد المسموح"}
        payload["offset"] = offset
        payload["length"] = length
    with _lock:
        _pending[request_id] = future
        if operation == "download":
            _downloads[request_id] = {"next_sequence": 0, "bytes_received": 0}
        elif operation == "read_range":
            _range_reads[request_id] = {
                "next_sequence": 0,
                "bytes_received": 0,
                "max_bytes": length,
                "start_offset": offset,
                "buffer": bytearray(),
            }
    if operation == "download":
        os.makedirs(_transfer_dir, exist_ok=True)
        try:
            os.remove(os.path.join(_transfer_dir, request_id + ".part"))
        except FileNotFoundError:
            pass
        except OSError as exc:
            with _lock:
                _pending.pop(request_id, None)
                _downloads.pop(request_id, None)
            return {"request_id": request_id, "success": False, "error": f"تعذر تهيئة ملف النقل: {exc}"}
    message = {"type": "phone_fs_request", "request_id": request_id,
               "operation": operation, "path": path, **payload}
    if not await manager.send_to_token(token, message, channel="phone_files"):
        with _lock:
            _pending.pop(request_id, None)
            _downloads.pop(request_id, None)
            _range_reads.pop(request_id, None)
        if operation == "download":
            try:
                os.remove(os.path.join(_transfer_dir, request_id + ".part"))
            except OSError:
                pass
        return {"request_id": request_id, "success": False, "error": "الهاتف غير متصل"}
    try:
        wait_timeout = (
            download_timeout_seconds(payload.get("expected_size_bytes"))
            if operation == "download"
            else 45 if operation == "read_range"
            else 90 if operation in {"list", "catalog"} and payload.get("recursive")
            else 30
        )
        return await asyncio.wait_for(future, timeout=wait_timeout)
    except asyncio.TimeoutError:
        with _lock:
            _pending.pop(request_id, None)
            _downloads.pop(request_id, None)
            _range_reads.pop(request_id, None)
        if operation == "download":
            try:
                os.remove(os.path.join(_transfer_dir, request_id + ".part"))
            except OSError:
                pass
        return {"request_id": request_id, "success": False, "error": "انتهت مهلة رد الهاتف"}


async def request_active(operation: str, path: str = "", **payload) -> dict:
    tokens = manager.active_tokens("phone_files")
    if not tokens:
        return {"success": False, "error": "لا يوجد هاتف متصل"}
    return await request(tokens[0], operation, path, **payload)
