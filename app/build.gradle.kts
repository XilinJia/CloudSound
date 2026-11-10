plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.parcelize")
}

kotlin { jvmToolchain(21) }

android {
    namespace = "ac.amalfi.cloudsound"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "ac.amalfi.cloudsound"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.0.10"
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    signingConfigs {
        create("releaseConfig") {
            enableV1Signing = true
            enableV2Signing = true
            storeFile = file(project.findProperty("releaseStoreFile") as? String ?: "keystore")
            storePassword = project.findProperty("releaseStorePassword") as? String ?: "password"
            keyAlias = project.findProperty("releaseKeyAlias") as? String ?:  "alias"
            keyPassword = project.findProperty("releaseKeyPassword") as? String ?:  "password"
        }
    }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs["releaseConfig"]
        }
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true   // for NewPipeExtractor
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    val appName = "CloudSound"
    val versionName = android.defaultConfig.versionName ?: "0.0.0"
    onVariants { variant ->
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val copyTask = tasks.register<Copy>("export${capitalized}Apks") {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)) {
                include("**/*.apk")
                eachFile {
                    name = name
                        .replace(Regex("-(release|debug)(?=\\.apk$)"), "")
                        .replace("app", appName)
                        .replace(".apk", "-$versionName.apk")
                }
                into("")
            }
            into(layout.buildDirectory.dir("exported-apks/$variantName"))
        }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach { finalizedBy(copyTask) }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    implementation(libs.ktor.http)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation("com.github.XilinJia:PodciniLib:1.1.9")
    implementation("com.github.XilinJia:NPEConnector:1.1.11")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
}
