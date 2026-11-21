import time
from collections import defaultdict, deque
from threading import Lock


class SecurityMonitor:
    """حاجز خفيف ضد محاولات الدخول المتكررة والسلوك غير الطبيعي."""
    def __init__(self, window_seconds: int = 300, max_failures: int = 8):
        self.window_seconds = window_seconds
        self.max_failures = max_failures
        self._failures = defaultdict(deque)
        self._blocked_until = {}
        self._lock = Lock()

    def allow(self, source: str) -> bool:
        now = time.time()
        with self._lock:
            self._prune_locked(now)
            if self._blocked_until.get(source, 0) > now:
                return False
            failures = self._failures[source]
            while failures and now - failures[0] > self.window_seconds:
                failures.popleft()
            return True

    def record_failure(self, source: str) -> bool:
        now = time.time()
        with self._lock:
            self._prune_locked(now)
            failures = self._failures[source]
            failures.append(now)
            while failures and now - failures[0] > self.window_seconds:
                failures.popleft()
            if len(failures) >= self.max_failures:
                self._blocked_until[source] = now + self.window_seconds
                failures.clear()
                return True
            return False

    def clear(self, source: str):
        with self._lock:
            self._failures.pop(source, None)
            self._blocked_until.pop(source, None)

    def snapshot(self) -> dict:
        now = time.time()
        with self._lock:
            self._prune_locked(now)
            blocked = sum(1 for value in self._blocked_until.values() if value > now)
            failures = sum(len(values) for values in self._failures.values())
        return {
            "status": "blocked_sources" if blocked else "normal",
            "blocked_sources": blocked,
            "recent_failed_attempts": failures,
            "window_seconds": self.window_seconds,
        }

    def _prune_locked(self, now: float):
        """تنظيف المصادر القديمة لمنع نمو الذاكرة مع مرور الوقت."""
        expired_blocks = [
            source for source, until in self._blocked_until.items()
            if until <= now
        ]
        for source in expired_blocks:
            self._blocked_until.pop(source, None)

        expired_failures = []
        for source, failures in self._failures.items():
            while failures and now - failures[0] > self.window_seconds:
                failures.popleft()
            if not failures and source not in self._blocked_until:
                expired_failures.append(source)
        for source in expired_failures:
            self._failures.pop(source, None)


monitor = SecurityMonitor()
