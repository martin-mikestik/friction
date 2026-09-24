plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets GITHUB_RUN_NUMBER, so every GitHub build gets a higher versionCode
// (Android refuses to install a lower versionCode over a higher one).
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Signing: the CI decodes the keystore from a GitHub secret and passes these in.
// Without them (e.g. a local build) the default Android debug key is used.
val keystorePath: String? = System.getenv("FRICTION_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("FRICTION_KEYSTORE_PASSWORD")
val hasFrictionKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

android {
    namespace = "dev.martin.friction"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.martin.friction"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (hasFrictionKey) {
            create("friction") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "friction"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasFrictionKey) signingConfig = signingConfigs.getByName("friction")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
