package com.desktwin.app.data

/**
 * الشاشات الأساسية للتطبيق - نسخة File Explorer محلية أولًا (Offline-First).
 * الشاشة الافتراضية هي FileBrowser مباشرة (تفتح من الكاش المحلي فورًا)،
 * والاتصال بالكمبيوتر (Discovery/Pairing) بقى Sub-flow تحت الإعدادات،
 * مش بوابة إجبارية قبل عرض أي حاجة للمستخدم.
 */
sealed class Screen {
    object Discovery : Screen()          // البحث عن كمبيوتر جديد - يُفتح من الإعدادات فقط
    object Pairing : Screen()            // إدخال كود الـ pairing
    object UserBinding : Screen()        // تسجيل اسم المستخدم أول مرة
    data class FileBrowser(val path: String? = null) : Screen()  // الشاشة الافتراضية - تفتح من الكاش دايمًا
    data class FileDetails(val fileId: Int) : Screen()           // تفاصيل ملف
    data class VideoStream(val fileId: Int, val name: String, val path: String) : Screen()
    object Search : Screen()             // البحث
    object Settings : Screen()           // الإعدادات - فيها كل إدارة الاتصال بالكمبيوتر
    object ThemeLibrary : Screen()       // مكتبة التصميمات والثيمات
    object Activity : Screen()            // سجل النشاط والعمليات الأخيرة
    object Conflicts : Screen()           // قرارات تعارضات المزامنة
    object Chat : Screen()
    object IntegrationHealth : Screen()
    object QuickActions : Screen()       // أوامر الجهاز السريعة
    object CommandDraft : Screen()       // إنشاء مسودة CMD/PowerShell فقط
    object Trash : Screen()
    object Upload : Screen()             // رفع ملف من التليفون للكمبيوتر
    object FolderPicker : Screen()       // اختيار مجلد وجهة عند الرفع (نفس شجرة التصفح، وضع اختيار)
    object SyncReview : Screen()         // مراجعة تحديثات الشجرة + الأوامر المؤجلة بعد إعادة الاتصال
}

/** حالة الاتصال الحالية بالكمبيوتر - بتتحكم في إيه اللي المستخدم شايفه. */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Discovering : ConnectionState()
    data class Found(val ip: String, val port: Int, val computerName: String) : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val computerName: String, val token: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
