package app.rasul.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons for the file card, drawn like the rest of the app's icons: black stroke, always tinted at the call site. */
internal object FileIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private const val PAGE = "M13 2H6C5.47 2 4.96 2.21 4.59 2.59C4.21 2.96 4 3.47 4 4V20C4 20.53 4.21 21.04 4.59 21.41C4.96 21.79 5.47 22 6 22H18C18.53 22 19.04 21.79 19.41 21.41C19.79 21.04 20 20.53 20 20V9L13 2Z"
    private const val FOLD = "M13 2V9H20"

    private val other = icon("file-other", PAGE, FOLD)
    private val pdf = icon("file-pdf", PAGE, FOLD, "M8 17H16")
    private val document = icon("file-document", PAGE, FOLD, "M8 13H16M8 17H16")
    private val text = icon("file-text", PAGE, FOLD, "M8 13H16M8 17H13")
    private val sheet = icon("file-sheet", "M5 3H19V21H5Z", "M5 9H19M5 15H19M12 9V21")
    private val archive = icon("file-archive", "M21 8V21H3V8", "M1 3H23V8H1Z", "M10 12H14")
    private val image = icon("file-image", "M19 3H5C3.9 3 3 3.9 3 5V19C3 20.1 3.9 21 5 21H19C20.1 21 21 20.1 21 19V5C21 3.9 20.1 3 19 3Z", "M8.5 10C9.33 10 10 9.33 10 8.5C10 7.67 9.33 7 8.5 7C7.67 7 7 7.67 7 8.5C7 9.33 7.67 10 8.5 10Z", "M21 15L16 10L5 21")
    private val audio = icon("file-audio", "M9 18V5L21 3V16", "M6 21C7.66 21 9 19.66 9 18C9 16.34 7.66 15 6 15C4.34 15 3 16.34 3 18C3 19.66 4.34 21 6 21Z", "M18 19C19.66 19 21 17.66 21 16C21 14.34 19.66 13 18 13C16.34 13 15 14.34 15 16C15 17.66 16.34 19 18 19Z")
    private val video = icon("file-video", "M23 7L16 12L23 17V7Z", "M14 5H3C1.9 5 1 5.9 1 7V17C1 18.1 1.9 19 3 19H14C15.1 19 16 18.1 16 17V7C16 5.9 15.1 5 14 5Z")

    val more = icon("more", "M12 5H12.01", "M12 12H12.01", "M12 19H12.01")

    fun forKind(kind: FileKind): ImageVector = when (kind) {
        FileKind.Pdf -> pdf
        FileKind.Document -> document
        FileKind.Sheet -> sheet
        FileKind.Archive -> archive
        FileKind.Text -> text
        FileKind.Image -> image
        FileKind.Audio -> audio
        FileKind.Video -> video
        FileKind.Other -> other
    }
}
