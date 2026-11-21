from collections import deque
from datetime import datetime
import threading
import uuid

_lock = threading.Lock()
_messages = deque(maxlen=50)

def add_message(text: str, sender: str = "phone", message_id: str | None = None, status: str = "received") -> str:
    message_id = message_id or uuid.uuid4().hex
    with _lock:
        # إعادة الإرسال بنفس المعرف بعد انقطاع ردّ التأكيد لا تنشئ رسالة
        # ثانية في المحادثة؛ الرسالة الأصلية هي مصدر الحقيقة.
        if any(item.get("id") == message_id for item in _messages):
            return message_id
        _messages.appendleft({"id": message_id, "text": text, "sender": sender,
                              "status": status, "received_at": datetime.now().strftime("%H:%M:%S")})
    return message_id

def update_status(message_id: str, status: str) -> bool:
    with _lock:
        for item in _messages:
            if item.get("id") == message_id:
                item["status"] = status
                return True
    return False

def get_messages():
    with _lock:
        return list(_messages)
