import java.util.Base64

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.ieum.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.ieum.app"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // --dart-define 값은 Flutter가 "dart-defines"(Base64, 쉼표 구분)로 넘겨준다. 키 값은 소스에 두지 않는다.
        val dartDefines = (project.findProperty("dart-defines") as String? ?: "")
            .split(",")
            .filter { it.isNotEmpty() }
            .associate {
                val pair = String(Base64.getDecoder().decode(it), Charsets.UTF_8)
                pair.substringBefore("=") to pair.substringAfter("=", "")
            }
        manifestPlaceholders["kakaoScheme"] = "kakao" + (dartDefines["KAKAO_NATIVE_APP_KEY"] ?: "")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}
