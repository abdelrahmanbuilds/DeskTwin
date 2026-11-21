"""
DeskTwin Local API
--------------------
القاعدة الذهبية (docs/architecture/SYNC_SPEC.md بند 0): الكمبيوتر مصدر البيانات، التليفون واجهة خفيفة.
لذلك في التصميم هنا فرق واضح ومتعمّد بين:

    GET /files            -> بيرجع METADATA بس (خفيف، سريع، الافتراضي دايمًا)
    GET /files/{id}/content -> بيرجع محتوى الملف الفعلي (تقيل، وبيحصل فقط
                               لو المستخدم صراحة طلب كده - "افتحه" أو "خليه Linked")

هذا الفصل مش تفصيلة تقنية - هو انعكاس مباشر لقرار المنتج:
مفيش أي endpoint بيبعت bytes الملف من غير طلب صريح ومقصود.
"""

import os
import sys
import subprocess
import asyncio
import json
import time
import threading
import uuid
import shutil
from functools import lru_cache
from io import BytesIO
from typing import Optional

sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from fastapi import FastAPI, HTTPException, Query, Header, Depends, UploadFile, WebSocket, WebSocketDisconnect, Request
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel

from database.db import DeskTwinDB
from database.db import now_iso
from auth.pairing import PairingManager
from config.settings import DeskTwinConfig
from scanner.special_folders import get_special_folders, get_available_drives, get_home_path
from scanner.scanner import compute_hash, _iso
from sync.ws_manager import manager as ws_manager
from transfer import resumable as transfer_resumable
from security.tls import ensure_certificate
from security.monitor import monitor as security_monitor
from transfer.http_range import TransferFileResponse, file_range_response

app = FastAPI(title="DeskTwin Local API", version="0.1")


def _copy_upload_stream(source, destination: str) -> int:
    """Copy a spooled multipart upload on a worker thread with a large buffer."""
    source.seek(0)
    with open(destination, "wb") as target:
        shutil.copyfileobj(source, target, length=4 * 1024 * 1024)
        target.flush()
        return target.tell()
DESKTWIN_PROTOCOL_VERSION = "1.0"
db = DeskTwinDB()
pairing = PairingManager(db)
config = DeskTwinConfig.load()

# Cache خفيف لنتائج /browse: نحتفظ بالـ metadata فقط، وليس محتوى الملفات.
# يتجدد عند تغير وقت تعديل المجلد أو بعد مدة قصيرة حتى لا نعيد scandir
# مكلفًا مع كل ضغطة في واجهة الهاتف.
_browse_cache: dict[str, tuple[float, float, object]] = {}
_BROWSE_CACHE_TTL = 2.0
_event_loop = None
_stream_started_at: dict[tuple[str, str], float] = {}

def send_manual_text_to_devices(text: str) -> bool:
    if not _event_loop or not ws_manager.has_active_connection:
        return False
    message_id = uuid.uuid4().hex
    future = asyncio.run_coroutine_threadsafe(
        ws_manager.broadcast_json({"type": "manual_text", "id": message_id, "text": text}), _event_loop
    )
    try:
        recipients = future.result(timeout=3)
        if recipients < 1:
            return False
        from sync.manual_messages import add_message
        add_message(text, sender="computer", message_id=message_id, status="sent")
        return True
    except Exception:
        return False


@app.on_event("startup")
async def _on_startup():
    """
    بنمسك نسخة من الـ event loop الحالي (asyncio) عشان الـ clipboard
    watcher (اللي شغال في Thread عادي منفصل) يقدر يبعت رسائل عن طريق
    الـ WebSocket بأمان - راجع شرح تفصيلي في sync/clipboard_sync.py.
    """
    global _event_loop
    loop = asyncio.get_event_loop()
    _event_loop = loop
    transfer_resumable.start_cleanup_loop(db)


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket, token: Optional[str] = Query(None)):
    """
    قناة الاتصال الفوري (real-time) - النوع الوحيد المدعوم دلوقتي هو
    clipboard_update، ومستقبلًا هتستخدمها فيتشرات تانية (mirroring،
    أوامر تحكم عن بعد، إلخ) بنفس الطريقة: رسالة JSON فيها "type".

    التوكن يفضّل ييجي في X-Device-Token لتفادي وضعه في URL؛ query fallback
    مؤقت للتوافق مع العملاء الأقدم. التحقق يستخدم pairing.validate_token.
    """
    # Native DeskTwin clients do not send Origin. Browsers do, so reject them
    # before authentication to prevent a token exposed to a web page from
    # becoming a cross-site WebSocket connection.
    if websocket.headers.get("origin"):
        await websocket.close(code=4403)
        return
    token = token or websocket.headers.get("x-device-token", "")
    source = websocket.client.host if websocket.client else "unknown"
    # نفس حاجز المحاولات الموجود في REST API؛ قناة WebSocket كانت
    # تتحقق من التوكن لكنها لا تسجل محاولات الدخول الفاشلة ولا تمنع
    # المصدر الذي يكرر المحاولات بسرعة.
    if not security_monitor.allow(source):
        await websocket.close(code=4429)
        return
    if not pairing.validate_token(token):
        security_monitor.record_failure(source)
        await websocket.close(code=4401)
        return

    security_monitor.clear(source)

    await ws_manager.connect(websocket, token)
    message_count = 0
    window_started = asyncio.get_running_loop().time()
    try:
        while True:
            raw = await websocket.receive_text()
            now = asyncio.get_running_loop().time()
            if now - window_started >= 60:
                message_count = 0
                window_started = now
            if len(raw.encode("utf-8")) > 2 * 1024 * 1024:
                await websocket.close(code=1009)
                return
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                data = None
            message_count += 1
            if message_count > 120:
                await websocket.close(code=4429)
                return
            if not isinstance(data, dict):
                continue

            if data.get("type") == "manual_text":
                text = data.get("text", "")
                if isinstance(text, str) and 0 < len(text) <= 250_000:
                    message_id = str(data.get("id") or uuid.uuid4().hex)
                    from sync.manual_messages import add_message
                    add_message(text, message_id=message_id, status="delivered")
                    from notifications.notifications import notify_operation
                    notify_operation("رسالة جديدة من الهاتف", " ".join(text.split())[:140])
                    # لا نعتبر الإرسال ناجحًا على الهاتف قبل أن يستلم
                    # السيرفر الرسالة فعلًا؛ التأكيد يمنع حالة "اترسل"
                    # الكاذبة عند انقطاع WebSocket مباشرة بعد الفتح.
                    await websocket.send_text(json.dumps({"type": "manual_ack", "id": message_id}, ensure_ascii=False))
            elif data.get("type") == "manual_delivered":
                message_id = str(data.get("id") or "")
                if message_id:
                    from sync.manual_messages import update_status
                    update_status(message_id, "delivered")
    except WebSocketDisconnect:
        pass
    finally:
        ws_manager.disconnect(websocket)
        # فصل القناة يسجل في النشاط، لكنه لا يلغي الثقة؛ انقطاع Wi‑Fi مؤقتًا
        # يجب ألا يجبر المستخدم على Pairing جديد.
        db.log_activity(token, "disconnected", detail="انتهت قناة الاتصال")


@app.websocket("/ws/phone-files")
async def phone_files_websocket(websocket: WebSocket, token: Optional[str] = Query(None)):
    """Dedicated trusted TLS channel for phone filesystem metadata and bytes."""
    if websocket.headers.get("origin"):
        await websocket.close(code=4403)
        return
    token = token or websocket.headers.get("x-device-token", "")
    source = websocket.client.host if websocket.client else "unknown"
    if not security_monitor.allow(source):
        await websocket.close(code=4429)
        return
    if not pairing.validate_token(token):
        security_monitor.record_failure(source)
        await websocket.close(code=4401)
        return

    security_monitor.clear(source)
    binary_transfer = websocket.headers.get("x-desktwin-transfer") == "binary-v1"
    await ws_manager.connect(
        websocket, token, channel="phone_files", binary_transfer=binary_transfer
    )
    message_count = 0
    window_started = asyncio.get_running_loop().time()
    try:
        while True:
            message = await websocket.receive()
            if message["type"] == "websocket.disconnect":
                break
            binary = message.get("bytes")
            if binary is not None:
                if not binary_transfer:
                    await websocket.close(code=1003)
                    return
                from sync.phone_fs import accept_binary_chunk
                await accept_binary_chunk(binary)
                continue
            raw = message.get("text")
            if raw is None:
                continue
            now = asyncio.get_running_loop().time()
            if now - window_started >= 60:
                message_count = 0
                window_started = now
            if len(raw.encode("utf-8")) > 6 * 1024 * 1024:
                await websocket.close(code=1009)
                return
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if not isinstance(data, dict):
                continue
            message_type = data.get("type")
            if message_type not in {"phone_fs_chunk", "phone_fs_upload_chunk"}:
                message_count += 1
                if message_count > 120:
                    await websocket.close(code=4429)
                    return
            if message_type == "phone_fs_response":
                from sync.phone_fs import resolve_response, resolve_upload_ack
                if data.get("event") == "uploaded":
                    resolve_upload_ack(data)
                elif not resolve_upload_ack(data):
                    await resolve_response(data)
            elif message_type == "phone_fs_chunk":
                from sync.phone_fs import accept_chunk
                accept_chunk(data)
            elif message_type == "phone_fs_upload_ack":
                from sync.phone_fs import resolve_upload_ack
                resolve_upload_ack(data)
            elif message_type == "phone_fs_event":
                from sync.phone_fs import publish_event
                publish_event(data)
    except WebSocketDisconnect:
        pass
    finally:
        ws_manager.disconnect(websocket)
        db.log_activity(token, "disconnected", detail="انتهت قناة ملفات الهاتف")


# ---------- Auth Dependency ----------

def require_trusted_device(request: Request, x_device_token: Optional[str] = Header(None)):
    """
    كل endpoint (غير /pair/*) لازم يمر من هنا.
    الجهاز لازم يبعت التوكن الدائم اللي أخده وقت الـ pairing في الـ header:
        X-Device-Token: <token>
    """
    source = request.client.host if request.client else "unknown"
    if not security_monitor.allow(source):
        raise HTTPException(status_code=429, detail="تم إيقاف الطلبات مؤقتًا لحماية الاتصال. انتظر ثم أعد المحاولة.")
    if not pairing.validate_token(x_device_token):
        security_monitor.record_failure(source)
        raise HTTPException(status_code=401, detail="جهاز غير موثوق. لازم Pairing الأول.")
    security_monitor.clear(source)
    return x_device_token


# ---------- Pairing Models ----------

class PairingCodeResponse(BaseModel):
    code: str
    expires_at: str
    valid_for_minutes: int


class VerifyCodeRequest(BaseModel):
    code: str
    device_name: str = "Unknown Device"
    session_minutes: Optional[int] = None


class VerifyCodeResponse(BaseModel):
    success: bool
    device_token: Optional[str] = None
    error: Optional[str] = None
    certificate_fingerprint: Optional[str] = None
    error_code: Optional[str] = None
    solution: Optional[str] = None


class PairingDiagnosticsResponse(BaseModel):
    service: str
    protocol_version: str
    server_version: str
    pairing_endpoint: str
    tls_fingerprint: Optional[str] = None


class BindUserRequest(BaseModel):
    user_name: str


class BoundUserResponse(BaseModel):
    user_name: Optional[str] = None
    bound_at: Optional[str] = None


# ---------- Response Models ----------

class FileMetadata(BaseModel):
    id: int
    name: str
    path: str
    extension: Optional[str]
    parent_folder: Optional[str]
    size_bytes: int
    modified_at: Optional[str]
    created_at: Optional[str]
    hash: Optional[str]
    version: int
    project: Optional[str]
    sync_state: str
    sync_mode: str  # METADATA_ONLY | LINKED | PHONE_ONLY | PC_ONLY


class StatusResponse(BaseModel):
    status: str
    computer_name: str
    files_indexed: int


class CapabilitiesResponse(BaseModel):
    protocol_version: str
    server_version: str
    endpoints: list[str]

class IntegrationHealthResponse(BaseModel):
    status: str
    protocol_version: str
    server_version: str
    trust_level: str
    websocket: str
    chat: str
    endpoints: list[str]
    hints: list[str]


class DirEntry(BaseModel):
    """عنصر واحد في نتيجة تصفح فولدر - ملف أو فولدر فرعي."""
    name: str
    path: str
    is_folder: bool
    size_bytes: int
    modified_at: Optional[str]
    extension: Optional[str]
    # id الملف في جدول files لو كان مفهرَس بالفعل (من scan_all أو الـ
    # Watcher) - من غيره التليفون مش هيقدر يستخدم /files/{id}/content
    # ولا /files/{id}/availability، لأن endpoints التحميل دي شغالة على
    # الـ id بتاع الفهرس مش على المسار مباشرة. لو الملف لسه ما اتفهرسش
    # (مثلاً اتضاف على القرص لحظيًا وقبل ما الـ Watcher يلحقه)، القيمة
    # هنا بترجع None والموبايل يعرف إن التحميل مش متاح للملف ده مؤقتًا.
    file_id: Optional[int] = None


class BrowseResponse(BaseModel):
    current_path: str
    parent_path: Optional[str]  # None لو إحنا في أعلى نقطة مسموح نطلع منها
    entries: list[DirEntry]


class SpecialFolderEntry(BaseModel):
    name: str
    path: str


class DriveEntry(BaseModel):
    name: str
    path: str


class HomeResponse(BaseModel):
    home_path: str
    special_folders: list[SpecialFolderEntry]
    drives: list[DriveEntry]


class AddTargetRequest(BaseModel):
    path: str
    label: str = ""
    recursive: bool = True


class RemoveTargetRequest(BaseModel):
    path: str


class ApprovalRequest(BaseModel):
    action_type: str  # delete_file | rename_file | create_file | make_linked | overwrite_file | command_draft
    file_id: Optional[int] = None
    file_path: Optional[str] = None
    new_value: Optional[str] = None


class ApprovalResponse(BaseModel):
    approval_id: int
    status: str


class FileAvailabilityRequest(BaseModel):
    mode: str  # temporary | shared


class FileAvailabilityResponse(BaseModel):
    success: bool
    file_id: int
    sync_mode: str
    approval_id: Optional[int] = None


class FileOperationRequest(BaseModel):
    operation: str  # copy | move | delete | rename | mkdir | open
    source: str
    destination: Optional[str] = None
    new_name: Optional[str] = None
    collision_mode: str = "reject"


