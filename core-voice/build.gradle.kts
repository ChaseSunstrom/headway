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
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM on purpose. Vosk exposes the same `org.vosk` API on desktop and on
// Android (different native artifact, identical Java classes), so keeping the
// recogniser and the command grammar off Android means the entire voice
// pipeline runs under `./gradlew test` against a real model and real audio --
// the thing Phase 5 is actually judged on. Android supplies only the audio
// source and the intent dispatch.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.coroutines.core)

    // Desktop natives, used by the tests. The Android app substitutes
    // com.alphacephei:vosk-android, which exposes the same org.vosk classes.
    compileOnly(libs.vosk)
    testImplementation(libs.vosk)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Where the Vosk model lives. tools/fetch-vosk-model.sh puts it here; the
    // acceptance test skips with a clear message rather than failing if it is
    // absent, so a contributor without the model still gets a green build.
    systemProperty(
        "headway.vosk.model",
        providers.gradleProperty("headway.vosk.model").orNull
            ?: System.getenv("HEADWAY_VOSK_MODEL")
            ?: "/opt/vosk/vosk-model-small-en-us-0.15",
    )

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
