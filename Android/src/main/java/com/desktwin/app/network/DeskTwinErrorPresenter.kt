package com.desktwin.app.network

import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import com.desktwin.app.diagnostics.DeskTwinDiagnostics

data class DeskTwinUserError(
    val title: String,
    val message: String,
    val solution: String,
    val action: ErrorAction
)

enum class ErrorAction { RETRY, RECONNECT, PAIR_AGAIN, REFRESH, REQUEST_ACCESS, NONE }

object DeskTwinErrorPresenter {
    fun fromHttp(code: Int?, detail: String? = null): DeskTwinUserError {
        DeskTwinDiagnostics.record("http", "error_presented", "code=${code ?: "unknown"}")
        return when (code) {
        400 -> DeskTwinUserError("الطلب غير صالح", detail ?: "البيانات المرسلة ناقصة أو غير صحيحة.", "راجع الملف أو المسار وحاول مرة أخرى.", ErrorAction.NONE)
        401 -> DeskTwinUserError("انتهت الثقة", "الكمبيوتر لم يعد يقبل Token هذا الهاتف.", "أعد اقتران الهاتف بالكمبيوتر.", ErrorAction.PAIR_AGAIN)
        403 -> if (detail?.contains("ثقة") == true || detail?.contains("أمر جهاز") == true) {
            DeskTwinUserError("الثقة غير كافية", detail, "افتح الأجهزة الموثوقة في الكمبيوتر، اختر الهاتف، واجعل مستوى الثقة: كامل، ثم أعد المحاولة.", ErrorAction.RETRY)
        } else DeskTwinUserError("الوصول مرفوض", detail ?: "المسار خارج الأماكن المسموح بها.", "اطلب مشاركة المجلد من إعدادات الكمبيوتر.", ErrorAction.REQUEST_ACCESS)
        404 -> DeskTwinUserError("العنصر غير موجود", detail ?: "الملف أو المجلد لم يعد موجودًا في الكمبيوتر.", "حدّث القائمة وتأكد أن العنصر لم يتم نقله أو حذفه.", ErrorAction.REFRESH)
        409 -> DeskTwinUserError("تعارض في العملية", detail ?: "تغيرت الحالة قبل تنفيذ الطلب.", "راجع التعارض واختر إعادة المحاولة أو التخطي.", ErrorAction.RETRY)
        410 -> DeskTwinUserError("الملف لم يعد موجودًا", detail ?: "تم حذف الملف أو نقله من الكمبيوتر.", "حدّث القائمة ثم اختر ملفًا موجودًا.", ErrorAction.REFRESH)
        426 -> DeskTwinUserError("نسخة غير متوافقة", "نسخة الموبايل والكمبيوتر لا تتفقان في البروتوكول.", "حدّث البرنامجين إلى نفس الإصدار.", ErrorAction.NONE)
        500, 502, 503 -> DeskTwinUserError("الكمبيوتر واجه مشكلة", detail ?: "الخدمة لم تستطع تنفيذ العملية.", "تأكد أن الخدمة تعمل ثم اضغط إعادة المحاولة.", ErrorAction.RECONNECT)
            else -> DeskTwinUserError("تعذر تنفيذ العملية", detail ?: "حدث خطأ غير متوقع.", "تأكد من الاتصال ثم حاول مرة أخرى.", ErrorAction.RETRY)
        }
    }

    fun fromThrowable(error: Throwable): DeskTwinUserError {
        DeskTwinDiagnostics.recordThrowable("network", error)
        return when (error) {
        is SSLHandshakeException -> DeskTwinUserError("هوية الكمبيوتر تغيرت", "الشهادة الأمنية لا تطابق الكمبيوتر المقترن.", "أعد الاقتران بالكمبيوتر إذا كنت تثق به.", ErrorAction.PAIR_AGAIN)
        is SocketTimeoutException, is ConnectException -> DeskTwinUserError("الكمبيوتر غير متاح", "لم يصل رد من الكمبيوتر في الوقت المحدد.", "تأكد أن الجهازين على نفس الشبكة ثم أعد الاتصال.", ErrorAction.RECONNECT)
            else -> DeskTwinUserError("تعذر تنفيذ العملية", error.message ?: "حدث خطأ غير متوقع.", "أعد المحاولة، وإذا تكرر الخطأ راجع حالة الكمبيوتر والاتصال.", ErrorAction.RETRY)
        }
    }
}
