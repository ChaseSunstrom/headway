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

// A Kotlin compiler for the parts of `:app` that do not need Android.
//
// ## Why this exists
//
// `:app` is an Android module, so building it needs the Android Gradle Plugin
// and an SDK. On a machine that has neither -- which includes the one most of
// this app is written on -- `:app` is compiled nowhere except CI. On
// 2026-08-19 that cost three CI rounds in an afternoon: a stray brace from a
// scripted re-indent, an extension function called as if it were qualified,
// and four call sites left behind by a signature change. None of them survive
// a compiler for a second; all of them took twenty minutes to hear about.
//
// The shape checker (`tools/check-kotlin-shapes.py`) catches shapes. It cannot
// catch types, and it should not try -- a regex that guesses at a type system
// is how you get a check that is wrong about working code.
//
// ## What goes in here
//
// Files under `app/` that import nothing from `android.` and reference nothing
// that does. Listed one by one rather than discovered, because "no android
// import" is not the same as "compiles without Android" -- a file can reach an
// Android type through a sibling -- and a harness that breaks when someone
// writes a perfectly good file is worse than one that needs a line adding.
//
// Add a file here when you write one that qualifies. The test source set is
// the half that earns its keep: both of the compile errors above were in tests.
val pureMain = listOf(
    "dev/headway/app/video/ForegroundApp.kt",
    "dev/headway/app/audio/MediaQueue.kt",
)

val pureTest = listOf(
    "dev/headway/app/video/AppPaneForegroundRuleTest.kt",
    "dev/headway/app/audio/MediaQueueTest.kt",
)

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets["main"].kotlin.setSrcDirs(listOf(file("../../../app/src/main/kotlin")))
    sourceSets["test"].kotlin.setSrcDirs(listOf(file("../../../app/src/test/kotlin")))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("main") { kotlin { setIncludes(pureMain) } }
    named("test") { kotlin { setIncludes(pureTest) } }
}

dependencies {
    implementation(libs.coroutines.core)
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
