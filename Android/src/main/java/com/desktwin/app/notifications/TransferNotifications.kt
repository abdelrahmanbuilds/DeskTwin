package com.desktwin.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * إشعار نظام حقيقي (زي تحميل المتصفح) لكل رفع/تحميل ملف - بيتحدث لحظيًا
 * بنسبة التقدّم الفعلية، مش إشعار ثابت بيقول "شغال" وخلاص.
 *
 * كل رفع/تحميل له notification ID مستقل (مبني من hash الاسم) عشان لو
 * فيه أكتر من عملية شغالة في نفس اللحظة (رفع ملفين مثلًا)، كل واحد له
 * إشعار منفصل بدل ما يبوظوا في بعض.
 */
object TransferNotifications {

    private const val CHANNEL_ID = "desktwin_transfers"

    /**
     * لازم تتنادى مرة واحدة بس، الأفضل في MainActivity.onCreate() - إنشاء
     * القناة نفسها مايحصلش غير مرة واحدة، لكن النداء آمن يتكرر (النظام
     * بيتجاهل تكرار نفس الـ channel ID من غير أي تأثير أو خطأ).
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "نقل الملفات", NotificationManager.IMPORTANCE_LOW
                // IMPORTANCE_LOW عشان الإشعار يظهر من غير صوت أو اهتزاز مع
                // كل تحديث نسبة - مزعج جدًا لو كل 1% غيّر صوّت أو رجّ التليفون.
            ).apply {
                description = "تقدّم رفع وتحميل الملفات بين التليفون والكمبيوتر"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun notificationId(key: String): Int = key.hashCode()

    /**
     * بتتنادى بعد كل تحديث تقدّم (upload أو download) - مسؤولة عن كل
     * الحالات: بداية، تحديث النسبة، نجاح، وفشل. `key` لازم يكون نفسه
     * طول عمر العملية الواحدة عشان الإشعار يتحدث في مكانه مش يتكرر.
     */
    fun updateProgress(context: Context, key: String, fileName: String, isUpload: Boolean, fraction: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return  // مفيش صلاحية - نتجاهل بصمت، مش لازم نعطل النقل نفسه
        }

        val title = if (isUpload) "بيترفع للكمبيوتر" else "بيتحمّل من الكمبيوتر"
        val percent = (fraction * 100).toInt()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)  // أيقونة نظام جاهزة - مفيش داعي أيقونة مخصصة لفيتشر بهذا الحجم
            .setContentTitle(title)
            .setContentText("$fileName — $percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)   // مينفعش يتمسح بسحبة المستخدم لحد ما العملية تخلص أو تفشل
            .setOnlyAlertOnce(true)  // صوت/اهتزاز أول مرة بس، مش مع كل تحديث نسبة
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(key), notification)
    }

    fun completeSuccess(context: Context, key: String, fileName: String, isUpload: Boolean) {
        // إشعار التقدم مؤقت؛ بعد النجاح لا نترك نسخة ثابتة في شريط الإشعارات.
        NotificationManagerCompat.from(context).cancel(notificationId(key))
    }

    fun completeFailure(context: Context, key: String, fileName: String, isUpload: Boolean) {
        NotificationManagerCompat.from(context).cancel(notificationId(key))
    }

    fun cancel(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(key))
    }
}
