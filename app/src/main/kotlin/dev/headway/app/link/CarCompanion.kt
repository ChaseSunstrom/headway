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

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.SharedPreferences
import android.net.MacAddress
import dev.headway.app.log.SessionLog
import java.util.regex.Pattern

/**
 * Pairs Headway with the car's access point, so joining it needs no approval sheet.
 *
 * ## The problem this solves
 *
 * `WifiNetworkSpecifier` is the only unprivileged way to join a named network,
 * and it costs a system approval sheet that a human has to tap. Once would be
 * survivable. It is not survivable as the thing standing between the driver and
 * every reconnection, and it is flatly incompatible with what CLAUDE.md asks
 * for — reconnecting "within 15 seconds of the car being available, without
 * user interaction". A phone in a pocket cannot tap a sheet.
 *
 * ## The route, from AOSP source
 *
 * `WifiNetworkFactory` has a sanctioned bypass and it is not a hack:
 *
 * ```java
 * private boolean isAccessPointApprovedInCompanionDeviceManager(
 *         @NonNull MacAddress bssid, @NonNull UserHandle requestorUserHandle,
 *         @NonNull String requestorPackageName) {
 *     ...
 *     boolean approved = mCompanionDeviceManager.isDeviceAssociatedForWifiConnection(
 *             requestorPackageName, bssid, requestorUserHandle);
 * ```
 *
 * (`WifiNetworkFactory.java` L1862-L1877.) It is consulted first in
 * `isAccessPointApprovedForActiveRequest` (L1880-L1899), which
 * `triggerConnectIfUserApprovedMatchFound` (L1955-L1998) uses to decide whether
 * to connect straight away. When it returns true and the request names a single
 * access point, the factory calls `handleConnectToNetworkUserSelectionInternal`
 * directly and **`startUi()` is never reached** (`needNetworkFor` L932-L947).
 *
 * So: associate the car's Wi-Fi BSSID with `CompanionDeviceManager` once, and
 * every later `WifiNetworkSpecifier` request naming that BSSID joins silently.
 *
 * ## Why this is in bounds
 *
 * `CompanionDeviceManager.associate` needs no permission. Its
 * `@RequiresPermission` is `conditional = true`, and the condition is having
 * called `setDeviceProfile` — which this class deliberately never does. The
 * consent *is* the system chooser: the user picks the car from a list the
 * platform built, and the platform records the association against Headway's
 * package. Nothing here is `@SystemApi`, `@hide` or privileged —
 * `WifiDeviceFilter.Builder.setBssid(MacAddress)`,
 * `AssociationRequest.Builder.addDeviceFilter`,
 * `CompanionDeviceManager.associate/getMyAssociations/disassociate` and
 * `AssociationInfo.getDeviceMacAddress` are all in `android-35/android.jar`.
 * `setDeviceProfile` is **required not to be called**, which is stronger than
 * "not needed". `PermissionsUtils.enforcePermissionForRequestingProfile`
 * short-circuits on `if (deviceProfile == null) return;` — every *named*
 * profile maps to a `REQUEST_COMPANION_PROFILE_*` permission, and the ones that
 * would suit a car (`APP_STREAMING`, `AUTOMOTIVE_PROJECTION`) are the
 * privileged ones ADR 0004 rejected. A profile-less association asks for
 * nothing and satisfies the Wi-Fi check completely.
 *
 * `setSelfManaged` is likewise never called, and that one is load-bearing:
 * `AssociationInfo.isLinkedTo` opens with `if (mSelfManaged) return false;`, so
 * a self-managed association can never satisfy the bypass.
 *
 * (An earlier version of this comment said all device profiles are privileged.
 * They are not — `DEVICE_PROFILE_WATCH` and `..._GLASSES` map to permissions
 * with `protectionLevel="normal"`. Neither is right for a car, but the blanket
 * claim was wrong.)
 *
 * ## The BSSID is the point, not a detail
 *
 * The bypass is keyed on the BSSID **in the request**. That is also why the
 * association is worth more than the sheet it removes: the chooser runs a live
 * scan with the *system's* permissions and hands back the access point the car
 * is actually broadcasting — where the BSSID the head unit names over Bluetooth
 * may be its other radio. This car has been seen on both `ce:22:26:bf:18:ec`
 * and `ce:44:26:bf:18:ec` while announcing only the second. Pairing replaces a
 * value Headway was told with one the phone observed.
 *
 * ## What is stored, and where
 *
 * The association itself lives in the platform and survives reboots and app
 * updates — `PackageMonitor` routes a replace to `onPackageUpdateStarted`, not
 * to `onPackageRemoved`, so an update never reaches the disassociate path. It
 * does **not** survive uninstall or "Clear storage", both of which
 * `CompanionDeviceManagerService` treats as a removal.
 *
 * There is no Settings page that lists it. Searching the Settings manifest for
 * a companion-device surface finds only an unrelated notification-access
 * dialog, so [forget] — surfaced as the Unpair button — is in practice the only
 * discoverable way to revoke it. An earlier version of this file told users to
 * look under Settings, Connected devices; that was wrong.
 *
 * Headway keeps only a map from SSID to BSSID in its own preferences so it
 * knows which association belongs to which car, and [associatedBssid] re-checks
 * the platform on every read — an association that has gone must stop being
 * used immediately, not at the next restart.
 */
class CarCompanion(private val context: Context) {

    private val manager: CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether this device has the companion-device subsystem at all. */
    val available: Boolean get() = manager != null