class FileOperationResponse(BaseModel):
    success: bool
    operation: str
    path: str
    destination: Optional[str] = None
    error: Optional[str] = None


class FileOperationBatchRequest(BaseModel):
    operations: list[FileOperationRequest]


class FileOperationBatchResponse(BaseModel):
    results: list[FileOperationResponse]
    succeeded: int
    failed: int


class DeviceActionRequest(BaseModel):
    action: str  # lock | logout | restart | shutdown | refresh


class DeviceActionResponse(BaseModel):
    success: bool
    action: str
    message: str


class RestoreRequest(BaseModel):
    original_path: str


class TrashItem(BaseModel):
    item_id: str
    original_path: str
    deleted_at: float


class SecurityStatusResponse(BaseModel):
    status: str
    blocked_sources: int
    recent_failed_attempts: int
    window_seconds: int


class FileVersionEntry(BaseModel):
    id: int
    file_path: str
    version_number: int
    stored_copy_path: str
    hash: Optional[str]
    size_bytes: Optional[int]
    source_device: str
    reason: str
    created_at: str


class CreateSavepointRequest(BaseModel):
    file_id: int


class ConflictEntry(BaseModel):
    id: int
    file_path: str
    pc_hash: Optional[str]
    pc_modified_at: Optional[str]
    phone_hash: Optional[str]
    phone_modified_at: Optional[str]
    detected_at: str
    status: str
    resolved_at: Optional[str]


class ResolveConflictRequest(BaseModel):
    resolution: str  # resolved_keep_pc | resolved_keep_phone | resolved_manual


# ---------- Pairing Endpoints (بلا حماية توكن - دي نقطة أول اتصال) ----------

@app.get("/pair/diagnostics", response_model=PairingDiagnosticsResponse)
def pairing_diagnostics():
    """فحص أولي آمن قبل إدخال كود الاقتران."""
    try:
        _, _, fingerprint = ensure_certificate()
    except Exception:
        fingerprint = None
    return PairingDiagnosticsResponse(
        service="online",
        protocol_version=DESKTWIN_PROTOCOL_VERSION,
        server_version=app.version,
        pairing_endpoint="/pair/verify-code",
        tls_fingerprint=fingerprint,
    )

@app.post("/pair/generate-code", response_model=PairingCodeResponse)
def generate_pairing_code(request: Request):
    """
    بتتنادى لما المستخدم يضغط 'Pair New Device' على شاشة الكمبيوتر.
    الكود ده هيظهر على شاشة الكمبيوتر بس - مش بيتبعت لحد.
    """
    if request.client and request.client.host not in {"127.0.0.1", "::1"}:
        raise HTTPException(status_code=403, detail="إنشاء كود الاقتران مسموح من الكمبيوتر المحلي فقط")
    result = pairing.generate_pairing_code()
    return PairingCodeResponse(**result)


@app.post("/pair/verify-code", response_model=VerifyCodeResponse)
def verify_pairing_code(request: VerifyCodeRequest, http_request: Request):
    """
    بتتنادى من تطبيق الموبايل بعد ما المستخدم كتب الكود اللي شافه على الكمبيوتر.
    لو الكود صح -> يترجع device_token دائم، والتطبيق يخزنه ويستخدمه في كل طلب بعد كده.
    """
    source = http_request.client.host if http_request.client else "unknown"
    if not security_monitor.allow(source):
        raise HTTPException(status_code=429, detail="محاولات الاقتران كثيرة. انتظر ثم أعد المحاولة.")
    result = pairing.verify_code_and_issue_token(request.code, request.device_name, request.session_minutes)
    if not result.get("success"):
        security_monitor.record_failure(source)
    else:
        security_monitor.clear(source)
    if not result.get("success"):
        error = result.get("error", "تعذر الاقتران")
        result["error_code"] = "PAIRING_CODE_INVALID"
        result["solution"] = "تأكد من إدخال الكود الحالي الظاهر على الكمبيوتر واطلب كودًا جديدًا إذا انتهت صلاحيته"
        if "انتهت" in error:
            result["error_code"] = "PAIRING_CODE_EXPIRED"
            result["solution"] = "أنشئ كود اقتران جديدًا من الكمبيوتر ثم أدخله خلال مدة صلاحيته"
    if result.get("success"):
        _, _, fingerprint = ensure_certificate()
        result["certificate_fingerprint"] = fingerprint
    return VerifyCodeResponse(**result)


# ---------- Single-User Binding ----------

@app.get("/user", response_model=BoundUserResponse)
def get_bound_user(_: str = Depends(require_trusted_device)):
    """المستخدم المربوط بالتطبيق حاليًا - لو مفيش حد لسه، ترجع قيم فاضية."""
    user = db.get_bound_user()
    if user is None:
        return BoundUserResponse()
    return BoundUserResponse(user_name=user["user_name"], bound_at=user["bound_at"])


@app.post("/user/bind", response_model=BoundUserResponse)
def bind_user_endpoint(request: BindUserRequest, _: str = Depends(require_trusted_device)):
    """
    أول ما شخص يسجل اسمه، التطبيق يترتبط بيه. مفيش قيد فعلي بيمنع
    جهاز تاني من الاتصال (ده شغل الـ Pairing)، لكن ده بيوضح للمستخدم
    نفسه "أنا مين اللي بستخدم الكمبيوتر ده من التليفون" في الواجهة.
    """
    db.bind_user(request.user_name)
    user = db.get_bound_user()
    return BoundUserResponse(user_name=user["user_name"], bound_at=user["bound_at"])


# ---------- Status ----------

@app.get("/status", response_model=StatusResponse)
def get_status(token: str = Depends(require_trusted_device)):
    """
    الموبايل بيستخدمها يعرف إن الكمبيوتر Online وعدد الملفات المفهرسة.
    دي كمان بالظبط نقطة "الثقة العمياء" الفعلية: الموبايل بينادي عليها
    بالتوكن المحفوظ فور ما يكتشف الكمبيوتر على الشبكة (mDNS)، من غير أي
    كود جديد أو تدخل من المستخدم. كل نداء ناجح هنا يسجَّل كـ auto-reconnect
    عشان يظهر للمستخدم في شاشة الأجهزة دليل واضح إن السلوك ده شغال فعليًا.
    """
    files = db.search_files(limit=1)  # مجرد تأكيد إن الـ DB شغالة
    with db.cursor() as cur:
        cur.execute("SELECT COUNT(*) as c FROM files WHERE sync_state != 'DELETED'")
        count = cur.fetchone()["c"]
    pairing.record_auto_reconnect(token)
    return StatusResponse(
        status="online",
        computer_name=os.environ.get("COMPUTERNAME", "DeskTwin-PC"),
        files_indexed=count,
    )


@app.get("/capabilities", response_model=CapabilitiesResponse)
def get_capabilities(_: str = Depends(require_trusted_device)):
    """عقد توافق يسمح للموبايل باكتشاف العمليات قبل استدعائها."""
    return CapabilitiesResponse(
        protocol_version=DESKTWIN_PROTOCOL_VERSION,
        server_version=app.version,
        endpoints=[
            "/status", "/browse", "/files", "/files/{id}/availability",
            "/files/{id}/show_in_explorer", "/files/{id}/open", "/files/operate",
            "/files/operate-batch", "/device/action", "/security/status",
            "/activity", "/files/{file_id}/versions", "/files/{file_id}/versions/savepoint",
            "/conflicts", "/conflicts/{conflict_id}/resolve",
            "/uploads/prepare", "/uploads/resumable/negotiate", "/sync/tree-diff",
            "/sync/batch/submit", "/approvals/request", "/targets/request",
        ],
    )

@app.get("/integration/health", response_model=IntegrationHealthResponse)
def integration_health(token: str = Depends(require_trusted_device)):
    capabilities = get_capabilities(token)
    trust = pairing.get_trust_level(token)
    websocket_online = token in ws_manager.active_tokens()
    hints = []
    if trust != "full":
        hints.append("اجعل الجهاز الموثوق بمستوى كامل لتشغيل أوامر الجهاز.")
    if not websocket_online:
        hints.append("قناة WebSocket غير مفتوحة؛ افتح Chat أو أعد الاتصال.")
    return IntegrationHealthResponse(
        status="healthy" if websocket_online else "needs_attention",
        protocol_version=DESKTWIN_PROTOCOL_VERSION,
        server_version=app.version,
        trust_level=trust,
        websocket="connected" if websocket_online else "offline",
        chat="connected" if websocket_online else "offline",
        endpoints=capabilities.endpoints,
        hints=hints,
    )


# ---------- Home / Special Folders (اختصارات ويندوز - Desktop, Downloads...) ----------

@app.get("/home", response_model=HomeResponse)
def get_home(_: str = Depends(require_trusted_device)):
    """
    نقطة البداية المنطقية لأي تصفح.

    - لو share_entire_device مفعّلة: نفس السلوك القديم بالظبط - المسار
      الرئيسي الحقيقي + اختصارات ويندوز القياسية (Desktop, Downloads...)
      + كل الـ Drives الحقيقية (C:, D:, ...).
    - لو مش مفعّلة (الوضع الافتراضي): الموبايل لازم يشوف بس الـ targets
      المفعّلة كـ "اختصارات" - مش أي مسار حقيقي تاني على الجهاز. ده تطبيق
      فعلي لما هو موثق في PROJECT_STATUS.md وdocs/architecture/SYNC_SPEC.md ("القاعدة
      الذهبية") - قبل هذا الإصلاح كان الـ endpoint بيرجّع اختصارات ودرايفات
      حقيقية دايمًا بغض النظر عن الإعدادات، وده كان بيسرّب مسارات برّه
      النطاق المسموح حتى لو /browse نفسها كانت هترفض الدخول عليها بعدين.
    """
    if config.share_entire_device:
        special = get_special_folders()
        drives = get_available_drives()
        return HomeResponse(
            home_path=get_home_path(),
            special_folders=[SpecialFolderEntry(name=n, path=p) for n, p in special.items()],
            drives=[DriveEntry(name=d["name"], path=d["path"]) for d in drives],
        )

    # النطاق الآمن الافتراضي: الاختصارات هي الـ targets المفعّلة نفسها
    target_shortcuts = [
        SpecialFolderEntry(name=(t.label or os.path.basename(t.normalized_path()) or t.path), path=t.path)
        for t in config.targets if t.enabled
    ]
    return HomeResponse(
        home_path=target_shortcuts[0].path if target_shortcuts else "",
        special_folders=target_shortcuts,
        drives=[],
    )


# ---------- Live Browse (تصفح مباشر لأي مسار - شجرة فولدرات حقيقية) ----------

@app.get("/browse", response_model=BrowseResponse)
def browse_path(
    path: str = Query(..., description="المسار المطلوب تصفحه"),
    token: str = Depends(require_trusted_device),
):
    """
    بيرجع محتويات فولدر معين مباشرة من نظام الملفات (مش من الفهرس المحفوظ) -
    عشان الموبايل يقدر يتصفح أي حتة على الكمبيوتر زي File Explorer حقيقي،
    لكن **بس جوه النطاق الآمن المسموح** (targets المفعّلة، أو الجهاز كامل
    لو share_entire_device مفعّلة صراحة من المستخدم). ده تطبيق فعلي للقاعدة
    الذهبية في docs/architecture/SYNC_SPEC.md - قبل هذا الإصلاح كان الـ endpoint بيسمح
    بتصفح أي مسار عشوائي على القرص بغض النظر عن الإعدادات.
    """
    if not os.path.isdir(path):
        raise HTTPException(status_code=404, detail="المسار مش موجود أو مش فولدر")

    if not config.is_path_allowed(path):
        raise HTTPException(status_code=403, detail="المسار ده خارج النطاق المسموح للموبايل")

    # تسجيل النشاط: "التليفون بيتصفح المجلد ده دلوقتي" - للعرض الحي في واجهة الكمبيوتر
    db.log_activity(token, "browsed_folder", detail=path)

    try:
        directory_mtime = os.stat(path).st_mtime
        cached = _browse_cache.get(os.path.normcase(os.path.abspath(path)))
        if cached and time.monotonic() - cached[0] < _BROWSE_CACHE_TTL and cached[1] == directory_mtime:
            return cached[2]
    except OSError:
        directory_mtime = 0.0

    entries = []
    try:
        with os.scandir(path) as it:
            for entry in it:
                try:
                    stat = entry.stat()
                    file_id = None
                    if not entry.is_dir():
                        # نبحث عن الملف في الفهرس بمساره الكامل - لو اتفهرس
                        # بالفعل (عادةً الحالة الطبيعية بعد أول scan) هيرجع
                        # id حقيقي يقدر التليفون يستخدمه للتحميل مباشرة.
                        existing = db.get_file_by_path(entry.path)
                        if existing:
                            file_id = existing["id"]
                    entries.append(DirEntry(
                        name=entry.name,
                        path=entry.path,
                        is_folder=entry.is_dir(),
                        size_bytes=0 if entry.is_dir() else stat.st_size,
                        modified_at=_iso_from_timestamp(stat.st_mtime),
                        extension=None if entry.is_dir() else os.path.splitext(entry.name)[1].lower(),
                        file_id=file_id,
                    ))
                except (PermissionError, OSError):
                    continue  # ملف/فولدر مالوش صلاحية وصول - نتجاهله بهدوء
    except PermissionError:
        raise HTTPException(status_code=403, detail="مفيش صلاحية للوصول للمجلد ده")

    # الترتيب: فولدرات الأول، بعدين ملفات، كل مجموعة أبجديًا
    entries.sort(key=lambda e: (not e.is_folder, e.name.lower()))

    parent = os.path.dirname(path.rstrip(os.sep))
    # لو وصلنا لجذر الـ drive نفسه (زي C:\)، مفيش parent نرجعله
    if parent == path or not parent:
        parent = None
    # لو الـ parent نفسه برّه النطاق المسموح (يعني احنا واقفين على جذر
    # target معين ومش عندنا share_entire_device) - منرجعوش عشان الموبايل
    # ميعرفش يطلع برّه النطاق عن طريق الضغط على "رجوع" في شجرة الفولدرات.
    if parent is not None and not config.is_path_allowed(parent):
        parent = None

    result = BrowseResponse(current_path=path, parent_path=parent, entries=entries)
    _browse_cache[os.path.normcase(os.path.abspath(path))] = (time.monotonic(), directory_mtime, result)
    # منع نمو الذاكرة بلا حدود لو المستخدم تصفح عددًا ضخمًا من المسارات.
    if len(_browse_cache) > 256:
        oldest = min(_browse_cache, key=lambda key: _browse_cache[key][0])
        _browse_cache.pop(oldest, None)
    return result


