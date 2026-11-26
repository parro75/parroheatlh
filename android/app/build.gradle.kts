plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signature de version STABLE (sinon chaque APK de la CI porte une clé différente et la mise à jour
// échoue). Les valeurs viennent de l'environnement : secrets GitHub en CI, jamais du dépôt.
val signingStore = System.getenv("PARRO_SIGNING_STORE_FILE")

android {
    namespace = "fr.parro.santesync"
    // Compose 1.13 exige de compiler contre les API 37.1 ; la cible (targetSdk) reste 36.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        // Inchangé depuis « Santé Sync » : le changer ferait une nouvelle appli pour Android
        // (permissions Health Connect et réglages perdus).
        applicationId = "fr.parro.santesync"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.14.0"
    }

    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = System.getenv("PARRO_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("PARRO_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("PARRO_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 : APK ~3× plus léger. Health Connect, Glance, WorkManager fournissent leurs propres règles ;
            // proguard-rules.pro garde en plus tout le code de l'appli (petit) par prudence.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
