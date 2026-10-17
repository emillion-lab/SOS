plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Постоянен ключ за подписване: подава се от workflow-а (никога не е в репото).
// Без него билдът е с временен debug ключ — става за проба, но ANDROID_ID и
// обновяването отгоре зависят от един и същ ключ.
val ksFile: String? = System.getenv("SOS_KEYSTORE_FILE")
val ksPass: String? = System.getenv("SOS_KEYSTORE_PASS")

android {
    namespace = "bg.emillion.sos"
    compileSdk = 34

    defaultConfig {
        applicationId = "bg.emillion.sos"
        minSdk = 26
        targetSdk = 34
        // ГГММДДЧЧ — расте винаги, независимо в кое репо е билдът
        val ver = System.getenv("SOS_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1"
        versionCode = ver.toInt()
        versionName = "0.3.$ver"
        buildConfigField("String", "SERVER", "\"https://sos.mihov-emil.workers.dev\"")
    }
    signingConfigs {
        if (ksFile != null && ksPass != null) create("fixed") {
            storeFile = file(ksFile)
            storePassword = ksPass
            keyAlias = "sos"
            keyPassword = ksPass
            storeType = "PKCS12"
        }
    }
    buildTypes {
        getByName("debug") {
            if (ksFile != null && ksPass != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
