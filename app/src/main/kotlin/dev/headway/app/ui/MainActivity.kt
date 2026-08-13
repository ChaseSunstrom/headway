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

package dev.headway.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import dev.headway.app.BuildConfig
import dev.headway.app.input.HeadwayAccessibilityService
import dev.headway.app.log.SessionLog
import dev.headway.app.update.AppUpdater
import dev.headway.app.update.AvailableRelease
import dev.headway.app.update.ReleaseCatalog
import dev.headway.app.update.UpdateException
import dev.headway.app.quirks.QuirkStore
import dev.headway.app.service.HeadwayService
import dev.headway.transport.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persisted user choices, in one place so the two activities cannot disagree.
 *
 * `SharedPreferences` rather than DataStore: four scalars, no migration story
 * needed, and one fewer dependency in an app that has to be auditable for
 * F-Droid.
 */
object HeadwaySettings {

    const val PREFS_NAME: String = "headway"

    /** Set once the user has seen the driving-safety notice. */
    const val KEY_SAFETY_NOTICE_ACCEPTED: String = "safety_notice_accepted"

    /**
     * The optional restriction CLAUDE.md describes: "provide an optional
     * 'parked-only for video apps' toggle (off by default, user's choice — this
     * is a user-freedom project, not a nanny)".
     */
    const val KEY_PARKED_ONLY_VIDEO: String = "parked_only_video"

    /** Package names shown on the car launcher grid. */
    const val KEY_PINNED_APPS: String = "pinned_apps"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun parkedOnlyVideo(context: Context): Boolean =
        of(context).getBoolean(KEY_PARKED_ONLY_VIDEO, false)
}