def _iso_from_timestamp(ts: float) -> str:
    from datetime import datetime, timezone
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()


# ---------- Metadata Endpoints (الافتراضي - خفيف دايمًا) ----------

@app.get("/files", response_model=list[FileMetadata])
def list_files(
    query: str = Query("", description="بحث بالاسم أو المسار"),
    project: Optional[str] = Query(None),
    sync_state: Optional[str] = Query(None),
    limit: int = Query(100, le=500),
    token: str = Depends(require_trusted_device),
):
    """
    بيرجع الـ METADATA بس لكل الملفات المطابقة - مفيش bytes ملفات هنا خالص.
    ده الـ endpoint الأساسي اللي شاشة "الملفات" على التليفون هتستخدمه.

    فلترة النطاق الآمن هنا بتحصل بعد الجلب من الـ DB (مش في الـ SQL نفسه)
    عشان limit يفضل معناه "أقصى عدد نتائج تترجع فعليًا للموبايل"، مش "أقصى
    عدد صف نقرأه من DB قبل الفلترة" - في المشاريع العادية (فولدرات قليلة
    نسبيًا مقابل عدد الملفات) الفرق مش محسوس، لكن يستاهل توضيح لو حد فتح
    الكود بعدين.
    """
    rows = db.search_files(query=query, project=project, sync_state=sync_state, limit=limit)
    allowed_rows = [r for r in rows if config.is_path_allowed(r["path"])]
    db.log_activity(token, "searched", detail=query or "كل الملفات")
    return [_row_to_metadata(r) for r in allowed_rows]


@app.get("/files/content_by_path")
def get_file_content_by_path(
    path: str = Query(..., description="المسار الكامل للملف على الكمبيوتر"),
    token: str = Depends(require_trusted_device),
):
    """
    بديل لـ /files/{file_id}/content لحالة واحدة تحديدًا: ملف ظهر لتوّه في
    /browse (اللي بتقرا القرص مباشرة real-time) لكن الـ Watcher لسه ماخدش
    وقته الطبيعي (debounce + حساب hash) عشان يفهرسه في الـ DB، فـ /browse
    رجّع file_id = null وميفيدش نطلب بيه المحتوى.

    هنا بدل ما نرفض الطلب بـ 404 ونسيب المستخدم يجرب تاني بعد شوية، نفهرس
    الملف فورًا (upsert) في نفس لحظة الطلب، ونكمّل تحميل المحتوى على طول -
    فمفيش أي تأخير أو رسالة "جرّب تاني" حسّاسة تظهر للمستخدم أصلًا.

    نفس فحوصات الأمان بالظبط زي /browse و/files/{file_id}/content -
    النطاق المسموح، والتأكد إن الملف موجود فعليًا على القرص.

    ملحوظة routing: الـ endpoint ده لازم يفضل متسجل قبل /files/{file_id}
    و/files/{file_id}/content - FastAPI بيطابق الـ routes بترتيب التسجيل،
    فلو {file_id} اتسجل الأول هيبلع أي طلب لـ /files/content_by_path على
    إنه file_id="content_by_path" ويطلع 422 (int_parsing) - وده بالظبط
    الخطأ اللي كان بيحصل قبل النقلة دي.
    """
    if not config.is_path_allowed(path):
        raise HTTPException(status_code=403, detail="الملف ده خارج النطاق المسموح للموبايل")

    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="الملف مش موجود على القرص")

    existing = db.get_file_by_path(path)
    if existing is None:
        # فهرسة فورية - نفس حقول الـ watcher بالظبط عشان الصف يطلع متسق
        # مع أي صف تاني اتعمل بالـ scanner أو بالـ watcher العادي.
        try:
            stat = os.stat(path)
        except (FileNotFoundError, PermissionError, OSError):
            raise HTTPException(status_code=404, detail="الملف مش موجود على القرص")

        file_hash = compute_hash(path, config.hash_size_limit_mb)
        db.upsert_file({
            "path": path,
            "name": os.path.basename(path),
            "extension": os.path.splitext(path)[1].lower(),
            "parent_folder": os.path.dirname(path),
            "size_bytes": stat.st_size,
            "created_at": _iso(stat.st_ctime),
            "modified_at": _iso(stat.st_mtime),
            "hash": file_hash,
            "project": None,
            "detected_by": "on_demand_content_request",
        })
        existing = db.get_file_by_path(path)

    from notifications.notifications import notify_transfer_started
    notify_transfer_started(path, "to_phone")
    db.log_activity(token, "downloaded_file", detail=path)
    return FileResponse(path, filename=os.path.basename(path))


@app.get("/files/{file_id}", response_model=FileMetadata)
def get_file_metadata(file_id: int, _: str = Depends(require_trusted_device)):
    """تفاصيل ملف واحد - لسه metadata بس."""
    with db.cursor() as cur:
        cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
        row = cur.fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")
    if not config.is_path_allowed(row["path"]):
        raise HTTPException(status_code=403, detail="الملف ده بقى خارج النطاق المسموح للموبايل")
    return _row_to_metadata(dict(row))


@app.post("/files/{file_id}/show_in_explorer")
def show_in_explorer(
    file_id: int,
    path: Optional[str] = Query(None, description="مسار حي لملف ظهر في التصفح المباشر"),
    token: str = Depends(require_trusted_device),
):
    """
    "افتح مكانه في File Explorer" - المستخدم بيدوس على الزرار من التليفون،
    وعلى الكمبيوتر يتفتح File Explorer فورًا مع تحديد (highlight) الملف
    نفسه - بالظبط زي "Show in folder" اللي بيظهر بعد تحميل ملف من المتصفح.

    يقبل مسار التصفح الحي أو رقم الفهرس؛ في الحالتين لازم الملف داخل
    النطاق المسموح وموجود فعلًا على القرص.
    """
    if path:
        if not config.is_path_allowed(path):
            raise HTTPException(status_code=403, detail="الملف خارج النطاق المسموح للموبايل")
    else:
        with db.cursor() as cur:
            cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
            row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")
        path = row["path"]
    if not config.is_path_allowed(path):
        raise HTTPException(status_code=403, detail="الملف ده خارج النطاق المسموح للموبايل")
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="الملف مش موجود على القرص دلوقتي")

    try:
        # explorer.exe /select بيفتح نافذة Explorer على المجلد الأب مع
        # عمل highlight للملف المطلوب - مفيش حاجة تانية محتاجين نعملها.
        # Popen مش subprocess.run لأن explorer.exe أحيانًا بيرجع exit code
        # غريب (مش 0) حتى لو نجح فعليًا - فمنعتمدش على انتظار النتيجة.
        # صيغة Explorer الدقيقة: تفتح المجلد الأب ثم تحدد نفس الملف، حتى
        # لو كان المسار على D: أو يحتوي مسافات؛ لا تفتح This PC فقط.
        subprocess.Popen(["explorer.exe", f"/select,{os.path.normpath(path)}"])
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"تعذر فتح File Explorer: {e}")

    db.log_activity(token, "opened_location", detail=path)
    from notifications.notifications import notify_operation
    notify_operation("فتح مكان ملف من الهاتف", os.path.basename(path))
    return {"success": True, "status": "executed", "operation": "show_in_explorer", "path": path}


@app.post("/files/{file_id}/open")
def open_file_on_computer(
    file_id: int,
    path: Optional[str] = Query(None, description="مسار حي لملف ظهر في التصفح المباشر"),
    token: str = Depends(require_trusted_device),
):
    """يعرض موقع الملف فقط؛ لا يشغّل محتوى من الهاتف عن بُعد."""
    if path:
        if not config.is_path_allowed(path):
            raise HTTPException(status_code=403, detail="الملف خارج النطاق المسموح")
    else:
        with db.cursor() as cur:
            cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
            row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")
        path = row["path"]
    if not config.is_path_allowed(path):
        raise HTTPException(status_code=403, detail="الملف خارج النطاق المسموح")
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="الملف مش موجود على القرص")
    try:
        subprocess.Popen(["explorer.exe", f"/select,{os.path.normpath(path)}"])
    except OSError as exc:
        raise HTTPException(status_code=500, detail=f"تعذر فتح موقع الملف: {exc}")
    db.log_activity(token, "opened_location", detail=path)
    from notifications.notifications import notify_operation
    notify_operation("فتح موقع ملف من الهاتف", os.path.basename(path))
    return {"success": True, "status": "executed", "operation": "show_in_explorer", "path": path}


@app.post("/files/{file_id}/set-wallpaper")
def set_wallpaper(file_id: int, token: str = Depends(require_trusted_device)):
    """تعيين صورة مفهرسة كخلفية Windows بدون صلاحيات مرتفعة."""
    with db.cursor() as cur:
        cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
        row = cur.fetchone()
    if row is None or not config.is_path_allowed(row["path"]):
        raise HTTPException(status_code=404, detail="الصورة غير موجودة أو خارج النطاق")
    path = row["path"]
    if not os.path.isfile(path) or os.path.splitext(path)[1].lower() not in {".jpg", ".jpeg", ".png", ".bmp"}:
        raise HTTPException(status_code=400, detail="الملف ليس صورة مدعومة")
    try:
        import ctypes
        ctypes.windll.user32.SystemParametersInfoW(20, 0, path, 3)
    except (AttributeError, OSError) as exc:
        raise HTTPException(status_code=500, detail=f"تعذر تعيين الخلفية: {exc}")
    db.log_activity(token, "set_wallpaper", detail=path)
    from notifications.notifications import notify_operation
    notify_operation("تغيير خلفية الكمبيوتر", os.path.basename(path))
    return {"success": True, "path": path}


@app.post("/files/{file_id}/availability", response_model=FileAvailabilityResponse)
def set_file_availability(file_id: int, request: FileAvailabilityRequest, token: str = Depends(require_trusted_device)):
    """تغيير وضع الملف مع موافقة الكمبيوتر عند طلب Linked."""
    if request.mode not in ("temporary", "shared"):
        raise HTTPException(status_code=400, detail="mode لازم يكون temporary أو shared")
    with db.cursor() as cur:
        cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
        row = cur.fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")
    if not config.is_path_allowed(row["path"]):
        raise HTTPException(status_code=403, detail="الملف خارج النطاق المسموح")
    if request.mode == "temporary":
        with db.cursor() as cur:
            cur.execute("UPDATE files SET sync_mode = 'METADATA_ONLY' WHERE id = ?", (file_id,))
        return FileAvailabilityResponse(success=True, file_id=file_id, sync_mode="METADATA_ONLY")
    approval_id = db.create_approval_request(
        device_token=token, action_type="make_linked", file_id=file_id,
        file_path=row["path"], new_value=None
    )
    return FileAvailabilityResponse(success=False, file_id=file_id, sync_mode=row["sync_mode"], approval_id=approval_id)


@app.post("/files/operate", response_model=FileOperationResponse)
def operate_file(request: FileOperationRequest, token: str = Depends(require_trusted_device)):
    """تنفيذ عملية ملف واحدة بعد إعادة فحص المسارات والصلاحيات."""
    operation = request.operation.lower()
    trust_level = pairing.get_trust_level(token)
    if operation in {"delete", "move", "rename", "mkdir", "open"} and trust_level == "standard":
        raise HTTPException(status_code=403, detail="العملية حساسة وتحتاج موافقة أو رفع مستوى ثقة الجهاز من صفحة الأجهزة")
    source = os.path.normpath(os.path.abspath(request.source.strip().strip('"')))
    if operation not in {"copy", "move", "delete", "rename", "mkdir", "open"}:
        raise HTTPException(status_code=400, detail="عملية ملفات غير مدعومة")
    if request.collision_mode not in {"reject", "duplicate"}:
        raise HTTPException(status_code=400, detail="collision_mode لازم يكون reject أو duplicate")
    if request.collision_mode == "duplicate" and operation != "copy":
        raise HTTPException(status_code=400, detail="collision_mode=duplicate متاح لعملية النسخ فقط")
    if operation != "mkdir" and not os.path.exists(source):
        raise HTTPException(status_code=409, detail="المصدر غير موجود وقت التنفيذ")
    if not config.is_path_allowed(source):
        raise HTTPException(status_code=403, detail="المصدر خارج النطاق المسموح")
    destination = None
    if operation in {"copy", "move"}:
        if not request.destination:
            raise HTTPException(status_code=400, detail="المسار الهدف مطلوب")
        destination = os.path.normpath(os.path.abspath(request.destination.strip().strip('"')))
        if not config.is_path_allowed(destination):
            raise HTTPException(status_code=403, detail="المسار الهدف خارج النطاق المسموح")
        if os.path.exists(destination) and request.collision_mode == "reject":
            raise HTTPException(status_code=409, detail="يوجد عنصر بالفعل في المسار الهدف")
    elif operation == "rename":
        if not request.new_name or os.path.basename(request.new_name) != request.new_name:
            raise HTTPException(status_code=400, detail="اسم جديد غير صالح")
        destination = os.path.join(os.path.dirname(source), request.new_name)
        if os.path.exists(destination):
            raise HTTPException(status_code=409, detail="يوجد عنصر بنفس الاسم")
    elif operation == "mkdir":
        destination = source
        parent = os.path.dirname(source)
        if not config.is_path_allowed(parent):
            raise HTTPException(status_code=403, detail="المجلد الأب خارج النطاق المسموح")
        if os.path.exists(source):
            raise HTTPException(status_code=409, detail="المجلد موجود بالفعل")
    try:
        if operation == "copy":
            from storage.file_operations import copy_without_overwrite
            destination = copy_without_overwrite(
                source,
                destination,
                duplicate_on_conflict=request.collision_mode == "duplicate",
            )
        elif operation == "move": shutil.move(source, destination)
        elif operation == "delete":
            from storage.trash import move_to_trash
            move_to_trash(source)
        elif operation == "rename": os.rename(source, destination)
        elif operation == "mkdir": os.makedirs(source)
        elif operation == "open": subprocess.Popen(["explorer.exe", f"/select,{os.path.normpath(source)}"])
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=f"صلاحية Windows مرفوضة: {exc}")
    except OSError as exc:
        raise HTTPException(status_code=409, detail=f"فشل تنفيذ العملية: {exc}")
    operation_label = {"copy": "نسخ ملف", "move": "نقل ملف", "delete": "حذف ملف", "rename": "إعادة تسمية ملف", "mkdir": "إنشاء مجلد", "open": "فتح مكان ملف"}[operation]
    try:
        db.log_activity(token, operation, detail=f"{source} -> {destination or source}")
    except Exception as exc:
        print(f"[API] العملية {operation} نجحت لكن تعذر تسجيلها في السجل: {exc}")
    try:
        from notifications.notifications import notify_operation
        notify_operation(operation_label, os.path.basename(source))
    except Exception as exc:
        print(f"[API] العملية {operation} نجحت لكن تعذر إرسال الإشعار: {exc}")
    return FileOperationResponse(success=True, operation=operation, path=source, destination=destination)


