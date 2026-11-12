import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Upload key for Google Play. The file lives outside the repository; when it is missing the release build stays unsigned.
val uploadKeyFile = File(System.getenv("USERPROFILE") ?: System.getProperty("user.home"), ".turkcetv/android-yukleme.properties")
val uploadKey = Properties().apply { if (uploadKeyFile.isFile) uploadKeyFile.inputStream().use { load(it) } }

android {
    namespace = "dev.turkce.olcum"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.turkcetv.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    signingConfigs {
        if (uploadKey.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures { buildConfig = true }
    // Native libraries stay uncompressed and page aligned in the package (16 KB page devices).
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is only a stub in unit tests
    implementation("com.google.zxing:core:3.5.3") // QR code for the phone settings page
}
