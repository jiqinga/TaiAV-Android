plugins {
    id("com.android.application")
}

android {
    namespace = "com.taiav.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taiav.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures { buildConfig = true }
    val releaseStore = providers.environmentVariable("TAIAV_SIGNING_STORE_FILE").orNull
    signingConfigs {
        if (!releaseStore.isNullOrBlank()) {
            create("distribution") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("TAIAV_SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TAIAV_SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TAIAV_SIGNING_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":network"))
    implementation("androidx.activity:activity:1.12.3")
    implementation("androidx.webkit:webkit:1.15.0")
    val media3 = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
}
