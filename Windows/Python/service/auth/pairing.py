"""
DeskTwin Pairing
-----------------
تسلسل الثقة بين الكمبيوتر والتليفون (بند 22: Security):

    1. الكمبيوتر يولّد كود مؤقت (6 أرقام، صلاحية قصيرة، استخدام مرة واحدة)
       ويحفظ HMAC للكود بمفتاح عشوائي في الذاكرة بدل النص الصريح في قاعدة البيانات
    2. المستخدم يكتب نفس الكود يدويًا في تطبيق الموبايل
    3. الكمبيوتر يتحقق من الكود -> لو صح، يولّد token دائم لهذا الجهاز
    4. الموبايل يخزن الـ token ده ويبعته في كل طلب بعد كده (header)

القاعدة: أي endpoint في الـ API (غير endpoints الـ pairing نفسها) لازم
يتحقق من الـ token قبل ما يرد - وإلا أي جهاز على نفس الشبكة يقدر يوصل
لملفات المستخدم من غير إذن.
"""

import os
import sys
import secrets
import string
import hashlib
import hmac
import threading
import time
from datetime import datetime, timedelta, timezone

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from database.db import DeskTwinDB, now_iso

CODE_LENGTH = 6
CODE_VALIDITY_MINUTES = 2
TRUSTED_DEVICE_INACTIVE_DAYS = 30
TRUSTED_DEVICE_CLEANUP_INTERVAL_SECONDS = 15 * 60


def _generate_code() -> str:
    return "".join(secrets.choice(string.digits) for _ in range(CODE_LENGTH))


def _generate_token() -> str:
    # توكن طويل وعشوائي بالكامل - ده اللي بيثبت هوية الجهاز بعد كده
    return secrets.token_urlsafe(32)


def _token_hash(token: str) -> str:
    return "sha256$" + hashlib.sha256(token.encode("utf-8")).hexdigest()