@app.post("/files/operate-batch", response_model=FileOperationBatchResponse)
def operate_file_batch(request: FileOperationBatchRequest, token: str = Depends(require_trusted_device)):
    """تنفيذ عمليات جماعية بترتيب ثابت مع نتيجة مستقلة لكل عنصر."""
    if not request.operations:
        raise HTTPException(status_code=400, detail="قائمة العمليات فارغة")
    if len(request.operations) > 1000:
        raise HTTPException(status_code=400, detail="عدد العمليات أكبر من الحد المسموح")

    results: list[FileOperationResponse] = []
    for operation in request.operations:
        try:
            results.append(operate_file(operation, token))
        except HTTPException as exc:
            results.append(FileOperationResponse(
                success=False,
                operation=operation.operation,
                path=operation.source,
                destination=operation.destination,
                error=str(exc.detail),
            ))
        except Exception as exc:
            results.append(FileOperationResponse(
                success=False,
                operation=operation.operation,
                path=operation.source,
                destination=operation.destination,
                error=str(exc),
            ))
    return FileOperationBatchResponse(
        results=results,
        succeeded=sum(1 for result in results if result.success),
        failed=sum(1 for result in results if not result.success),
    )


@app.post("/device/action", response_model=DeviceActionResponse)
def device_action(request: DeviceActionRequest, token: str = Depends(require_trusted_device)):
    """الإجراءات المتاحة للأجهزة المقترنة؛ وتتطلب الأوامر المدمرة ثقة كاملة."""
    action = request.action.lower()
    if action not in {"lock", "logout", "restart", "shutdown", "refresh", "this_pc", "recycle_bin", "control_panel", "settings", "microsoft_store", "volume_up", "volume_down", "volume_mute", "brightness_up", "brightness_down"}:
        raise HTTPException(status_code=400, detail="أمر جهاز غير مدعوم")
    if action in {"lock", "logout", "restart", "shutdown"} and pairing.get_trust_level(token) != "full":
        raise HTTPException(status_code=403, detail="هذا الإجراء يحتاج جهازًا بمستوى ثقة كامل")
    try:
        deferred_action = None
        if action == "lock":
            # نؤجل الأوامر التي قد تقفل/تقطع الجلسة جزءًا صغيرًا من الثانية
            # حتى يصل رد HTTP المؤكد للهاتف أولًا.
            deferred_action = lambda: subprocess.Popen(["rundll32.exe", "user32.dll,LockWorkStation"])
        elif action == "logout":
            def logout_after_confirmation():
                try:
                    db.log_activity(token, "disconnected", detail="تسجيل خروج من الهاتف")
                except Exception as exc:
                    print(f"[API] تعذر تسجيل طلب تسجيل الخروج: {exc}")
                try:
                    pairing.revoke_token(token)
                except Exception as exc:
                    print(f"[API] تعذر إلغاء اعتماد الجهاز قبل تسجيل الخروج: {exc}")
                subprocess.Popen(["shutdown", "/l"])
            deferred_action = logout_after_confirmation
        elif action == "restart":
            deferred_action = lambda: subprocess.Popen(["shutdown", "/r", "/t", "5"])
        elif action == "shutdown":
            deferred_action = lambda: subprocess.Popen(["shutdown", "/s", "/t", "5"])
        elif action == "refresh":
            # The phone reloads its current home/folder after this acknowledgement.
            # Drop server-side snapshots first so that fetch cannot return stale entries.
            _browse_cache.clear()
        elif action == "this_pc":
            os.startfile("shell:MyComputerFolder")
        elif action == "recycle_bin":
            os.startfile("shell:RecycleBinFolder")
        elif action == "control_panel":
            subprocess.Popen(["control.exe"])
        elif action == "settings":
            os.startfile("ms-settings:")
        elif action == "microsoft_store":
            os.startfile("ms-windows-store:")
        elif action in {"volume_up", "volume_down", "volume_mute"}:
            import ctypes
            key = {"volume_up": 0xAF, "volume_down": 0xAE, "volume_mute": 0xAD}[action]
            ctypes.windll.user32.keybd_event(key, 0, 0, 0)
            ctypes.windll.user32.keybd_event(key, 0, 2, 0)
        elif action in {"brightness_up", "brightness_down"}:
            # powercfg يغير إعداد خطة الطاقة فقط، ولا يغير ضوء شاشة اللابتوب
            # عند كثير من التعريفات. WMI يتعامل مع شاشة الجهاز مباشرة.
            step = 10 if action == "brightness_up" else -10
            script = (
                "$current = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness -ErrorAction Stop | Select-Object -First 1; "
                f"$target = [Math]::Max(0, [Math]::Min(100, [int]$current.CurrentBrightness + ({step}))); "
                "$method = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods -ErrorAction Stop | Select-Object -First 1; "
                "Invoke-CimMethod -InputObject $method -MethodName WmiSetBrightness -Arguments @{Timeout=1;Brightness=$target} -ErrorAction Stop | Out-Null"
            )
            subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                check=True, capture_output=True, text=True,
                timeout=8,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        action_label = {
            "this_pc": "فتح This PC", "recycle_bin": "فتح سلة المحذوفات",
            "control_panel": "فتح لوحة التحكم", "settings": "فتح إعدادات Windows",
            "microsoft_store": "فتح Microsoft Store", "brightness_up": "رفع سطوع الشاشة",
            "brightness_down": "خفض سطوع الشاشة", "volume_up": "رفع الصوت",
            "volume_down": "خفض الصوت", "volume_mute": "كتم الصوت",
            "refresh": "تحديث الملفات", "lock": "قفل الكمبيوتر", "restart": "إعادة تشغيل الكمبيوتر",
            "shutdown": "إيقاف الكمبيوتر", "logout": "تسجيل الخروج",
        }[action]
        if deferred_action is not None:
            timer = threading.Timer(0.35, deferred_action)
            timer.daemon = True
            timer.start()
            result = DeviceActionResponse(
                success=True,
                action=action,
                message="تم قبول الطلب وجدولته للتنفيذ خلال لحظات",
            )
        else:
            result = DeviceActionResponse(
                success=True,
                action=action,
                message="تم طلب تحديث الملفات" if action == "refresh" else "تم تنفيذ الأمر على الكمبيوتر",
            )
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(
            status_code=504,
            detail="انتهت مهلة تنفيذ أمر التحكم في السطوع على Windows. أعد المحاولة؛ لم يصل تأكيد بالتنفيذ.",
        ) from exc
    except subprocess.CalledProcessError as exc:
        diagnostic = " ".join(str(exc.stderr or exc.stdout or "").split())[:240]
        detail = "تعذر تغيير سطوع الشاشة. قد لا تدعم الشاشة التحكم البرمجي."
        if diagnostic:
            detail += f" تفاصيل Windows: {diagnostic}"
        raise HTTPException(status_code=500, detail=detail) from exc
    except OSError as exc:
        raise HTTPException(status_code=500, detail=f"تعذر تنفيذ الأمر: {exc}")

    # The OS action (or scheduling) has succeeded. Audit and toast failures must
    # not turn that success into an HTTP error that misleads the phone.
    try:
        if action != "logout":
            db.log_activity(token, "device_action", detail=action)
    except Exception as exc:
        print(f"[API] أمر الجهاز {action} تم لكن تعذر تسجيله في السجل: {exc}")
    try:
        from notifications.notifications import notify_operation
        notify_operation(action_label, "طلب من الهاتف")
    except Exception as exc:
        print(f"[API] أمر الجهاز {action} تم لكن تعذر إرسال الإشعار: {exc}")
    return result


@app.post("/trash/restore")
def restore_trash_item(request: RestoreRequest, token: str = Depends(require_trusted_device)):
    """Undo للحذف: استعادة عنصر من Windows Recycle Bin لمساره الأصلي."""
    from storage.trash import restore_from_recycle_bin
    if restore_from_recycle_bin(request.original_path):
        db.log_activity(token, "restored_from_recycle_bin", detail=request.original_path)
        return {"success": True, "path": request.original_path}
    raise HTTPException(status_code=404, detail="العنصر غير موجود في Recycle Bin أو تم حذفه نهائيًا")


@app.post("/trash/delete-permanently")
def permanently_delete_trash_item(request: RestoreRequest, token: str = Depends(require_trusted_device)):
    from storage.trash import permanently_delete_from_recycle_bin
    if permanently_delete_from_recycle_bin(request.original_path):
        db.log_activity(token, "permanently_deleted_file", detail=request.original_path)
        from notifications.notifications import notify_operation
        notify_operation("حذف نهائي من سلة المحذوفات", os.path.basename(request.original_path))
        return {"success": True, "path": request.original_path}
    raise HTTPException(status_code=404, detail="العنصر غير موجود في Recycle Bin أو تعذر حذفه نهائيًا")


@app.get("/trash", response_model=list[TrashItem])
def list_trash(token: str = Depends(require_trusted_device)):
    from storage.trash import TRASH_DIR
    items = []
    if os.path.isdir(TRASH_DIR):
        for name in os.listdir(TRASH_DIR):
            if not name.endswith(".json"):
                continue
            try:
                with open(os.path.join(TRASH_DIR, name), encoding="utf-8") as handle:
                    data = json.load(handle)
                items.append(TrashItem(**data))
            except (OSError, ValueError):
                continue
    return items


@app.get("/security/status", response_model=SecurityStatusResponse)
def security_status(_: str = Depends(require_trusted_device)):
    """حالة الحماية الحالية للجهاز الموثوق فقط."""
    return SecurityStatusResponse(**security_monitor.snapshot())


@app.get("/projects")
def list_projects(_: str = Depends(require_trusted_device)):
    """أسماء المشاريع (الـ targets) المتاحة - لعرضها في شاشة Projects على الموبايل."""
    with db.cursor() as cur:
        cur.execute(
            "SELECT project, COUNT(*) as file_count FROM files "
            "WHERE project IS NOT NULL AND sync_state != 'DELETED' "
            "GROUP BY project"
        )
        return [dict(row) for row in cur.fetchall()]


# ---------- Content Endpoint (الاستثناء - تقيل، طلب صريح فقط) ----------

@app.get("/files/{file_id}/content")
def get_file_content(
    file_id: int,
    request: Request,
    stream: bool = Query(False),
    path: Optional[str] = Query(None, description="مسار حي لملف ظهر في التصفح المباشر"),
    token: str = Depends(require_trusted_device),
):
    """
    بيرجع bytes الملف الفعلي.
    هذا الـ endpoint الوحيد اللي بيبعت محتوى حقيقي - ومينفعش يتنادى تلقائيًا
    من قائمة الملفات؛ لازم يكون فعل صريح من المستخدم (فتح / تحميل / Link).

    لو sync_mode = METADATA_ONLY، النظام مش هيمنع الطلب (المستخدم عنده حرية
    يفتح أي ملف وقت ما يحب) لكن التطبيق على التليفون هو المسؤول يوضح للمستخدم
    إن ده هيحمّل الملف فعليًا على مساحته - القرار وواجهة التأكيد دي مسؤولية
    الـ Android Client، مش الـ API.
    """
    row = None
    if path:
        # /browse يرى القرص مباشرة؛ الملفات غير المفهرسة تحمل file_id=null
        # ويحوّلها Android إلى 0. المسار الحي يتجاوز الفهرس فقط بعد فحص
        # النطاق المسموح ووجود ملف فعلي، فلا نحتاج hash أو فهرسة فيديو ضخم.
        file_path = path
        if not config.is_path_allowed(file_path):
            raise HTTPException(status_code=403, detail="الملف خارج النطاق المسموح للموبايل")
        if not os.path.isfile(file_path):
            raise HTTPException(status_code=404, detail="الملف مش موجود على القرص")
        file_name = os.path.basename(file_path)
    else:
        with db.cursor() as cur:
            cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
            row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")

        file_path = row["path"]
        # يظل مسار الفهرس خاضعًا لفحص النطاق حتى لو تغيّرت إعدادات المشاركة.
        if not config.is_path_allowed(file_path):
            raise HTTPException(status_code=403, detail="الملف ده بقى خارج النطاق المسموح للموبايل")
        if not os.path.exists(file_path):
            raise HTTPException(status_code=410, detail="الملف اتمسح من على الكمبيوتر")
        file_name = row["name"]

    if stream:
        # Media3 يطلب ranges متتابعة؛ نسجل بداية الجلسة فقط لا كل جزء.
        key = (token, os.path.normcase(os.path.abspath(file_path)))
        now = time.monotonic()
        if now - _stream_started_at.get(key, 0) > 30:
            _stream_started_at[key] = now
            from notifications.notifications import notify_operation
            notify_operation("بدء بث فيديو للهاتف", os.path.basename(file_path))
            db.log_activity(token, "streamed_video", detail=file_path)
    else:
        from notifications.notifications import notify_transfer_started
        notify_transfer_started(file_path, "to_phone")
        db.log_activity(token, "downloaded_file", detail=file_path)
    if stream:
        # The pinned Starlette version used by this app predates FileResponse's
        # Range support. Serve ranges explicitly so Media3 can seek to MP4's
        # index (often stored at the end) without downloading the whole video.
        return file_range_response(file_path, file_name, request.headers.get("range"))
    return TransferFileResponse(file_path, filename=file_name)


