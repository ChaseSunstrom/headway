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

package dev.headway.app.link

import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings

/**
 * Getting the car's access point saved on the phone, so its MAC stops changing.
 *
 * ## The problem this exists for
 *
 * A `WifiNetworkSpecifier` connection — the one [CarWifiNetwork.join] makes —
 * presents a **new MAC address on every single association** on GrapheneOS, and
 * nothing an app or a user can do changes that. The chain, verified against
 * source rather than assumed:
 *
 * - `WifiNetworkFactory.handleConnectToNetworkUserSelectionInternal()` builds
 *   `new WifiConfiguration(specifier.wifiConfiguration)`
 *   (`WifiNetworkFactory.java` L1160-L1161), so the configuration originates in
 *   the *requesting app's* process.
 * - In Headway's process, GrapheneOS's default for that field is
 *   `RANDOMIZATION_ALWAYS` (= 100, a GrapheneOS-only value,
 *   `WifiConfiguration.java` L1913), which re-randomizes on every connect —
 *   not merely per network, as AOSP's persistent default does.
 * - `WifiNetworkFactory` never touches the field, and
 *   `WifiNetworkSpecifier.Builder` has no setter for it.
 * - GrapheneOS Settings' per-network Privacy control is gated on `isSaved()`,
 *   and a specifier network is not a saved network — so the user cannot reach
 *   it either.
 *
 * A head unit therefore sees a brand new device on every attempt.
 *
 * ## Why Google's Android Auto does not hit this
 *
 * Because GrapheneOS carved it out, for this exact bug
 * (`WifiConfiguration.java` L3400-L3405):
 *
 * ```java
 * if (android.app.compat.gms.GmsCompat.isAndroidAuto()) {
 *     // Per-connection MAC randomization doesn't work with some cars, see
 *     // https://github.com/GrapheneOS/os-issue-tracker/issues/4139
 *     macRandomizationSetting = RANDOMIZATION_PERSISTENT;
 *     mIsSendDhcpHostnameEnabled = true;
 * }
 * ```
 *
 * Note it is **two** changes. GrapheneOS also defaults
 * `mIsSendDhcpHostnameEnabled` to false where AOSP defaults it true, and flips
 * it back for Android Auto — so GrapheneOS judged that a stable MAC alone was
 * not enough. `setSendDhcpHostnameEnabled` is `@SystemApi` behind
 * `NETWORK_SETTINGS`/`NETWORK_SETUP_WIZARD` and has no equivalent on
 * `WifiNetworkSuggestion.Builder`, so **Headway cannot do that half at all**.
 * See BLOCKERS.md B-006.
 *
 * ## What this class can do
 *
 * Get the network *saved*, which is what puts both toggles in front of the
 * user, and ask for a persistent MAC where the API allows it. Two routes:
 *
 * - [addNetworksIntent] hands the credentials to the system's own add-networks
 *   panel. One tap, and the user never types an SSID or a passphrase.
 * - [suggest] registers the same credentials as a network suggestion, which is
 *   the only public API that carries a MAC randomization preference. It is not
 *   the default path — see [suggest] for why.
 */
object CarWifiProvisioning {

    /**
     * A suggestion for the car's access point, asking for a stable MAC.
     *
     * `setBssid` is deliberately **not** called. A suggestion pinned to one
     * BSSID matches only that BSS, and this vehicle presents two BSSIDs for one
     * SSID (`ce:22:26:bf:18:ec` and `ce:44:26:bf:18:ec`) of which only one
     * carries projection — pinning the wrong one silently matches nothing, and
     * the whole point of saving the network is that it be found without
     * Headway's help.
     *
     * `RANDOMIZATION_PERSISTENT` is per-network rather than per-connection:
     * the same MAC every time for this SSID, a different one for every other
     * network. It is the strongest stability the public API offers —
     * `WifiNetworkSuggestion` has only `RANDOMIZATION_PERSISTENT` and
     * `RANDOMIZATION_NON_PERSISTENT`, with no "use the device MAC" option
     * (`WifiConfiguration.setMacRandomizationSetting`, which does have one, is
     * `@SystemApi` and throws for a normal app).
     */
    fun suggestionFor(credentials: CarCredentials): WifiNetworkSuggestion =
        WifiNetworkSuggestion.Builder()
            .setSsid(credentials.ssid)
            .apply { if (credentials.passphrase.isNotEmpty()) setWpa2Passphrase(credentials.passphrase) }
            .setIsHiddenSsid(credentials.hiddenSsid)
            .setMacRandomizationSetting(WifiNetworkSuggestion.RANDOMIZATION_PERSISTENT)
            // The car AP has no internet and never will. Saying so up front is
            // not cosmetic: an unmetered-looking network that fails validation
            // gets deprioritised, and marking it untrusted would be worse.
            .setIsMetered(false)
            .build()

