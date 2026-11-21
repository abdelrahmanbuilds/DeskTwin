"""Safe helpers for local file operations exposed by the DeskTwin API."""

import os
import shutil


def _duplicate_path(destination: str, index: int) -> str:
    parent, name = os.path.split(destination)
    stem, extension = os.path.splitext(name)
    suffix = " (copy)" if index == 1 else f" (copy {index})"
    return os.path.join(parent, f"{stem}{suffix}{extension}")


def _copy_once_without_overwrite(source: str, destination: str) -> None:
    if os.path.isdir(source) and not os.path.islink(source):
        # Reserve the name atomically so a concurrent request cannot overwrite it.
        os.mkdir(destination)
        try:
            shutil.copytree(source, destination, dirs_exist_ok=True)
        except Exception:
            shutil.rmtree(destination, ignore_errors=True)
            raise
        return

    descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o666)
    try:
        with os.fdopen(descriptor, "wb") as target, open(source, "rb") as origin:
            shutil.copyfileobj(origin, target)
        shutil.copystat(source, destination)
    except Exception:
        try:
            os.close(descriptor)
        except OSError:
            pass
        try:
            os.unlink(destination)
        except OSError:
            pass
        raise


def copy_without_overwrite(source: str, destination: str, *, duplicate_on_conflict: bool) -> str:
    """Copy to destination, optionally choosing a numbered sibling on collision.

    Returns the actual destination. Existing files/directories are never overwritten.
    """
    candidates = (destination,) if not duplicate_on_conflict else (
        destination,
        *(_duplicate_path(destination, index) for index in range(1, 10_001)),
    )
    for candidate in candidates:
        try:
            _copy_once_without_overwrite(source, candidate)
            return candidate
        except FileExistsError:
            if not duplicate_on_conflict:
                raise
    raise FileExistsError("تعذر إيجاد اسم متاح للنسخة بعد محاولات متعددة")
