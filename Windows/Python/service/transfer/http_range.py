"""Small, single-range HTTP streaming support for video playback."""

import mimetypes
import os
from urllib.parse import quote

from fastapi import HTTPException
from fastapi.responses import FileResponse, StreamingResponse


class TransferFileResponse(FileResponse):
    """Use 1 MiB reads for large file downloads instead of 64 KiB defaults."""

    chunk_size = 1024 * 1024


def parse_byte_range(range_header: str | None, size: int) -> tuple[int, int, bool]:
    """Return inclusive start/end and whether the response is partial."""
    if not range_header:
        return 0, max(size - 1, -1), False

    unit, separator, value = range_header.partition("=")
    if separator != "=" or unit.strip().lower() != "bytes" or "," in value:
        raise HTTPException(416, "Unsupported or invalid byte range", headers={"Content-Range": f"bytes */{size}"})

    first, dash, last = value.strip().partition("-")
    if not dash or size <= 0:
        raise HTTPException(416, "Unsatisfiable byte range", headers={"Content-Range": f"bytes */{size}"})

    try:
        if not first:
            suffix_length = int(last)
            if suffix_length <= 0:
                raise ValueError
            start = max(size - suffix_length, 0)
            end = size - 1
        else:
            start = int(first)
            end = int(last) if last else size - 1
    except ValueError:
        raise HTTPException(416, "Invalid byte range", headers={"Content-Range": f"bytes */{size}"}) from None

    if start < 0 or start >= size or end < start:
        raise HTTPException(416, "Unsatisfiable byte range", headers={"Content-Range": f"bytes */{size}"})
    return start, min(end, size - 1), True


def _iter_file_range(path: str, start: int, length: int, chunk_size: int = 1024 * 1024):
    with open(path, "rb") as file:
        file.seek(start)
        remaining = length
        while remaining:
            chunk = file.read(min(chunk_size, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk


def file_range_response(path: str, filename: str, range_header: str | None) -> StreamingResponse:
    """Serve a video lazily, honoring Media3's Range seeks without loading it all."""
    size = os.path.getsize(path)
    start, end, partial = parse_byte_range(range_header, size)
    length = max(end - start + 1, 0)
    headers = {
        "Accept-Ranges": "bytes",
        "Content-Length": str(length),
        "Content-Disposition": f"inline; filename*=UTF-8''{quote(filename, safe='')}",
        "Cache-Control": "no-transform",
    }
    if partial:
        headers["Content-Range"] = f"bytes {start}-{end}/{size}"
    media_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    return StreamingResponse(
        _iter_file_range(path, start, length),
        status_code=206 if partial else 200,
        headers=headers,
        media_type=media_type,
    )
