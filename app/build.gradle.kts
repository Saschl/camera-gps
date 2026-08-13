import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("io.sentry.android.gradle") version "6.14.0"
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.saschl.cameragps"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    // Reproducible builds: native libs (libsqliteJni.so from sqlite-bundled) are
    // stripped with the NDK's llvm-strip, so CI and F-Droid must use the SAME
    // NDK. Keep in sync with `ndk:` in the fdroiddata
    ndkVersion = "29.0.14206865"

    androidResources {
        generateLocaleConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }


    defaultConfig {
        applicationId = "com.saschl.cameragps"
        minSdk = 26
        targetSdk = 37
        versionCode = 150
        versionName = "v1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // F-Droid builds from source without the keystore or signing secrets — release
    // must fall back to an unsigned APK instead of failing.
    val releaseSigningAvailable =
        file("keystore.jks").exists() && System.getenv("SIGNING_KEY_ALIAS") != null

    signingConfigs {
        create("release") {
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            storeFile = file("keystore.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig =
                if (releaseSigningAvailable) signingConfigs.getByName("release") else null

        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Play Store build: GMS fused location, Play in-app review, Sentry.
        create("gplay") {
            dimension = "distribution"
            isDefault = true
        }
        // F-Droid build: no GMS, no Play libraries, no Sentry.
        create("foss") {
            dimension = "distribution"
        }
    }

    dependenciesInfo {
        // The dependency-info block is an encrypted blob only Google Play can read;
        // F-Droid rejects APKs that contain it.
        includeInApk = false
        includeInBundle = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }

}

dependencies {

    implementation(project(":sharednew"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.timber)
    //implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.accompanist.permissions)

    // Proprietary bits stay out of the foss (F-Droid) flavor.
    "gplayImplementation"(libs.google.play.services.location)
    "gplayImplementation"(libs.review)
    "gplayImplementation"(libs.review.ktx)
    "gplayImplementation"(libs.sentry.android)
    "gplayImplementation"(libs.sentry.android.timber)

    implementation(libs.material)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.compose.runtime.livedata)
    //implementation(libs.betterypermissionhelper)

    // Room database dependencies
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    //implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.appcompat)

    implementation(compose.components.resources)

}


sentry {
    org.set("sascha-ni")
    projectName.set("android")

    // this will upload your source code to Sentry to show it as part of the stack traces
    // disable if you don't want to expose your sources
    includeSourceContext.set(true)

    // The foss flavor ships without Sentry; the SDK is added explicitly via
    // gplayImplementation instead of auto-installation (which is variant-blind).
    ignoredFlavors.set(setOf("foss"))
    autoInstallation {
        enabled.set(false)
    }
}
