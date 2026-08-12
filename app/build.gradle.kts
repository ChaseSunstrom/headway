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
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Frame-level protocol logging and TLS key export are debug-only.
            // A release build must never be able to write session keys to disk.
            buildConfigField("boolean", "PROTOCOL_DEBUG", "true")
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