    /**
     * The BSSID Headway is paired with for [ssid], or null.
     *
     * Re-derived from the platform's own association list on every call rather
     * than trusted from storage. A user who revokes the pairing in Settings has
     * revoked it; continuing to pin a BSSID on the strength of a stale
     * preference would turn a working SSID-only join into a silent non-match,
     * which is precisely the failure this class exists to end.
     */
    fun associatedBssid(ssid: String): String? {
        val remembered = prefs.getString(keyFor(ssid), null) ?: return null
        val live = associations().any { info ->
            info.deviceMacAddress?.toString().equals(remembered, ignoreCase = true)
        }
        if (!live) {
            // Forgotten rather than reported: the next join simply goes back to
            // matching on the SSID alone, which works.
            prefs.edit().remove(keyFor(ssid)).apply()
            SessionLog.shared.info(
                TAG,
                "the companion pairing for '$ssid' is gone from Settings; " +
                    "matching on the SSID alone again",
            )
            return null
        }
        return remembered
    }

    /** Every association Headway holds, or empty when the API is unavailable. */
    fun associations(): List<AssociationInfo> = runCatching {
        manager?.myAssociations.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * The request that opens the chooser for one car's access point.
     *
     * @param ssid the network name, used as the chooser's filter and its label.
     * @param bssid the BSSID the head unit announced, when there is one. Passed
     *   as a *hint* only — it narrows the chooser to one row when the announced
     *   radio is the one on the air, and is omitted when it is not usable so the
     *   chooser can still show whatever the car is actually broadcasting.
     *
     * `setSingleDevice(false)`: the announced BSSID may be the wrong radio, and
     * a single-device request that matched nothing would present an empty
     * chooser with no way for the driver to pick the access point that *is*
     * there.
     */
    fun requestFor(ssid: String, bssid: String? = null): AssociationRequest {
        val filter = WifiDeviceFilter.Builder()
            // Quoted: an SSID is user-visible text and may contain regex
            // metacharacters. `myChevrolet1189` would be fine; `Bob's Car (5G)`
            // would not.
            .setNamePattern(Pattern.compile(Pattern.quote(ssid)))
            .apply {
                CarWifiNetwork.parseBssid(bssid.orEmpty())?.let { setBssid(it) }
            }
            .build()
        return AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .setDisplayName(ssid)
            .build()
    }

    /**
     * Records a completed association.
     *
     * @param info what `CompanionDeviceManager.EXTRA_ASSOCIATION` carried back.
     * @return the BSSID stored, or null when the association named no MAC —
     *   which should not happen for a Wi-Fi filter and is reported rather than
     *   assumed away.
     */
    fun remember(ssid: String, info: AssociationInfo): String? {
        val mac: MacAddress = info.deviceMacAddress ?: run {
            SessionLog.shared.warn(
                TAG,
                "the companion association for '$ssid' carries no MAC address, so it " +
                    "cannot be used to skip the Wi-Fi approval sheet",
            )
            return null
        }
        val bssid = mac.toString()
        prefs.edit().putString(keyFor(ssid), bssid).apply()
        SessionLog.shared.info(
            TAG,
            "paired with '$ssid' at $bssid; joining it will no longer ask for approval",
        )
        return bssid
    }

    /** Drops the pairing, in the platform and here. */
    fun forget(ssid: String) {
        val remembered = prefs.getString(keyFor(ssid), null)
        prefs.edit().remove(keyFor(ssid)).apply()
        if (remembered == null) return
        associations()
            .filter { it.deviceMacAddress?.toString().equals(remembered, ignoreCase = true) }
            .forEach { info -> runCatching { manager?.disassociate(info.id) } }
        SessionLog.shared.info(TAG, "unpaired from '$ssid'")
    }

    /** One line for the setup screen. */
    fun describe(ssid: String?): String = when {
        manager == null -> "This phone has no companion-device support"
        ssid == null ->
            "Connect once so Headway learns the car's network name, then pair with it here"
        associatedBssid(ssid) != null ->
            "Paired with \"$ssid\" — joining it needs no approval"
        else -> "Not paired — Android will ask for approval on every connection"
    }

    /**
     * Whether pinning the BSSID is required for the pairing to do anything.
     *
     * It is, and this is the trap in the whole design.
     * `triggerConnectIfUserApprovedMatchFound` reads the BSSID out of the
     * *request*:
     *
     * ```java
     * MacAddress bssid = mActiveSpecificNetworkRequestSpecifier.bssidPatternMatcher.first;
     * ```
     *
     * An SSID-only request leaves that at `WifiManager.ALL_ZEROS_MAC_ADDRESS`,
     * so `isDeviceAssociatedForWifiConnection` is asked about
     * `00:00:00:00:00:00` and always answers false. A paired car whose request
     * is not pinned therefore gets the approval sheet back with no indication
     * why — the association is still there, still correct, and silently
     * bypassed.
     *
     * So a quirk file that forces `pinBssid = false` on a paired car is a
     * contradiction, and the caller logs it rather than quietly losing the
     * bypass.
     */
    fun pairingNeedsPinnedBssid(ssid: String, quirkPinBssid: Boolean?): String? {
        if (associatedBssid(ssid) == null) return null
        if (quirkPinBssid != false) return null
        return "the quirk file sets pinBssid=false, which stops the pairing for '$ssid' " +
            "working: Android looks the pairing up by the BSSID in the request, and an " +
            "SSID-only request carries none. Remove that quirk or unpair"
    }

    private fun keyFor(ssid: String) = KEY_PREFIX + ssid

    companion object {
        private const val TAG = "HeadwayCompanion"

        private const val PREFS_NAME = "headway_companion"
        private const val KEY_PREFIX = "bssid_"

        fun of(context: Context): CarCompanion = CarCompanion(context.applicationContext)
    }
}
