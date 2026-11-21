"""
DeskTwin Resumable Transfers (نقل الملفات الكبيرة على أجزاء)
--------------------------------------------------------------
الفكرة (زي التورنت بالظبط، بس أبسط لأن هنا جهازين بس مش شبكة كاملة):

    - الملف بيتكتب من البداية في مكانه النهائي الحقيقي على القرص، مش في
      ملفات أجزاء منفصلة بتتلحق بعدين. يعني لو الملف 3GB وحصل 900MB بس،
      هتلاقي على القرص ملف بنفس الاسم حجمه 900MB - جزء حقيقي من الملف
      الأصلي من أوله، مش ملف تالف أو placeholder.
    - لو الاتصال اتقطع، مفيش حاجة اسمها "آخر جزء نجح" بنعتمد عليها -
      بدل كده، بنقارن *الحجم الفعلي الموجود على القرص دلوقتي* بحجم
      الملف الأصلي الكامل. الفرق ده هو اللي محتاجين نبعته/نستقبله.
    - عشان نتأكد إن الجزء الناقص ده تبع "نفس" الملف الأصلي فعلاً (مش
      ملف تاني بالصدفة اسمه زي بعضه، أو الملف الأصلي اتغيّر من ساعة ما
      الرفع بدأ)، بنستخدم "بصمة" (fingerprint): hash لأول جزء صغير بس
      من الملف (أول 4MB مثلاً) - رخيصة تتحسب (مش الملف كله) لكن كافية
      كإثبات هوية عملي.

البروتوكول (خطوات أي استئناف رفع/تحميل):
    1. الجهاز المرسل (اللي عنده النسخة الكاملة) يسأل: "الملف ده - بصمته
       كذا، حجمه الكامل كذا - عندك منه قد إيه؟"
    2. الجهاز المستقبل يرد بالحجم الفعلي الموجود عنده دلوقتي على القرص
       (أو صفر لو مفيش حاجة أصلاً - رفع جديد كامل).
    3. المرسل يتأكد إن الجزء الموجود عند المستقبل بصمته متطابقة مع نفس
       الجزء عنده هو (فحص إضافي بسيط، مش hash تاني كامل) - لو مش متطابق
       (يعني الملفين مختلفين فعليًا رغم تشابه الاسم/الحجم)، يبدأ من الصفر
       بدل ما يكمل على أساس غلط.
    4. لو متطابق، المرسل يبعت بس البايتات من نقطة "عندك قد إيه" لحد آخر
       الملف - مش الملف كامل تاني.
"""

import hashlib
import os
import shutil
import threading
import time

FINGERPRINT_SAMPLE_BYTES = 4 * 1024 * 1024  # أول 4MB - كافية كبصمة، رخيصة الحساب
LARGE_FILE_THRESHOLD_BYTES = 1 * 1024 * 1024 * 1024  # 1GB - الحد اللي الفيتشر ده بيتفعل بعده
_path_locks_guard = threading.Lock()
_path_locks: dict[str, threading.Lock] = {}
_COPY_BUFFER_BYTES = 4 * 1024 * 1024


def _path_lock(path: str) -> threading.Lock:
    key = os.path.normcase(os.path.abspath(path))
    with _path_locks_guard:
        return _path_locks.setdefault(key, threading.Lock())


def is_large_file(size_bytes: int) -> bool:
    """الفيتشر ده بيتطبق بس على الملفات اللي بتبدأ من 1GB، زي ما المستخدم حدد."""
    return size_bytes >= LARGE_FILE_THRESHOLD_BYTES


def compute_fingerprint(path: str, sample_bytes: int = FINGERPRINT_SAMPLE_BYTES) -> str:
    """
    بصمة رخيصة للملف: SHA-256 لأول `sample_bytes` بايت بس، مش الملف كامل.
    ملحوظة مهمة: بتقرأ من بداية الملف بس - فلو المستخدم قطع/لصق نفس
    البداية بالظبط في ملفين مختلفين فعليًا (نادر جدًا عمليًا)، البصمة
    هتتطابق غلط. ده تنازل مقبول هنا لأن البديل (hash الملف كامل، اللي
    ممكن يكون عدة جيجا) هيكلف وقت طويل يهدم الهدف الأساسي من الفيتشر.
    """
    h = hashlib.sha256()
    with open(path, "rb") as f:
        remaining = sample_bytes
        while remaining > 0:
            chunk = f.read(min(65536, remaining))
            if not chunk:
                break
            h.update(chunk)
            remaining -= len(chunk)
    return h.hexdigest()