    /**
     * An intent that asks the system to save the car's network.
     *
     * `Settings.ACTION_WIFI_ADD_NETWORKS` shows a system panel listing the
     * networks to save and a Save button. Verified present and public in
     * android-35 (`javap android.provider.Settings`: `ACTION_WIFI_ADD_NETWORKS`,
     * `EXTRA_WIFI_NETWORK_LIST`, `EXTRA_WIFI_NETWORK_RESULT_LIST`,
     * `ADD_WIFI_RESULT_SUCCESS`/`ALREADY_EXISTS`/`ADD_OR_UPDATE_FAILED`), not
     * `@SystemApi`, and carrying no `@RequiresPermission`.
     *
     * **This must be launched from an Activity, never from the service.** It is
     * an activity intent, and Android's background-activity-launch restrictions
     * would drop it from a service — the real-car log shows `MainActivity`
     * going through `onPause` while a join is in flight, so there is no moment
     * mid-connect when launching it would be reliable.
     *
     * It is honest to call this a one-time setup step rather than a fix: it
     * does not connect anything by itself, and what it produces is a saved
     * network on DHCP. Its value is that the saved network is the only place
     * GrapheneOS exposes the per-network MAC and DHCP-hostname controls, and
     * that the user does not have to transcribe a passphrase they never see.
     */
    fun addNetworksIntent(credentials: CarCredentials): Intent =
        Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
            Settings.EXTRA_WIFI_NETWORK_LIST,
            arrayListOf(suggestionFor(credentials)),
        )

    /** What the add-networks panel said, as something worth logging. */
    fun describeResult(resultCode: Int, results: List<Int>): String = when {
        results.isEmpty() -> "the Wi-Fi panel closed without saying what it did " +
            "(result code $resultCode). Check Android's Wi-Fi settings for the car's " +
            "network"
        results.all { it == Settings.ADD_WIFI_RESULT_SUCCESS } ->
            "the car's network is now saved on this phone"
        results.all { it == Settings.ADD_WIFI_RESULT_ALREADY_EXISTS } ->
            "the car's network was already saved, which is just as good"
        results.any { it == Settings.ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED } ->
            "Android refused to save the car's network. Join it by hand in Wi-Fi " +
                "settings instead"
        else -> "the Wi-Fi panel returned $results"
    }

    /**
     * Registers the car as a network suggestion, for a stable MAC.
     *
     * **Not the default path, and off unless the quirk file turns it on.** The
     * costs are real and were established before writing this, not after:
     *
     * - The first suggestion needs the user to accept a notification
     *   (`STATUS_SUGGESTION_APPROVAL_PENDING`), and a refusal is **sticky** —
     *   `STATUS_SUGGESTION_APPROVAL_REJECTED_BY_USER` leads to
     *   `STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED`, which stays until
     *   the user digs into Settings → Apps → Special app access → Wi-Fi
     *   control. One mis-tap in a moving car disables the feature.
     * - The platform decides when to associate. There is no on-demand connect
     *   and no bound on how long it takes, which sits badly with a 15-second
     *   reconnect target.
     * - It hands back no `Network`, so [CarWifiNetwork.adoptExistingCarNetwork]
     *   becomes the load-bearing acquisition path rather than a fallback.
     * - The car AP never validates, so sockets still have to be bound to it,
     *   and a saved network that fails validation can be auto-disabled.
     * - It does nothing about the DHCP hostname half of GrapheneOS's fix.
     *
     * So this ships as something to try, with the evidence for whether it helps
     * coming from a log rather than from confidence.
     *
     * @return null on success, or why it was refused.
     */
    fun suggest(wifiManager: WifiManager?, credentials: CarCredentials): String? {
        val manager = wifiManager ?: return "no WifiManager on this device"
        val suggestion = runCatching { suggestionFor(credentials) }
            .getOrElse { return "could not build a suggestion for ${credentials.ssid}: ${it.message}" }

        // Replace rather than accumulate. Suggestions persist across restarts,
        // and re-adding an identical one returns
        // STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE, which would read as a
        // failure on every subsequent connect.
        runCatching { manager.removeNetworkSuggestions(listOf(suggestion)) }

        val status = runCatching { manager.addNetworkSuggestions(listOf(suggestion)) }
            .getOrElse { return "Android refused the suggestion: ${it.message}" }
        return statusMessage(status)
    }

    /** Forgets Headway's suggestions, so turning the setting off really does. */
    fun withdraw(wifiManager: WifiManager?, credentials: CarCredentials) {
        val manager = wifiManager ?: return
        runCatching { manager.removeNetworkSuggestions(listOf(suggestionFor(credentials))) }
    }

    /** Null when the status means success. */
    internal fun statusMessage(status: Int): String? = when (status) {
        WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> null
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> null
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
            "this phone has Headway blocked from suggesting Wi-Fi networks, which " +
                "happens after the suggestion notification is dismissed or refused once. " +
                "Turn it back on in Settings → Apps → Special app access → Wi-Fi control"
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
            "too many Wi-Fi suggestions from Headway already"
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED ->
            "Android does not allow Headway to suggest Wi-Fi networks here"
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID ->
            "Android rejected the car's credentials as an invalid network suggestion"
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL ->
            "Android hit an internal error registering the suggestion"
        // Printed rather than mapped: a code this build has no name for is a
        // code from a newer platform, and the number is what makes it findable.
        else -> "the suggestion was refused with status $status"
    }

    /**
     * The parts of the car's credentials this file needs.
     *
     * Deliberately not `CarNetworkCredentials` from `core-transport`: nothing
     * here needs the endpoint, the security enum or the advertised frequencies,
     * and a narrower type keeps the passphrase from travelling further than it
     * has to.
     */
    data class CarCredentials(
        val ssid: String,
        val passphrase: String,
        val hiddenSsid: Boolean = false,
    )
}
