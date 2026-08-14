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

// Pure JVM, and deliberately so.
//
// Everything the car screen decides *before* it draws anything lives here: what
// a layout is, how an edit changes it, which palette is in force, and where an
// app's picture lands inside a pane. None of that needs Android, all of it is
// where the bugs are, and on this side of the line it runs under `./gradlew
// test` on any machine -- including one that cannot reach Google's Maven
// repository and therefore cannot build an APK at all. See ADR 0010.
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
    // Android ships org.json in the platform, so the app must not package a
    // second copy: compileOnly here, real jar on the test classpath. Same shape
    // core-voice uses for Vosk.
    compileOnly(libs.json)
    testImplementation(libs.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