@lru_cache(maxsize=128)
def _cached_file_thumbnail(file_path: str, modified_ns: int, size_bytes: int, extension: str) -> bytes:
    image_extensions = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".tif", ".tiff"}
    video_extensions = {".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".wmv"}
    if extension in image_extensions:
        from PIL import Image, ImageOps

        with Image.open(file_path) as image:
            if image.width * image.height > 40_000_000:
                raise ValueError("Image is too large to create a thumbnail safely")
            thumbnail = ImageOps.exif_transpose(image)
            thumbnail.thumbnail((480, 480), Image.Resampling.LANCZOS)
            if thumbnail.mode != "RGB":
                thumbnail = thumbnail.convert("RGB")
            output = BytesIO()
            thumbnail.save(output, format="JPEG", quality=76, optimize=True)
            return output.getvalue()

    if extension in video_extensions:
        import imageio_ffmpeg

        command = [
            imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-loglevel", "error",
            "-ss", "1", "-i", file_path, "-frames:v", "1",
            "-vf", "scale=480:480:force_original_aspect_ratio=decrease",
            "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "6", "pipe:1",
        ]
        result = subprocess.run(command, capture_output=True, timeout=12, check=False)
        if result.returncode != 0 or not result.stdout:
            raise ValueError("Video frame could not be decoded")
        return result.stdout

    raise ValueError("This file type has no thumbnail")


@app.get("/files/{file_id}/thumbnail")
def get_file_thumbnail(
    file_id: int,
    path: Optional[str] = Query(None, description="مسار حي لملف ظهر في التصفح المباشر"),
    _: str = Depends(require_trusted_device),
):
    """Return a small cached image/video preview after the same path-scope check as file reads."""
    if path:
        file_path = path
    else:
        with db.cursor() as cur:
            cur.execute("SELECT path FROM files WHERE id = ?", (file_id,))
            row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")
        file_path = row["path"]

    if not config.is_path_allowed(file_path):
        raise HTTPException(status_code=403, detail="الملف خارج النطاق المسموح للموبايل")
    if not os.path.isfile(file_path):
        raise HTTPException(status_code=404, detail="الملف مش موجود على القرص")

    stat = os.stat(file_path)
    extension = os.path.splitext(file_path)[1].lower()
    try:
        thumbnail = _cached_file_thumbnail(file_path, stat.st_mtime_ns, stat.st_size, extension)
    except ImportError as exc:
        raise HTTPException(status_code=503, detail="مكوّن إنشاء المعاينات غير متاح") from exc
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(status_code=504, detail="انتهت مهلة إنشاء معاينة الفيديو") from exc
    except Exception as exc:
        raise HTTPException(status_code=415, detail="تعذر إنشاء معاينة لهذا الملف") from exc

    return Response(
        content=thumbnail,
        media_type="image/jpeg",
        headers={"Cache-Control": "private, max-age=300"},
    )


# ---------- Upload Endpoint (رفع ملف من الموبايل للكمبيوتر) ----------

class PrepareUploadRequest(BaseModel):
    target_folder: str
    file_name: str


class PrepareUploadResponse(BaseModel):
    collision: bool
    destination: str
    existing_file_id: Optional[int] = None
    approval_id: Optional[int] = None  # لو فيه تصادم، لازم موافقة قبل replace


@app.post("/uploads/prepare", response_model=PrepareUploadResponse)
def prepare_upload(request: PrepareUploadRequest, token: str = Depends(require_trusted_device)):
    """
    خطوة تحضير قبل الرفع الفعلي: الموبايل بيسأل "لو رفعت الملف ده بالاسم ده
    في المكان ده، هل هيحصل تصادم؟" من غير ما يبعت أي bytes لسه.

    - لو مفيش تصادم: destination جاهز، الموبايل يكمل ويرفع مباشرة بـ /upload
      مع collision_mode=duplicate (السلوك الافتراضي الآمن أصلاً).
    - لو فيه تصادم: بيتسجل approval request بحالة 'pending' (action_type
      'overwrite_file') ويرجع approval_id. الموبايل يستنى موافقة المستخدم
      من واجهة الكمبيوتر (GET /approvals/{id}) قبل ما يرفع بـ
      collision_mode=replace - نفس القاعدة الذهبية: لا استبدال صامت أبدًا.
    """
    target_folder = os.path.normpath(os.path.abspath(request.target_folder.strip().strip('"')))
    if not os.path.isdir(target_folder):
        raise HTTPException(status_code=400, detail=f"المجلد الهدف غير موجود أو غير قابل للوصول: {target_folder}")

    if not config.is_path_allowed(target_folder):
        raise HTTPException(status_code=403, detail="المجلد الهدف خارج النطاق المسموح للموبايل")

    safe_name = os.path.basename(request.file_name)
    destination = os.path.join(target_folder, safe_name)

    if not os.path.exists(destination):
        return PrepareUploadResponse(collision=False, destination=destination)

    existing_file_row = db.get_file_by_path(destination)
    existing_file_id = existing_file_row["id"] if existing_file_row else None

    approval_id = db.create_approval_request(
        device_token=token,
        action_type="overwrite_file",
        file_id=existing_file_id,
        file_path=destination,
        new_value=None,
    )

    return PrepareUploadResponse(
        collision=True,
        destination=destination,
        existing_file_id=existing_file_id,
        approval_id=approval_id,
    )


@app.post("/upload")
async def upload_file(
    file: UploadFile,
    target_folder: str = Query(..., description="المسار على الكمبيوتر اللي الملف هيتحفظ فيه"),
    collision_mode: str = Query("duplicate", description="duplicate (الافتراضي) أو replace (لازم approval_id موافق عليه)"),
    approval_id: Optional[int] = Query(None, description="approval_id من /uploads/prepare - مطلوب فقط لو collision_mode=replace"),
    token: str = Depends(require_trusted_device),
):
    """
    الموبايل بيبعت ملف حقيقي (bytes) هنا. السيناريو الكامل:
        1. المستخدم لقى ملف مهم وهو بره البيت (زي مستند شغل) وحطه في DeskTwin
        2. الموبايل يستنى لحد ما يلاقي الكمبيوتر على نفس الشبكة (Trusted connection)
        3. بمجرد الاتصال، الموبايل يبعت الملف تلقائيًا هنا (من غير استهلاك نت -
           الاتصال ده LAN بحت زي أي endpoint تاني)
        4. الكمبيوتر يحفظ الملف في target_folder، ويرجّع تأكيد بمساره ومعرّفه
        5. الموبايل بعد التأكيد، يقرر يمسح نسخته المحلية ويحولها Metadata
           (أو يسيبها حسب اختيار المستخدم - القرار ده في التطبيق، مش هنا)

    collision_mode:
        - 'duplicate' (افتراضي): لو فيه تصادم بالاسم، يضيف رقم بدل ما يكتب
          فوق الملف الموجود - السلوك الآمن دايمًا، مش محتاج approval.
        - 'replace': يكتب فوق الملف الموجود فعليًا - **لازم approval_id
          صالح وموافَق عليه من /uploads/prepare**، وبيتستهلك مرة واحدة بس
          (القاعدة الذهبية: موافقة الاستبدال متتكررش لعملية رفع تانية).
    """
    if collision_mode not in ("duplicate", "replace"):
        raise HTTPException(status_code=400, detail="collision_mode لازم يكون duplicate أو replace")

    target_folder = os.path.normpath(os.path.abspath(target_folder.strip().strip('"')))
    if not os.path.isdir(target_folder):
        raise HTTPException(status_code=400, detail=f"المجلد الهدف غير موجود أو غير قابل للوصول: {target_folder}")

    if not config.is_path_allowed(target_folder):
        # طبقة حماية ثانية (defense in depth): حتى لو /uploads/prepare
        # اتخطّاها الموبايل بالكامل ونادى /upload مباشرة، النطاق الآمن
        # لازم يتفحص هنا كمان - مش بس في مرحلة التحضير.
        raise HTTPException(status_code=403, detail="المجلد الهدف خارج النطاق المسموح للموبايل")

    safe_name = os.path.basename((file.filename or "").replace("/", os.sep).replace("\\", os.sep)).strip()
    if not safe_name or safe_name in (".", ".."):
        raise HTTPException(status_code=400, detail="اسم الملف غير صالح")
    destination = os.path.join(target_folder, safe_name)

    if collision_mode == "replace":
        if approval_id is None:
            raise HTTPException(status_code=400, detail="collision_mode=replace محتاج approval_id")

        approval = db.get_approval(approval_id)
        if approval is None:
            raise HTTPException(status_code=404, detail="approval_id مش موجود")
        if approval["action_type"] != "overwrite_file":
            raise HTTPException(status_code=400, detail="الـ approval ده مش نوعه overwrite_file")
        if approval["file_path"] != destination:
            raise HTTPException(status_code=400, detail="الـ approval ده مش لنفس المسار الهدف")
        if approval["status"] != "approved":
            raise HTTPException(status_code=403, detail=f"الـ approval لسه مش موافَق عليه (الحالة: {approval['status']})")

        # الاستهلاك الفعلي مرة واحدة بس: أول ما نستخدمها هنا، نحولها لحالة
        # منتهية عشان مينفعش نفس approval_id يتستخدم لعملية رفع تانية.
        with db.cursor() as cur:
            cur.execute(
                "UPDATE pending_approvals SET status = 'expired' WHERE id = ? AND status = 'approved'",
                (approval_id,)
            )
            consumed = cur.rowcount > 0
        if not consumed:
            # حصل race condition (approval اتستهلك من طلب متزامن قبلنا بلحظة)
            raise HTTPException(status_code=409, detail="الـ approval اتستهلك بالفعل")

        # نعمل savepoint للملف القديم قبل ما نكتب فوقه - حماية إضافية
        # (لو الملف الموجود مش متسجل في version_retention كـ Linked، برضو
        # الأولى نحتفظ بنسخة منه بدل ما يضيع للأبد لو الاستبدال غلط).
        if os.path.exists(destination):
            try:
                _make_savepoint(destination, source_device="pc", reason="pre_sync_backup")
            except FileNotFoundError:
                pass  # اختفى الملف بين التحقق والتنفيذ - نكمل عادي، مفيش نسخة نحفظها
    else:
        # duplicate: لو فيه ملف بنفس الاسم، نحافظ عليه ونضيف رقم -
        # مانكتبش فوقه من غير علم المستخدم
        if os.path.exists(destination):
            base, ext = os.path.splitext(safe_name)
            counter = 1
            while os.path.exists(destination):
                destination = os.path.join(target_folder, f"{base} ({counter}){ext}")
                counter += 1

    try:
        # Multipart parsing has already spooled the upload. Copy it to its
        # destination in one worker operation, avoiding two thread-pool hops
        # per MiB and keeping disk work off the server event loop.
        await asyncio.to_thread(_copy_upload_stream, file.file, destination)
    except (OSError, PermissionError) as e:
        raise HTTPException(status_code=500, detail=f"فشل حفظ الملف: {e}")

    db.log_activity(token, "uploaded_file", detail=destination)

    # نضيفه فورًا للفهرس عشان يظهر بحالة NEW من غير ما نستنى الـ Scanner القادم
    stat = os.stat(destination)
    from scanner.scanner import compute_hash, _iso
    file_hash = await asyncio.to_thread(compute_hash, destination, config.hash_size_limit_mb)
    file_info = {
        "path": destination,
        "name": os.path.basename(destination),
        "extension": os.path.splitext(destination)[1].lower(),
        "parent_folder": target_folder,
        "size_bytes": stat.st_size,
        "created_at": _iso(stat.st_ctime),
        "modified_at": _iso(stat.st_mtime),
        "hash": file_hash,
        "project": None,
        "detected_by": "watcher",
    }
    db.upsert_file(file_info)

    with db.cursor() as cur:
        cur.execute("SELECT id FROM files WHERE path = ?", (destination,))
        new_file_id = cur.fetchone()["id"]

    # إشعار Windows: الملف اتحفظ وانفهرس فعليًا - أنسب لحظة للإشعار (مش
    # قبل كده، عشان لو الحفظ أو الفهرسة فشلوا، مفيش إشعار كاذب يطلع).
    # الدالة دي شغالة في Thread منفصل جوّاها، فمفيش أي تأخير هنا على رد الموبايل.
    from notifications.notifications import notify_new_file
    notify_new_file(destination, stat.st_size, file_info["extension"])

    return {
        "success": True,
        "file_id": new_file_id,
        "saved_path": destination,
        "size_bytes": stat.st_size,
    }


# ---------- Resumable Transfers (نقل الملفات الكبيرة على أجزاء - رفع من التليفون) ----------
# راجع transfer/resumable.py للشرح الكامل للفكرة والبروتوكول.
# ده مسار منفصل تمامًا عن /upload العادي (اللي بيقرا الملف كله في
# الذاكرة دفعة واحدة بـ await file.read() - غير عملي خالص لملفات الجيجا).
# هنا كل حاجة بتتكتب جزء جزء على القرص مباشرة.

class NegotiateResumableRequest(BaseModel):
    target_folder: str
    file_name: str
    total_size_bytes: int
    source_fingerprint: str  # من transfer/resumable.compute_fingerprint على جهاز التليفون


class NegotiateResumableResponse(BaseModel):
    destination: str
    resume_offset: int      # ابدأ ابعت من هنا (0 = رفع جديد بالكامل)
    fingerprint_matched: bool  # False = في جزء موجود لكنه مش لنفس الملف، لازم يبدأ من الصفر


@app.post("/uploads/resumable/negotiate", response_model=NegotiateResumableResponse)
def negotiate_resumable_upload(request: NegotiateResumableRequest, token: str = Depends(require_trusted_device)):
    """
    الخطوة الأولى في أي رفع كبير (≥1GB): التليفون بيسأل "عندك من الملف
    ده قد إيه فعليًا؟" قبل ما يبعت أي بايتات. راجع transfer/resumable.py
    للبروتوكول الكامل.
    """
    if not os.path.isdir(request.target_folder):
        raise HTTPException(status_code=400, detail="المجلد الهدف مش موجود على الكمبيوتر")
    if not config.is_path_allowed(request.target_folder):
        raise HTTPException(status_code=403, detail="المجلد الهدف خارج النطاق المسموح للموبايل")

    safe_name = os.path.basename(request.file_name)
    destination = os.path.join(request.target_folder, safe_name)

    if not os.path.exists(destination):
        # مفيش أي جزء موجود - رفع جديد بالكامل من الصفر
        from config.app_settings import AppSettings
        retention_hours = AppSettings.load().incomplete_transfer_retention_hours
        db.create_resumable_transfer(
            file_path=destination, direction="upload",
            total_size_bytes=request.total_size_bytes,
            source_fingerprint=request.source_fingerprint,
            device_token=token, retention_hours=retention_hours,
        )
        return NegotiateResumableResponse(destination=destination, resume_offset=0, fingerprint_matched=True)

    # فيه ملف بنفس المسار - ممكن يكون جزء ناقص من رفع سابق اتقطع، أو ملف
    # كامل مختلف تمامًا. لازم نتأكد قبل ما نقرر نكمل عليه أو نبدأ من جديد.
    matched = transfer_resumable.verify_partial_matches_fingerprint(destination, request.source_fingerprint)

    if not matched:
        # مش نفس الملف - بلاش نخاطر نكمل على أساس غلط. نمسح الموجود
        # ونبدأ تسجيل نقل جديد من الصفر.
        try:
            os.remove(destination)
        except OSError as e:
            raise HTTPException(status_code=500, detail=f"تعذر مسح الملف القديم المختلف: {e}")
        db.delete_resumable_transfer(destination)
        from config.app_settings import AppSettings
        retention_hours = AppSettings.load().incomplete_transfer_retention_hours
        db.create_resumable_transfer(
            file_path=destination, direction="upload",
            total_size_bytes=request.total_size_bytes,
            source_fingerprint=request.source_fingerprint,
            device_token=token, retention_hours=retention_hours,
        )
        return NegotiateResumableResponse(destination=destination, resume_offset=0, fingerprint_matched=False)

    resume_offset = transfer_resumable.get_resume_offset(destination)

    existing_transfer = db.get_resumable_transfer(destination)
    if existing_transfer is None:
        # الملف موجود وبصمته مطابقة، لكن مفيش سجل transfer له (مثلاً
        # اتعمل upload عادي قديم بطريقة تانية) - نسجله دلوقتي عشان
        # التتبع يبقى صحيح من هنا وطالع.
        from config.app_settings import AppSettings
        retention_hours = AppSettings.load().incomplete_transfer_retention_hours
        db.create_resumable_transfer(
            file_path=destination, direction="upload",
            total_size_bytes=request.total_size_bytes,
            source_fingerprint=request.source_fingerprint,
            device_token=token, retention_hours=retention_hours,
        )
    else:
        db.update_resumable_progress(destination, resume_offset)

    return NegotiateResumableResponse(destination=destination, resume_offset=resume_offset, fingerprint_matched=True)


@app.post("/uploads/resumable/chunk")
async def upload_resumable_chunk(
    file: UploadFile,
    destination: str = Query(..., description="المسار الراجع من /uploads/resumable/negotiate"),
    offset: int = Query(..., description="المكان اللي الجزء ده المفروض يتكتب فيه"),
    token: str = Depends(require_trusted_device),
):
    """
    جزء واحد من الملف - بيتكتب في مكانه الصحيح مباشرة. offset هنا لازم
    يتطابق مع حجم الملف الفعلي على القرص وقت الاستلام، وإلا في احتمال
    فيه جزء اتفقد أو تكرر - نرفض الكتابة بدل ما نخاطر بتلف الملف
    (راجع transfer/resumable.append_chunk).
    """
    if not config.is_path_allowed(destination):
        raise HTTPException(status_code=403, detail="المسار ده خارج النطاق المسموح للموبايل")

    transfer = db.get_resumable_transfer(destination)
    if transfer is None:
        raise HTTPException(status_code=404, detail="مفيش سجل نقل لهذا الملف")
    try:
        new_size = await asyncio.to_thread(
            transfer_resumable.append_upload_stream,
            destination,
            offset,
            file.file,
            int(transfer["total_size_bytes"]),
        )
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))
    except (OSError, PermissionError) as e:
        raise HTTPException(status_code=500, detail=f"فشل كتابة الجزء: {e}")

    db.update_resumable_progress(destination, new_size)

    return {"success": True, "bytes_received": new_size}


