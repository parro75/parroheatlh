package fr.parro.santesync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.parro.santesync.R

// ---- Thème « nuit » (26/11/2026) -------------------------------------------------------------------
// Fonds neutres qui suivent le thème du téléphone ; une couleur FIXE par domaine pour s'y retrouver d'un
// coup d'œil (pas de Material You : il changerait ces repères avec le fond d'écran).

@Immutable
data class ParroColors(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val inset: Color, val line: Color,
    val text: Color, val muted: Color, val faint: Color,
    val sleep: Color, val sleepSoft: Color, val deep: Color, val rem: Color, val light: Color, val awake: Color,
    val heart: Color, val heartSoft: Color, val hrv: Color, val hrvSoft: Color,
    val activity: Color, val activitySoft: Color, val body: Color, val bodySoft: Color,
    val stress: Color, val score: Color, val good: Color, val warn: Color, val onAccent: Color,
    val dark: Boolean,
)

val NightColors = ParroColors(
    bg = Color(0xFF0D1015), surface = Color(0xFF161A21), surfaceHigh = Color(0xFF1F2430), inset = Color(0xFF11151B),
    line = Color(0xFF2A303C), text = Color(0xFFECEEF2), muted = Color(0xFFA0A8B6), faint = Color(0xFF7D8594),
    sleep = Color(0xFF9AA5FF), sleepSoft = Color(0xFF252B47), deep = Color(0xFF6B7BFF), rem = Color(0xFFC59BFF),
    light = Color(0xFF9AA5FF), awake = Color(0xFFF5BE6B),
    heart = Color(0xFFFF8170), heartSoft = Color(0xFF3A2226), hrv = Color(0xFF5FD8CB), hrvSoft = Color(0xFF1B3533),
    activity = Color(0xFF8FE388), activitySoft = Color(0xFF1E3322), body = Color(0xFFF5BE6B), bodySoft = Color(0xFF3A2F1C),
    stress = Color(0xFFFFB36B), score = Color(0xFF7FC6FF), good = Color(0xFF8FE388), warn = Color(0xFFF5BE6B),
    onAccent = Color(0xFF0D1015), dark = true,
)

val DayColors = ParroColors(
    bg = Color(0xFFF4F5F8), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFEEF0F4), inset = Color(0xFFF0F2F6),
    line = Color(0xFFE1E4EA), text = Color(0xFF14171D), muted = Color(0xFF5A6270), faint = Color(0xFF6E7684),
    sleep = Color(0xFF4F5BD5), sleepSoft = Color(0xFFE5E8FF), deep = Color(0xFF3443B8), rem = Color(0xFF8A55D6),
    light = Color(0xFF7C88F0), awake = Color(0xFFC98A2E),
    heart = Color(0xFFD9473A), heartSoft = Color(0xFFFDE6E2), hrv = Color(0xFF0F8F83), hrvSoft = Color(0xFFDDF4F1),
    activity = Color(0xFF2E8B3E), activitySoft = Color(0xFFE1F4E3), body = Color(0xFFB7791F), bodySoft = Color(0xFFF8EDD9),
    stress = Color(0xFFC46A12), score = Color(0xFF1F78C8), good = Color(0xFF2E8B3E), warn = Color(0xFFB7791F),
    onAccent = Color(0xFFFFFFFF), dark = false,
)

val LocalParro = staticCompositionLocalOf { NightColors }

/** Accès court aux couleurs : `P.c.sleep`. */
object P {
    val c: ParroColors @Composable get() = LocalParro.current
}

/** Chiffres en Space Grotesk (police libre SIL OFL, embarquée : aucune requête à Google Fonts). */
val NumFont = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@Composable
fun ParroTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) NightColors else DayColors
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.sleep, onPrimary = c.onAccent, primaryContainer = c.sleepSoft, onPrimaryContainer = c.text,
        secondary = c.hrv, onSecondary = c.onAccent, secondaryContainer = c.sleepSoft, onSecondaryContainer = c.text,
        background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.muted, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.surfaceHigh, surfaceContainerLow = c.surface,
        outline = c.line, outlineVariant = c.line, error = c.heart,
    )
    CompositionLocalProvider(LocalParro provides c) { MaterialTheme(colorScheme = scheme, content = content) }
}

// ---- icônes au trait (mêmes tracés que la maquette) ------------------------------------------------

private fun strokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

object ParroIcons {
    val today = strokeIcon("today", "M8,12a4,4 0 1,0 8,0a4,4 0 1,0 -8,0",
        "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val trends = strokeIcon("trends", "M3 3v18h18", "M7 15l4-4 3 3 6-7")
    val journal = strokeIcon("journal", "M4 4h12a4 4 0 0 1 4 4v12H8a4 4 0 0 1-4-4z", "M9 9h6M9 13h4")
    val profile = strokeIcon("profile", "M8,8a4,4 0 1,0 8,0a4,4 0 1,0 -8,0", "M4 21a8 8 0 0 1 16 0")
    val moon = strokeIcon("moon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val heart = strokeIcon("heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8z")
    val pulse = strokeIcon("pulse", "M3 12h4l3-8 4 16 3-8h4")
    val scale = strokeIcon("scale", "M7 4h10a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4z",
        "M8 10a4 4 0 0 1 8 0", "M12 10l1.5-2")
    val bolt = strokeIcon("bolt", "M13 2L4 14h7l-1 8 9-12h-7z")
    val gauge = strokeIcon("gauge", "M4 16a8 8 0 0 1 16 0", "M12 16l4-5")
    val back = strokeIcon("back", "M15 18l-6-6 6-6")
    val chevron = strokeIcon("chevron", "M9 6l6 6-6 6")
    val doc = strokeIcon("doc", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M9 13h6M9 17h4")
    val sync = strokeIcon("sync", "M21 12a9 9 0 0 1-15.5 6.2", "M3 12A9 9 0 0 1 18.5 5.8", "M18 2v4h-4", "M6 22v-4h4")
    val trash = strokeIcon("trash", "M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val plus = strokeIcon("plus", "M12 5v14M5 12h14")
    val minus = strokeIcon("minus", "M5 12h14")
}
