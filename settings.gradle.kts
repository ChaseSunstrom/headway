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

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "headway"

// --- Pure-JVM modules -------------------------------------------------------
// These carry the entire AAP implementation and have NO Android dependencies,
// so they are unit-testable on any JDK and are shared verbatim between the
// phone app and the head-unit emulator. See docs/adr/0001-kotlin-protocol-core.md
include(":core-protocol")
include(":core-transport")
include(":headunit-emulator")

// Pure JVM: on-device speech recognition and the command grammar. Kept off
// Android so the whole voice pipeline is executable in CI with a real model.
include(":core-voice")

// --- Android modules --------------------------------------------------------
// Added to the build as each lands with real content, so that every commit
// configures and every commit's tests run.
// include(":app")            // added in Phase 2
// include(":core-video")     // added in Phase 2
// include(":core-input")     // added in Phase 3
// include(":core-audio")     // added in Phase 4
