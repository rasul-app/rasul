package app.rasul.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons embedded as ImageVectors with no icon pack dependency. Stroke colour is black: always draw with
 * Icon(tint = ...). Strings starting with "F" are filled + stroked (the dots).
 */
private fun icon(name: String, size: Float, stroke: Float, mirror: Boolean = false, vararg paths: String): ImageVector =
    ImageVector.Builder(name, size.dp, size.dp, size, size, autoMirror = mirror).apply {
        paths.forEach { raw ->
            val filled = raw.startsWith("F")
            val d = if (filled) raw.substring(1) else raw
            addPath(
                PathParser().parsePathString(d).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object RasulIcons {
    val Bot = icon(
        "bot", 24f, 2f, false,
        "M16 8H8C5.79 8 4 9.79 4 12V16C4 18.21 5.79 20 8 20H16C18.21 20 20 18.21 20 16V12C20 9.79 18.21 8 16 8Z",
        "M12 8V5",
        "M12 5C12.55 5 13 4.55 13 4C13 3.45 12.55 3 12 3C11.45 3 11 3.45 11 4C11 4.55 11.45 5 12 5Z",
        "FM9 15C9.55 15 10 14.55 10 14C10 13.45 9.55 13 9 13C8.45 13 8 13.45 8 14C8 14.55 8.45 15 9 15Z",
        "FM15 15C15.55 15 16 14.55 16 14C16 13.45 15.55 13 15 13C14.45 13 14 13.45 14 14C14 14.55 14.45 15 15 15Z",
        "M2 13V16M22 13V16",
    )
    val Chat = icon(
        "chat", 24f, 2f, false,
        "M21 12C20.99 13.36 20.64 14.7 19.97 15.89C19.3 17.07 18.34 18.07 17.18 18.78C16.01 19.49 14.69 19.89 13.33 19.95C11.97 20.01 10.62 19.71 9.4 19.1L4 20L5 15.4C4.55 13.28 4.96 11.06 6.14 9.25C7.32 7.43 9.18 6.15 11.3 5.7C13.42 5.25 15.64 5.66 17.45 6.84C19.27 8.02 20.55 9.88 21 12Z",
    )
    val Calendar = icon(
        "calendar", 24f, 2f, false,
        "M19 5H5C3.9 5 3 5.9 3 7V19C3 20.1 3.9 21 5 21H19C20.1 21 21 20.1 21 19V7C21 5.9 20.1 5 19 5Z",
        "M8 3V7M16 3V7M3 10H21",
    )
    val Dots = icon(
        "dots", 24f, 2f, false,
        "FM5 13.6C5.88 13.6 6.6 12.88 6.6 12C6.6 11.12 5.88 10.4 5 10.4C4.12 10.4 3.4 11.12 3.4 12C3.4 12.88 4.12 13.6 5 13.6Z",
        "FM12 13.6C12.88 13.6 13.6 12.88 13.6 12C13.6 11.12 12.88 10.4 12 10.4C11.12 10.4 10.4 11.12 10.4 12C10.4 12.88 11.12 13.6 12 13.6Z",
        "FM19 13.6C19.88 13.6 20.6 12.88 20.6 12C20.6 11.12 19.88 10.4 19 10.4C18.12 10.4 17.4 11.12 17.4 12C17.4 12.88 18.12 13.6 19 13.6Z",
    )
    val Search = icon(
        "search", 22f, 1.83f, false,
        "M10.08 16.5C13.63 16.5 16.5 13.63 16.5 10.08C16.5 6.54 13.63 3.67 10.08 3.67C6.54 3.67 3.67 6.54 3.67 10.08C3.67 13.63 6.54 16.5 10.08 16.5Z",
        "M18.33 18.33L15.13 15.13",
    )
    val Compose = icon(
        "compose", 22f, 1.83f, false,
        "M11 3.67H5.5C5.01 3.67 4.55 3.86 4.2 4.2C3.86 4.55 3.67 5.01 3.67 5.5V16.5C3.67 16.99 3.86 17.45 4.2 17.8C4.55 18.14 5.01 18.33 5.5 18.33H16.5C16.99 18.33 17.45 18.14 17.8 17.8C18.14 17.45 18.33 16.99 18.33 16.5V11",
        "M16.96 3.21C17.32 2.84 17.82 2.64 18.33 2.64C18.85 2.64 19.34 2.84 19.71 3.21C20.07 3.57 20.28 4.07 20.28 4.58C20.28 5.1 20.07 5.59 19.71 5.96L11 14.67L7.33 15.58L8.25 11.92L16.96 3.21Z",
    )
    val Pc = icon(
        "pc", 16f, 1.33f, false,
        "M12.67 2.67H3.33C2.6 2.67 2 3.26 2 4V9.33C2 10.07 2.6 10.67 3.33 10.67H12.67C13.4 10.67 14 10.07 14 9.33V4C14 3.26 13.4 2.67 12.67 2.67Z",
        "M5.33 13.33H10.67M8 10.67V13.33",
    )
    val ChevDown = icon("chevd", 14f, 1.46f, false, "M3.5 5.25L7 8.75L10.5 5.25")
    val Chev = icon("chev", 18f, 1.5f, true, "M6.75 13.5L11.25 9L6.75 4.5")
    val Clock = icon(
        "clock", 20f, 1.67f, false,
        "M10 17.5C14.14 17.5 17.5 14.14 17.5 10C17.5 5.86 14.14 2.5 10 2.5C5.86 2.5 2.5 5.86 2.5 10C2.5 14.14 5.86 17.5 10 17.5Z",
        "M10 5.83V10L12.5 11.67",
    )
    val Pause = icon("pause", 20f, 1.67f, false, "M6.67 4.17V15.83M13.33 4.17V15.83")
    val Check = icon("check", 20f, 1.67f, false, "M4.17 10L8.33 14.17L16.67 5.83")
    val X = icon("x", 20f, 1.67f, false, "M5 5L15 15M15 5L5 15")
    val Plus = icon("plus", 22f, 1.83f, false, "M11 4.58V17.42M4.58 11H17.42")
    val Lock = icon(
        "lock", 24f, 2f, false,
        "M17 11H7C5.9 11 5 11.9 5 13V19C5 20.1 5.9 21 7 21H17C18.1 21 19 20.1 19 19V13C19 11.9 18.1 11 17 11Z",
        "M8 11V7C8 5.94 8.42 4.92 9.17 4.17C9.92 3.42 10.94 3 12 3C13.06 3 14.08 3.42 14.83 4.17C15.58 4.92 16 5.94 16 7V11",
    )
    val Globe = icon(
        "globe", 24f, 2f, false,
        "M12 21C16.97 21 21 16.97 21 12C21 7.03 16.97 3 12 3C7.03 3 3 7.03 3 12C3 16.97 7.03 21 12 21Z",
        "M3 12H21M12 3C14.12 5.52 15.28 8.71 15.28 12C15.28 15.29 14.12 18.48 12 21C9.88 18.48 8.72 15.29 8.72 12C8.72 8.71 9.88 5.52 12 3Z",
    )
    val Info = icon(
        "info", 24f, 2f, false,
        "M12 21C16.97 21 21 16.97 21 12C21 7.03 16.97 3 12 3C7.03 3 3 7.03 3 12C3 16.97 7.03 21 12 21Z",
        "M12 11V17M12 7.5V7.6",
    )
    val Key = icon(
        "key", 20f, 1.67f, false,
        "M6.67 15.83C8.51 15.83 10 14.34 10 12.5C10 10.66 8.51 9.17 6.67 9.17C4.83 9.17 3.33 10.66 3.33 12.5C3.33 14.34 4.83 15.83 6.67 15.83Z",
        "M9.17 10L16.67 2.5M13.33 5.83L15.83 8.33",
    )
}
