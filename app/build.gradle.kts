import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

// The voice codec is native C, so it needs the NDK. A machine without the
// NDK — a phone, for example — builds the rest with -PskipNative and the
// voice quietly falls back to plain samples.
val skipNative = project.hasProperty("skipNative")

android {
    namespace = "app.wifigram"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.wifigram"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 8
        versionName = "0.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (!skipNative) {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_STL=none")
                }
            }
        }
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    // The release is split by the architecture, so the APK is small
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    val hasReleaseSigning = localProps.containsKey("KEYSTORE_PATH") &&
        localProps.containsKey("KEYSTORE_PASSWORD") &&
        localProps.containsKey("KEY_ALIAS") &&
        localProps.containsKey("KEY_PASSWORD")

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(localProps["KEYSTORE_PATH"] as String)
                storePassword = localProps["KEYSTORE_PASSWORD"] as String
                keyAlias = localProps["KEY_ALIAS"] as String
                keyPassword = localProps["KEY_PASSWORD"] as String
                storeType = localProps["KEYSTORE_TYPE"] as? String ?: "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            // The program is small: the unused code and the unused icons are
            // thrown away, so the phone gets a thin package.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
