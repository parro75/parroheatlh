package fr.parro.santesync.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Couleurs fixes des séries, lisibles en clair comme en sombre. */
object SeriesColors {
    val deep = Color(0xFF3F51B5)
    val rem = Color(0xFF26A69A)
    val light = Color(0xFF90CAF9)
    val awake = Color(0xFFFFB74D)
    val steps = Color(0xFF43A047)
    val heart = Color(0xFFE53935)
    val heartSoft = Color(0xFFEF9A9A)
    val hrv = Color(0xFF8E24AA)
    val stress = Color(0xFFFB8C00)
    val score = Color(0xFF1E88E5)
}

@Composable
fun ParroTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD1AE))
        else -> lightColorScheme(primary = Color(0xFF1E6B52))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
