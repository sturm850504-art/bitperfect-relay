import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: the keystore and its passwords live outside the repository
// (default ~/.android/bitperfect-relay/keystore.properties, override with BITPERFECT_SIGNING).
// Without that file the release build is left unsigned.
val signingProps = Properties().apply {
    val path = System.getenv("BITPERFECT_SIGNING")
        ?: "${System.getProperty("user.home")}/.android/bitperfect-relay/keystore.properties"
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bitperfect.relay"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.bitperfect.relay"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // arm64-v8a: real-device target (SPEC). x86_64: Android VM test
            // (RULES.md: features not needing a real device are validated on the
            // emulator first; the test AVD is x86_64). [AI judgment] 2026-10-10
            abiFilters += setOf("arm64-v8a", "x86_64")
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Whole-app R8 with the default Android keep rules (on by default since AGP 9.3; they keep
            // native method names, and the app uses no reflection or JNI callbacks).
            // Not packageScope: shrinking only androidx/kotlin made Start crash in the first signed
            // release (IllegalAccessError: an app class extended a repackaged kotlin.text class,
            // device 2026-10-24).
            optimization {
                enable = true
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // [AI judgment] Extract .so files at install: dllib dlopens libusb/libsoxr by path under
    // nativeLibraryDir (also the restore target of the library swap). With the default
    // in-APK packaging that directory is empty and both loads fail.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
