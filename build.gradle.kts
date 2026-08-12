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
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.protobuf) apply false
}

/**
 * Hard constraint from CLAUDE.md: no Google Play Services anywhere in the merged
 * dependency graph, including transitively. This task fails the build if any
 * configuration resolves a GMS/Firebase artifact.
 *
 * Run with: ./gradlew checkNoGms
 */
val forbiddenDependencyGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.mlkit",
    "com.google.android.play",
)

subprojects {
    tasks.register("checkNoGms") {
        group = "verification"
        description = "Fails if any resolvable configuration pulls in Google Play Services."

        // Resolve at execution time; capture only serialisable state for the
        // configuration cache.
        val projectPath = path
        val configurationsToCheck = provider {
            configurations
                .filter { it.isCanBeResolved }
                // Test/tooling classpaths are irrelevant to the shipped APK, but we
                // check them anyway: a GMS transitive there usually means it will
                // creep into the runtime classpath later.
                .filterNot { it.name.contains("lintClassPath", ignoreCase = true) }
        }

        doLast {
            val offenders = mutableListOf<String>()
            configurationsToCheck.get().forEach { config ->
                val resolved = runCatching {
                    config.incoming.artifactView { lenient(true) }
                        .artifacts.artifacts.map { it.variant.displayName }
                }.getOrDefault(emptyList())

                runCatching {
                    config.incoming.resolutionResult.allComponents.forEach { component ->
                        val id = component.id.displayName
                        if (forbiddenDependencyGroups.any { id.startsWith(it) }) {
                            offenders += "$projectPath / ${config.name} -> $id"
                        }
                    }
                }
                resolved.forEach { name ->
                    if (forbiddenDependencyGroups.any { name.contains(it) }) {
                        offenders += "$projectPath / ${config.name} -> $name"
                    }
                }
            }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Google Play Services dependencies detected — this violates the")
                        appendLine("'no GMS' hard constraint. Offending components:")
                        offenders.distinct().sorted().forEach { appendLine("  $it") }
                    }
                )
            }
        }
    }
}

tasks.register("checkNoGms") {
    group = "verification"
    description = "Aggregate GMS check across all modules."
    dependsOn(subprojects.map { "${it.path}:checkNoGms" })
}
