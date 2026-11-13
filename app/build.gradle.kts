import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ключ подписи релиза — вне репозитория: keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "app.loopa"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.loopa"
        minSdk = 26
        targetSdk = 35
        // -PvCode=1 -PvName=1.0-test — сборка «старой» версии для проверки обновления на эмуляторе
        versionCode = (project.findProperty("vCode") as String?)?.toInt() ?: 40
        versionName = (project.findProperty("vName") as String?) ?: "1.12.2"
    }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // без keystore.properties релиз подписывается отладочным ключом — для сборки у себя
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // видео: mp4 с серверов RedGIFs
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")   // видео v.redd.it
    implementation("androidx.media3:media3-database:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")   // один OkHttp: пул соединений, HTTP/2, кэш DNS
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.12.1")   // окно входа без заголовка X-Requested-With
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
