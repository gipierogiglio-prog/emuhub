plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.emuhub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.emuhub.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 52
        versionName = "1.2"
    }

    // Gera APKs separados por arquitetura em vez de um APK gigante com ambas
    splits {
        abi {
            isUniversalApk = false
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                System.getenv("EMUHUB_KEYSTORE_PATH") ?: "../keystore.jks"
            )
            storePassword = System.getenv("EMUHUB_STORE_PASSWORD") ?: "Gpbox2026!"
            keyAlias = System.getenv("EMUHUB_KEY_ALIAS") ?: "gpbox"
            keyPassword = System.getenv("EMUHUB_KEY_PASSWORD") ?: "Gpbox2026!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
}
