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
    alias(libs.plugins.kotlin.jvm)
}

// --- the AAP client certificates ---------------------------------------------
//
// Headway presents a TLS client certificate to the head unit. The three it can
// present are Google-issued certificates, with their private keys, that were
// extracted from Android Auto and from head-unit firmware and published years
// ago by the reverse-engineering projects this one is derived from.
//
// They are **not vendored here.** They are fetched at build time from those
// upstream repositories, pinned to a commit and verified against a SHA-256, and
// written into generated resources. Nothing about that changes what the app
// does; what it changes is that this repository does not itself redistribute
// somebody else's private key. A key is not copyrightable, so that is not the
// concern — the concern is DMCA §1201, under which distributing material that
// defeats an access control has been treated as trafficking in a circumvention
// device, whatever the intent. Headway is a §1201(f) interoperability project
// and the upstream repos have hosted these files unchallenged for years, so
// this is caution rather than a settled question. Moving the distribution back
// to the projects that already do it costs nothing and removes Headway from
// that chain.
//
// A user can still supply their own pair in the app, which remains the only
// path that involves no third-party key at all.
val certificateCache = layout.projectDirectory.dir("../.gradle/cert-cache")
val certificateResources = layout.buildDirectory.dir("generated/certs/resources")

/** An upstream file, pinned to a commit and a hash. */
data class UpstreamFile(val name: String, val url: String, val sha256: String)

val aacsCommit = "faa1cf208feb5dfe1cb9535be16daeac4f08da0c"
val aasdkCommit = "1bc0fe69d5f5f505c978a0c6e32c860e820fa8f6"
val aacsRaw = "https://raw.githubusercontent.com/tomasz-grobelny/AACS/$aacsCommit"
val aasdkRaw = "https://raw.githubusercontent.com/openDsh/aasdk/$aasdkCommit"

val upstreamCertificates = listOf(
    // The phone-role pair every reference sends. Expired 2022-08-24; kept
    // because a head unit that ignores dates accepts it and it is the only one
    // issued for the right role. See BLOCKERS.md B-003.
    UpstreamFile(
        "phone.crt",
        "$aacsRaw/AAServer/ssl/android_auto.crt",
        "ad993aaea70629ae1a0a6d5f9d90f07f356ce3ef1b5a1312899bfa1bd6edc4a7",
    ),
    UpstreamFile(
        "phone.key",
        "$aacsRaw/AAServer/ssl/android_auto.key",
        "92d00db87d62b8fc02c1e7fe619a3af2d1d6d628f912b7ca82be9bb57174ee8d",
    ),
    // O=Android-Auto-Internal, valid to 2048. Same CA, wrong role, and a unit
    // that checks the chain and the dates but not the role takes it.
    UpstreamFile(
        "internal.crt",
        "$aacsRaw/AAClient/ssl/headunit.crt",
        "92515745cff06a913f2f7c3731189fc1a8be3675c7d6d1310e995aa2548a7262",
    ),
    UpstreamFile(
        "internal.key",
        "$aacsRaw/AAClient/ssl/headunit.key",
        "768437bcb5aca284cdb7173dad7d92c8ee27fb1d975f345e732e18c7c2a6876d",
    ),
    // O=JVC Kenwood, valid to 2045. A third subject to try when a unit objects
    // to Android-Auto-Internal by name -- the Malibu accepted `internal`, not
    // this one (B-003). aasdk does not ship it as a file: it is two
    // C++ string literals in Cryptor.cpp, so the whole source file is fetched
    // and the PEMs are cut out of it below.
    UpstreamFile(
        "aasdk-Cryptor.cpp",
        "$aasdkRaw/src/Messenger/Cryptor.cpp",
        "6b1e9ec904d77b4a7d8a7fe1e9152d29051c778fa53f9fc2d018d82bbd84a42e",
    ),
)

/** Attempts before a certificate fetch is called a failure. See the retry below. */
val CERTIFICATE_FETCH_ATTEMPTS = 4