def verify_partial_matches_fingerprint(partial_path: str, expected_fingerprint: str,
                                          sample_bytes: int = FINGERPRINT_SAMPLE_BYTES) -> bool:
    """
    قبل ما نكمل رفع/تحميل ملف ناقص، لازم نتأكد إن اللي موجود فعلاً هو
    بداية نفس الملف - مش ملف تاني وصلته بالصدفة بنفس الاسم والمسار.

    لو الجزء الموجود على القرص أصغر من عينة البصمة نفسها (يعني الملف
    قطع رفعه من أول ثانية تقريبًا)، بنرجع False بحذر - أنسب نبدأ من
    الصفر بدل ما نخمّن على عينة ناقصة.
    """
    try:
        actual_size = os.path.getsize(partial_path)
    except OSError:
        return False

    if actual_size < sample_bytes:
        return False

    actual_fingerprint = compute_fingerprint(partial_path, sample_bytes)
    return actual_fingerprint == expected_fingerprint


def get_resume_offset(partial_path: str) -> int:
    """
    نقطة الاستئناف = الحجم الفعلي الموجود على القرص دلوقتي، مش أي رقم
    متسجل قبل كده كـ "آخر جزء نجح" - المصدر الحقيقي الوحيد هو القرص
    نفسه، عشان نتجنب أي عدم اتساق لو حصل قطع فجائي وسط الكتابة.
    """
    try:
        return os.path.getsize(partial_path)
    except OSError:
        return 0


def append_chunk(path: str, offset: int, data: bytes):
    """
    بتكتب جزء جديد في مكانه الصحيح جوه الملف - `offset` هنا هو تأكيد
    (assertion) مش مجرد قيمة نثق فيها: لازم يتطابق مع حجم الملف الفعلي
    على القرص وقت الكتابة، وإلا في احتمال حصل تعديل متزامن غريب على
    الملف من مصدر تاني في نفس اللحظة - وأفضل نوقف بدل ما نكتب في مكان غلط.
    """
    current_size = os.path.getsize(path) if os.path.exists(path) else 0
    if current_size != offset:
        raise ValueError(
            f"عدم تطابق في نقطة الاستئناف: الملف حجمه فعليًا {current_size} بايت، "
            f"لكن المتوقع كان {offset} بايت - رفض الكتابة تفاديًا لتلف الملف."
        )
    with open(path, "ab") as f:
        f.write(data)


def append_upload_stream(path: str, offset: int, source, expected_total_size: int) -> int:
    """Append a spooled multipart stream without materializing it in RAM."""
    with _path_lock(path):
        current_size = os.path.getsize(path) if os.path.exists(path) else 0
        if current_size != offset:
            raise ValueError(
                f"عدم تطابق في نقطة الاستئناف: الملف حجمه فعليًا {current_size} بايت، "
                f"لكن المتوقع كان {offset} بايت - رفض الكتابة تفاديًا لتلف الملف."
            )
        source.seek(0, os.SEEK_END)
        incoming_size = source.tell()
        source.seek(0)
        if incoming_size < 0 or offset + incoming_size > expected_total_size:
            raise ValueError("حجم الجزء يتجاوز الحجم الأصلي المسجل للنقل")
        with open(path, "ab") as destination:
            shutil.copyfileobj(source, destination, length=_COPY_BUFFER_BYTES)
            destination.flush()
            return destination.tell()


def cleanup_expired_transfers(db):
    """
    بتتنادى دوريًا (راجع start_cleanup_loop) - بتدور على أي ملف ناقص عدى
    عليه وقت الاحتفاظ المحدد (retention_hours، من إعدادات المستخدم) من
    غير أي تقدّم جديد، وتمسحه نهائيًا من القرص ومن سجل النقل - بالظبط
    زي ما المستخدم قرر: "يتمسح الملف الناقص نهائيًا من الكمبيوتر".
    """
    expired = db.list_expired_transfers()
    for row in expired:
        path = row["file_path"]
        try:
            if os.path.exists(path):
                os.remove(path)
        except OSError as e:
            print(f"[RESUMABLE_TRANSFER] تعذر مسح الملف الناقص المنتهي '{path}': {e}")
            continue  # نسيبه في السجل عشان نحاول تاني في الدورة الجاية
        db.delete_resumable_transfer(path)
        print(f"[RESUMABLE_TRANSFER] اتمسح ملف ناقص منتهي الصلاحية: {path}")


def start_cleanup_loop(db, interval_seconds: int = 3600):
    """
    Thread خلفي دائم بيفحص كل ساعة (افتراضيًا) هل فيه ملفات ناقصة عدت
    مدة الاحتفاظ بتاعتها - نفس نمط clipboard_sync._watch_loop بالظبط.
    """
    def _loop():
        while True:
            time.sleep(interval_seconds)
            try:
                cleanup_expired_transfers(db)
            except Exception as e:
                print(f"[RESUMABLE_TRANSFER] خطأ غير متوقع في دورة التنظيف: {e}")

    threading.Thread(target=_loop, daemon=True).start()