@app.post("/uploads/resumable/complete")
def complete_resumable_upload(destination: str = Query(...), token: str = Depends(require_trusted_device)):
    """
    التليفون بينادي هنا بعد ما يبعت آخر جزء - تأكيد إن الملف اكتمل
    فعليًا. بنتأكد إن الحجم النهائي متطابق مع اللي كان متوقع في
    resumable_transfers قبل ما نعتبره مكتمل ونفهرسه فهرسة نهائية.
    """
    if not config.is_path_allowed(destination):
        raise HTTPException(status_code=403, detail="المسار ده خارج النطاق المسموح للموبايل")

    transfer_row = db.get_resumable_transfer(destination)
    if transfer_row is None:
        raise HTTPException(status_code=404, detail="مفيش سجل نقل لهذا الملف")

    if not os.path.isfile(destination):
        raise HTTPException(status_code=404, detail="الملف مش موجود على القرص")

    actual_size = os.path.getsize(destination)
    if actual_size != transfer_row["total_size_bytes"]:
        # الملف لسه ناقص فعليًا رغم إن التليفون فاكر إنه خلص - أخطر حالة
        # نتجاهلها هنا هي نفهرس ملف ناقص على إنه مكتمل بالغلط.
        raise HTTPException(
            status_code=409,
            detail=f"الملف لسه ناقص: موجود {actual_size} من أصل {transfer_row['total_size_bytes']} بايت"
        )

    db.mark_resumable_completed(destination)

    stat = os.stat(destination)
    from scanner.scanner import compute_hash as _compute_hash, _iso as _iso_fn
    file_hash = _compute_hash(destination, config.hash_size_limit_mb)
    db.upsert_file({
        "path": destination,
        "name": os.path.basename(destination),
        "extension": os.path.splitext(destination)[1].lower(),
        "parent_folder": os.path.dirname(destination),
        "size_bytes": stat.st_size,
        "created_at": _iso_fn(stat.st_ctime),
        "modified_at": _iso_fn(stat.st_mtime),
        "hash": file_hash,
        "project": None,
        "detected_by": "watcher",
    })
    db.log_activity(token, "uploaded_file", detail=destination)

    with db.cursor() as cur:
        cur.execute("SELECT id FROM files WHERE path = ?", (destination,))
        new_file_id = cur.fetchone()["id"]

    from notifications.notifications import notify_new_file
    notify_new_file(destination, stat.st_size, os.path.splitext(destination)[1].lower())

    return {"success": True, "file_id": new_file_id, "saved_path": destination, "size_bytes": stat.st_size}


@app.get("/uploads/resumable/incomplete")
def list_incomplete_resumable_transfers(_: str = Depends(require_trusted_device)):
    """
    للتليفون: لو فيه رفع كبير اتقطع وسايبه، ده بيرجّعه عشان التطبيق
    يعرض للمستخدم "فيه رفع ناقص، تحب تكمله؟" لما يفتح التطبيق.
    """
    return db.list_incomplete_transfers()


# ---------- Offline File-Tree Mirror (نسخة شجرة الملفات - metadata بس) ----------
# بند: التليفون بيحتفظ بنسخة محلية كاملة من metadata شجرة الملفات (مسارات،
# أحجام، تواريخ تعديل) - مش الملفات الفعلية بحجمها. لما يرجع يتصل بعد فترة
# أوفلاين، بيبعت آخر نسخة عنده، والكمبيوتر يقارنها بالحالة الحقيقية دلوقتي
# ويرجّع 3 قوايم: added / removed / modified. مفيش أي تتبع مستمر على
# الكمبيوتر - المقارنة بتحصل لحظة الاتصال بس، من جدول files الموجود أصلًا.

class TreeSnapshotEntry(BaseModel):
    path: str
    is_folder: bool
    size_bytes: int  # 0 للفولدرات
    modified_at: str


class TreeEntryOut(BaseModel):
    """
    عنصر واحد في شجرة العرض - ملف أو فولدر. مختلف عن FileMetadata لأنه
    بيغطي الفولدرات كمان (اللي جدول files أصلًا مابيفهرسهاش، هو بيفهرس
    الملفات بس). الفولدرات هنا مُشتقة من مسارات الملفات الموجودة فعليًا،
    مش من مسح منفصل للقرص.
    """
    path: str
    name: str
    is_folder: bool
    size_bytes: int
    modified_at: Optional[str] = None
    extension: Optional[str] = None
    file_id: Optional[int] = None  # None للفولدرات (مفيش صف في جدول files ليها)


class TreeDiffRequest(BaseModel):
    snapshot: list[TreeSnapshotEntry]  # آخر نسخة محفوظة عند التليفون


class TreeDiffResponse(BaseModel):
    added: list[TreeEntryOut]      # موجودة دلوقتي على الكمبيوتر، مش موجودة عند التليفون
    removed: list[str]             # مسارات كانت عند التليفون، مبقتش موجودة (أو اتمسحت)
    modified: list[TreeEntryOut]   # موجودة عند الاتنين، لكن الحجم أو تاريخ التعديل اختلف


def _get_enabled_target_paths() -> list[str]:
    return [os.path.normpath(t.path) for t in config.targets if t.enabled]


def _path_under_any_target(path: str, target_paths: list[str]) -> bool:
    normalized = os.path.normpath(path)
    return any(
        normalized == target or normalized.startswith(target + os.sep)
        for target in target_paths
    )


def _build_full_tree_entries(target_paths: list[str]) -> dict[str, "TreeEntryOut"]:
    """
    بيبني قايمة كل عناصر الشجرة (ملفات + فولدرات) تحت الـ targets المفعّلة،
    من جدول files بس - من غير أي مسح إضافي للقرص. الفولدرات مُشتقة: لكل
    ملف، بناخد كل الفولدرات الأب بتاعته لحد ما نوصل لجذر الـ target نفسه،
    ونضيفها كعناصر is_folder=True (مع تجاهل التكرار عن طريق dict بالمسار).

    ملحوظة: فولدر فاضي تمامًا (مفيهوش أي ملف مفهرس جواه ولا في أي مستوى
    تحته) مش هيظهر هنا، لأن مفيش أي إشارة له في جدول files أصلًا - ده
    تنازل مقبول (فولدر فاضي بالكامل نادر عمليًا ومفيش فيه معلومة تهم
    "شجرة الملفات" أصلًا).
    """
    with db.cursor() as cur:
        cur.execute("SELECT * FROM files WHERE sync_state != 'DELETED'")
        rows = [dict(row) for row in cur.fetchall()]

    entries: dict[str, TreeEntryOut] = {}

    for row in rows:
        path = row["path"]
        if not _path_under_any_target(path, target_paths):
            continue

        entries[path] = TreeEntryOut(
            path=path, name=row["name"], is_folder=False,
            size_bytes=row["size_bytes"], modified_at=row["modified_at"],
            extension=row["extension"], file_id=row["id"],
        )

        # نطلع لفوق من فولدر الملف لحد جذر الـ target اللي هو جواه بالظبط
        matching_target = next(
            (t for t in target_paths if path == t or path.startswith(t + os.sep)), None
        )
        if matching_target is None:
            continue

        current = os.path.dirname(path)
        while current and (current == matching_target or current.startswith(matching_target + os.sep)):
            if current not in entries:
                entries[current] = TreeEntryOut(
                    path=current, name=os.path.basename(current), is_folder=True,
                    size_bytes=0, modified_at=None, extension=None, file_id=None,
                )
            if current == matching_target:
                break
            current = os.path.dirname(current)

    return entries


@app.post("/sync/tree-diff", response_model=TreeDiffResponse)
def compute_tree_diff(request: TreeDiffRequest, _: str = Depends(require_trusted_device)):
    """
    المقارنة الفعلية - بتحصل لحظة الطلب بس، من غير أي تتبع مستمر. تغطي
    الملفات والفولدرات المُشتقة منها معًا (راجع _build_full_tree_entries).
    """
    target_paths = _get_enabled_target_paths()
    current_by_path = _build_full_tree_entries(target_paths)
    # Windows paths can differ only by slash direction/case between scans.
    # Compare canonical keys so reconnect does not report phantom add/remove
    # operations when nothing actually changed on disk.
    def tree_key(path: str) -> str:
        return os.path.normcase(os.path.normpath(path))

    current_by_key = {tree_key(path): entry for path, entry in current_by_path.items()}
    snapshot_by_key = {tree_key(entry.path): entry for entry in request.snapshot}

    added = [
        entry for path, entry in current_by_path.items()
        if tree_key(path) not in snapshot_by_key
    ]
    removed = [
        entry.path for entry in snapshot_by_key.values()
        if tree_key(entry.path) not in current_by_key
    ]
    modified = [
        entry for path, entry in current_by_path.items()
        if tree_key(path) in snapshot_by_key
        and not entry.is_folder  # فولدرات مالهاش حجم/تاريخ حقيقي يتغيّر - التعديل يهم الملفات بس
        and (
            entry.size_bytes != snapshot_by_key[tree_key(path)].size_bytes
            or entry.modified_at != snapshot_by_key[tree_key(path)].modified_at
        )
    ]

    return TreeDiffResponse(added=added, removed=removed, modified=modified)


@app.get("/sync/full-tree-snapshot", response_model=list[TreeEntryOut])
def get_full_tree_snapshot(_: str = Depends(require_trusted_device)):
    """
    أول مرة يعمل فيها المستخدم "مزامنة شجرة الملفات" على التليفون (أو لو
    قرر يبدأ من جديد)، ميبقاش محتاج يبعت snapshot فاضي لـ tree-diff -
    بيجيب هنا كل حاجة (ملفات + فولدرات) تحت الـ targets المفعّلة دفعة
    واحدة كنقطة بداية.
    """
    target_paths = _get_enabled_target_paths()
    return list(_build_full_tree_entries(target_paths).values())


# ---------- Offline Deferred Command Queue (طابور الأوامر المؤجلة) ----------
# بند: التليفون بيسجل أوامر (حذف/تسمية/تحميل/رفع) وهو أوفلاين، وبيبعتها
# كدفعة واحدة مرتبة زمنيًا لحظة ما يرجع يتصل. مختلف عن نظام الموافقات
# العادي (pending_approvals) اللي محتاج موافقة يدوية من واجهة الكمبيوتر:
# هنا الموافقة حصلت على التليفون نفسه قبل ما يبعت الدفعة، فالكمبيوتر دوره
# تنفيذ فقط - بالترتيب، مع إيقاف فوري عند أول تعارض (مفيش تخطي صامت).

class OfflineCommandItem(BaseModel):
    command_type: str  # delete | rename | download | upload | copy | move
    file_path: str      # المسار وقت إنشاء الأمر (على الكمبيوتر)
    new_value: Optional[str] = None  # الاسم الجديد لو rename، أو target_folder لو upload


class SubmitBatchRequest(BaseModel):
    commands: list[OfflineCommandItem]  # لازم تكون مرتبة زمنيًا بالفعل من التليفون


class BatchCommandResult(BaseModel):
    id: int
    sequence_index: int
    command_type: str
    file_path: str
    new_value: Optional[str] = None
    status: str  # pending | executed | conflict | skipped | cancelled
    conflict_reason: Optional[str] = None


class BatchExecutionResult(BaseModel):
    batch_id: int
    status: str  # completed | stopped_on_conflict
    commands: list[BatchCommandResult]


