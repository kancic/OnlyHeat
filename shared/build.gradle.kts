import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
    android {
        namespace = "ancic.karim.onlyheat.shared"
        compileSdk { version = release(37) { minorApiLevel = 1 } }
        minSdk = 30
        androidResources { enable = true } // required for compose resources to be packaged
    }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.navigationevent.compose)
            api(libs.compose.material3) // ColorScheme is in App()'s signature
            implementation(libs.compose.resources)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.settings)
            implementation(libs.navigation3.ui)
            implementation(libs.kotlinx.datetime)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        named("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.kotlinx.coroutines.test) }
    }
}

dependencies {
    listOf("kspAndroid", "kspDesktop", "kspIosArm64", "kspIosSimulatorArm64").forEach { add(it, libs.room3.compiler) }
}

room3 { schemaDirectory("$projectDir/schemas") }

compose.resources {
    packageOfResClass = "ancic.karim.onlyheat.res"
}

compose.desktop.application {
    mainClass = "ancic.karim.onlyheat.MainKt"
    // Daemon runs on Android Studio's JBR, which lacks jpackage; package with a full JDK (foojay fetches one if missing).
    javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25); vendor = JvmVendorSpec.ADOPTIUM }
        .get().metadata.installationPath.asFile.absolutePath
    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        packageName = "OnlyHeat"
        macOS {
            iconFile.set(rootProject.file("icons/onlyheat.icns"))
            bundleID = "ancic.karim.onlyheat"
            // Without this macOS silently blocks reaching the controller on the home network (Local Network privacy).
            infoPlist {
                extraKeysRawXml = """
                    <key>NSLocalNetworkUsageDescription</key>
                    <string>OnlyHeat talks to your heating controller on your home network to restart it and edit its schedule.</string>
                """.trimIndent()
            }
        }
    }
}
