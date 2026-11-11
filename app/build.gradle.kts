plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.andrew.peremena"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.andrew.peremena"
        minSdk = 26          // Android 8.0
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Один постоянный ключ подписи для всех сборок (GitHub Actions, любой ПК),
    // чтобы новая версия ставилась поверх старой без удаления.
    // Ключ лежит в репозитории — годится только для личного использования,
    // не для публикации в магазинах.
    signingConfigs {
        create("personal") {
            storeFile = file("peremena.keystore")
            storePassword = "peremena"
            keyAlias = "peremena"
            keyPassword = "peremena"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
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
    lint {
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
