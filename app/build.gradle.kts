import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.pumsok.story"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pumsok.story"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.6.0"
        // ONNX Runtime ships ~35MB per ABI; real phones only need ARM.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { compose = true }
    val keystoreFile = rootProject.file("keystore.properties")
    val uploadSigning = if (keystoreFile.exists()) {
        val p = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("upload") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = uploadSigning ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    testImplementation("junit:junit:4.13.2")
}
