package com.desktwin.app.network

import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import com.desktwin.app.diagnostics.DeskTwinDiagnostics
import com.desktwin.app.ui.theme.localizedText

data class DeskTwinUserError(
    val title: String,
    val message: String,
    val solution: String,
    val action: ErrorAction
)

enum class ErrorAction { RETRY, RECONNECT, PAIR_AGAIN, REFRESH, REQUEST_ACCESS, NONE }

object DeskTwinErrorPresenter {
    private val arabicCharacters = Regex("[\\u0600-\\u06FF]")

    private fun detailForLanguage(detail: String?, language: String): String? = detail?.takeIf {
        if (language == "ar") arabicCharacters.containsMatchIn(it) else !arabicCharacters.containsMatchIn(it)
    }

    fun fromHttp(code: Int?, detail: String? = null, language: String = "en"): DeskTwinUserError {
        DeskTwinDiagnostics.record("http", "error_presented", "code=${code ?: "unknown"}")
        val safeDetail = detailForLanguage(detail, language)
        return when (code) {
        400 -> DeskTwinUserError(localizedText(language, "الطلب غير صالح", "Invalid request"), safeDetail ?: localizedText(language, "البيانات المرسلة ناقصة أو غير صحيحة.", "Some required information is missing or invalid."), localizedText(language, "راجع الملف أو المسار وحاول مرة أخرى.", "Check the file or path, then try again."), ErrorAction.NONE)
        401 -> DeskTwinUserError(localizedText(language, "انتهت الثقة", "Pairing expired"), localizedText(language, "الكمبيوتر لم يعد يقبل رمز هذا الهاتف.", "The computer no longer accepts this phone's pairing token."), localizedText(language, "أعد اقتران الهاتف بالكمبيوتر.", "Pair the phone with the computer again."), ErrorAction.PAIR_AGAIN)
        403 -> if (safeDetail?.contains("ثقة") == true || safeDetail?.contains("أمر جهاز") == true || detail?.contains("device command", true) == true) {
            DeskTwinUserError(localizedText(language, "الثقة غير كافية", "Insufficient trust"), safeDetail ?: localizedText(language, "هذا الإجراء يحتاج مستوى ثقة كاملًا.", "This action requires Full trust."), localizedText(language, "افتح الأجهزة الموثوقة على الكمبيوتر، واختر الهاتف، ثم غيّر مستوى الثقة إلى كامل.", "Open Trusted devices on the computer, select the phone, and set its trust level to Full."), ErrorAction.RETRY)
        } else DeskTwinUserError(localizedText(language, "الوصول مرفوض", "Access denied"), safeDetail ?: localizedText(language, "المسار خارج الأماكن المسموح بها.", "This path is outside the allowed locations."), localizedText(language, "اطلب مشاركة المجلد من إعدادات الكمبيوتر.", "Ask the computer user to share this folder."), ErrorAction.REQUEST_ACCESS)
        404 -> DeskTwinUserError(localizedText(language, "العنصر غير موجود", "Item not found"), safeDetail ?: localizedText(language, "الملف أو المجلد لم يعد موجودًا على الكمبيوتر.", "The file or folder is no longer available on the computer."), localizedText(language, "حدّث القائمة وتأكد أن العنصر لم يُنقل أو يُحذف.", "Refresh the list and check whether the item was moved or deleted."), ErrorAction.REFRESH)
        409 -> DeskTwinUserError(localizedText(language, "تعارض في العملية", "Operation conflict"), safeDetail ?: localizedText(language, "تغيرت الحالة قبل تنفيذ الطلب.", "The item changed before the request could finish."), localizedText(language, "راجع التعارض واختر إعادة المحاولة أو التخطي.", "Review the conflict, then retry or skip."), ErrorAction.RETRY)
        410 -> DeskTwinUserError(localizedText(language, "الملف لم يعد موجودًا", "File is no longer available"), safeDetail ?: localizedText(language, "تم حذف الملف أو نقله من الكمبيوتر.", "The file was deleted or moved from the computer."), localizedText(language, "حدّث القائمة ثم اختر ملفًا موجودًا.", "Refresh the list and choose an available file."), ErrorAction.REFRESH)
        426 -> DeskTwinUserError(localizedText(language, "نسخة غير متوافقة", "Incompatible versions"), localizedText(language, "نسخة الهاتف والكمبيوتر لا تتفقان في البروتوكول.", "The phone and computer versions use incompatible protocols."), localizedText(language, "حدّث البرنامجين إلى الإصدار نفسه.", "Update both apps to compatible versions."), ErrorAction.NONE)
        500, 502, 503 -> DeskTwinUserError(localizedText(language, "الكمبيوتر واجه مشكلة", "Computer error"), safeDetail ?: localizedText(language, "تعذرت الخدمة عن تنفيذ العملية.", "The service could not complete this operation."), localizedText(language, "تأكد أن الخدمة تعمل ثم حاول مرة أخرى.", "Check that the service is running, then try again."), ErrorAction.RECONNECT)
            else -> DeskTwinUserError(localizedText(language, "تعذر تنفيذ العملية", "Could not complete the operation"), safeDetail ?: localizedText(language, "حدث خطأ غير متوقع.", "An unexpected error occurred."), localizedText(language, "تأكد من الاتصال ثم حاول مرة أخرى.", "Check the connection, then try again."), ErrorAction.RETRY)
        }
    }

    fun fromThrowable(error: Throwable, language: String = "en"): DeskTwinUserError {
        DeskTwinDiagnostics.recordThrowable("network", error)
        return when (error) {
        is SSLHandshakeException -> DeskTwinUserError(localizedText(language, "هوية الكمبيوتر تغيرت", "Computer identity changed"), localizedText(language, "الشهادة الأمنية لا تطابق الكمبيوتر المقترن.", "The security certificate does not match the paired computer."), localizedText(language, "أعد الاقتران بالكمبيوتر إذا كنت تثق به.", "Pair with the computer again if you trust it."), ErrorAction.PAIR_AGAIN)
        is SocketTimeoutException, is ConnectException -> DeskTwinUserError(localizedText(language, "الكمبيوتر غير متاح", "Computer unavailable"), localizedText(language, "لم يصل رد من الكمبيوتر في الوقت المحدد.", "The computer did not respond in time."), localizedText(language, "تأكد أن الجهازين على الشبكة نفسها ثم أعد الاتصال.", "Put both devices on the same network, then reconnect."), ErrorAction.RECONNECT)
            else -> DeskTwinUserError(localizedText(language, "تعذر تنفيذ العملية", "Could not complete the operation"), localizedText(language, "حدث خطأ غير متوقع.", "An unexpected error occurred."), localizedText(language, "أعد المحاولة. إذا تكرر الخطأ، راجع حالة الكمبيوتر والاتصال.", "Try again. If the problem continues, check the computer and connection status."), ErrorAction.RETRY)
        }
    }
}
