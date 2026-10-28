plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.youraicqa.app"; compileSdk = 35
    fun cfg(name: String): String? = (System.getenv(name) ?: providers.gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }
    buildFeatures { buildConfig = true }
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.yourbitai.app"; minSdk = 26; targetSdk = 35; versionCode = 5; versionName = "3.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    val ksPath = cfg("KEYSTORE_PATH")
    val ksPass = cfg("KEYSTORE_PASSWORD")
    val ksAlias = cfg("KEY_ALIAS")
    val ksKeyPass = cfg("KEY_PASSWORD")
    val hasSigning = ksPath != null && ksPass != null && ksAlias != null && ksKeyPass != null && file(ksPath).exists()
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }
    buildTypes {
        getByName("debug") { if (hasSigning) signingConfig = signingConfigs.getByName("release") }
        getByName("release") { if (hasSigning) signingConfig = signingConfigs.getByName("release") }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
