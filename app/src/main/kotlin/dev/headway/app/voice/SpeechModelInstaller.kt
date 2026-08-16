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

package dev.headway.app.voice

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled speech model onto the phone, once.
 *
 * ## Why the model cannot simply be read from assets
 *
 * `org.vosk.Model` takes a filesystem path and the native decoder opens the
 * files itself. Assets inside an APK are not files — they are entries in a zip
 * the framework serves through an `AssetManager` stream — so the model has to be
 * materialised in `filesDir` before Vosk can load it. That costs ~68 MB of
 * storage on top of the ~41 MB already in the APK, and there is no way around it
 * short of a different decoder.
 *
 * ## The three things that make this safe to run at any time
 *
 * 1. **Atomic.** Unpacking goes to a temporary directory and is renamed into
 *    place at the end. Without that, a half-unpacked directory passes
 *    `CarVoiceStream.voskRecognizer`'s `isDirectory` check and then fails inside
 *    the native loader as an opaque error — the worst possible symptom, since it
 *    looks like a broken model rather than an interrupted copy.
 * 2. **Versioned.** A marker file records which model is installed, so an app
 *    update that ships a different one replaces it instead of running the old
 *    one forever, and a matching one costs a single file read.
 * 3. **Zip-slip guarded.** Entry names are resolved and checked to stay inside
 *    the target. The archive here is a known one fetched against a pinned
 *    SHA-256, so this is defence in depth rather than a live threat — but an
 *    unpacker without the check is the kind of thing that gets copied into a
 *    context where the archive is not trusted.
 *
 * ## Where it runs
 *
 * At first launch, from the UI, not from the car session. Unpacking 68 MB takes
 * seconds; doing it lazily on the first voice command would spend them while the
 * driver waits, and doing it during session bring-up would spend them against
 * the head unit's fifteen-second video deadline.
 */
object SpeechModelInstaller {

    /**
     * The asset the build packs.
     *
     * Kept in step with `voskModelName` in `app/build.gradle.kts`; a mismatch
     * shows up immediately as [isInstalled] never becoming true, and [install]
     * says which name it looked for.
     */
    const val ASSET_NAME: String = "vosk-model-small-en-us-0.15.zip"

    /** Directory under `filesDir`; the same name `CarVoiceStream` looks in. */
    const val DIRECTORY: String = CarVoiceStream.MODEL_DIRECTORY

    private const val MARKER = ".installed"
    private const val TEMPORARY_SUFFIX = ".tmp"

    fun modelDirectory(context: Context): File = File(context.filesDir, DIRECTORY)

    /** True when a model matching [ASSET_NAME] is already unpacked. */
    fun isInstalled(context: Context): Boolean {
        val marker = File(modelDirectory(context), MARKER)
        return marker.isFile && runCatching { marker.readText().trim() }.getOrNull() == ASSET_NAME
    }

    /**
     * Unpacks the model if it is not already installed.
     *
     * Blocking and potentially several seconds; call it off the main thread.
     *
     * @return true when a usable model is in place afterwards, whether this call
     *   put it there or found it already. False is not fatal anywhere: voice
     *   degrades to "the car microphone works, nothing is transcribed", which
     *   `CarVoiceStream` reports in as many words.
     */
    fun install(context: Context, onStep: (String) -> Unit = {}): Boolean {
        val target = modelDirectory(context)
        if (isInstalled(context)) return true

        val staging = File(context.filesDir, DIRECTORY + TEMPORARY_SUFFIX)
        // A previous attempt that died mid-unpack. Its contents are unknowable,
        // so they are discarded rather than resumed.
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            onStep("voice: could not create ${staging.absolutePath} for the speech model")
            return false
        }

        val unpacked = runCatching {
            context.assets.open(ASSET_NAME).use { stream ->
                unpack(ZipInputStream(stream), staging)
            }
        }.getOrElse {
            onStep("voice: could not unpack the speech model ($it)")
            staging.deleteRecursively()
            return false
        }

        // Vosk models are a directory of directories; a zip with one top-level
        // folder unpacks one level deeper than the model root, which the native
        // loader will not accept.
        val root = modelRootIn(staging)
        if (root == null) {
            onStep("voice: the bundled speech model archive did not contain a model")
            staging.deleteRecursively()
            return false
        }

        target.deleteRecursively()
        val moved = if (root == staging) {
            staging.renameTo(target)
        } else {
            // Rename the inner directory up, then drop the wrapper.
            val ok = root.renameTo(target)
            staging.deleteRecursively()
            ok
        }
        if (!moved) {
            onStep("voice: could not move the speech model into place")
            staging.deleteRecursively()
            return false
        }

        File(target, MARKER).writeText(ASSET_NAME)
        // A fresh model is a fresh chance. If a previous load took the process
        // down with it -- which is what the marker records, and which is not a
        // property of the model but of whether the OS will let the engine build
        // its call trampolines -- re-installing is the driver's way of asking
        // Headway to try again. See `CarVoiceStream.voskRecognizer`.
        CarVoiceStream.forgetSpeechModelCrash(context)
        onStep("voice: installed the speech model ($unpacked file(s)) at ${target.absolutePath}")
        return true
    }

    /** @return how many files were written. */
    private fun unpack(zip: ZipInputStream, into: File): Int {
        val root = into.canonicalFile
        var files = 0
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val entry = zip.nextEntry ?: break
            val destination = File(root, entry.name).canonicalFile
            // Zip-slip: an entry named ../../something would otherwise write
            // outside filesDir entirely.
            require(destination.path.startsWith(root.path + File.separator)) {
                "archive entry ${entry.name} escapes the target directory"
            }
            if (entry.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                destination.outputStream().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
                files++
            }
            zip.closeEntry()
        }
        return files
    }

    /**
     * The directory the decoder should be pointed at.
     *
     * A Vosk model always contains an `am` directory; finding it identifies the
     * root whether the archive was packed with or without a wrapping folder.
     */
    private fun modelRootIn(staging: File): File? {
        if (File(staging, "am").isDirectory) return staging
        return staging.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "am").isDirectory }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
