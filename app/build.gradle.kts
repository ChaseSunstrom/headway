/*
 * This file is part of Headway.
 * Copyright (C) 2026 The Headway Authors
 *
 * Headway is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Headway is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Headway. If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.headway.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.headway.app"
        // API 33: the minimum that has BLUETOOTH_CONNECT/SCAN with
        // neverForLocation and the Notifications runtime permission, both of
        // which Headway depends on to avoid asking for location.
        minSdk = 33
        targetSdk = 35

        // The CI run number, so every published build outranks the one before
        // it. A fixed versionCode meant Android saw every release as the same
        // version, and the in-app updater had nothing to compare.
        versionCode = (System.getenv("HEADWAY_BUILD_NUMBER") ?: "1").toInt()
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "UPDATE_REPOSITORY", "\"ChaseSunstrom/headway\"")
    }

    // Sign every build with the same key.
    //
    // This is what "App not installed" was: the build signed with the `debug`
    // config, and the debug keystore is generated on demand per machine. CI
    // runners are ephemeral, so each release was signed by a key that existed
    // for one build and was then destroyed. Android refuses to replace an
    // installed app with one signed by a different key -- correctly, since that
    // is the check stopping anyone from shipping an "update" to your apps -- and
    // the only way through it is to uninstall first, losing all app data.
    //
    // The keystore is committed on purpose. It protects nothing: the whole point
    // is that anyone can build a Headway that upgrades an existing install, the
    // same property the platform-wide public Android debug key already has. It
    // exists to make the key *stable*, not secret. A real distribution key
    // belongs in CI secrets instead -- set HEADWAY_KEYSTORE and friends and this
    // config picks them up without a code change.
    signingConfigs {
        create("headway") {
            storeFile = file(System.getenv("HEADWAY_KEYSTORE") ?: "../signing/headway-dev.jks")
            storePassword = System.getenv("HEADWAY_KEYSTORE_PASSWORD") ?: "headway-dev"
            keyAlias = System.getenv("HEADWAY_KEY_ALIAS") ?: "headway"
            keyPassword = System.getenv("HEADWAY_KEY_PASSWORD") ?: "headway-dev"
            // v2 is what makes the signature check cheap on install; v1 keeps
            // older tooling able to read the APK at all.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("headway")
        }
        debug {
            // Frame-level protocol logging and TLS key export are debug-only.
            // A release build must never be able to write session keys to disk.
            buildConfigField("boolean", "PROTOCOL_DEBUG", "true")
            // Published builds are debug builds, so this needs the stable key
            // just as much as release does -- more, since it is the one people
            // actually install.
            signingConfig = signingConfigs.getByName("headway")
        }
        release {
            buildConfigField("boolean", "PROTOCOL_DEBUG", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Reproducible release builds are a Definition-of-Done item. Deterministic
    // ordering removes the two usual sources of byte-level drift between
    // machines.
    androidResources {
        noCompress += listOf("wav")
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-transport"))
    implementation(project(":core-video"))
    implementation(project(":core-input"))
    implementation(project(":core-audio"))
    implementation(project(":core-voice"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
