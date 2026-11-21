package com.desktwin.app.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.desktwin.app.ui.theme.LocalDeskTwinThemePreset
import com.desktwin.app.ui.theme.ThemeArtStyle

/**
 * أيقونات مرسومة بالكامل بالكود (Canvas)، وألوان المجلد تتبع الثيم الحالي.
 * أسلوب الملفات يظل واضحًا حسب النوع؛ Orchard يحتفظ بألوان الطبيعة، بينما
 * Apple يرسم أيقونات تفاح أحادية اللون هنا من دون أصول خارجية.
 *
 * كل الأبعاد نسبية لحجم الأيقونة (w/h) عشان تفضل واضحة من 24dp لـ 64dp.
 */

private val PaperWhite = Color(0xFFFFFFFF)
private val PaperEdge = Color(0xFFD5D9E0)

private data class FilePaperPalette(val fill: Color, val edge: Color, val fold: Color)

private fun filePaperPalette(preset: com.desktwin.app.ui.theme.DeskTwinThemePreset) = when (preset.artStyle) {
    ThemeArtStyle.ORBIT -> FilePaperPalette(Color(0xFFEDEBFF), preset.accent.copy(alpha = 0.58f), Color(0xFFD9D5FF))
    ThemeArtStyle.CODE -> FilePaperPalette(Color(0xFF17231D), preset.accentDark.copy(alpha = 0.74f), Color(0xFF26382E))
    ThemeArtStyle.EMBER -> FilePaperPalette(Color(0xFFFFF2E5), Color(0xFFE6CDB5), Color(0xFFF2DFCD))
    ThemeArtStyle.ORCHARD -> FilePaperPalette(Color(0xFFF5F8F2), Color(0xFFC9D8C7), Color(0xFFE5EDE3))
    ThemeArtStyle.APPLE -> FilePaperPalette(PaperWhite, PaperEdge, Color(0xFFEDEFF3))
    ThemeArtStyle.LINK -> FilePaperPalette(PaperWhite, Color(0xFFBCCBE0), Color(0xFFEAF0F8))
}

// لون علامة كل نوع ملف
private fun kindColor(kind: FileKind): Color = when (kind) {
    FileKind.IMAGE -> Color(0xFF26A69A)
    FileKind.VIDEO -> Color(0xFF7E57C2)
    FileKind.AUDIO -> Color(0xFFEC407A)
    FileKind.DOCUMENT -> Color(0xFF2D6CDF)
    FileKind.SPREADSHEET -> Color(0xFF2E9E5B)
    FileKind.PRESENTATION -> Color(0xFFE8710A)
    FileKind.ARCHIVE -> Color(0xFF8D6E63)
    FileKind.CODE -> Color(0xFF546E7A)
    FileKind.PDF -> Color(0xFFE53935)
    FileKind.OTHER -> Color(0xFF9AA3AF)
}

// ---------------------------------------------------------------------
// أيقونة الفولدر
// ---------------------------------------------------------------------

@Composable
fun FolderIcon(content: FolderContent, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val preset = LocalDeskTwinThemePreset.current
    val paperPalette = filePaperPalette(preset)
    val folderBack = when (preset.artStyle) {
        ThemeArtStyle.CODE -> preset.accentDark
        ThemeArtStyle.ORCHARD -> preset.accent
        ThemeArtStyle.APPLE -> MaterialTheme.colorScheme.onSurface
        else -> preset.accent
    }
    val folderFront = if (preset.artStyle == ThemeArtStyle.APPLE) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else preset.secondary
    val folderShade = folderFront.copy(alpha = 0.78f)
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // 1) الخلفية الخلفية للفولدر + اللسان العلوي
        drawFolderBack(w, h, folderBack)

        // 2) الورق اللي جوه الفولدر (بين الخلفية والواجهة الأمامية)
        when (content) {
            FolderContent.Empty -> Unit  // فولدر فاضي: مفيش ورق
            is FolderContent.Single -> drawPaper(
                left = w * 0.24f, top = h * 0.02f, pw = w * 0.52f, ph = h * 0.62f,
                kind = content.kind, markCenterY = 0.34f,
                paperFill = paperPalette.fill, paperEdge = paperPalette.edge, foldFill = paperPalette.fold
            )
            is FolderContent.Mixed -> {
                // ورقتين متداخلتين بميل بسيط = "ميكس"
                drawPaper(
                    left = w * 0.12f, top = h * 0.10f, pw = w * 0.46f, ph = h * 0.54f,
                    kind = content.secondary, markCenterY = 0.36f,
                    paperFill = paperPalette.fill, paperEdge = paperPalette.edge, foldFill = paperPalette.fold
                )
                drawPaper(
                    left = w * 0.38f, top = h * 0.02f, pw = w * 0.50f, ph = h * 0.60f,
                    kind = content.primary, markCenterY = 0.34f,
                    paperFill = paperPalette.fill, paperEdge = paperPalette.edge, foldFill = paperPalette.fold
                )
            }
        }

        // 3) الواجهة الأمامية للفولدر بتغطي أسفل الورق
        drawFolderFront(w, h, folderFront, folderShade)
    }
}