def _execute_command_batch(batch_id: int) -> dict:
    """
    التنفيذ الفعلي، أمر أمر بالترتيب، مع إيقاف فوري عند أول تعارض:
        - delete/rename: بينفذوا فعليًا هنا على القرص، بنفس فحوصات الأمان
          المستخدمة في /approvals (النطاق المسموح + وجود الملف فعليًا
          وقت التنفيذ - مش وقت ما الأمر اتسجل وهو أوفلاين، عشان لو حصل
          أي تغيير من مصدر تاني في الفترة دي نكتشفه كتعارض بدل ما نكسر حاجة).
        - download/upload: مفيش نقل bytes هنا خالص - ده بيحصل بعدين عن
          طريق endpoints النقل العادية. هنا بس تحقق إن الأمر لسه منطقي
          (الملف لسه موجود، أو مجلد الرفع الهدف لسه صالح)، ونعلّمه
          "executed" كتصريح صالح يبدأ بيه التليفون النقل الفعلي بعد كده.
    """
    commands = db.list_batch_commands(batch_id)
    stopped_on_conflict = False

    for command in commands:
        if command["status"] != "pending":
            continue

        action = command["command_type"]
        path = command["file_path"]

        try:
            if action == "delete":
                if not config.is_path_allowed(path):
                    db.mark_command_conflict(command["id"], "المسار بقى خارج النطاق المسموح")
                    stopped_on_conflict = True
                    break
                if not os.path.exists(path):
                    db.mark_command_conflict(command["id"], "الملف مش موجود - اتمسح أو اتحرك قبل كده")
                    stopped_on_conflict = True
                    break
                # الحذف المؤجل يجب أن يطابق الحذف المباشر: نقل مادي إلى
                # Windows Recycle Bin، لا حذف نهائي بـ os.remove.
                from storage.trash import move_to_trash
                move_to_trash(path)
                with db.cursor() as cur:
                    cur.execute("SELECT id FROM files WHERE path = ?", (path,))
                    row = cur.fetchone()
                if row:
                    db.delete_file_record(row["id"])
                db.mark_command_executed(command["id"])

            elif action == "rename":
                new_name = command["new_value"]
                if not config.is_path_allowed(path):
                    db.mark_command_conflict(command["id"], "المسار بقى خارج النطاق المسموح")
                    stopped_on_conflict = True
                    break
                if not os.path.exists(path):
                    db.mark_command_conflict(command["id"], "الملف مش موجود - اتمسح أو اتحرك قبل كده")
                    stopped_on_conflict = True
                    break
                if not new_name:
                    db.mark_command_conflict(command["id"], "اسم جديد غير محدد")
                    stopped_on_conflict = True
                    break
                new_path = os.path.join(os.path.dirname(path), new_name)
                if os.path.exists(new_path):
                    db.mark_command_conflict(command["id"], f"في ملف تاني بنفس الاسم '{new_name}' موجود بالفعل")
                    stopped_on_conflict = True
                    break
                os.rename(path, new_path)
                with db.cursor() as cur:
                    cur.execute("SELECT id FROM files WHERE path = ?", (path,))
                    row = cur.fetchone()
                if row:
                    db.rename_file_record(row["id"], new_path, new_name)
                db.mark_command_executed(command["id"])

            elif action == "download":
                if not config.is_path_allowed(path):
                    db.mark_command_conflict(command["id"], "المسار بقى خارج النطاق المسموح")
                    stopped_on_conflict = True
                    break
                if not os.path.exists(path):
                    db.mark_command_conflict(command["id"], "الملف اتمسح أو اتحرك قبل ما يتحمل")
                    stopped_on_conflict = True
                    break
                db.mark_command_executed(command["id"])

            elif action == "upload":
                target_folder = command["new_value"]
                if not target_folder or not os.path.isdir(target_folder):
                    db.mark_command_conflict(command["id"], "المجلد الهدف مش موجود على الكمبيوتر")
                    stopped_on_conflict = True
                    break
                if not config.is_path_allowed(target_folder):
                    db.mark_command_conflict(command["id"], "المجلد الهدف بقى خارج النطاق المسموح")
                    stopped_on_conflict = True
                    break
                db.mark_command_executed(command["id"])

            elif action in ("copy", "move"):
                destination = command["new_value"]
                if not destination:
                    db.mark_command_conflict(command["id"], "المسار الهدف غير محدد")
                    stopped_on_conflict = True
                    break
                if not os.path.exists(path) or not config.is_path_allowed(path):
                    db.mark_command_conflict(command["id"], "المصدر غير موجود أو خارج النطاق")
                    stopped_on_conflict = True
                    break
                destination = os.path.normpath(os.path.abspath(destination))
                if not config.is_path_allowed(destination):
                    db.mark_command_conflict(command["id"], "المسار الهدف خارج النطاق")
                    stopped_on_conflict = True
                    break
                if os.path.exists(destination):
                    db.mark_command_conflict(command["id"], "يوجد عنصر بالفعل في المسار الهدف")
                    stopped_on_conflict = True
                    break
                if action == "copy":
                    shutil.copy2(path, destination) if os.path.isfile(path) else shutil.copytree(path, destination)
                else:
                    shutil.move(path, destination)
                db.mark_command_executed(command["id"])

            else:
                db.mark_command_conflict(command["id"], f"نوع أمر غير مدعوم: {action}")
                stopped_on_conflict = True
                break

        except (OSError, PermissionError) as e:
            db.mark_command_conflict(command["id"], str(e))
            stopped_on_conflict = True
            break

    final_status = "stopped_on_conflict" if stopped_on_conflict else "completed"
    db.set_batch_status(batch_id, final_status)
    return {"batch_id": batch_id, "status": final_status, "commands": db.list_batch_commands(batch_id)}


@app.post("/sync/batch/submit", response_model=BatchExecutionResult)
def submit_offline_batch(request: SubmitBatchRequest, token: str = Depends(require_trusted_device)):
    """
    التليفون بيبعت هنا الدفعة كاملة بعد ما المستخدم وافق عليها في واجهة
    التليفون نفسها. التنفيذ بيحصل فورًا وبالكامل هنا (مش async في الخلفية)
    عشان الرد نفسه يرجّع حالة كل أمر - نجح، اتعارض، أو لسه معلّق لو
    التنفيذ توقف قبل ما يوصله.
    """
    batch_id = db.create_command_batch(token, [c.dict() for c in request.commands])
    result = _execute_command_batch(batch_id)
    return BatchExecutionResult(**result)


@app.post("/sync/batch/{batch_id}/resume", response_model=BatchExecutionResult)
def resume_offline_batch(batch_id: int, token: str = Depends(require_trusted_device)):
    """
    لو دفعة سابقة اتوقفت عند تعارض والمستخدم قرر "تخطى وكمل" على
    التليفون، ينده هنا بعد ما يعلّم الأمر المتعارض بنفسه كـ skipped
    (عن طريق /sync/batch/{id}/skip-current) - بيكمل التنفيذ من الأمر
    اللي بعده على طول.
    """
    batch = db.get_batch(batch_id)
    if batch is None:
        raise HTTPException(status_code=404, detail="الدفعة مش موجودة")
    result = _execute_command_batch(batch_id)
    return BatchExecutionResult(**result)


@app.post("/sync/batch/{batch_id}/skip-current")
def skip_current_conflicted_command(batch_id: int, token: str = Depends(require_trusted_device)):
    """
    المستخدم قرر يتخطى الأمر المتعارض ويكمل اللي بعده - بنعلّم أول أمر
    بحالة conflict كـ skipped، وبعدها التليفون ينده /resume عشان
    التنفيذ يكمل من الأمر اللي بعده.
    """
    commands = db.list_batch_commands(batch_id)
    conflicted = next((c for c in commands if c["status"] == "conflict"), None)
    if conflicted is None:
        raise HTTPException(status_code=404, detail="مفيش أمر متعارض حاليًا في الدفعة دي")
    db.mark_command_skipped(conflicted["id"])
    return {"success": True}


@app.post("/sync/batch/{batch_id}/cancel")
def cancel_offline_batch(batch_id: int, token: str = Depends(require_trusted_device)):
    """المستخدم قرر يلغي باقي الدفعة كلها بعد ما شاف تعارض."""
    batch = db.get_batch(batch_id)
    if batch is None:
        raise HTTPException(status_code=404, detail="الدفعة مش موجودة")
    db.set_batch_status(batch_id, "cancelled")
    return {"success": True}


@app.get("/sync/batch/{batch_id}", response_model=BatchExecutionResult)
def get_batch_status(batch_id: int, token: str = Depends(require_trusted_device)):
    """التليفون يستعلم بيها عن حالة دفعة سابقة (بعد resume أو skip مثلًا)."""
    batch = db.get_batch(batch_id)
    if batch is None:
        raise HTTPException(status_code=404, detail="الدفعة مش موجودة")
    return BatchExecutionResult(
        batch_id=batch_id, status=batch["status"], commands=db.list_batch_commands(batch_id)
    )


# ---------- Sensitive Operations (تحتاج موافقة من الكمبيوتر) ----------

@app.post("/approvals/request", response_model=ApprovalResponse)
def request_approval(request: ApprovalRequest, token: str = Depends(require_trusted_device)):
    """
    التليفون بينادي هنا لما يطلب عملية حساسة (حذف، تسمية، إنشاء ملف...).
    الطلب بيتسجل بحالة 'pending' والواجهة الرسومية بتاعة الكمبيوتر
    بتعرضه للمستخدم يوافق أو يرفض. الموبايل بعد كده بيستعلم عن الحالة
    عبر GET /approvals/{id} لحد ما يوصله قرار.
    """
    allowed_actions = {
        "delete_file", "rename_file", "create_file", "make_linked",
        "overwrite_file", "command_draft",
    }
    if request.action_type not in allowed_actions:
        raise HTTPException(status_code=400, detail="نوع طلب الموافقة غير مدعوم")

    activity_map = {
        "delete_file": "requested_delete",
        "rename_file": "requested_rename",
    }
    if request.action_type in activity_map:
        db.log_activity(token, activity_map[request.action_type], detail=request.file_path or "")

    approval_id = db.create_approval_request(
        device_token=token,
        action_type=request.action_type,
        file_id=request.file_id,
        file_path=request.file_path,
        new_value=request.new_value,
    )
    from notifications.notifications import notify_approval_request
    notify_approval_request(activity_map.get(request.action_type, request.action_type), request.file_path)
    return ApprovalResponse(approval_id=approval_id, status="pending")


@app.get("/approvals/{approval_id}", response_model=ApprovalResponse)
def get_approval_status(approval_id: int, token: str = Depends(require_trusted_device)):
    """التليفون بيستعلم بيها دوريًا: هل المستخدم وافق على الطلب ولا لسه؟"""
    row = db.get_approval(approval_id)
    if row is None:
        raise HTTPException(status_code=404, detail="الطلب مش موجود")
    # لا تسمح لجهاز موثوق آخر بقراءة أو متابعة طلب جهاز مختلف.
    if row["device_token"] != token:
        raise HTTPException(status_code=403, detail="الطلب ده مش تابع للجهاز الحالي")
    return ApprovalResponse(approval_id=row["id"], status=row["status"])


# ---------- Target Management (إضافة/حذف مجلدات مراقبة) ----------
# ملحوظة أمنية حاسمة: إضافة target جديد = توسيع النطاق الآمن نفسه (كل
# ملف جوه المسار الجديد بيبقى قابل للوصول من التليفون). قبل هذا الإصلاح
# كان POST /targets بينفذ الإضافة فورًا من غير أي موافقة من المستخدم على
# الكمبيوتر - ده نقض مباشر لـ"القاعدة الذهبية" في docs/architecture/SYNC_SPEC.md: التليفون
# كان يقدر يوسّع صلاحياته بنفسه (حتى يضيف C:\ أو / كـ target) من غير أي
# تدخل بشري. دلوقتي: التليفون يقدر *يطلب* إضافة target بس، والتنفيذ
# الفعلي بيحصل بس بعد موافقة صريحة من واجهة الكمبيوتر - بالضبط زي حذف/
# استبدال ملف.

@app.get("/targets")
def list_targets(_: str = Depends(require_trusted_device)):
    """قائمة المجلدات المراقبة حاليًا - لعرضها في إعدادات الموبايل."""
    return [
        {"path": t.path, "label": t.label, "recursive": t.recursive, "enabled": t.enabled, "is_file": t.is_file}
        for t in config.targets
    ]


@app.post("/targets/request")
def request_add_target(request: AddTargetRequest, token: str = Depends(require_trusted_device)):
    """
    التليفون بيطلب إضافة مجلد جديد للمراقبة - مش بينفذها فورًا. الطلب
    بيتسجل كـ approval بحالة 'pending' (action_type='create_file' مع
    new_value=المسار المطلوب، عشان نستخدم نفس آلية الموافقات الموجودة
    بدل ما نضيف action_type جديد في الـ schema)، والمستخدم لازم يوافق
    من واجهة الكمبيوتر قبل ما المجلد يتضاف فعليًا للنطاق الآمن.
    """
    approval_id = db.create_approval_request(
        device_token=token,
        action_type="create_file",
        file_path=request.path,
        # الصيغة: "ADD_TARGET|<recursive:0 or 1>|<label>" - recursive في
        # الأول عمدًا لأنه دايمًا "0" أو "1" (منعًا لأي التباس)، والـ label
        # (اللي ممكن يحتوي على أي حروف زي | لو المستخدم كتبها) بياخد كل
        # الباقي من غير قطع - split(sep, 2) بيضمن ده حتى لو label نفسها
        # فيها | جوّاها.
        new_value=f"ADD_TARGET|{int(request.recursive)}|{request.label}",
    )
    return {"success": True, "approval_id": approval_id, "status": "pending"}


@app.delete("/targets")
def remove_target_endpoint(request: RemoveTargetRequest, _: str = Depends(require_trusted_device)):
    """
    حذف مجلد من قائمة المراقبة - ده تضييق للنطاق (مش توسيع)، فمفيش خطر
    أمني من تنفيذه فورًا من الموبايل (أسوأ حالة: المستخدم يضيفه تاني من
    الكمبيوتر لو حصل غلط). القاعدة الذهبية بتخص التوسيع بس.
    """
    before = len(config.targets)
    config.targets = [t for t in config.targets if t.normalized_path() != os.path.normpath(os.path.abspath(request.path))]
    config.save()
    removed = before - len(config.targets)
    if removed == 0:
        raise HTTPException(status_code=404, detail="المجلد مش موجود في قائمة المراقبة")
    return {"success": True, "targets_count": len(config.targets)}


