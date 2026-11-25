plugins {
    id("com.android.application") version "9.5.2" apply false
    // Doit suivre la version de Kotlin intégrée à AGP (2.2.10 pour AGP 9.5).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
