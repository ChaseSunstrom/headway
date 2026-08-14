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

// A second entry point into the same source tree that builds ONLY the pure-JVM
// modules.
//
// Why it exists: the main build declares the Android Gradle Plugin, which lives
// exclusively on Google's Maven repository. In an environment that cannot reach
// `dl.google.com` -- a locked-down CI runner, an F-Droid-style reproducibility
// check, or a sandbox with an allowlist -- resolving AGP fails while configuring
// the *root* project, so `./gradlew :core-protocol:test` cannot run either, even
// though nothing it needs is Android at all.
//
// Everything below points at the real module directories; there is no copy and
// no second source of truth. The main build is unchanged and remains the one
// that produces an APK.
//
// Usage, from the repository root:
//     ./gradlew -p tools/jvm-only test
//
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "headway-jvm"

include(":core-protocol")
include(":core-transport")
include(":core-voice")
include(":core-dash")
include(":headunit-emulator")

project(":core-protocol").projectDir = file("../../core-protocol")
project(":core-transport").projectDir = file("../../core-transport")
project(":core-voice").projectDir = file("../../core-voice")
project(":core-dash").projectDir = file("../../core-dash")
project(":headunit-emulator").projectDir = file("../../headunit-emulator")