# ---------- Activity Feed (للعرض الحي في واجهة الكمبيوتر) ----------

@app.get("/activity")
def get_activity(limit: int = Query(30, ge=1, le=100), token: str = Depends(require_trusted_device)):
    """آخر نشاطات التليفون - تصفح، فتح ملفات، طلبات حذف/تسمية..."""
    # الهاتف يرى نشاطه هو فقط؛ واجهة الكمبيوتر المحلية هي التي تعرض
    # السجل الكامل لكل الأجهزة الموثوقة.
    rows = db.get_recent_activity(limit=limit, device_token=token)
    # لا نعيد أي معرّف أمني للعميل؛ التوكن مطلوب للمصادقة فقط.
    return [
        {
            "id": row.get("id"),
            "activity_type": row.get("activity_type"),
            "detail": row.get("detail"),
            "created_at": row.get("created_at"),
        }
        for row in rows
    ]


# ---------- Version History (نسخ قديمة من الملفات) ----------
# جدول file_versions كان موجود في الـ schema من البداية بدون أي endpoint
# يستخدمه فعليًا - هذا أول تطبيق حقيقي له.

PROJECT_ROOT = os.path.abspath(os.environ.get("DESKTWIN_ROOT") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
VERSIONS_STORAGE_DIR = os.path.join(PROJECT_ROOT, "database", "file_versions_storage")


def _make_savepoint(file_path: str, source_device: str, reason: str) -> int:
    """
    بينسخ الملف الحالي فعليًا لمكان تخزين النسخ القديمة، ويسجله في file_versions.
    بيرجع version id، أو بيرمي استثناء لو الملف مش موجود على القرص.
    """
    import shutil
    if not os.path.exists(file_path):
        raise FileNotFoundError(file_path)

    os.makedirs(VERSIONS_STORAGE_DIR, exist_ok=True)
    stat = os.stat(file_path)
    safe_name = os.path.basename(file_path)
    ts_tag = now_iso().replace(":", "-").replace(".", "-")
    stored_copy_path = os.path.join(VERSIONS_STORAGE_DIR, f"{safe_name}.{ts_tag}.bak")

    shutil.copy2(file_path, stored_copy_path)

    from scanner.scanner import compute_hash
    file_hash = compute_hash(file_path, config.hash_size_limit_mb)

    return db.add_file_version(
        file_path=file_path,
        stored_copy_path=stored_copy_path,
        hash_value=file_hash,
        size_bytes=stat.st_size,
        source_device=source_device,
        reason=reason,
    )


@app.get("/files/{file_id}/versions", response_model=list[FileVersionEntry])
def list_file_versions(file_id: int, _: str = Depends(require_trusted_device)):
    """كل النسخ القديمة المحفوظة لملف معين، الأحدث أولًا."""
    with db.cursor() as cur:
        cur.execute("SELECT path FROM files WHERE id = ?", (file_id,))
        row = cur.fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")
    if not config.is_path_allowed(row["path"]):
        raise HTTPException(status_code=403, detail="الملف ده بقى خارج النطاق المسموح للموبايل")

    versions = db.list_file_versions(row["path"])
    return [_row_to_version_entry(v) for v in versions]


@app.post("/files/{file_id}/versions/savepoint", response_model=FileVersionEntry)
def create_savepoint(file_id: int, _: str = Depends(require_trusted_device)):
    """
    نقطة حفظ يدوية: المستخدم بيطلب تسجيل نسخة من الحالة الحالية للملف
    دلوقتي (قبل ما يعمل تعديل كبير مثلًا) - بند 'manual_savepoint' في الـ schema.
    """
    with db.cursor() as cur:
        cur.execute("SELECT path FROM files WHERE id = ?", (file_id,))
        row = cur.fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="الملف مش موجود في الفهرس")
    if not config.is_path_allowed(row["path"]):
        raise HTTPException(status_code=403, detail="الملف ده بقى خارج النطاق المسموح للموبايل")

    try:
        version_id = _make_savepoint(row["path"], source_device="pc", reason="manual_savepoint")
    except FileNotFoundError:
        raise HTTPException(status_code=410, detail="الملف اتمسح من على الكمبيوتر، مينفعش نعمل savepoint")

    version = db.get_file_version(version_id)
    return _row_to_version_entry(version)


@app.post("/files/{file_id}/versions/{version_id}/restore")
def restore_file_version(file_id: int, version_id: int, token: str = Depends(require_trusted_device)):
    """Restore a historical copy after validating file ownership and storage boundaries."""
    with db.cursor() as cur:
        cur.execute("SELECT path FROM files WHERE id = ?", (file_id,))
        file_row = cur.fetchone()
    version = db.get_file_version(version_id)
    if file_row is None or version is None or version["file_path"] != file_row["path"]:
        raise HTTPException(status_code=404, detail="النسخة أو الملف مش موجود")
    target = os.path.realpath(file_row["path"])
    stored = os.path.realpath(version["stored_copy_path"])
    versions_root = os.path.realpath(VERSIONS_STORAGE_DIR)
    if not config.is_path_allowed(target) or os.path.commonpath([stored, versions_root]) != versions_root:
        raise HTTPException(status_code=403, detail="مسار النسخة خارج النطاق المسموح")
    if not os.path.isfile(stored):
        raise HTTPException(status_code=410, detail="ملف النسخة غير موجود")
    import shutil
    shutil.copy2(stored, target)
    db.log_activity(token, "restored_file_version", detail=file_row["path"])
    return {"success": True, "file_id": file_id, "version_id": version_id}


@app.delete("/versions/{version_id}")
def delete_file_version_endpoint(version_id: int, _: str = Depends(require_trusted_device)):
    """
    مسح نسخة قديمة معينة - يدوي بس (نفس القرار الموثق في docs/architecture/SYNC_SPEC.md بند 3:
    الحذف التلقائي معطّل افتراضيًا، والمستخدم هو اللي بيتحكم في وقت المسح).
    """
    version = db.get_file_version(version_id)
    if version is None:
        raise HTTPException(status_code=404, detail="النسخة مش موجودة")

    # نمسح الملف الفعلي من على القرص الأول، وبعدين صف الـ DB - لو مسح
    # الملف فشل، منمسحش الصف عشان منسيبش نسخة "موجودة" في الفهرس لكن
    # مفقودة فعليًا من غير أي أثر.
    stored_path = version["stored_copy_path"]
    if os.path.exists(stored_path):
        try:
            os.remove(stored_path)
        except OSError as e:
            raise HTTPException(status_code=500, detail=f"فشل مسح ملف النسخة: {e}")

    db.delete_file_version(version_id)
    return {"success": True, "deleted_version_id": version_id}


# ---------- Conflicts (تعارضات المزامنة الثنائية) ----------
# جدول conflicts كان موجود في الـ schema من البداية بدون أي endpoint
# يستخدمه فعليًا - نفس وضع file_versions. القاعدة الرسمية (docs/architecture/SYNC_SPEC.md
# بند 2.2): لا يوجد auto-resolve؛ أي تعارض = وقفة إجبارية لحد ما المستخدم يقرر.

@app.get("/conflicts", response_model=list[ConflictEntry])
def list_conflicts_endpoint(
    status: Optional[str] = Query("pending", description="فلترة بالحالة، أو فاضي لكل الحالات"),
    _: str = Depends(require_trusted_device),
):
    """التعارضات المفتوحة (أو كل التعارضات لو status فاضي) - لعرضها في واجهة الكمبيوتر."""
    rows = db.list_conflicts(status=status if status else None)
    return [_row_to_conflict_entry(r) for r in rows]


@app.get("/conflicts/{conflict_id}", response_model=ConflictEntry)
def get_conflict_endpoint(conflict_id: int, _: str = Depends(require_trusted_device)):
    row = db.get_conflict(conflict_id)
    if row is None:
        raise HTTPException(status_code=404, detail="التعارض مش موجود")
    return _row_to_conflict_entry(row)


@app.post("/conflicts/{conflict_id}/resolve")
def resolve_conflict_endpoint(
    conflict_id: int, request: ResolveConflictRequest,
    _: str = Depends(require_trusted_device),
):
    """
    المستخدم بيقرر: يفضّل نسخة الكمبيوتر، أو نسخة التليفون، أو حل يدوي.
    القرار يتطبق فعليًا على القرص هنا (نسخ النسخة المختارة فوق الملف
    الحالي لو كانت النسخة المحفوظة موجودة)، وبعدين sync_state يرجع SYNCED.
    """
    conflict = db.get_conflict(conflict_id)
    if conflict is None:
        raise HTTPException(status_code=404, detail="التعارض مش موجود")
    if conflict["status"] != "pending":
        raise HTTPException(status_code=409, detail="التعارض ده اتحل قبل كده")

    valid_resolutions = {"resolved_keep_pc", "resolved_keep_phone", "resolved_manual"}
    if request.resolution not in valid_resolutions:
        raise HTTPException(status_code=400, detail=f"resolution غير صالحة. المسموح: {valid_resolutions}")

    # لو المستخدم اختار "افضّل نسخة الكمبيوتر" - الملف الحالي على القرص
    # هو أصلاً نسخة الكمبيوتر، فمفيش حاجة نستبدلها. القرار الوحيد اللي
    # محتاج فعل على القرص هو "افضّل نسخة التليفون" (لسه المزامنة الثنائية
    # الفعلية مش مبنية بالكامل - راجع القسم 5 في docs/architecture/SYNC_SPEC.md - فده أقصى
    # حاجة نقدر نطبقها دلوقتي بأمان بدون افتراض وجود مزامنة فعلية شغالة).
    try:
        import shutil
        selected_id = {
            "resolved_keep_phone": conflict["phone_backup_version_id"],
            "resolved_keep_pc": conflict["pc_backup_version_id"],
        }.get(request.resolution)
        if request.resolution == "resolved_keep_phone" and not selected_id:
            raise HTTPException(status_code=409, detail="نسخة الهاتف غير محفوظة، لا يمكن تطبيق الاختيار")
        if selected_id:
            selected = db.get_file_version(selected_id)
            target = os.path.realpath(conflict["file_path"])
            stored = os.path.realpath(selected["stored_copy_path"]) if selected else ""
            versions_root = os.path.realpath(VERSIONS_STORAGE_DIR)
            if not selected or os.path.commonpath([stored, versions_root]) != versions_root or not config.is_path_allowed(target):
                raise HTTPException(status_code=403, detail="نسخة التعارض خارج النطاق المسموح")
            if not os.path.isfile(stored):
                raise HTTPException(status_code=410, detail="ملف النسخة المختارة غير موجود")
            shutil.copy2(stored, target)
        db.resolve_conflict(conflict_id, request.resolution)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return {"success": True, "conflict_id": conflict_id, "status": request.resolution}


# ---------- Version Retention Settings (إعدادات مسح النسخ القديمة) ----------

class RetentionSettingsResponse(BaseModel):
    max_versions_per_file: str
    auto_delete_after_days: Optional[int]
    cleanup_mode: str


class UpdateRetentionSettingsRequest(BaseModel):
    max_versions_per_file: Optional[str] = None
    auto_delete_after_days: Optional[int] = None
    cleanup_mode: Optional[str] = None


@app.get("/settings/version-retention", response_model=RetentionSettingsResponse)
def get_retention_settings(_: str = Depends(require_trusted_device)):
    settings = db.get_version_retention_settings()
    return RetentionSettingsResponse(**settings)


@app.put("/settings/version-retention", response_model=RetentionSettingsResponse)
def update_retention_settings(request: UpdateRetentionSettingsRequest, _: str = Depends(require_trusted_device)):
    if request.cleanup_mode is not None:
        valid_modes = {"manual", "by_count", "by_age", "both"}
        if request.cleanup_mode not in valid_modes:
            raise HTTPException(status_code=400, detail=f"cleanup_mode غير صالح. المسموح: {valid_modes}")

    db.update_version_retention_settings(
        max_versions_per_file=request.max_versions_per_file,
        auto_delete_after_days=request.auto_delete_after_days,
        cleanup_mode=request.cleanup_mode,
    )
    settings = db.get_version_retention_settings()
    return RetentionSettingsResponse(**settings)


# ---------- Helpers ----------

def _row_to_metadata(row: dict) -> FileMetadata:
    return FileMetadata(
        id=row["id"],
        name=row["name"],
        path=row["path"],
        extension=row.get("extension"),
        parent_folder=row.get("parent_folder"),
        size_bytes=row["size_bytes"],
        modified_at=row.get("modified_at"),
        created_at=row.get("created_at"),
        hash=row.get("hash"),
        version=row["version"],
        project=row.get("project"),
        sync_state=row["sync_state"],
        sync_mode=row.get("sync_mode", "METADATA_ONLY"),
    )


def _row_to_version_entry(row: dict) -> FileVersionEntry:
    """يحوّل صف من جدول file_versions - اللي فيه أعمدة أكتر من الـ response model - لـ FileVersionEntry بأمان."""
    return FileVersionEntry(
        id=row["id"],
        file_path=row["file_path"],
        version_number=row["version_number"],
        stored_copy_path=row["stored_copy_path"],
        hash=row.get("hash"),
        size_bytes=row.get("size_bytes"),
        source_device=row["source_device"],
        reason=row["reason"],
        created_at=row["created_at"],
    )


def _row_to_conflict_entry(row: dict) -> ConflictEntry:
    """يحوّل صف من جدول conflicts - فيه pc_backup_version_id/phone_backup_version_id
    مش موجودين في ConflictEntry - لـ ConflictEntry بأمان (صريح، مش معتمد على
    سلوك افتراضي معين لأي مكتبة تتعامل مع مفاتيح زيادة)."""
    return ConflictEntry(
        id=row["id"],
        file_path=row["file_path"],
        pc_hash=row.get("pc_hash"),
        pc_modified_at=row.get("pc_modified_at"),
        phone_hash=row.get("phone_hash"),
        phone_modified_at=row.get("phone_modified_at"),
        detected_at=row["detected_at"],
        status=row["status"],
        resolved_at=row.get("resolved_at"),
    )


if __name__ == "__main__":
    import uvicorn
    from security.tls import ensure_certificate
    cert_path, key_path, _ = ensure_certificate()
    # 0.0.0.0 عشان يبقى متاح على الشبكة المحلية، مش بس localhost
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8420,
        ssl_certfile=cert_path,
        ssl_keyfile=key_path,
        ws_per_message_deflate=False,
    )
