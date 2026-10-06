plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "krd.bgremover"
    compileSdk = 35

    defaultConfig {
        applicationId = "krd.bgremover"
        minSdk = 29
        targetSdk = 35
        versionCode = 25
        versionName = "6.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            if (System.getenv("DIAG_X86") == "1") abiFilters += "x86_64"   // تەنها بۆ تاقیکردنەوە لە emulator
        }
        // کلیلی Serper ی هاوبەش بۆ هەموو مۆبایلەکان (لە GitHub Secret ـەوە، نەک لە کۆدەکەدا)
        buildConfigField("String", "DEFAULT_SERPER_KEY", "\"${System.getenv("SERPER_KEY") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // واژووی debug بۆ ئەوەی ڕاستەوخۆ دابمەزرێت
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // کۆمپرێسکردنی کتێبخانە native ەکان بۆ APK ی بچووکتر
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // IS-Net لەسەر مۆبایل
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // لابردنی باکگراوند لەسەر مۆبایل (بێ ئینتەرنێت)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // دۆزینەوەی مرۆڤ بۆ «تەنها مرۆڤ» (مۆدێلی بچووک لەناو ئەپدایە)
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
