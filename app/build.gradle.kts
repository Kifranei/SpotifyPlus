import java.util.Properties

plugins {

    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing secrets live in local.properties (gitignored) — never commit passwords/keystores.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.kifranei.spotifyplus"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kifranei.spotifyplus"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("debugKey") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val storePath = localProps.getProperty("RELEASE_STORE_FILE")
        if (!storePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debugKey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.version")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Modern LSPosed / libxposed API 102 (resolved by LSPosed at runtime)
    compileOnly("io.github.libxposed:api:102.0.0")
    // Packaged: Settings dual-writes remote prefs via XposedServiceHelper + XposedProvider
    implementation("io.github.libxposed:service:102.0.0")

    // DexKit: resolve R8-obfuscated host classes by string anchors
    implementation("org.luckypray:dexkit:2.2.0")

    // Lyricon (词幕) provider protocol — same artifacts LyricProvider uses
    implementation("io.github.proify.lyricon:provider:0.1.70")
    implementation("io.github.proify.lyricon.lyric:model:0.1.70")

    // Miuix (HyperOS / MIUI-style Compose UI) — settings Activity
    val miuix = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:$miuix")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
