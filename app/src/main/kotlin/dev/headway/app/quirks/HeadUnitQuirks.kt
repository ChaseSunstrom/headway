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

package dev.headway.app.quirks

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import android.content.Context
import dev.headway.protocol.channel.CarPoint
import dev.headway.protocol.control.VersionHandshake
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Corrections applied to touch coordinates arriving from the head unit.
 *
 * Every field defaults to the identity transform, because the protocol says the
 * head unit has already rescaled physical panel coordinates into the projected
 * video coordinate space before sending
 * (`openauto/src/autoapp/Projection/InputDevice.cpp` L391-L392, quoted in
 * `TouchTransform`'s KDoc). When that holds, nothing here is needed.
 *
 * These knobs exist because it is not known whether it holds for every unit, and
 * the failure mode is silent: a mirrored or offset axis produces touches that
 * land somewhere plausible but wrong, which reads as "the app is broken", not as
 * "the coordinates are flipped". A user with a real car and a frame log can fix
 * their own unit by editing a JSON file rather than waiting for a release.
 *
 * No reference implementation documents any head unit needing any of this — the
 * whole class is speculative headroom, not encoded observation.
 */
data class TouchQuirks(
    /** Mirror horizontally about the centre of the car's coordinate space. */
    val invertX: Boolean = false,
    /** Mirror vertically. */
    val invertY: Boolean = false,
    /** Exchange the axes, for a unit that reports in a rotated frame. */
    val swapAxes: Boolean = false,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    /** Added after scaling, in car coordinate units. */
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    /**
     * Use the `InputSourceService.TouchScreen` width/height as the car
     * coordinate space instead of the advertised video resolution.
     *
     * The two fields are distinct in the protobuf and are usually equal; when a
     * unit reports them differently, one of the two is what its touch events are
     * actually expressed in, and there is no way to tell which without a log.
     * Default false = trust the video resolution, which is what
     * `InputDevice.cpp` L391-L392 rescales into.
     */
    val useTouchscreenGeometry: Boolean = false,
) {

    /** True when this is the identity transform and can be skipped entirely. */
    val isIdentity: Boolean
        get() = !invertX && !invertY && !swapAxes &&
            scaleX == 1.0 && scaleY == 1.0 && offsetX == 0.0 && offsetY == 0.0

    /**
     * Applies the corrections to one raw car point.
     *
     * Order is swap, then invert, then scale, then offset. Swapping first means
     * [invertX] always refers to the axis the driver perceives as horizontal
     * rather than to whichever axis the unit happened to send first; putting the
     * offset last means it is expressed in final car coordinates, which is the
     * only form a user reading a frame log can measure.
     */
    fun correct(point: CarPoint, carWidth: Int, carHeight: Int): CarPoint {
        if (isIdentity) return point
        var x = point.x
        var y = point.y
        if (swapAxes) {
            val swapped = x
            x = y
            y = swapped
        }
        if (invertX) x = carWidth - x
        if (invertY) y = carHeight - y
        return CarPoint(x * scaleX + offsetX, y * scaleY + offsetY)
    }
}

/**
 * Per-head-unit knobs, as CLAUDE.md requires ("make quirks configurable").
 *
 * The defaults are the values Headway derives from the references, not guesses;
 * each one cites where it came from. A profile only ever *overrides* them, so a
 * quirk file that is empty, corrupt or absent behaves exactly like no quirk file
 * at all.
 */
data class HeadUnitQuirks(
    /**
     * Largest plaintext payload put in one AAP frame before fragmenting.
     *
     * Default 16384 = aasdk's `cMaxFramePayloadSize`
     * (`aasdk/include/aasdk/Messenger/MessageOutStream.hpp` L62). The knob exists
     * because the references disagree and one of them says why: AACS's head-unit
     * server uses 2000 with the comment that ~16k "should work up to about 16k,
     * but we might get some weird hardware issues"
     * (`AACS/AAServer/src/AaCommunicator.cpp` L371-L372), its client uses 10000
     * (`AACS/AAClient/src/AaCommunicator.cpp` L281), and aa-proxy-rs uses 16120
     * as an *observed* size from real streams
     * (`aa-proxy-rs/src/packet_fragment.rs` L3-L10). A unit that chokes on 16 KB
     * frames is therefore a documented possibility, and 2000 is the known-working
     * fallback.
     */
    val maxFragmentSize: Int = DEFAULT_MAX_FRAGMENT_SIZE,

    /**
     * AAP major version Headway announces in its `VersionResponse`.
     *
     * @see announcedVersionMinor
     */
    val announcedVersionMajor: Int = VersionHandshake.MAJOR,

    /**
     * AAP minor version Headway announces.
     *
     * Default 1.6 follows aasdk (`aasdk/include/aasdk/Version.hpp` L22-L23).
     * AACS announces 1.1 (`AACS/AAClient/src/AaCommunicator.cpp` L56) and its
     * phone side answers 1.5 (`AACS/AAServer/src/AaCommunicator.cpp` L89-L90),
     * while aa-proxy-rs defaults its version override to 5.1 and notes a real
     * unit may already advertise 6.0 (`aa-proxy-rs/src/config.rs` L1025-L1027,
     * `aa-proxy-rs/src/mitm.rs` L1424). Only the AACS phone implementation is
     * known to gate on the major alone; whether a factory unit cares about the
     * minor is unknown, so it is left adjustable.
     */
    val announcedVersionMinor: Int = VersionHandshake.MINOR,

    /**
     * Send third-party media audio over the AAP media-audio channel.
     *
     * **On by default, reversing CLAUDE.md.** The spec makes Bluetooth A2DP the
     * primary path on the premise that it "coexists with the AAP session". It
     * does not. A capture of real Android Auto against a 2021 Chevrolet
     * Infotainment 3 unit shows Gearhead tearing A2DP down on purpose —
     * `disabling A2dp route while in projection`, then
     * `A2DP playing while in projection. Trying disabling`, then
     * `ANDROID_AUTO_BLUETOOTH_A2DP_DISCONNECTED` — and streaming a named
     * third-party player over the AAP media channel instead. The user confirmed
     * the same thing from the driver's seat: with Headway projecting, the car is
     * silent.
     *
     * So A2DP is not a fallback on this class of head unit; it is no audio at
     * all. The toggle survives for a unit that genuinely does keep A2DP alive,
     * where leaving music on Bluetooth avoids the capture path's opt-out and its
     * ~45 ms.
     *
     * See ADR 0005.
     */
    val mediaAudioOverAap: Boolean = true,

    /**
     * Frames between forced H.264 IDRs.
     *
     * Default 25 is AACS's `key-int-max`
     * (`AACS/AAServer/src/VideoChannelHandler.cpp` L73-L74) — the only explicit
     * keyframe cadence in any reference. **No reference states a
     * protocol-mandated keyframe interval.** aa-proxy-rs's consumers wait for a
     * live IDR before starting playback (`aa-proxy-rs/src/media_tap.rs`
     * L884-L905), which implies but does not prove that the phone must emit one
     * at session start and after each focus regain. A unit that shows a black
     * screen until it happens to get an IDR wants a shorter interval here.
     */
    val keyframeIntervalFrames: Int = DEFAULT_KEYFRAME_INTERVAL_FRAMES,

    /**
     * Probe for the head unit's SSID instead of waiting to see it beaconed.
     *
     * Off by default because every access point in the references broadcasts
     * (`WirelessAndroidAutoDongle/.../hostapd.conf.in`, and aa-proxy-rs writes
     * `ignore_broadcast_ssid=0` explicitly at `src/bluetooth.rs` L3374). But
     * aa-proxy-rs is also the only reference that *joins* a real car's access
     * point from credentials received over Bluetooth, and both branches of its
     * `wpa_supplicant_config` set `scan_ssid=1` unconditionally (L2851, L2863)
     * — which exists for exactly one purpose, finding an AP that does not
     * beacon.
     *
     * A hidden head-unit SSID fails indistinguishably from a car that is not
     * there: Android's scan behind the approval prompt never matches, nothing
     * is tappable, and the request runs to its deadline with no association
     * error. If that is the shape of the failure, turn this on. It costs a
     * directed probe request and nothing else — a broadcasting AP still
     * associates normally with it set.
     */
    val hiddenSsid: Boolean = false,

    /**
     * Tell the head unit which of its advertised Wi-Fi frequencies we accept.
     *
     * Field 5 of `WifiVersionResponse`. Off by default because the field's
     * meaning is inferred rather than documented — see
     * `WirelessHandshake.announceSelectedWifiChannel` for the full argument on
     * both sides. Worth turning on for a head unit that hands over credentials
     * and then never brings its access point up, which is what a unit waiting
     * to be told a channel would look like.
     */
    val announceWifiChannel: Boolean = false,

    /**
     * Require the exact BSSID the head unit named over Bluetooth.
     *
     * **Null means automatic**, which is the default and the right answer for
     * an unknown car: Headway pins on one attempt and matches by SSID alone on
     * the next, so neither choice can be a dead end. Set it explicitly only
     * when a log shows which one this car needs.
     *
     * The history is worth knowing, because it was reasoned wrong once in each
     * direction. Pinning was removed on the theory that the target vehicle's
     * two BSSIDs for one SSID — `ce:22:26:bf:18:ec` and `ce:44:26:bf:18:ec` —
     * were a dual-radio access point, so pinning the announced one would match
     * nothing. But `docs/protocol-notes.md` §"The third capture" records a
     * *successful* pinned join to `ce:44:26:bf:18:ec`, 7.6 s after the request,
     * and the SSID-only builds that followed could not join at all. On GM
     * vehicles the vehicle hotspot and the projection access point share an
     * SSID on separate BSSs, so the two BSSIDs are two different networks
     * rather than two radios carrying one — and only the one Bluetooth names
     * carries projection.
     */
    val pinBssid: Boolean? = null,

    /**
     * Which bundled certificate to present first.
     *
     * **Null means start at the beginning and rotate**, which is the default:
     * each authentication rejection advances to the next candidate, so a car
     * that refuses the expired phone-role certificate is offered the unexpired
     * siblings without anybody having to know they exist. See
     * [dev.headway.transport.tls.AapTls.bundledPhoneCredentials] for what they
     * are and why an unexpired head-unit certificate is worth trying from the
     * phone side at all.
     *
     * Set it to an id — `phone`, `internal` or `headunit` — once a log says
     * which one this car accepts, and the rotation starts there instead of
     * spending two failed sessions rediscovering it. An unknown id is ignored
     * rather than fatal: a typo in a hand-edited file should cost the default
     * behaviour, not the connection.
     *
     * Ignored entirely when a certificate has been imported from Diagnostics —
     * that was a deliberate choice and rotating away from it would undo it.
     */
    val certificate: String? = null,

    /**
     * Register the car as a Wi-Fi *suggestion* instead of requesting it.
     *
     * **Off by default, and this is the knob for the one thing Headway cannot
     * otherwise do: get a stable MAC address.**
     *
     * A `WifiNetworkSpecifier` connection presents a new MAC on every
     * association on GrapheneOS and no API changes that — the reasoning and the
     * source citations are in
     * [dev.headway.app.link.CarWifiProvisioning]. `WifiNetworkSuggestion` is
     * the only public API carrying a MAC randomization preference, so turning
     * this on is the way to test whether MAC churn is what stops this car
     * issuing an address.
     *
     * It is not the default because the trade is genuinely bad if it turns out
     * not to be the cause: the first suggestion needs the user to accept a
     * notification and a refusal is sticky, the platform rather than Headway
     * decides when to associate (so there is no on-demand connect and no bound
     * on reconnect time), and it hands back no `Network`, which makes
     * `adoptExistingCarNetwork` load-bearing rather than a fallback.
     *
     * When on, Headway registers the suggestion and then waits to adopt the
     * network the platform joins, rather than also requesting one — running
     * both would open a second connection to the same access point with a fresh
     * random MAC on STA+STA hardware, which is the opposite of the point.
     */
    val suggestCarNetwork: Boolean = false,

    val touch: TouchQuirks = TouchQuirks(),
) {

    /** Convenience for handing the announced version straight to `AapSession`. */
    val announcedVersion: VersionHandshake.Version
        get() = VersionHandshake.Version(announcedVersionMajor, announcedVersionMinor)

    /** Human-readable one-liner for the log export and the settings screen. */
    fun describe(): String = "fragment=$maxFragmentSize version=$announcedVersion " +
        "mediaAudioOverAap=$mediaAudioOverAap keyframe=$keyframeIntervalFrames " +
        "hiddenSsid=$hiddenSsid pinBssid=${pinBssid ?: "auto"} " +
        "announceWifiChannel=$announceWifiChannel certificate=${certificate ?: "auto"} " +
        "suggestCarNetwork=$suggestCarNetwork " +
        "touch=${if (touch.isIdentity) "identity" else touch.toString()}"

    companion object {
        const val DEFAULT_MAX_FRAGMENT_SIZE: Int = 0x4000
        const val DEFAULT_KEYFRAME_INTERVAL_FRAMES: Int = 25

        /**
         * The profile used when nothing matches.
         *
         * Deliberately identical to the constructor defaults: "no profile" and
         * "the default profile" must be the same behaviour, or a user debugging a
         * car has to reason about which of the two they are in.
         */
        val DEFAULT: HeadUnitQuirks = HeadUnitQuirks()

        /** Smallest fragment any reference uses; below this is certainly a typo. */
        const val MIN_FRAGMENT_SIZE: Int = 256

        /**
         * The 2-byte frame size field bounds a frame, and the encrypted form is
         * larger than the plaintext it carries, so a plaintext fragment at the
         * 16-bit limit could not be framed once TLS overhead is added.
         */
        const val MAX_FRAGMENT_SIZE: Int = 65_000
    }
}

/**
 * Who the head unit says it is.
 *
 * Every field is nullable because every one of them is `optional` in the
 * protobuf and several are marked `deprecated`; a unit that fills in none of
 * them is legal and must still get a session.
 */
data class HeadUnitIdentity(
    val make: String? = null,
    val model: String? = null,
    val year: String? = null,
    val softwareBuild: String? = null,
    val softwareVersion: String? = null,
) {

    /** What the user sees in the log export and what they match a profile on. */
    fun describe(): String = buildString {
        append(make ?: "unknown make")
        append(' ')
        append(model ?: "unknown model")
        year?.let { append(" ($it)") }
        softwareVersion?.let { append(" sw $it") }
        softwareBuild?.let { append(" build $it") }
    }

    companion object {

        /**
         * Reads the identity out of a `ServiceDiscoveryResponse`.
         *
         * The response carries the same five facts twice: as top-level fields 2,
         * 3, 4, 7-10 — all marked `deprecated` — and again inside
         * `headunit_info` (field 17), whose own fields are not deprecated
         * (`core-protocol/src/main/proto/aap_protobuf/service/control/message/ServiceDiscoveryResponse.proto`
         * L13-L27, `.../HeadUnitInfo.proto` L5-L13). The nested copy is
         * preferred and the deprecated ones are the fallback, because an older
         * unit may fill only the latter and a newer one only the former.
         *
         * `make`/`model` describe the *vehicle*; `head_unit_make`/`_model`
         * describe the radio. Quirks belong to the radio, so those win when they
         * are present — a Chevrolet Infotainment 3 head unit behaves the same in
         * a Malibu and in a Traverse.
         */
        @Suppress("DEPRECATION")
        fun from(response: ServiceDiscoveryResponse): HeadUnitIdentity {
            val info = if (response.hasHeadunitInfo()) response.headunitInfo else null

            fun pick(nested: String?, nestedHu: String?, top: String?, topHu: String?): String? =
                listOfNotNull(nestedHu, topHu, nested, top).firstOrNull { it.isNotBlank() }

            return HeadUnitIdentity(
                make = pick(
                    info?.takeIf { it.hasMake() }?.make,
                    info?.takeIf { it.hasHeadUnitMake() }?.headUnitMake,
                    response.takeIf { it.hasMake() }?.make,
                    response.takeIf { it.hasHeadUnitMake() }?.headUnitMake,
                ),
                model = pick(
                    info?.takeIf { it.hasModel() }?.model,
                    info?.takeIf { it.hasHeadUnitModel() }?.headUnitModel,
                    response.takeIf { it.hasModel() }?.model,
                    response.takeIf { it.hasHeadUnitModel() }?.headUnitModel,
                ),
                year = listOfNotNull(
                    info?.takeIf { it.hasYear() }?.year,
                    response.takeIf { it.hasYear() }?.year,
                ).firstOrNull { it.isNotBlank() },
                softwareBuild = listOfNotNull(
                    info?.takeIf { it.hasHeadUnitSoftwareBuild() }?.headUnitSoftwareBuild,
                    response.takeIf { it.hasHeadUnitSoftwareBuild() }?.headUnitSoftwareBuild,
                ).firstOrNull { it.isNotBlank() },
                softwareVersion = listOfNotNull(
                    info?.takeIf { it.hasHeadUnitSoftwareVersion() }?.headUnitSoftwareVersion,
                    response.takeIf { it.hasHeadUnitSoftwareVersion() }?.headUnitSoftwareVersion,
                ).firstOrNull { it.isNotBlank() },
            )
        }
    }
}

/**
 * One entry in the quirk file: a match pattern and the knobs it selects.
 *
 * Patterns are case-insensitive and support exactly one wildcard form — a
 * trailing `*` meaning "starts with". Full globbing was not implemented on
 * purpose: the strings being matched are short vendor names that a user has to
 * copy out of a log by eye, and a regex dialect they have to get right as well
 * is a way to make that fail silently.
 */
data class QuirkProfile(
    val makePattern: String = MATCH_ANY,
    val modelPattern: String = MATCH_ANY,
    val quirks: HeadUnitQuirks = HeadUnitQuirks.DEFAULT,
) {

    /**
     * How specific this profile is, for ranking competing matches.
     *
     * An exact model beats an exact make beats a wildcard, so a user's
     * "Chevrolet" plus "Infotainment 3" entry wins over their own
     * make-only catch-all without them having to think about file ordering.
     *
     * (Written without the literal wildcard-path spelling on purpose: a slash
     * followed by a star inside a KDoc opens a nested comment in Kotlin and
     * silently swallows the rest of the file.)
     */
    val specificity: Int
        get() = weight(makePattern) + weight(modelPattern) * 2

    fun matches(identity: HeadUnitIdentity): Boolean =
        matches(makePattern, identity.make) && matches(modelPattern, identity.model)

    companion object {
        const val MATCH_ANY: String = "*"

        private fun weight(pattern: String): Int = when {
            pattern == MATCH_ANY -> 0
            pattern.endsWith(MATCH_ANY) -> 1
            else -> 2
        }

        private fun matches(pattern: String, value: String?): Boolean {
            if (pattern == MATCH_ANY) return true
            // A unit that reported no make cannot match a pattern that names one.
            val actual = value?.trim()?.lowercase(Locale.ROOT) ?: return false
            val wanted = pattern.trim().lowercase(Locale.ROOT)
            return if (wanted.endsWith(MATCH_ANY)) {
                actual.startsWith(wanted.dropLast(1))
            } else {
                actual == wanted
            }
        }
    }
}

/**
 * The result of reading a quirk file: what was understood, and what was not.
 *
 * [warnings] is not decoration. A hand-edited JSON file with a misspelled key
 * would otherwise apply silently as "no override", and the user would conclude
 * the knob does nothing rather than that they typed `maxFragmentsize`. The
 * warnings are surfaced in the settings screen and written into the log export.
 */
data class QuirkLoad(
    val profiles: List<QuirkProfile>,
    val warnings: List<String> = emptyList(),
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
}

/**
 * Reads and writes the per-head-unit quirk overrides in app storage.
 *
 * Parsed by hand with `org.json` rather than a serialization library: the file
 * is a handful of scalars, it is edited by humans on a phone with no build
 * tools, and every unknown or malformed field has to survive into [QuirkLoad
 * .warnings] rather than throw. A generated parser gives the opposite of all
 * three.
 *
 * ## Failure policy
 *
 * Nothing here throws. A missing file, unreadable storage, invalid JSON, a
 * negative fragment size — each degrades to the built-in default plus a warning.
 * The alternative is an app that will not connect to a car because of a stray
 * comma, which is precisely the situation the quirk file exists to rescue.
 */
class QuirkStore(
    private val file: File,
    /**
     * Profiles applied before the file's, i.e. lowest priority.
     *
     * Contains only the universal default. There is deliberately no shipped
     * entry for the target vehicle: CLAUDE.md says "do not hardcode
     * Malibu-specific behavior", and no real unit has been observed, so any
     * entry would encode a guess as though it were knowledge.
     */
    private val builtIn: List<QuirkProfile> = listOf(QuirkProfile()),
) {

    /** Reads the file. Cheap enough to call per session; not cached on purpose. */
    fun load(): QuirkLoad {
        if (!file.exists()) return QuirkLoad(builtIn)
        val text = try {
            file.readText()
        } catch (e: java.io.IOException) {
            return QuirkLoad(builtIn, listOf("could not read ${file.name}: ${e.message}"))
        }
        val parsed = parse(text)
        return QuirkLoad(builtIn + parsed.profiles, parsed.warnings)
    }

    /** The knobs to use for this head unit, with file overrides applied. */
    fun quirksFor(identity: HeadUnitIdentity): HeadUnitQuirks = resolve(load().profiles, identity)

    /** Overwrites the file. Used by the settings screen and by [writeTemplateIfAbsent]. */
    fun write(profiles: List<QuirkProfile>) {
        file.parentFile?.mkdirs()
        file.writeText(serialize(profiles))
    }

    /**
     * Writes a starting point a user can edit, if no file exists yet.
     *
     * The point is discoverability: a user told "edit the quirk file" needs a
     * file with the real key names in it, not a blank page and a wiki.
     */
    fun writeTemplateIfAbsent(): Boolean {
        if (file.exists()) return false
        write(listOf(QuirkProfile()))
        return true
    }

    /** Where the user will find the file, for display in the UI. */
    val path: String get() = file.absolutePath

    /**
     * Rewrites the universal (`*`/`*`) profile through [edit], leaving every
     * other profile alone.
     *
     * This exists because the file itself is not reachable. It lives in the
     * app's private storage, which no file manager can open without root, and
     * moving it to `Android/data` would not help either — DocumentsUI has
     * blocked navigation into that directory since Android 11. So the knobs a
     * user needs while sitting in a car, with no laptop, were documented and
     * unusable. The settings screen drives this instead, and the file stays as
     * the way to express anything more specific.
     */
    fun editUniversalProfile(edit: (HeadUnitQuirks) -> HeadUnitQuirks) {
        val existing = load().profiles.filter { it !in builtIn }
        val universal = existing.firstOrNull {
            it.makePattern == QuirkProfile.MATCH_ANY && it.modelPattern == QuirkProfile.MATCH_ANY
        }
        val updated = QuirkProfile(quirks = edit(universal?.quirks ?: HeadUnitQuirks.DEFAULT))
        write(existing.filterNot { it === universal } + updated)
    }

    /** The universal profile's knobs, for rendering the settings screen. */
    fun universalQuirks(): HeadUnitQuirks =
        load().profiles.lastOrNull {
            it.makePattern == QuirkProfile.MATCH_ANY && it.modelPattern == QuirkProfile.MATCH_ANY
        }?.quirks ?: HeadUnitQuirks.DEFAULT

    companion object {
        const val FILE_NAME: String = "head-unit-quirks.json"

        /** Schema version, so a future format change can be detected rather than misread. */
        const val SCHEMA_VERSION: Int = 2

        /**
         * The version in which `mediaAudioOverAap` still defaulted to false.
         *
         * A version-1 file was written by Headway itself, from the defaults of
         * the build that wrote it, and every one of them therefore pins
         * `mediaAudioOverAap: false`. That was a default nobody chose, and ADR
         * 0005 reversed it after a real car proved A2DP goes silent while
         * projecting — so honouring the stored value means the driver's music
         * stays broken until they hand-edit a JSON file they have never seen.
         *
         * So on a version-1 file that one key is ignored and the new default
         * applies. Every other key is a real choice and is honoured.
         */
        const val MEDIA_ROUTE_DEFAULT_CHANGED_IN: Int = 2

        private const val KEY_VERSION = "version"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_MAKE = "make"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_FRAGMENT_SIZE = "maxFragmentSize"
        private const val KEY_ANNOUNCED_VERSION = "announcedVersion"
        private const val KEY_MEDIA_AUDIO_OVER_AAP = "mediaAudioOverAap"
        private const val KEY_KEYFRAME_INTERVAL = "keyframeIntervalFrames"
        private const val KEY_HIDDEN_SSID = "hiddenSsid"
        private const val KEY_ANNOUNCE_WIFI_CHANNEL = "announceWifiChannel"
        private const val KEY_PIN_BSSID = "pinBssid"
        private const val KEY_CERTIFICATE = "certificate"
        private const val KEY_SUGGEST_CAR_NETWORK = "suggestCarNetwork"
        private const val KEY_TOUCH = "touch"

        private const val KEY_INVERT_X = "invertX"
        private const val KEY_INVERT_Y = "invertY"
        private const val KEY_SWAP_AXES = "swapAxes"
        private const val KEY_SCALE_X = "scaleX"
        private const val KEY_SCALE_Y = "scaleY"
        private const val KEY_OFFSET_X = "offsetX"
        private const val KEY_OFFSET_Y = "offsetY"
        private const val KEY_USE_TOUCHSCREEN_GEOMETRY = "useTouchscreenGeometry"

        /**
         * Keys a profile may contain. Anything else is reported as unknown.
         *
         * **Every key `serialize` writes must be in here.** A key that is
         * written but not listed makes Headway warn about its own output: the
         * user sets a documented knob, and the log tells them it was ignored.
         * `HeadUnitQuirksTest` asserts the two sets agree, because this has
         * already happened once — `certificate` shipped without being listed.
         */
        private val PROFILE_KEYS = setOf(
            KEY_MAKE, KEY_MODEL, KEY_MAX_FRAGMENT_SIZE, KEY_ANNOUNCED_VERSION,
            KEY_MEDIA_AUDIO_OVER_AAP, KEY_KEYFRAME_INTERVAL, KEY_TOUCH,
            KEY_HIDDEN_SSID, KEY_PIN_BSSID, KEY_ANNOUNCE_WIFI_CHANNEL,
            KEY_CERTIFICATE, KEY_SUGGEST_CAR_NETWORK,
        )

        /** Exposed so a test can prove [PROFILE_KEYS] covers everything written. */
        internal val knownProfileKeys: Set<String> get() = PROFILE_KEYS

        private val TOUCH_KEYS = setOf(
            KEY_INVERT_X, KEY_INVERT_Y, KEY_SWAP_AXES, KEY_SCALE_X, KEY_SCALE_Y,
            KEY_OFFSET_X, KEY_OFFSET_Y, KEY_USE_TOUCHSCREEN_GEOMETRY,
        )

        /** The store backed by the app's private storage. */
        fun inAppStorage(context: Context): QuirkStore =
            QuirkStore(File(context.filesDir, FILE_NAME))

        /**
         * Picks the winning profile.
         *
         * Highest [QuirkProfile.specificity] wins; among equals the last one
         * listed wins, so a user appending a line to the file overrides an
         * earlier line rather than being silently ignored by it.
         */
        fun resolve(profiles: List<QuirkProfile>, identity: HeadUnitIdentity): HeadUnitQuirks {
            var best: QuirkProfile? = null
            for (profile in profiles) {
                if (!profile.matches(identity)) continue
                if (best == null || profile.specificity >= best.specificity) best = profile
            }
            return best?.quirks ?: HeadUnitQuirks.DEFAULT
        }

        /** Parses the document body. Public so the settings screen can validate before saving. */
        fun parse(text: String): QuirkLoad {
            val warnings = mutableListOf<String>()
            val root = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return QuirkLoad(emptyList(), listOf("not valid JSON: ${e.message}"))
            }

            val version = root.optInt(KEY_VERSION, SCHEMA_VERSION)
            if (version < MEDIA_ROUTE_DEFAULT_CHANGED_IN) {
                warnings += "file predates ADR 0005; ignoring its '$KEY_MEDIA_AUDIO_OVER_AAP' " +
                    "so media audio takes this build's default (${HeadUnitQuirks.DEFAULT.mediaAudioOverAap})"
            }
            if (version != SCHEMA_VERSION) {
                // Parsed anyway: refusing outright would strand a user whose file
                // is fine apart from a number they typed from an old README.
                warnings += "file declares version $version, this build understands $SCHEMA_VERSION"
            }
            for (key in root.keys()) {
                if (key != KEY_VERSION && key != KEY_PROFILES) {
                    warnings += "ignored unknown top-level key '$key'"
                }
            }

            val array: JSONArray = root.optJSONArray(KEY_PROFILES)
                ?: return QuirkLoad(emptyList(), warnings + "no '$KEY_PROFILES' array")

            val honourMediaRoute = version >= MEDIA_ROUTE_DEFAULT_CHANGED_IN
            val profiles = mutableListOf<QuirkProfile>()
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index)
                if (entry == null) {
                    warnings += "profile #$index is not an object"
                    continue
                }
                profiles += parseProfile(entry, index, warnings, honourMediaRoute)
            }
            return QuirkLoad(profiles, warnings)
        }

        /** Renders profiles back to the on-disk form. Round-trips with [parse]. */
        fun serialize(profiles: List<QuirkProfile>): String {
            val array = JSONArray()
            for (profile in profiles) {
                val quirks = profile.quirks
                val touch = quirks.touch
                val json = JSONObject()
                    .put(KEY_MAKE, profile.makePattern)
                    .put(KEY_MODEL, profile.modelPattern)
                    .put(KEY_MAX_FRAGMENT_SIZE, quirks.maxFragmentSize)
                    .put(
                        KEY_ANNOUNCED_VERSION,
                        "${quirks.announcedVersionMajor}.${quirks.announcedVersionMinor}",
                    )
                    .put(KEY_MEDIA_AUDIO_OVER_AAP, quirks.mediaAudioOverAap)
                    .put(KEY_KEYFRAME_INTERVAL, quirks.keyframeIntervalFrames)
                    .put(KEY_HIDDEN_SSID, quirks.hiddenSsid)
                    .put(KEY_ANNOUNCE_WIFI_CHANNEL, quirks.announceWifiChannel)
                    .put(KEY_SUGGEST_CAR_NETWORK, quirks.suggestCarNetwork)
                    .apply {
                        // Omitted when automatic: an absent key is what "let
                        // Headway alternate" looks like, and writing `false`
                        // would silently pin the template to SSID-only matching.
                        quirks.pinBssid?.let { put(KEY_PIN_BSSID, it) }
                        // Same reasoning: absent means "start at the first
                        // candidate and rotate on rejection".
                        quirks.certificate?.let { put(KEY_CERTIFICATE, it) }
                    }
                    .put(
                        KEY_TOUCH,
                        JSONObject()
                            .put(KEY_INVERT_X, touch.invertX)
                            .put(KEY_INVERT_Y, touch.invertY)
                            .put(KEY_SWAP_AXES, touch.swapAxes)
                            .put(KEY_SCALE_X, touch.scaleX)
                            .put(KEY_SCALE_Y, touch.scaleY)
                            .put(KEY_OFFSET_X, touch.offsetX)
                            .put(KEY_OFFSET_Y, touch.offsetY)
                            .put(KEY_USE_TOUCHSCREEN_GEOMETRY, touch.useTouchscreenGeometry),
                    )
                array.put(json)
            }
            return JSONObject()
                .put(KEY_VERSION, SCHEMA_VERSION)
                .put(KEY_PROFILES, array)
                .toString(2)
        }

        private fun parseProfile(
            json: JSONObject,
            index: Int,
            warnings: MutableList<String>,
            honourMediaRoute: Boolean = true,
        ): QuirkProfile {
            for (key in json.keys()) {
                if (key !in PROFILE_KEYS) warnings += "profile #$index: ignored unknown key '$key'"
            }

            val defaults = HeadUnitQuirks.DEFAULT
            val fragment = json.optIntChecked(
                KEY_MAX_FRAGMENT_SIZE, defaults.maxFragmentSize, index, warnings,
            ).coerceOrWarn(
                HeadUnitQuirks.MIN_FRAGMENT_SIZE,
                HeadUnitQuirks.MAX_FRAGMENT_SIZE,
                KEY_MAX_FRAGMENT_SIZE, defaults.maxFragmentSize, index, warnings,
            )

            var major = defaults.announcedVersionMajor
            var minor = defaults.announcedVersionMinor
            if (json.has(KEY_ANNOUNCED_VERSION)) {
                val raw = json.optString(KEY_ANNOUNCED_VERSION)
                val parts = raw.split('.')
                val parsedMajor = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val parsedMinor = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (parts.size != 2 || parsedMajor == null || parsedMinor == null) {
                    warnings += "profile #$index: '$KEY_ANNOUNCED_VERSION' should look like " +
                        "\"1.6\", got '$raw'; using $major.$minor"
                } else {
                    major = parsedMajor
                    minor = parsedMinor
                }
            }

            val keyframe = json.optIntChecked(
                KEY_KEYFRAME_INTERVAL, defaults.keyframeIntervalFrames, index, warnings,
            ).coerceOrWarn(
                1, 600, KEY_KEYFRAME_INTERVAL, defaults.keyframeIntervalFrames, index, warnings,
            )

            val touchJson = json.optJSONObject(KEY_TOUCH)
            val touch = if (touchJson == null) TouchQuirks() else {
                for (key in touchJson.keys()) {
                    if (key !in TOUCH_KEYS) {
                        warnings += "profile #$index: ignored unknown touch key '$key'"
                    }
                }
                TouchQuirks(
                    invertX = touchJson.optBoolean(KEY_INVERT_X, false),
                    invertY = touchJson.optBoolean(KEY_INVERT_Y, false),
                    swapAxes = touchJson.optBoolean(KEY_SWAP_AXES, false),
                    scaleX = touchJson.optDouble(KEY_SCALE_X, 1.0).finiteOr(1.0),
                    scaleY = touchJson.optDouble(KEY_SCALE_Y, 1.0).finiteOr(1.0),
                    offsetX = touchJson.optDouble(KEY_OFFSET_X, 0.0).finiteOr(0.0),
                    offsetY = touchJson.optDouble(KEY_OFFSET_Y, 0.0).finiteOr(0.0),
                    useTouchscreenGeometry =
                        touchJson.optBoolean(KEY_USE_TOUCHSCREEN_GEOMETRY, false),
                )
            }

            return QuirkProfile(
                makePattern = json.optString(KEY_MAKE, QuirkProfile.MATCH_ANY)
                    .ifBlank { QuirkProfile.MATCH_ANY },
                modelPattern = json.optString(KEY_MODEL, QuirkProfile.MATCH_ANY)
                    .ifBlank { QuirkProfile.MATCH_ANY },
                quirks = HeadUnitQuirks(
                    maxFragmentSize = fragment,
                    announcedVersionMajor = major,
                    announcedVersionMinor = minor,
                    mediaAudioOverAap =
                        if (honourMediaRoute) {
                            json.optBoolean(KEY_MEDIA_AUDIO_OVER_AAP, defaults.mediaAudioOverAap)
                        } else {
                            // See MEDIA_ROUTE_DEFAULT_CHANGED_IN.
                            defaults.mediaAudioOverAap
                        },
                    keyframeIntervalFrames = keyframe,
                    hiddenSsid = json.optBoolean(KEY_HIDDEN_SSID, defaults.hiddenSsid),
                    announceWifiChannel = json.optBoolean(
                        KEY_ANNOUNCE_WIFI_CHANNEL, defaults.announceWifiChannel,
                    ),
                    pinBssid =
                        if (json.has(KEY_PIN_BSSID)) json.optBoolean(KEY_PIN_BSSID, false)
                        else defaults.pinBssid,
                    certificate =
                        json.optString(KEY_CERTIFICATE, "").ifBlank { null }
                            ?: defaults.certificate,
                    suggestCarNetwork = json.optBoolean(
                        KEY_SUGGEST_CAR_NETWORK, defaults.suggestCarNetwork,
                    ),
                    touch = touch,
                ),
            )
        }

        /**
         * `optInt` returns the fallback for a non-numeric value as well as for a
         * missing one, which would hide `"maxFragmentSize": "big"` completely.
         */
        private fun JSONObject.optIntChecked(
            key: String,
            fallback: Int,
            index: Int,
            warnings: MutableList<String>,
        ): Int {
            if (!has(key)) return fallback
            val value = opt(key)
            val number = (value as? Number)?.toInt() ?: (value as? String)?.trim()?.toIntOrNull()
            if (number == null) {
                warnings += "profile #$index: '$key' is not a number ('$value'); using $fallback"
                return fallback
            }
            return number
        }

        private fun Int.coerceOrWarn(
            min: Int,
            max: Int,
            key: String,
            fallback: Int,
            index: Int,
            warnings: MutableList<String>,
        ): Int {
            if (this in min..max) return this
            warnings += "profile #$index: '$key' = $this is outside $min..$max; using $fallback"
            return fallback
        }

        /** `optDouble` yields NaN for garbage, and NaN in a coordinate transform is silent death. */
        private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback
    }
}
