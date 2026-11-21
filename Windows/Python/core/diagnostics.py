"""Structured, privacy-safe diagnostics for DeskTwin field debugging."""

from __future__ import annotations

import json
import logging
import logging.handlers
import os
import re
import time
import uuid
from pathlib import Path
from typing import Any


_SECRET = re.compile(
    r"(?i)(?P<prefix>(?:authorization|token|password|secret)\s*[=:]\s*(?:bearer\s+)?)[^\s,;]+"
)
_LOGGER = logging.getLogger("desktwin.diagnostics")
_INITIALIZED = False


def _safe(value: Any) -> Any:
    if isinstance(value, str):
        return _SECRET.sub(
            lambda match: f"{match.group('prefix')}<redacted>",
            value,
        )
    return value


def _safe_fields(fields: dict[str, Any]) -> dict[str, Any]:
    return {
        key: "<redacted>" if re.search(r"(?i)(token|authorization|password|secret)", key) else _safe(value)
        for key, value in fields.items()
    }


def initialize(log_dir: str | Path | None = None) -> None:
    global _INITIALIZED
    if _INITIALIZED:
        return
    project_root = Path(os.environ.get("DESKTWIN_ROOT") or Path(__file__).resolve().parents[2])
    target = Path(log_dir) if log_dir else project_root / "data" / "logs"
    target.mkdir(parents=True, exist_ok=True)
    handler = logging.handlers.RotatingFileHandler(
        target / "desktwin-diagnostics.jsonl", maxBytes=512 * 1024, backupCount=2, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(message)s"))
    _LOGGER.addHandler(handler)
    _LOGGER.setLevel(logging.INFO)
    _INITIALIZED = True
    record("diagnostics", "started")


def operation_id() -> str:
    return uuid.uuid4().hex[:8]


def record(stage: str, event: str, operation: str | None = None, **fields: Any) -> None:
    if not _INITIALIZED:
        initialize()
    payload = {
        "time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "operation_id": operation or "-",
        "stage": stage,
        "event": event,
        **_safe_fields(fields),
    }
    _LOGGER.info(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))


def record_exception(stage: str, error: BaseException, operation: str | None = None, **fields: Any) -> None:
    record(stage, "failure", operation, error_type=type(error).__name__, error=str(error), **fields)


def run_preflight(project_root: str | Path | None = None) -> list[str]:
    """Return actionable warnings before startup; never mutates user data."""
    root = Path(project_root or os.environ.get("DESKTWIN_ROOT") or Path(__file__).resolve().parents[2])
    warnings: list[str] = []
    checks = {
        "config": root / "config" / "config.json",
        "database": root / "database" / "desktwin.db",
        "api": root / "Python" / "service" / "api" / "server.py",
        "watcher": root / "Python" / "service" / "watcher" / "watcher.py",
    }
    for name, path in checks.items():
        if not path.exists():
            warnings.append(f"missing_{name}")
            record("preflight", "warning", check=name, reason="missing_file", path=str(path))
    syntax_targets = [
        checks["api"], checks["watcher"], root / "Python" / "core" / "desktwin_service.py",
        root / "Python" / "service" / "auth" / "pairing.py",
    ]
    for path in syntax_targets:
        if not path.exists():
            continue
        try:
            compile(path.read_text(encoding="utf-8"), str(path), "exec")
        except SyntaxError as error:
            warning = f"syntax_error:{path.name}:{error.lineno or 0}"
            warnings.append(warning)
            record(
                "preflight", "warning", check="python_syntax", reason="syntax_error",
                path=str(path), line=error.lineno or 0, column=error.offset or 0,
            )
    config_path = checks["config"]
    if config_path.exists() and config_path.stat().st_size == 0:
        warnings.append("empty_config")
        record("preflight", "warning", check="config", reason="empty_file")
    record("preflight", "completed", warning_count=len(warnings))
    return warnings