private fun DrawScope.drawFolderBack(w: Float, h: Float, color: Color) {
    val r = w * 0.06f
    val path = Path().apply {
        moveTo(w * 0.06f, h * 0.24f + r)
        quadraticTo(w * 0.06f, h * 0.24f, w * 0.06f + r, h * 0.24f)
        lineTo(w * 0.36f, h * 0.24f)
        lineTo(w * 0.44f, h * 0.14f)
        lineTo(w * 0.94f - r, h * 0.14f)
        quadraticTo(w * 0.94f, h * 0.14f, w * 0.94f, h * 0.14f + r)
        lineTo(w * 0.94f, h * 0.84f)
        lineTo(w * 0.06f, h * 0.84f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawFolderFront(w: Float, h: Float, color: Color, shade: Color) {
    val r = w * 0.06f
    // الواجهة الأمامية أعرض ومايلة سنة بسيطة لفوق من الشمال
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.04f, h * 0.38f),
        size = Size(w * 0.92f, h * 0.48f),
        cornerRadius = CornerRadius(r, r)
    )
    // ظل خفيف تحت لإحساس العمق
    drawRoundRect(
        color = shade,
        topLeft = Offset(w * 0.04f, h * 0.78f),
        size = Size(w * 0.92f, h * 0.08f),
        cornerRadius = CornerRadius(r, r)
    )
}

// ---------------------------------------------------------------------
// أيقونة الملف: ورقة بيضا عليها علامة النوع (زي ملف عادي في المستكشف)
// ---------------------------------------------------------------------

@Composable
fun FileIcon(kind: FileKind, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val preset = LocalDeskTwinThemePreset.current
    val paperPalette = filePaperPalette(preset)
    val appleBody = MaterialTheme.colorScheme.onSurface
    val appleLeaf = MaterialTheme.colorScheme.onSurfaceVariant
    val appleCutout = MaterialTheme.colorScheme.surface
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (preset.artStyle == ThemeArtStyle.APPLE) {
            drawAppleFile(
                w = w,
                h = h,
                kind = kind,
                color = appleBody,
                leafColor = appleLeaf,
                cutoutColor = appleCutout,
                markColor = appleCutout
            )
        } else {
            drawPaper(
                left = w * 0.16f, top = h * 0.06f, pw = w * 0.68f, ph = h * 0.88f,
                kind = kind, showCornerFold = true,
                paperFill = paperPalette.fill, paperEdge = paperPalette.edge, foldFill = paperPalette.fold
            )
        }
    }
}

private fun DrawScope.drawAppleFile(
    w: Float,
    h: Float,
    kind: FileKind,
    color: Color,
    leafColor: Color,
    cutoutColor: Color,
    markColor: Color
) {
    val cx = w * 0.5f
    val body = Path().apply {
        moveTo(cx, h * 0.29f)
        cubicTo(w * 0.24f, h * 0.12f, w * 0.08f, h * 0.31f, w * 0.14f, h * 0.57f)
        cubicTo(w * 0.20f, h * 0.83f, w * 0.37f, h * 0.92f, cx, h * 0.80f)
        cubicTo(w * 0.63f, h * 0.92f, w * 0.80f, h * 0.83f, w * 0.86f, h * 0.57f)
        cubicTo(w * 0.92f, h * 0.31f, w * 0.76f, h * 0.12f, cx, h * 0.29f)
        close()
    }
    val leaf = Path().apply {
        moveTo(cx, h * 0.24f)
        cubicTo(cx, h * 0.08f, w * 0.67f, h * 0.03f, w * 0.72f, h * 0.16f)
        cubicTo(w * 0.68f, h * 0.25f, w * 0.56f, h * 0.29f, cx, h * 0.24f)
        close()
    }
    drawPath(body, color)
    drawPath(leaf, leafColor)
    drawLine(leafColor, Offset(cx, h * 0.31f), Offset(cx, h * 0.17f), strokeWidth = w * 0.045f)
    // عضة على اليسار، بلون السطح الحالي حتى تظل واضحة في الوضعين.
    drawCircle(cutoutColor, w * 0.088f, Offset(w * 0.105f, h * 0.435f))
    drawCircle(cutoutColor, w * 0.074f, Offset(w * 0.158f, h * 0.535f))
    drawCircle(cutoutColor, w * 0.055f, Offset(w * 0.235f, h * 0.602f))
    drawKindMark(kind, cx, h * 0.57f, w * 0.15f, markColor = markColor)
}