/**
 * The phone-side setup screen.
 *
 * Everything the user has to do once before a car will work, in the order they
 * have to do it, with the state of each step visible: grant the runtime
 * permissions, turn on the accessibility service in Settings, accept the safety
 * notice, connect.
 *
 * ## Why the UI is built in code
 *
 * No Compose and no layout XML. Compose would add several megabytes and a
 * dependency tree to an app whose entire phone-side UI is a column of labels and
 * buttons, and F-Droid has to audit whatever is added. Building the views in
 * Kotlin also means this screen needs no new resources, so it does not collide
 * with the small hand-written `res/` the project already has.
 *
 * ## Accessibility cannot be granted from here
 *
 * There is no API for it and there must not be — a self-granting accessibility
 * service is a keylogger. The most an app may do is explain what it needs and
 * open the right Settings page, which is what [openAccessibilitySettings] does.
 * The explanation matters: a user who reads "Headway wants to observe your
 * actions and retrieve window content" in the system dialog, with no context,
 * should reasonably refuse. So the screen states plainly what Headway does with
 * it, and states the limitation the manifest actually enforces — Headway's
 * service is configured without window-content access.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusValue: TextView
    private lateinit var permissionRows: LinearLayout
    private lateinit var accessibilityValue: TextView
    private lateinit var connectButton: Button
    private lateinit var parkedOnlySwitch: SwitchCompat
    private lateinit var updateValue: TextView
    private lateinit var certificateValue: TextView
    private lateinit var updateButton: Button

    private var uiScope: CoroutineScope? = null

    /**
     * For the update download, which must outlive the activity being stopped.
     *
     * [uiScope] is cancelled in `onStop`, which is right for the state
     * collectors and wrong for a download: switching away for a moment — to
     * check the log viewer, say — killed a half-finished APK transfer. This one
     * lives until the activity is destroyed, which is also when its views stop
     * being safe to touch.
     */
    private var updateScope: CoroutineScope? = null

    /** Holds the certificate PEM between the two file picks. */
    private var pendingCertPem: String? = null

    private val pickKey =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val certPem = pendingCertPem
            pendingCertPem = null
            if (uri == null || certPem == null) {
                toast("Import cancelled")
                return@registerForActivityResult
            }
            val keyPem = readTextFrom(uri) ?: return@registerForActivityResult
            val store = dev.headway.app.link.PhoneCertificateStore.inAppStorage(this)
            val problem = store.store(certPem, keyPem)
            if (problem == null) {
                SessionLog.shared.info(TAG, "imported a phone certificate: ${store.describe()}")
                AlertDialog.Builder(this)
                    .setTitle("Certificate imported")
                    .setMessage(store.describe() + "\n\nEvery session from now on uses it.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Could not import that pair")
                    .setMessage(problem)
                    .setPositiveButton("OK", null)
                    .show()
            }
            refresh()
        }

    private val pickCertificate =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                toast("Import cancelled")
                return@registerForActivityResult
            }
            pendingCertPem = readTextFrom(uri) ?: return@registerForActivityResult
            toast("Now pick the matching private key (PKCS#8 .pem)")
            runCatching { pickKey.launch(arrayOf("*/*")) }
                .onFailure { toast("No file picker available") }
        }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            granted.filterValues { !it }.keys.forEach {
                SessionLog.shared.info(TAG, "permission denied: $it")
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        updateScope = CoroutineScope(Dispatchers.Main.immediate)
        if (!HeadwaySettings.of(this).getBoolean(HeadwaySettings.KEY_SAFETY_NOTICE_ACCEPTED, false)) {
            showSafetyNotice(firstRun = true)
        }
    }

    override fun onStart() {
        super.onStart()
        note("onStart")
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        uiScope = scope
        scope.launch {
            HeadwayService.linkState.collect { state -> showLinkState(state) }
        }
        scope.launch {
            // The user grants accessibility in another app, so the only way to
            // notice is to watch the binding rather than to poll.
            HeadwayAccessibilityService.instance.collect { refreshAccessibility() }
        }
    }

    override fun onResume() {
        super.onResume()
        note("onResume")
        // Permissions and the accessibility setting can both change while this
        // activity is stopped, because changing them means leaving it.
        refresh()
    }

    override fun onPause() {
        // Logged because a pause landing within a moment of the connect press
        // is Android's Wi-Fi approval prompt appearing over this activity --
        // and that, plus the stop/restart pattern around it, was the only
        // evidence available for diagnosing a real car's failed join. It came
        // from logcat, which a user cannot capture without adb; the in-app
        // export, which is the whole point of SessionLog, had none of it.
        note("onPause")
        super.onPause()
    }

    override fun onStop() {
        note("onStop — something opaque covered this activity, or the screen went off")
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        note("window focus ${if (hasFocus) "gained" else "lost"}")
    }

    override fun onDestroy() {
        note("onDestroy")
        updateScope?.cancel()
        updateScope = null
        super.onDestroy()
    }

    // --- layout -------------------------------------------------------------

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        column.addView(heading("Headway"))
        column.addView(
            body(
                "Casts this phone to a wireless Android Auto head unit. " +
                    "Nothing leaves the device and nothing needs the internet.",
            ),
        )

        column.addView(sectionTitle("Connection"))
        statusValue = body("Idle")
        column.addView(statusValue)
        connectButton = button("Connect to the car") { toggleConnection() }
        column.addView(connectButton)

        column.addView(sectionTitle("Permissions"))
        column.addView(
            body(
                "Headway asks for the minimum: Bluetooth to find the car and " +
                    "collect its Wi-Fi details, nearby devices to join that Wi-Fi, " +
                    "and notifications so the connection can stay alive while the " +
                    "screen is off. It never asks for location.",
            ),
        )
        permissionRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(permissionRows)
        column.addView(button("Grant the missing permissions") { requestMissingPermissions() })
        column.addView(
            button("Open Headway's system settings") { openAppSettings() },
        )

        column.addView(sectionTitle("Car touchscreen"))
        column.addView(
            body(
                "To let the car's touchscreen control this phone, Android requires " +
                    "you to switch Headway on yourself under Settings > Accessibility. " +
                    "No app is allowed to grant this to itself.\n\n" +
                    "The system dialog is worded in general terms because it covers " +
                    "every accessibility service. Headway's is registered without " +
                    "permission to read your screen content: it can inject the taps " +
                    "and swipes the car sends, and nothing else.\n\n" +
                    "If it switches itself off, that is Android, not a bug. " +
                    "Uninstalling clears the grant, and force-stopping the app can " +
                    "too. Nothing here can turn it back on — a service able to " +
                    "re-enable itself would be a keylogger, which is the whole reason " +
                    "the platform forbids it. Ordinary updates should keep it.",
            ),
        )
        accessibilityValue = body("Checking...")
        column.addView(accessibilityValue)
        column.addView(button("Open Accessibility settings") { openAccessibilitySettings() })

        column.addView(sectionTitle("Video while driving"))
        column.addView(
            body(
                "What you show on the car screen is your responsibility, and " +
                    "playing video while driving is illegal in many places. " +
                    "Headway does not decide for you.",
            ),
        )
        parkedOnlySwitch = SwitchCompat(this).apply {
            text = "Only allow video apps while parked"
            setTextColor(TEXT)
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            isChecked = HeadwaySettings.parkedOnlyVideo(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                HeadwaySettings.of(this@MainActivity).edit()
                    .putBoolean(HeadwaySettings.KEY_PARKED_ONLY_VIDEO, checked)
                    .apply()
                SessionLog.shared.info(TAG, "parked-only video set to $checked")
            }
        }
        column.addView(parkedOnlySwitch, marginParams())
        column.addView(button("Show the safety notice again") { showSafetyNotice(firstRun = false) })

        column.addView(sectionTitle("Diagnostics"))
        column.addView(
            body(
                "If the car refuses to connect, export the log and send it with " +
                    "your report. Wi-Fi passwords are removed from it, and only " +
                    "debug builds can write encryption keys.",
            ),
        )
        column.addView(button("Export the session log") { exportLog() })

        column.addView(sectionTitle("If the car's Wi-Fi never gets joined"))
        column.addView(
            body(
                "Three things are known to differ between head units, and there " +
                    "is no way to tell from the phone which yours needs. Change " +
                    "one at a time and press Connect again; the log says which " +
                    "combination was used.",
            ),
        )
        column.addView(
            quirkSwitch(
                "Probe for a hidden network name",
                "For a head unit that does not broadcast its SSID. It fails " +
                    "exactly like a car that is not there.",
                { it.hiddenSsid },
            ) { q, on -> q.copy(hiddenSsid = on) },
        )
        column.addView(
            quirkSwitch(
                "Tell the car which Wi-Fi channel we accept",
                "For a head unit that hands over credentials and then never " +
                    "brings its Wi-Fi up.",
                { it.announceWifiChannel },
            ) { q, on -> q.copy(announceWifiChannel = on) },
        )
        column.addView(
            body(
                "Matching the car's exact radio is alternated automatically on " +
                    "each attempt, because both settings have been needed on real " +
                    "hardware. Edit the quirk file to force one.",
            ),
        )
        column.addView(
            button("Create the head unit quirk file") { createQuirkTemplate() },
        )

        column.addView(sectionTitle("Phone certificate"))
        certificateValue = body(
            dev.headway.app.link.PhoneCertificateStore.inAppStorage(this).describe(),
        )
        column.addView(certificateValue)
        column.addView(
            body(
                "The certificate every open-source Android Auto implementation " +
                    "ships expired in August 2022. A head unit that checks it lets " +
                    "the session get all the way through TLS and then refuses to " +
                    "authenticate — some describe it on screen as the phone and " +
                    "vehicle clocks disagreeing. Import a valid certificate and " +
                    "key once and every session after that uses them.",
            ),
        )
        column.addView(
            button("Import a certificate and key") {
                toast("Pick the certificate (.pem) first")
                runCatching { pickCertificate.launch(arrayOf("*/*")) }
                    .onFailure { toast("No file picker available") }
            },
        )
        column.addView(
            button("Go back to the bundled certificate") {
                dev.headway.app.link.PhoneCertificateStore.inAppStorage(this).clear()
                refresh()
                toast("Using the bundled certificate again")
            },
        )

        column.addView(sectionTitle("Updates"))
        column.addView(
            body(
                "Headway is installed from GitHub releases, so it checks for new " +
                    "builds there. This only ever happens when you press the " +
                    "button — nothing checks in the background, and the car link " +
                    "never uses the internet.",
            ),
        )
        updateValue = body("Build ${BuildConfig.VERSION_CODE} installed")
        column.addView(updateValue)
        updateButton = button("Check for updates") { checkForUpdate() }
        column.addView(updateButton)

        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    // --- state --------------------------------------------------------------

    private fun refresh() {
        refreshPermissions()
        refreshAccessibility()
        // Re-read rather than trust the widget: the preference is shared with the
        // car launcher and with anything else that may have changed it while this
        // activity was stopped.
        parkedOnlySwitch.isChecked = HeadwaySettings.parkedOnlyVideo(this)
        if (::certificateValue.isInitialized) {
            certificateValue.text =
                dev.headway.app.link.PhoneCertificateStore.inAppStorage(this).describe()
        }
    }

    private fun refreshPermissions() {
        permissionRows.removeAllViews()
        for (need in requiredPermissions()) {
            val ok = isGranted(need.permission)
            permissionRows.addView(
                body(
                    "${if (ok) "granted" else "not granted"} — ${need.label}",
                ).apply { setTextColor(if (ok) GOOD else WARN) },
            )
        }
    }

    private fun refreshAccessibility() {
        val enabled = HeadwayAccessibilityService.isEnabled(this)
        val bound = HeadwayAccessibilityService.instance.value != null
        accessibilityValue.text = when {
            enabled && bound -> "On — the car's touchscreen will work"
            // Enabled in Settings but not yet bound is normal for a second or
            // two after the toggle, and permanent if the service crashed.
            enabled -> "Switched on, waiting for Android to start it"
            else -> "Off — the car screen will show the phone but touches will do nothing"
        }
        accessibilityValue.setTextColor(if (enabled && bound) GOOD else WARN)
    }

    private fun showLinkState(state: LinkState) {
        statusValue.text = when (state) {
            is LinkState.Idle -> "Not connected"
            is LinkState.Connecting -> "Connecting to the car (attempt ${state.attempt})"
            is LinkState.Connected -> "Connected"
            is LinkState.WaitingToRetry ->
                "Retrying in ${state.delayMillis / 1000}s — ${state.cause}"
            is LinkState.GaveUp -> "Stopped — ${state.cause}"
        }
        statusValue.setTextColor(
            when (state) {
                is LinkState.Connected -> GOOD
                is LinkState.GaveUp -> BAD
                else -> TEXT
            },
        )
        connectButton.text =
            if (state is LinkState.Idle || state is LinkState.GaveUp) {
                "Connect to the car"
            } else {
                "Disconnect"
            }

        // Released as soon as the link settles either way. Keeping a phone
        // screen awake for a whole drive is not this screen's job -- the car
        // launcher holds its own flag once the session is up.
        val keepAwake = state is LinkState.Connecting || state is LinkState.WaitingToRetry
        if (keepAwake) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // --- actions ------------------------------------------------------------

    private fun toggleConnection() {
        val running = HeadwayService.linkState.value.let {
            it !is LinkState.Idle && it !is LinkState.GaveUp
        }
        if (running) {
            HeadwayService.stop(this)
            return
        }
        val missing = essentialPermissions().filterNot { isGranted(it.permission) }
        if (missing.isNotEmpty()) {
            // Starting anyway would fail inside the service with a
            // SecurityException the user never sees, and read as "it just does
            // nothing when I press connect".
            toast("Grant ${missing.first().label} first")
            requestMissingPermissions()
            return
        }
        // Wi-Fi being off is worth catching here rather than 90 seconds into the
        // attempt. The platform refuses a WifiNetworkSpecifier request outright
        // when the radio is off and gives the app nothing but an anonymous
        // "unavailable", so without this the user is told to tap an approval
        // sheet that is never going to appear.
        val wifi = getSystemService(android.net.wifi.WifiManager::class.java)
        if (wifi != null && !wifi.isWifiEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Turn Wi-Fi on first")
                .setMessage(
                    "The car link runs over the head unit's own Wi-Fi, so the radio " +
                        "has to be switched on. It does not need to be connected to " +
                        "anything — Headway joins the car's network itself, and that " +
                        "network has no internet.",
                )
                .setPositiveButton("Open Wi-Fi settings") { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.Panel.ACTION_WIFI))
                    }.onFailure { toast("Turn Wi-Fi on in Settings, then press Connect") }
                }
                // Still offered, because this reads the radio's state and the
                // user knows their phone better than this check does.
                .setNegativeButton("Try anyway") { _, _ -> startLink() }
                .show()
            return
        }

        startLink()
    }

    private fun startLink() {
        // Hold the screen on for the attempt.
        //
        // Android's Wi-Fi approval prompt has to be tapped by a person, and a
        // display that times out while it is waiting takes the tap with it --
        // scans are throttled with the screen off and there is nothing to press.
        // In the capture that prompted all this, the activity stopped fourteen
        // seconds into a seventy-five second wait with no other explanation.
        // CarLauncherActivity already does this for the car screen; the phone
        // screen needs it for exactly as long as the join does.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        HeadwayService.start(this)
        // The one moment where the user has a job: Android shows an approval
        // prompt for the car's network, and it has to be tapped.
        //
        // The advice used to end "keep this screen open", which was actively
        // harmful. Android's prompt is an activity in its own task
        // (taskAffinity .wifi.NetworkRequestDialogActivity, excludeFromRecents),
        // and its onPause unregisters the scan callback that populates it.
        // Returning to Headway to see how it is going covers the prompt, and it
        // does not reliably come back. Headway does not need to be visible at
        // all: the foreground service is what satisfies the platform's
        // requirement, and that check runs once, when the request is made.
        toast(
            "Android will ask to connect to the car's Wi-Fi. Tap the car on that " +
                "prompt and leave it on screen — don't switch back here to check."
        )
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions()
            .filterNot { isGranted(it.permission) }
            .map { it.permission }
        if (missing.isEmpty()) {
            toast("All the permissions Headway needs are granted")
            return
        }
        // After a second refusal Android silently returns "denied" without
        // showing anything, so send the user to Settings instead of firing a
        // request that visibly does nothing.
        val blocked = missing.filterNot { shouldShowRequestPermissionRationale(it) }
            .filterNot { isFirstRequest(it) }
        if (blocked.isNotEmpty()) {
            openAppSettings()
            toast("Android will not ask again — turn them on in Settings")
            return
        }
        missing.forEach { markRequested(it) }
        permissionRequest.launch(missing.toTypedArray())
    }

    private fun openAccessibilitySettings() {
        val intent = HeadwayAccessibilityService.settingsIntent(this)
            // Launched from an activity, so the new-task flag the service-side
            // helper adds is unwanted here.
            .setFlags(0)
        if (intent.resolveActivity(packageManager) == null) {
            toast("This device has no Accessibility settings screen")
            return
        }
        startActivity(intent)
        toast("Find Headway in the list and switch it on")
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)),
        )
    }

    private fun exportLog() {
        val file = try {
            SessionLog.shared.export(this)
        } catch (e: java.io.IOException) {
            toast("Could not write the log: ${e.message}")
            return
        }
        val path = file.absolutePath
        AlertDialog.Builder(this)
            .setTitle("Log exported")
            .setMessage(
                "Saved to:\n\n$path\n\nShare it straight into your report — that " +
                    "folder is under Android/data, which the Files app has not been " +
                    "allowed to open since Android 11.",
            )
            .setPositiveButton("Share") { _, _ -> shareLog(file) }
            .setNeutralButton("Copy path") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Headway log", path))
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun shareLog(file: java.io.File) {
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.logs", file,
            )
        }.getOrElse {
            toast("Could not share the log: ${it.message}")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Headway log (build ${BuildConfig.VERSION_CODE})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share the Headway log")) }
            .onFailure { toast("Nothing on this phone can share a file") }
    }

    /**
     * The whole update flow: ask, confirm, download, hand to the installer.
     *
     * User-initiated from end to end. Nothing here is reachable except by
     * pressing the button, which is what keeps the "works with no network"
     * property CLAUDE.md requires — see [ReleaseCatalog]'s note.
     */
    private fun checkForUpdate() {
        val scope = updateScope ?: return
        val updater = AppUpdater(this)

        updateButton.isEnabled = false
        updateValue.text = "Asking GitHub..."
        scope.launch {
            val release = try {
                updater.check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not only UpdateException: check() can surface an IOException, a
                // JSON error or anything else, and an uncaught exception here has
                // no CoroutineExceptionHandler above it, so it would take the
                // whole app down instead of just failing the check.
                updateValue.text = "Could not check: ${e.message}"
                updateButton.isEnabled = true
                return@launch
            }

            if (release == null) {
                updateValue.text =
                    "Build ${BuildConfig.VERSION_CODE} is the newest published build"
                updateButton.isEnabled = true
                return@launch
            }

            updateValue.text = "Build ${release.buildNumber} is available"
            updateButton.isEnabled = true
            AlertDialog.Builder(this@MainActivity)
                .setTitle(release.name)
                .setMessage(
                    "You have build ${BuildConfig.VERSION_CODE}. Build " +
                        "${release.buildNumber} is available (${release.sizeMegabytes}).\n\n" +
                        "Android will ask you to confirm the install.",
                )
                .setPositiveButton("Download and install") { _, _ -> startUpdate(release) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun startUpdate(release: AvailableRelease) {
        val scope = updateScope ?: return
        val updater = AppUpdater(this)

        // Asked for before the download rather than after, so a refusal costs
        // nothing and the reason is obvious while the request is on screen.
        if (!updater.canInstall()) {
            AlertDialog.Builder(this)
                .setTitle("Allow Headway to install updates")
                .setMessage(
                    "Android needs your permission for an app to install another " +
                        "app. Turn on \"Allow from this source\" for Headway, then " +
                        "press Check for updates again.",
                )
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching { startActivity(updater.installPermissionIntent()) }
                        .onFailure { toast("No settings screen for this on this device") }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        updateButton.isEnabled = false
        scope.launch {
            try {
                updateValue.text = "Downloading build ${release.buildNumber}..."
                val apk = updater.download(release) { percent ->
                    updateValue.text = "Downloading build ${release.buildNumber}: $percent%"
                }
                updateValue.text = "Starting the installer..."
                updater.install(apk)
                updateValue.text = "Waiting for you to confirm the install"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // install() can throw SecurityException if the user revokes the
                // install permission between the check and the commit, and
                // catching only UpdateException would let that kill the app
                // mid-update. Any failure here is a failed update, not a crash.
                SessionLog.shared.info(TAG, "update failed: ${e.message}")
                updateValue.text = "Update failed: ${e.message}"
            } finally {
                updateButton.isEnabled = true
            }
        }
    }

    private fun createQuirkTemplate() {
        val store = QuirkStore.inAppStorage(this)
        // A storage failure here is a diagnostic inconvenience, not a reason to
        // crash the only screen the user has.
        val created = runCatching { store.writeTemplateIfAbsent() }.getOrElse { failure ->
            toast("Could not write ${store.path}: ${failure.message}")
            return
        }
        val load = store.load()
        AlertDialog.Builder(this)
            .setTitle(if (created) "Quirk file created" else "Quirk file")
            .setMessage(
                buildString {
                    append(store.path)
                    append("\n\nEdit it to change fragment size, the announced AAP ")
                    append("version, keyframe interval, media audio routing or touch ")
                    append("corrections for your head unit.")
                    append("\n\nIf the car's Wi-Fi never gets joined, the two to try are ")
                    append("\"hiddenSsid\": true, for a head unit that does not ")
                    append("broadcast its network name, and \"pinBssid\": true, if ")
                    append("another car with the same network name is in range.")
                    if (load.hasWarnings) {
                        append("\n\nProblems found:\n")
                        load.warnings.forEach { append("- ").append(it).append('\n') }
                    }
                },
            )
            .setPositiveButton("Done", null)
            .show()
    }

    /**
     * The notice CLAUDE.md requires on first run.
     *
     * Not dismissible by tapping outside: it is shown once, and a user who
     * dismissed it by accident would never see it again. It states the position
     * rather than asking permission — the toggle underneath is where the choice
     * lives.
     */
    private fun showSafetyNotice(firstRun: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Before you drive")
            .setMessage(
                "Headway shows this phone's screen on your car's display. What " +
                    "you choose to show there is your responsibility.\n\n" +
                    "Playing video, or interacting with the phone, while the car " +
                    "is moving is illegal in many places and dangerous in all of " +
                    "them. Headway does not check what you are doing and does not " +
                    "block anything by default.\n\n" +
                    "If you want the app to hold you to it, switch on " +
                    "\"Only allow video apps while parked\" on this screen. It is " +
                    "off unless you turn it on.",
            )
            .setCancelable(!firstRun)
            .setPositiveButton(if (firstRun) "I understand" else "Close") { _, _ ->
                HeadwaySettings.of(this).edit()
                    .putBoolean(HeadwaySettings.KEY_SAFETY_NOTICE_ACCEPTED, true)
                    .apply()
            }
            .show()
    }

    // --- permissions --------------------------------------------------------

    private data class PermissionNeed(val permission: String, val label: String)

    /**
     * Exactly the runtime permissions the manifest declares as dangerous.
     *
     * `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `INTERNET` and the foreground
     * service ones are install-time and cannot be requested; `RECORD_AUDIO` is
     * deliberately absent because it belongs to the optional phone-mic fallback
     * and asking for a microphone on first launch of a car app is exactly the
     * kind of thing this project exists to avoid.
     */
    private fun requiredPermissions(): List<PermissionNeed> = listOf(
        PermissionNeed(Manifest.permission.BLUETOOTH_CONNECT, "Bluetooth: talk to the car"),
        PermissionNeed(Manifest.permission.BLUETOOTH_SCAN, "Bluetooth: find the car"),
        PermissionNeed(Manifest.permission.NEARBY_WIFI_DEVICES, "Wi-Fi: join the car's network"),
        PermissionNeed(Manifest.permission.POST_NOTIFICATIONS, "Notifications: stay connected"),
    )

    /**
     * The subset without which connecting cannot work at all.
     *
     * `POST_NOTIFICATIONS` is deliberately not in it. The foreground service
     * runs whether or not its notification can be shown -- posting without the
     * permission is a no-op on the platform side, not an error -- so refusing
     * to start without it turned a cosmetic refusal into "the Connect button
     * does nothing". It is still requested and still shown as missing, because
     * a user who cannot see the link state is worse off; it just no longer
     * blocks the car.
     */
    private fun essentialPermissions(): List<PermissionNeed> =
        requiredPermissions().filterNot { it.permission == Manifest.permission.POST_NOTIFICATIONS }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Distinguishes "never asked" from "asked and refused twice".
     *
     * `shouldShowRequestPermissionRationale` is false in both cases, so on its
     * own it would send a first-time user straight to Settings instead of
     * showing them the ordinary system prompt.
     */
    private fun isFirstRequest(permission: String): Boolean =
        !HeadwaySettings.of(this).getBoolean(requestedKey(permission), false)

    private fun markRequested(permission: String) {
        HeadwaySettings.of(this).edit().putBoolean(requestedKey(permission), true).apply()
    }

    private fun requestedKey(permission: String) = "requested_$permission"

    // --- view helpers -------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun marginParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setPadding(0, 0, 0, dp(8))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        letterSpacing = 0.08f
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        minHeight = dp(MIN_TOUCH_TARGET_DP)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = marginParams()
    }

    /**
     * A switch bound to one boolean in the universal quirk profile.
     *
     * These are on the phone screen rather than only in the quirk file because
     * the file is not reachable: it lives in the app's private storage, and the
     * obvious alternative, `Android/data`, has been closed to file managers
     * since Android 11. A user debugging a car has a phone and nothing else.
     */
    private fun quirkSwitch(
        label: String,
        explanation: String,
        read: (dev.headway.app.quirks.HeadUnitQuirks) -> Boolean,
        write: (dev.headway.app.quirks.HeadUnitQuirks, Boolean) -> dev.headway.app.quirks.HeadUnitQuirks,
    ): View {
        val store = QuirkStore.inAppStorage(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(
            SwitchCompat(this).apply {
                text = label
                setTextColor(TEXT)
                minHeight = dp(MIN_TOUCH_TARGET_DP)
                isChecked = runCatching { read(store.universalQuirks()) }.getOrDefault(false)
                setOnCheckedChangeListener { _, checked ->
                    // A storage failure here must not crash the only screen the
                    // user has, and must not silently pretend to have worked.
                    runCatching { store.editUniversalProfile { write(it, checked) } }
                        .onSuccess {
                            SessionLog.shared.info(TAG, "quirk '$label' set to $checked")
                            toast("Saved. Press Connect to try it.")
                        }
                        .onFailure { toast("Could not save that: ${it.message}") }
                }
            },
            marginParams(),
        )
        row.addView(body(explanation))
        return row
    }

    private fun readTextFrom(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull().also { if (it == null) toast("Could not read that file") }

    /** One activity-lifecycle line into the exportable log. */
    private fun note(what: String) {
        SessionLog.shared.info(TAG, "MainActivity $what")
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "HeadwayUi"

        /** Android's own minimum, and the floor CLAUDE.md sets for the car screen. */
        private const val MIN_TOUCH_TARGET_DP = 48

        // ARGB literals rather than Color.parseColor, which is deprecated on
        // API 35, and rather than colour resources, which this screen avoids
        // adding so it needs no res/ changes.
        private val BACKGROUND: Int = 0xFF000000.toInt()
        private val TEXT: Int = 0xFFECEFF1.toInt()
        private val ACCENT: Int = 0xFF7EC8FF.toInt()
        private val GOOD: Int = 0xFF7BE38B.toInt()
        private val WARN: Int = 0xFFFFC46B.toInt()
        private val BAD: Int = 0xFFFF7A7A.toInt()
    }
}
