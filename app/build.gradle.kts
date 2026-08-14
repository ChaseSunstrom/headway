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

import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// --- the bundled speech model ------------------------------------------------
//
// Voice has to work with no network and no setup step: CLAUDE.md requires the
// app to "function on a phone in airplane mode", and the Definition of Done
// wants a voice round-trip that is "fully offline". So the model ships inside
// the APK rather than being fetched on the device.
//
// It is not committed. 41 MB in git history, permanently, for a file that can be
// verified against upstream by hash is a bad trade — so it is downloaded at
// build time and checked. The pinned SHA-256 is what makes that reproducible:
// the download is a cache fill, the hash is the source of truth, and a build
// whose bytes differ from the pin fails instead of silently shipping something
// else.
//
// Note this is a *build-time* fetch. The runtime constraint is about the phone
// in the car, and nothing here runs there.
val voskModelName = "vosk-model-small-en-us-0.15"
val voskModelSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
val voskModelUrl = "https://alphacephei.com/vosk/models/$voskModelName.zip"

// Outside build/, so `clean` does not cost another 41 MB download.
val voskModelCache = layout.projectDirectory.dir("../.gradle/model-cache")
val voskAssetsDir = layout.buildDirectory.dir("generated/vosk/assets")

// Bundling can be turned off with -Pheadway.model=none, which drops the APK
// from ~63 MB to ~21 MB. That is not a preference knob — it exists because a
// 63 MB file cannot be sent through some channels, and a build that cannot be
// delivered cannot be tested. A slim build has no speech model on the device
// and reports so; everything else is identical.
val bundleVoskModel = (providers.gradleProperty("headway.model").orNull ?: "bundled") != "none"

val fetchVoskModel by tasks.registering {
    description = "Downloads and verifies the bundled Vosk speech model."
    onlyIf { bundleVoskModel }
    val cached = voskModelCache.file("$voskModelName.zip").asFile
    val staged = voskAssetsDir.map { it.file("$voskModelName.zip") }
    outputs.file(staged)
    outputs.upToDateWhen { staged.get().asFile.exists() }
    doLast {
        if (!cached.exists()) {
            cached.parentFile.mkdirs()
            logger.lifecycle("Fetching $voskModelName (~41 MB)...")
            uri(voskModelUrl).toURL().openStream().use { input ->
                cached.outputStream().use { input.copyTo(it) }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(cached.readBytes())
        val actual = digest.joinToString("") { byte -> "%02x".format(byte) }
        check(actual == voskModelSha256) {
            "speech model checksum mismatch\n  expected $voskModelSha256\n  actual   $actual\n" +
                "Delete ${cached.absolutePath} and rebuild, or update voskModelSha256 if the " +
                "upstream model was intentionally changed."
        }
        val target = staged.get().asFile
        target.parentFile.mkdirs()
        cached.copyTo(target, overwrite = true)
    }
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

        // Vosk ships a ~9 MB libvosk.so per ABI, and minSdk 33 means AGP stores
        // native libraries uncompressed -- so a universal APK carries 36 MB of
        // decoder to run 9 MB of it. Filtering is not an optimisation here, it
        // is the difference between a 62 MB and a 90 MB download.
        //
        // A property rather than `splits { abi { } }`: split APKs need per-ABI
        // versionCode offsets, and versionCode is HEADWAY_BUILD_NUMBER, which
        // the in-app updater compares. Offsetting it would break updates.
        //
        // CI's instrumentation job passes -Pheadway.abis=arm64-v8a,x86_64.
        ndk {
            abiFilters += (providers.gradleProperty("headway.abis").orNull ?: "arm64-v8a")
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
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
        // JNA arrives with vosk-android and carries its dispatcher for every
        // platform it has ever supported — AIX on PowerPC, macOS, both Windows
        // architectures. On Android the dispatcher comes from the AAR's own
        // jniLibs, so these are 1.7 MB that can never be loaded. The Android
        // and Linux entries are deliberately not excluded.
        resources.excludes += "com/sun/jna/aix-*/**"
        resources.excludes += "com/sun/jna/darwin*/**"
        resources.excludes += "com/sun/jna/win32-*/**"
        resources.excludes += "com/sun/jna/freebsd-*/**"
        resources.excludes += "com/sun/jna/openbsd-*/**"
        resources.excludes += "com/sun/jna/sunos-*/**"
    }

    // Reproducible release builds are a Definition-of-Done item. Deterministic
    // ordering removes the two usual sources of byte-level drift between
    // machines.
    androidResources {
        // The model zip is already deflated; asking AGP to compress it again
        // costs build time and gains nothing. Shipping it as one opaque archive
        // rather than 68 MB of loose assets is also what keeps it to a single
        // checksum and a single atomic unpack on the device.
        noCompress += listOf("wav", "zip")
    }

    testOptions {
        unitTests.all {
            // The rest of the project is JUnit 5; :app was the only module
            // without a unit-test source set, so this had never been set.
            it.useJUnitPlatform()
        }
    }

    // Registered only when bundling. Skipping the fetch task alone is not
    // enough — the generated directory survives in build/, so a slim build
    // straight after a bundled one would package the leftover model and be
    // 41 MB heavier than it claims.
    if (bundleVoskModel) {
        sourceSets.named("main") {
            assets.srcDir(voskAssetsDir)
        }
    }
}

// The assets have to exist before AGP merges them, and merge tasks are created
// per variant, so this hooks every one rather than guessing at names.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(fetchVoskModel) }

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

    // The speech decoder. :core-voice declares the desktop jar `compileOnly` so
    // it can stay a pure-JVM module and its acceptance test can run on the
    // build machine; this is the Android build of the same library, exporting
    // the same `org.vosk` classes, and it is what makes VoskSpeechRecognizer
    // resolvable at runtime on a phone. Without it the reflective load in
    // CarVoiceStream fails and voice degrades to "no on-device speech model",
    // which is exactly what a real drive reported.
    implementation(libs.vosk.android)

    // :app had no unit-test source set at all until the dashboard's layout tree
    // needed one -- `:app:testDebugUnitTest` reported NO-SOURCE, which reads in
    // CI exactly like a suite that passed.
    //
    // org.json is the reason this is not just a JUnit dependency. The Android
    // SDK jar the unit-test classpath uses stubs every android.* and org.json
    // method to `throw new RuntimeException("Stub!")`, so anything touching
    // JSONObject dies unless a real implementation shadows it. AGP's
    // `unitTests.isReturnDefaultValues` only turns those throws into nulls,
    // which is worse. The upstream org.json artifact is the real thing.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