/**
 * ورقة واحدة + علامة النوع. showCornerFold بيضيف الركن المطوي (للملفات
 * المستقلة بس، مش للأوراق الصغيرة اللي جوه الفولدر).
 */
private fun DrawScope.drawPaper(
    left: Float, top: Float, pw: Float, ph: Float,
    kind: FileKind, showCornerFold: Boolean = false, markCenterY: Float? = null,
    markColor: Color? = null,
    paperFill: Color = PaperWhite,
    paperEdge: Color = PaperEdge,
    foldFill: Color = Color(0xFFEDEFF3)
) {
    val fold = if (showCornerFold) pw * 0.28f else 0f
    val radius = pw * 0.08f

    val body = Path().apply {
        moveTo(left + radius, top)
        lineTo(left + pw - fold, top)
        if (showCornerFold) lineTo(left + pw, top + fold) else lineTo(left + pw, top + radius)
        lineTo(left + pw, top + ph - radius)
        quadraticTo(left + pw, top + ph, left + pw - radius, top + ph)
        lineTo(left + radius, top + ph)
        quadraticTo(left, top + ph, left, top + ph - radius)
        lineTo(left, top + radius)
        quadraticTo(left, top, left + radius, top)
        close()
    }
    drawPath(body, paperFill)
    drawPath(body, paperEdge, style = Stroke(width = pw * 0.035f))

    if (showCornerFold) {
        val foldPath = Path().apply {
            moveTo(left + pw - fold, top)
            lineTo(left + pw - fold, top + fold)
            lineTo(left + pw, top + fold)
            close()
        }
        drawPath(foldPath, foldFill)
        drawPath(foldPath, paperEdge, style = Stroke(width = pw * 0.03f))
    }

    // العلامة نفسها في نص الورقة (تحت الركن المطوي لو موجود)
    val cx = left + pw / 2f
    val cy = top + ph * (markCenterY ?: (if (showCornerFold) 0.60f else 0.52f))
    val m = pw * 0.34f
    drawKindMark(kind, cx, cy, m, markColor)
}