class PairingManager:
    def __init__(self, db: DeskTwinDB):
        self.db = db
        # Pairing codes have only one million possible values. Use a process-
        # local key so a database copy cannot cheaply brute-force a stored code.
        # Codes are intentionally invalidated when the service process restarts.
        self._pairing_code_key = secrets.token_bytes(32)
        self._cleanup_lock = threading.Lock()
        self._last_inactivity_cleanup = 0.0

    def expire_inactive_devices(self, force: bool = False) -> int:
        """Remove trusted-device records after 30 days without an authenticated request."""
        now_monotonic = time.monotonic()
        if not force and now_monotonic - self._last_inactivity_cleanup < TRUSTED_DEVICE_CLEANUP_INTERVAL_SECONDS:
            return 0

        with self._cleanup_lock:
            now_monotonic = time.monotonic()
            if not force and now_monotonic - self._last_inactivity_cleanup < TRUSTED_DEVICE_CLEANUP_INTERVAL_SECONDS:
                return 0
            cutoff = (datetime.now(timezone.utc) - timedelta(days=TRUSTED_DEVICE_INACTIVE_DAYS)).isoformat()
            with self.db.cursor() as cur:
                cur.execute(
                    "DELETE FROM trusted_devices WHERE revoked = 0 "
                    "AND COALESCE(last_seen_at, paired_at, '') < ?",
                    (cutoff,),
                )
                removed = cur.rowcount
            self._last_inactivity_cleanup = now_monotonic
            return removed

    def _pairing_code_hash(self, code: str) -> str:
        encoded = code.encode("utf-8", errors="surrogatepass")
        return hmac.new(self._pairing_code_key, encoded, hashlib.sha256).hexdigest()

    # ---------- خطوة 1: الكمبيوتر يولّد كود ----------

    def generate_pairing_code(self) -> dict:
        """
        بتتنادى لما المستخدم يضغط 'Pair New Device' على الكمبيوتر.
        بترجع الكود ووقت انتهاء صلاحيته عشان يتعرض على الشاشة.
        """
        code = _generate_code()
        created = datetime.now(timezone.utc)
        expires = created + timedelta(minutes=CODE_VALIDITY_MINUTES)

        with self.db.cursor() as cur:
            cur.execute("""
                INSERT INTO pairing_codes (code, created_at, expires_at, used)
                VALUES (?, ?, ?, 0)
            """, (self._pairing_code_hash(code), created.isoformat(), expires.isoformat()))

        return {"code": code, "expires_at": expires.isoformat(), "valid_for_minutes": CODE_VALIDITY_MINUTES}

    # ---------- خطوة 2+3: التليفون يبعت الكود، الكمبيوتر يتحقق ويصدر توكن ----------

    def verify_code_and_issue_token(self, code: str, device_name: str = "Unknown Device", session_minutes: int | None = None) -> dict:
        """
        بتتنادى لما المستخدم يكتب الكود في تطبيق الموبايل.
        لو الكود صح ومش منتهي الصلاحية ومش مستخدم قبل كده -> يترجع token دائم.
        """
        now = datetime.now(timezone.utc)
        code_hash = self._pairing_code_hash(code)

        # استهلاك ذري للكود يمنع سباق طلبين متزامنين من إصدار توكنين للكود نفسه.
        with self.db.cursor() as cur:
            cur.execute(
                "UPDATE pairing_codes SET used = 1 "
                "WHERE code = ? AND used = 0 AND expires_at > ?",
                (code_hash, now.isoformat()),
            )
            if cur.rowcount != 1:
                cur.execute("SELECT expires_at FROM pairing_codes WHERE code = ?", (code_hash,))
                row = cur.fetchone()
                if row and now >= datetime.fromisoformat(row["expires_at"]):
                    return {"success": False, "error": "الكود انتهت صلاحيته، اطلب كود جديدًا من الكمبيوتر"}
                return {"success": False, "error": "الكود غلط أو مستخدم قبل كده"}

        # نصدر token دائم للجهاز
        token = _generate_token()
        expires_at = None if session_minutes is None else (now + timedelta(minutes=max(5, min(int(session_minutes), 1440)))).isoformat()
        with self.db.cursor() as cur:
            cur.execute("""
                INSERT INTO trusted_devices (device_token, device_name, paired_at, last_seen_at, revoked, trust_level, session_expires_at)
                VALUES (?, ?, ?, ?, 0, 'standard', ?)
            """, (_token_hash(token), device_name, now_iso(), now_iso(), expires_at))

        return {"success": True, "device_token": token, "session_expires_at": expires_at}

    # ---------- التحقق من الطلبات بعد كده ----------

    def validate_token(self, token: str) -> bool:
        """بتتنادى مع كل طلب API (غير pairing) للتأكد إن الجهاز موثوق ومش ملغي."""
        self.expire_inactive_devices()
        if not token:
            return False
        presented_hash = _token_hash(token)
        with self.db.cursor() as cur:
            cur.execute("SELECT * FROM trusted_devices WHERE revoked = 0")
            rows = cur.fetchall()

        row = None
        for candidate in rows:
            stored = candidate["device_token"]
            # دعم التوكنات القديمة مؤقتًا ثم ترحيلها إلى Hash عند أول استخدام.
            matches = hmac.compare_digest(stored, presented_hash) or hmac.compare_digest(stored, token)
            if matches:
                row = candidate
                if stored == token:
                    with self.db.cursor() as cur:
                        cur.execute("UPDATE trusted_devices SET device_token = ? WHERE id = ?", (presented_hash, candidate["id"]))
                break

        if row is None:
            return False
        last_seen_value = row["last_seen_at"] or row["paired_at"]
        try:
            last_seen_at = datetime.fromisoformat(last_seen_value)
            if last_seen_at.tzinfo is None:
                last_seen_at = last_seen_at.replace(tzinfo=timezone.utc)
        except (TypeError, ValueError):
            last_seen_at = None
        if last_seen_at is None or datetime.now(timezone.utc) - last_seen_at >= timedelta(days=TRUSTED_DEVICE_INACTIVE_DAYS):
            with self.db.cursor() as cur:
                cur.execute("DELETE FROM trusted_devices WHERE id = ?", (row["id"],))
            return False

        if row["session_expires_at"]:
            try:
                if datetime.now(timezone.utc) >= datetime.fromisoformat(row["session_expires_at"]):
                    with self.db.cursor() as cur:
                        cur.execute("UPDATE trusted_devices SET revoked = 1 WHERE id = ?", (row["id"],))
                    return False
            except ValueError:
                return False

        with self.db.cursor() as cur:
            cur.execute(
                "UPDATE trusted_devices SET last_seen_at = ? WHERE id = ?",
                (now_iso(), row["id"])
            )
        return True

    def get_trust_level(self, token: str) -> str:
        with self.db.cursor() as cur:
            cur.execute(
                "SELECT trust_level FROM trusted_devices WHERE revoked = 0 AND (device_token = ? OR device_token = ?)",
                (_token_hash(token), token),
            )
            row = cur.fetchone()
        return (row["trust_level"] if row and row["trust_level"] else "standard")

    # ---------- إدارة الأجهزة (يظهر في إعدادات الكمبيوتر) ----------

    def list_trusted_devices(self) -> list:
        self.expire_inactive_devices(force=True)
        with self.db.cursor() as cur:
            cur.execute(
                "SELECT id, device_name, paired_at, last_seen_at, revoked, trust_level, "
                "auto_reconnect_count, last_auto_reconnect_at FROM trusted_devices"
            )
            return [dict(row) for row in cur.fetchall()]

    def get_device_name(self, token: str) -> str | None:
        """Return only the display name for one active connection token."""
        with self.db.cursor() as cur:
            cur.execute(
                "SELECT device_name FROM trusted_devices "
                "WHERE device_token IN (?, ?) AND revoked = 0 ORDER BY id DESC LIMIT 1",
                (_token_hash(token), token),
            )
            row = cur.fetchone()
        return row["device_name"] if row and row["device_name"] else None

    def revoke_device(self, device_id: int):
        """المستخدم يقدر يلغي ثقة جهاز معين - مثلاً لو ضاع منه التليفون."""
        with self.db.cursor() as cur:
            cur.execute("UPDATE trusted_devices SET revoked = 1 WHERE id = ?", (device_id,))

    def delete_revoked_devices(self) -> int:
        """يمسح من السجل فقط الأجهزة التي أُلغيت ثقتها بالفعل."""
        with self.db.cursor() as cur:
            cur.execute("DELETE FROM trusted_devices WHERE revoked = 1")
            return cur.rowcount

    def revoke_token(self, token: str):
        """إلغاء جلسة الجهاز الحالي بعد تسجيل الخروج الصريح."""
        with self.db.cursor() as cur:
            cur.execute(
                "UPDATE trusted_devices SET revoked = 1 WHERE device_token IN (?, ?)",
                (_token_hash(token), token),
            )

    def set_trust_level(self, device_id: int, trust_level: str):
        if trust_level not in {"standard", "trusted", "full"}:
            raise ValueError("مستوى ثقة غير صالح")
        with self.db.cursor() as cur:
            cur.execute("UPDATE trusted_devices SET trust_level = ? WHERE id = ?", (trust_level, device_id))

    def record_auto_reconnect(self, token: str):
        """
        بتتنادى من الـ API (مش من validate_token نفسها) لما endpoint اتصال
        صريح زي /status يتنادى بتوكن محفوظ من قبل - ده الدليل الفعلي إن
        "الثقة العمياء" شغالة: جهاز رجع يتصل من غير أي كود جديد.
        """
        with self.db.cursor() as cur:
            cur.execute(
                "UPDATE trusted_devices SET auto_reconnect_count = auto_reconnect_count + 1, "
                "last_auto_reconnect_at = ? WHERE id = (SELECT id FROM trusted_devices "
                "WHERE revoked = 0 AND (device_token = ? OR device_token = ? ) LIMIT 1)",
                (now_iso(), _token_hash(token), token)
            )
