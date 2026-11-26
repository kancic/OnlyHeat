plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ancic.karim.onlyheat"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "ancic.karim.onlyheat"
        minSdk = 30
        targetSdk = 37
        val version = providers.gradleProperty("appVersion").get()
        versionName = version
        versionCode = version.split('.').fold(0) { code, part -> code * 100 + part.toInt() } // 1.2.3 -> 10203
    }

    // The release key lives outside the repo: ~/.gradle/gradle.properties locally, GitHub secrets in release.yml.
    val keystore = providers.gradleProperty("onlyheat.keystore").orNull
    if (keystore != null) signingConfigs.create("release") {
        storeFile = file(keystore)
        storePassword = providers.gradleProperty("onlyheat.keystorePassword").get()
        keyAlias = "onlyheat"
        keyPassword = storePassword
    }

    buildTypes {
        // Own app id, so Studio builds (debug key) live beside the self-updating release app (release key).
        debug { applicationIdSuffix = ".debug" }
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            proguardFiles("proguard-rules.pro")
        }
    }
    // Lists English + Croatian under Settings → Apps → OnlyHeat → Language (Android 13+).
    androidResources { generateLocaleConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime)
    implementation(libs.koin.android)
}
