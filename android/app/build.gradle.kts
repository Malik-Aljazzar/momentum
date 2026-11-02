import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")      // reads google-services.json (Firebase project momentum-df519)
}

// Sprites come straight from the pixel-art generator output (single source of truth).
val spriteSource = rootProject.file("../pixelart/out")
val generatedAssets = layout.buildDirectory.dir("generated/spriteAssets")
val syncSprites by tasks.registering(Sync::class) {
    from(spriteSource) {
        include("*/sheet.png", "icons/*.png", "banners/*.png", "app_icon_192.png", "emoji/emoji.json")
        exclude("emoji/sheet.png")        // the server's (overlays); the app has the font
    }
    into(generatedAssets.map { it.dir("sprites") })
}
// What's new after an update: the project's CHANGELOG.md, read by data/Changelog.kt.
val changelogAssets = layout.buildDirectory.dir("generated/changelogAssets")
val syncChangelog by tasks.registering(Copy::class) {
    from(rootProject.file("../CHANGELOG.md"))
    into(changelogAssets)
}

/** LAN address of the build machine: the debug/prototype backend runs there (python -m server). */
fun lanIp(): String = try {
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName("10.255.255.255"), 1)
        socket.localAddress.hostAddress
    }
} catch (e: Exception) {
    "10.0.2.2"   // emulator alias for the host
}

// Play Store build: `gradlew :app:bundleRelease -Pplay` (an AAB signed with the upload key from keystore.properties;
// Play re-signs it). Without -Pplay: the APK offered at /app.apk, signed with the debug key as before.
val play = project.hasProperty("play")
val keystoreProps = rootProject.file("keystore.properties")     // never in the repo: storeFile, storePassword, keyAlias, keyPassword

android {
    namespace = "com.momentum.app"
    compileSdk = 36

    signingConfigs {
        if (keystoreProps.exists()) create("upload") {
            val p = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.momentum.app"
        minSdk = 26
        targetSdk = 36
        // every build is a new version: Android offers "update" and the settings show which build runs
        val now = LocalDateTime.now()
        versionCode = (System.currentTimeMillis() / 60_000 - 29_000_000).toInt()
        versionName = "0.1." + now.format(DateTimeFormatter.ofPattern("yyMMdd.HHmm"))
        // Backend: -PmomentumServer=https://... (deployed server), otherwise the PC that built the app.
        // Developer mode can override it in the app.
        val server = (project.findProperty("momentumServer") as String?) ?: "http://${lanIp()}:8000"
        buildConfigField("String", "DEFAULT_SERVER", "\"$server\"")
        // Play builds: updates come from Play (the server's APK version gate skips them, X-App-Channel: play)
        buildConfigField("boolean", "PLAY", "$play")
    }

    buildTypes {
        release {
            // R8 + resource shrinking: Compose runs much faster than in debug builds
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // the APK on the website keeps the debug key (installed apps update over it); Play gets the upload key
            signingConfig = if (play) signingConfigs.findByName("upload")
                ?: throw GradleException("-Pplay needs android/keystore.properties (the upload key)")
            else signingConfigs.getByName("debug")
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
    sourceSets["main"].assets.srcDir(generatedAssets)
    sourceSets["main"].assets.srcDir(changelogAssets)
}

tasks.named("preBuild") { dependsOn(syncSprites, syncChangelog) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.emoji2:emoji2:1.3.0")                // only started on Android 8/9 (MomentumApplication)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")       // background check for notifications
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")      // push: the server wakes the app up
    implementation("androidx.media3:media3-transformer:1.5.1")    // videos compressed on the phone before uploading
    implementation("androidx.media3:media3-effect:1.5.1")
    val camerax = "1.4.1"                                          // the app's own camera
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.credentials:credentials:1.3.0")                    // sign in with Google
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")       // JVM tests (the chat encryption against the shared vector)
}
