plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kidstvguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kidstvguard"
        minSdk = 24
        targetSdk = 34
        // 版本号可由 CI 注入：./gradlew assembleRelease -Pvc=<versionCode> -Pvn=<versionName>
        // 本地不传则用默认值。应用内自动更新靠 versionCode 比对，CI 每次构建用 GitHub run number 递增。
        versionCode = (project.findProperty("vc") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("vn") as? String) ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            // 固定签名密钥（仓库内 signing/release.p12）。每次构建都用同一把钥匙签名，
            // 这样应用内自动更新才能覆盖安装；换钥匙会导致老设备"签名不一致"装不上。
            storeFile = file("../signing/release.p12")
            storePassword = "kidstvguard"
            keyAlias = "upload"
            keyPassword = "kidstvguard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }

    applicationVariants.all {
        val variantName = name
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "公主答题可看-${variantName}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