private fun DrawScope.drawKindMark(kind: FileKind, cx: Float, cy: Float, m: Float, markColor: Color? = null) {
    val c = markColor ?: kindColor(kind)
    val sw = m * 0.16f

    when (kind) {
        FileKind.IMAGE -> {
            // جبلين + شمس
            val p = Path().apply {
                moveTo(cx - m, cy + m * 0.7f)
                lineTo(cx - m * 0.3f, cy - m * 0.1f)
                lineTo(cx + m * 0.1f, cy + m * 0.4f)
                lineTo(cx + m * 0.45f, cy + m * 0.05f)
                lineTo(cx + m, cy + m * 0.7f)
                close()
            }
            drawPath(p, c)
            drawCircle(c, radius = m * 0.2f, center = Offset(cx + m * 0.55f, cy - m * 0.5f))
        }
        FileKind.VIDEO -> {
            // مثلث تشغيل داخل مستطيل
            drawRoundRect(
                c, topLeft = Offset(cx - m, cy - m * 0.7f), size = Size(m * 2f, m * 1.4f),
                cornerRadius = CornerRadius(m * 0.25f), style = Stroke(width = sw)
            )
            val tri = Path().apply {
                moveTo(cx - m * 0.28f, cy - m * 0.38f)
                lineTo(cx + m * 0.42f, cy)
                lineTo(cx - m * 0.28f, cy + m * 0.38f)
                close()
            }
            drawPath(tri, c)
        }
        FileKind.AUDIO -> {
            // نغمة موسيقية: دايرة + عصاية
            drawCircle(c, radius = m * 0.32f, center = Offset(cx - m * 0.3f, cy + m * 0.5f))
            drawLine(c, Offset(cx + m * 0.0f, cy + m * 0.5f), Offset(cx + m * 0.0f, cy - m * 0.7f), strokeWidth = sw)
            drawLine(c, Offset(cx + m * 0.0f, cy - m * 0.7f), Offset(cx + m * 0.6f, cy - m * 0.4f), strokeWidth = sw)
        }
        FileKind.DOCUMENT -> {
            // 3 سطور نص
            for (i in 0..2) {
                val y = cy - m * 0.55f + i * m * 0.55f
                val endX = if (i == 2) cx + m * 0.2f else cx + m
                drawLine(c, Offset(cx - m, y), Offset(endX, y), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
        FileKind.SPREADSHEET -> {
            // شبكة 2x2
            drawRoundRect(
                c, topLeft = Offset(cx - m, cy - m * 0.8f), size = Size(m * 2f, m * 1.6f),
                cornerRadius = CornerRadius(m * 0.15f), style = Stroke(width = sw)
            )
            drawLine(c, Offset(cx, cy - m * 0.8f), Offset(cx, cy + m * 0.8f), strokeWidth = sw)
            drawLine(c, Offset(cx - m, cy), Offset(cx + m, cy), strokeWidth = sw)
        }
        FileKind.PRESENTATION -> {
            // شاشة عرض + قاعدة
            drawRoundRect(
                c, topLeft = Offset(cx - m, cy - m * 0.75f), size = Size(m * 2f, m * 1.25f),
                cornerRadius = CornerRadius(m * 0.15f), style = Stroke(width = sw)
            )
            drawLine(c, Offset(cx, cy + m * 0.5f), Offset(cx, cy + m * 0.9f), strokeWidth = sw)
            drawLine(c, Offset(cx - m * 0.5f, cy + m * 0.9f), Offset(cx + m * 0.5f, cy + m * 0.9f), strokeWidth = sw, cap = StrokeCap.Round)
        }
        FileKind.ARCHIVE -> {
            // سوستة مضغوطة: أسنان متبادلة + مقبض صغير، كلها في حدود العلامة
            // عشان ميتقطعش تحت واجهة الفولدر الأمامية لما الورقة تكون جواه.
            for (i in 0..2) {
                val y = cy - m * 0.75f + i * m * 0.36f
                val left = i % 2 == 0
                drawRect(
                    c,
                    topLeft = Offset(if (left) cx - m * 0.36f else cx + m * 0.0f, y),
                    size = Size(m * 0.36f, m * 0.24f)
                )
            }
            drawRoundRect(
                c, topLeft = Offset(cx - m * 0.26f, cy + m * 0.4f), size = Size(m * 0.52f, m * 0.42f),
                cornerRadius = CornerRadius(m * 0.12f), style = Stroke(width = sw * 0.8f)
            )
        }
        FileKind.CODE -> {
            // < >
            val l = Path().apply {
                moveTo(cx - m * 0.35f, cy - m * 0.55f)
                lineTo(cx - m * 0.95f, cy)
                lineTo(cx - m * 0.35f, cy + m * 0.55f)
            }
            val r = Path().apply {
                moveTo(cx + m * 0.35f, cy - m * 0.55f)
                lineTo(cx + m * 0.95f, cy)
                lineTo(cx + m * 0.35f, cy + m * 0.55f)
            }
            drawPath(l, c, style = Stroke(width = sw, cap = StrokeCap.Round))
            drawPath(r, c, style = Stroke(width = sw, cap = StrokeCap.Round))
        }
        FileKind.PDF -> {
            // مستطيل مليان بحرف فاتح تجريدي (شريط) = علامة PDF
            drawRoundRect(
                c, topLeft = Offset(cx - m, cy - m * 0.55f), size = Size(m * 2f, m * 1.1f),
                cornerRadius = CornerRadius(m * 0.2f)
            )
            drawLine(Color.White, Offset(cx - m * 0.55f, cy), Offset(cx + m * 0.55f, cy), strokeWidth = sw, cap = StrokeCap.Round)
        }
        FileKind.OTHER -> {
            // دايرة فاضية = نوع غير معروف
            drawCircle(c, radius = m * 0.6f, center = Offset(cx, cy), style = Stroke(width = sw))
        }
    }
}
