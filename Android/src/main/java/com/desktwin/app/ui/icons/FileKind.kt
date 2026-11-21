package com.desktwin.app.ui.icons

/**
 * تصنيف الملف حسب الامتداد فقط - مفيش فتح للملف ولا thumbnail حقيقي.
 * الهدف إن التطبيق يرسم شكل قريب من نوع الملف من الـ metadata وحدها،
 * وده اللي بيخلي الشكل يشتغل أوفلاين تمامًا.
 */
enum class FileKind {
    IMAGE, VIDEO, AUDIO, DOCUMENT, SPREADSHEET, PRESENTATION,
    ARCHIVE, CODE, PDF, OTHER
}

object FileKindClassifier {

    private val map: Map<String, FileKind> = buildMap {
        listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "tiff", "svg", "ico")
            .forEach { put(it, FileKind.IMAGE) }
        listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "mts", "m4v", "mpg", "mpeg", "3gp")
            .forEach { put(it, FileKind.VIDEO) }
        listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus")
            .forEach { put(it, FileKind.AUDIO) }
        listOf("doc", "docx", "txt", "rtf", "odt", "md")
            .forEach { put(it, FileKind.DOCUMENT) }
        listOf("xls", "xlsx", "csv", "ods", "tsv")
            .forEach { put(it, FileKind.SPREADSHEET) }
        listOf("ppt", "pptx", "odp")
            .forEach { put(it, FileKind.PRESENTATION) }
        listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
            .forEach { put(it, FileKind.ARCHIVE) }
        listOf("py", "js", "ts", "kt", "java", "cpp", "c", "h", "cs", "html", "css", "json", "xml", "sql", "bat", "ps1", "sh")
            .forEach { put(it, FileKind.CODE) }
        put("pdf", FileKind.PDF)
    }

    /** الامتداد ممكن يجي بنقطة (".mp4") أو من غيرها، وبأي حالة أحرف. */
    fun classify(extension: String?): FileKind {
        val clean = extension?.trim()?.trimStart('.')?.lowercase().orEmpty()
        if (clean.isEmpty()) return FileKind.OTHER
        return map[clean] ?: FileKind.OTHER
    }
}

/**
 * ملخص محتوى فولدر: إيه الأنواع اللي جواه. بيحدد شكل أيقونة الفولدر.
 *   - EMPTY: مفيش ولا ملف تحته
 *   - SINGLE: نوع واحد بس سايد على كل الملفات
 *   - MIXED: أكتر من نوع (بنحتفظ بأكتر نوعين تكرارًا للرسم)
 */
sealed class FolderContent {
    object Empty : FolderContent()
    data class Single(val kind: FileKind) : FolderContent()
    data class Mixed(val primary: FileKind, val secondary: FileKind) : FolderContent()
}

object FolderContentSummarizer {

    /**
     * @param childExtensions امتدادات كل الملفات تحت الفولدر (المباشرة، أو
     * كل المستويات - القرار للمستدعي). الفولدرات الفرعية مش بتتعد كنوع.
     *
     * لو الملفات النوع OTHER بس (مفيش نوع معروف)، بنعاملها كفولدر بمحتوى
     * غير محدد: Single(OTHER)، عشان يفضل باين إن فيه حاجة جواه.
     *
     * الترتيب عند التعادل: بحسب ترتيب ظهور الـ enum، عشان النتيجة تبقى
     * ثابتة بين مرة والتانية (مش عشوائية).
     */
    fun summarize(childExtensions: List<String?>): FolderContent {
        if (childExtensions.isEmpty()) return FolderContent.Empty

        val counts = childExtensions
            .map { FileKindClassifier.classify(it) }
            .groupingBy { it }
            .eachCount()

        // أنواع معروفة أولًا - OTHER بيدخل بس لو هو الوحيد.
        val known = counts.filterKeys { it != FileKind.OTHER }
        val source = if (known.isNotEmpty()) known else counts

        val ranked = source.entries
            .sortedWith(compareByDescending<Map.Entry<FileKind, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { it.key }

        return if (ranked.size == 1) {
            FolderContent.Single(ranked[0])
        } else {
            FolderContent.Mixed(ranked[0], ranked[1])
        }
    }
}

/**
 * بيبني خريطة مسار-الفولدر -> FolderContent لكل فولدر في الشجرة المحفوظة، في
 * لفة واحدة على الشجرة كلها (بدل ما نمسحها من الأول لكل صف في الشاشة).
 *
 * كل ملف بيتحسب لكل فولدر أب فوقه (كل المستويات)، فأيقونة فولدر "الصور"
 * بتعكس اللي جواه حتى لو الملفات في فولدرات فرعية. الفولدر اللي ملوش أي
 * ملف تحته مش هيبقى في الخريطة، والمستدعي بيعامله كـ Empty.
 */
object FolderContentIndex {

    fun build(entries: List<com.desktwin.app.network.TreeEntry>): Map<String, FolderContent> {
        val extensionsByFolder = HashMap<String, MutableList<String?>>()

        for (entry in entries) {
            if (entry.is_folder) continue
            var parent = parentOf(entry.path)
            while (parent != null) {
                extensionsByFolder.getOrPut(parent) { mutableListOf() }.add(entry.extension)
                parent = parentOf(parent)
            }
        }
        return extensionsByFolder.mapValues { FolderContentSummarizer.summarize(it.value) }
    }

    private fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('\\', '/')
        val i = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
        return if (i > 0) trimmed.substring(0, i) else null
    }
}
