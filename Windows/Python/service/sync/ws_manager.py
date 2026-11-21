"""
DeskTwin WebSocket Manager
---------------------------
قناة اتصال فورية (real-time) في الاتجاهين بين الكمبيوتر والتليفون - عكس
باقي الـ API اللي كله HTTP request/response عادي (طلب واحد، رد واحد،
خلاص). الفيتشرات اللي محتاجة "حاجة تحصل فورًا من غير ما حد يطلبها" -
زي مزامنة الـ clipboard، وبعدين مستقبلاً أوامر التحكم عن بعد - كلها
هتمر من هنا.

تصميم الرسائل: كل رسالة JSON بسيط فيه حقل "type" يحدد نوعها، عشان نقدر
نضيف أنواع جديدة بعدين (mirror_frame، process_list، إلخ) من غير ما
نكسر أي حاجة قديمة. النوع الوحيد المدعوم دلوقتي: "clipboard_update".

اتصال واحد بس في كل مرة متوقع عمليًا (تليفون واحد بيتحكم في كمبيوتر
واحد في نفس اللحظة) - لكن الكود هنا بيدعم أكتر من اتصال من ناحية البنية
(list مش متغير واحد) عشان لو احتجنا نوسّعها بعدين.
"""

import json
import threading
from typing import Optional

from fastapi import WebSocket


class ConnectionManager:
    def __init__(self):
        self._connections: list[tuple[WebSocket, str, str]] = []
        self._binary_transfer_connections: set[WebSocket] = set()
        # قفل عادي (مش asyncio.Lock) لأن الكود ده ممكن يتنادى من thread
        # المراقبة العادي (clipboard watcher) اللي شغال بره الـ event loop
        # بتاع FastAPI/asyncio - فمحتاجين مزامنة تشتغل مع الاتنين بأمان.
        self._lock = threading.Lock()

    async def connect(
        self,
        websocket: WebSocket,
        token: str = "",
        channel: str = "control",
        binary_transfer: bool = False,
    ):
        headers = [(b"x-desktwin-transfer-protocol", b"binary-v1")] if binary_transfer else None
        await websocket.accept(headers=headers)
        with self._lock:
            self._connections.append((websocket, token, channel))
            if binary_transfer:
                self._binary_transfer_connections.add(websocket)

    def disconnect(self, websocket: WebSocket):
        with self._lock:
            self._connections = [(ws, t, channel) for ws, t, channel in self._connections if ws is not websocket]
            self._binary_transfer_connections.discard(websocket)

    async def broadcast_json(self, data: dict) -> int:
        """
        بتبعت لكل الاتصالات المفتوحة حاليًا. لو اتصال معين وقع أو حصل فيه
        خطأ وقت البعت، بنشيله من القائمة بدل ما نسيبه يعطل باقي الاتصالات
        أو يرمي استثناء يوقف العملية كلها.
        """
        with self._lock:
            targets = [ws for ws, _, channel in self._connections if channel == "control"]

        dead: list[WebSocket] = []
        payload = json.dumps(data, ensure_ascii=False)
        delivered = 0
        for ws in targets:
            try:
                await ws.send_text(payload)
                delivered += 1
            except Exception:
                dead.append(ws)

        if dead:
            with self._lock:
                for ws in dead:
                    self._connections = [(candidate, token, channel) for candidate, token, channel in self._connections if candidate is not ws]
        return delivered

    async def send_to_token(self, token: str, data: dict, channel: str = "control") -> bool:
        with self._lock:
            targets = [ws for ws, candidate, candidate_channel in self._connections if candidate == token and candidate_channel == channel]
        payload = json.dumps(data, ensure_ascii=False)
        for ws in targets:
            try:
                await ws.send_text(payload)
                return True
            except Exception:
                self.disconnect(ws)
        return False

    async def send_binary_to_token(self, token: str, data: bytes, channel: str = "phone_files") -> bool:
        with self._lock:
            targets = [
                ws for ws, candidate, candidate_channel in self._connections
                if candidate == token and candidate_channel == channel
                and ws in self._binary_transfer_connections
            ]
        for ws in targets:
            try:
                await ws.send_bytes(data)
                return True
            except Exception:
                self.disconnect(ws)
        return False

    def supports_binary_transfer(self, token: str, channel: str = "phone_files") -> bool:
        with self._lock:
            return any(
                ws in self._binary_transfer_connections
                for ws, candidate, candidate_channel in self._connections
                if candidate == token and candidate_channel == channel
            )

    @property
    def has_active_connection(self) -> bool:
        with self._lock:
            return any(channel == "control" for _, _, channel in self._connections)

    @property
    def active_count(self) -> int:
        """عدد جلسات WebSocket المفتوحة حاليًا، للعرض والمراقبة الأمنية."""
        with self._lock:
            return len(self._connections)

    def active_tokens(self, channel: str = "control") -> list[str]:
        with self._lock:
            return [token for _, token, candidate_channel in self._connections if token and candidate_channel == channel]


# نسخة واحدة مشتركة على مستوى التطبيق كله - نفسها اللي server.py والـ
# clipboard watcher هيستخدموها، عشان الكل يبعت وياخد من نفس قائمة الاتصالات.
manager = ConnectionManager()
