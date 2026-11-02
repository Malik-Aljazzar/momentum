import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// The admin app (own APK, not offered to players): bug reports / feature requests with their
// status, server statistics and storage. Talks to /api/admin/* (accounts with User.admin).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.momentum.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.momentum.admin"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.currentTimeMillis() / 60_000 - 29_000_000).toInt()
        versionName = "0.1." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd.HHmm"))
        // the server can also be changed on the login screen
        val server = (project.findProperty("momentumServer") as String?) ?: "https://momentum.mal-zeta.de"
        buildConfigField("String", "DEFAULT_SERVER", "\"$server\"")
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            manifestPlaceholders["cleartext"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}
