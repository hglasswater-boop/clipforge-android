plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionProperties = java.util.Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val clipForgeVersionName = versionProperties.getProperty("versionName") ?: "0.1.0"
val clipForgeBuildNumber = providers.gradleProperty("buildNumber").orNull?.toIntOrNull() ?: 1

val stableKeystorePath = System.getenv("CLIPFORGE_KEYSTORE")
val stableKeystorePassword = System.getenv("CLIPFORGE_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("CLIPFORGE_KEY_ALIAS")
val stableKeyPassword = System.getenv("CLIPFORGE_KEY_PASSWORD")
val stableSigningAvailable = listOf(
    stableKeystorePath,
    stableKeystorePassword,
    stableKeyAlias,
    stableKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "app.clipforge"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.clipforge"
        minSdk = 26
        targetSdk = 36
        versionCode = clipForgeBuildNumber
        versionName = clipForgeVersionName
    }

    signingConfigs {
        if (stableSigningAvailable) {
            create("stable") {
                storeFile = file(stableKeystorePath!!)
                storePassword = stableKeystorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        if (stableSigningAvailable) {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("stable")
            }
            getByName("release") {
                signingConfig = signingConfigs.getByName("stable")
            }
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

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    // Compose 1.12 requires compileSdk 37. Keep the newest stable API-36 line
    // until Android 17 / API 37 is a production SDK target for this app.
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.7")

    testImplementation("junit:junit:4.13.2")
}