val fetchCertificates by tasks.registering {
    description = "Fetches the AAP client certificates from the upstream reference projects."
    val outputDir = certificateResources.map { it.dir("dev/headway/transport/tls") }
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile
        target.mkdirs()
        val fetched = mutableMapOf<String, ByteArray>()
        for (file in upstreamCertificates) {
            val cached = certificateCache.file(file.name).asFile
            if (!cached.exists()) {
                cached.parentFile.mkdirs()
                logger.lifecycle("Fetching ${file.name} from upstream...")
                // Retried, because the failure this saw in practice was not the
                // file being wrong or gone. raw.githubusercontent.com answered
                // HTTP 429 -- rate limiting, from a shared CI runner address --
                // and the whole protocol job failed on it with nothing about
                // the commit at fault. The content is pinned by SHA-256 below,
                // so a retry cannot smuggle anything in; it can only turn a
                // transient refusal into the same bytes a moment later.
                var lastFailure: Exception? = null
                for (attempt in 1..CERTIFICATE_FETCH_ATTEMPTS) {
                    try {
                        uri(file.url).toURL().openStream().use { input ->
                            cached.outputStream().use { input.copyTo(it) }
                        }
                        lastFailure = null
                        break
                    } catch (failure: Exception) {
                        lastFailure = failure
                        runCatching { cached.delete() }
                        if (attempt == CERTIFICATE_FETCH_ATTEMPTS) break
                        val wait = 2000L * attempt
                        logger.lifecycle(
                            "  ${file.name}: attempt $attempt failed ($failure); " +
                                "retrying in ${wait}ms"
                        )
                        Thread.sleep(wait)
                    }
                }
                lastFailure?.let {
                    throw GradleException(
                        "could not fetch ${file.name} after $CERTIFICATE_FETCH_ATTEMPTS " +
                            "attempts: $it. This is a network failure rather than anything " +
                            "about the code being built; re-running the job is the usual fix.",
                        it,
                    )
                }
            }
            val bytes = cached.readBytes()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            check(actual == file.sha256) {
                "${file.name} checksum mismatch\n  expected ${file.sha256}\n  actual   $actual\n" +
                    "Delete ${cached.absolutePath} and rebuild. If upstream genuinely changed " +
                    "the file, verify the new content before updating the pin."
            }
            fetched[file.name] = bytes
            if (file.name != "aasdk-Cryptor.cpp") {
                File(target, file.name).writeBytes(bytes)
            }
        }

        // aasdk embeds its pair as C++ literals: a quoted string whose lines end
        // in a backslash continuation, with \n for the PEM's own newlines.
        val source = String(fetched.getValue("aasdk-Cryptor.cpp"))
        File(target, "headunit.crt").writeText(extractCppLiteral(source, "cCertificate"))
        File(target, "headunit.key").writeText(extractCppLiteral(source, "cPrivateKey"))
    }
}

/** Pulls one `const std::string Cryptor::<name> = "...";` literal out of the source. */
fun extractCppLiteral(source: String, name: String): String {
    val marker = "Cryptor::$name = "
    val declaration = source.indexOf(marker)
    check(declaration >= 0) { "aasdk Cryptor.cpp no longer defines $name" }
    val open = source.indexOf('"', declaration)
    val close = source.indexOf("\";", open + 1)
    check(open >= 0 && close > open) { "could not read the $name literal" }
    return source.substring(open + 1, close)
        .replace("\\\n", "")
        .replace("\\n", "\n")
}

// Pure JVM: socket transports, the in-memory TLS engine, and the fake transport
// used by CI. Android-specific transports (Bluetooth RFCOMM, Wi-Fi network
// binding) live in :app and implement interfaces declared here. See ADR 0001.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets.named("main") {
    resources.srcDir(certificateResources)
}

tasks.named("processResources") { dependsOn(fetchCertificates) }

dependencies {
    api(project(":core-protocol"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
