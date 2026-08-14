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
import android.companion.CompanionDeviceManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import dev.headway.app.dash.tiles.NowPlayingTile
import dev.headway.app.diag.SelfTest
import dev.headway.app.input.HeadwayAccessibilityService
import dev.headway.app.link.CarCompanion
import dev.headway.app.log.SessionLog
import dev.headway.app.quirks.QuirkStore
import dev.headway.app.service.HeadwayService
import dev.headway.app.video.ProjectionRequestActivity
import dev.headway.app.ui.theme.HeadwayMark
import dev.headway.app.ui.theme.HeadwayTheme
import dev.headway.dash.ThemeAccent
import dev.headway.dash.ThemeBase
import dev.headway.app.ui.theme.Phone
import dev.headway.app.update.AppUpdater
import dev.headway.app.update.AvailableRelease
import dev.headway.app.update.ReleaseCatalog
import dev.headway.app.update.UpdateException
import dev.headway.app.update.UpdateReceiver
import dev.headway.app.video.OverlayDisplay
import dev.headway.app.voice.SpeechModelInstaller
import dev.headway.transport.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var mark: HeadwayMark
    private lateinit var connectButton: TextView
    private lateinit var parkedOnlySwitch: SwitchCompat
    private lateinit var dashboardSwitch: SwitchCompat
    private lateinit var autoConnectSwitch: SwitchCompat
    private var themeValue: android.widget.TextView? = null
    private var allowedAppsSummary: android.widget.TextView? = null
    private lateinit var updateValue: TextView
    private lateinit var certificateValue: TextView
    private lateinit var carWifiValue: TextView
    private lateinit var updateButton: TextView

    /** The readiness checklist, one row per thing a user has to grant once. */
    private lateinit var permissionStatus: Phone.StatusRow
    private lateinit var accessibilityStatus: Phone.StatusRow
    private lateinit var notificationStatus: Phone.StatusRow
    private lateinit var speechStatus: Phone.StatusRow
    private lateinit var pairingStatus: Phone.StatusRow
    private lateinit var phoneStatus: Phone.StatusRow
    private lateinit var appDisplayStatus: Phone.StatusRow
    private lateinit var appDisplaySwitch: SwitchCompat

    /**
     * The tab editor, held so `onStart` can re-read the store.
     *
     * The car screen writes to the same store when the driver switches tabs, so
     * a card built once and never refreshed shows the wrong tab as active after
     * any drive.
     */
    private var tabsCard: TabsCard? = null

    private lateinit var selfTestStatus: TextView
    private lateinit var selfTestButton: TextView
    private lateinit var installFailureRow: Phone.StatusRow

    /** Guards against a second run while the first is still binding apps. */
    private var selfTestRunning = false

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

    /**
     * The companion-device chooser's answer.
     *
     * `CompanionDeviceManager.associate` hands back an `IntentSender` rather
     * than showing anything itself, so the chooser is launched as an activity
     * result and the association arrives in the data extras. Two extras carry
     * it: `EXTRA_ASSOCIATION` is the `AssociationInfo`, which is what
     * [CarCompanion.remember] needs because it holds the MAC the pairing was
     * recorded against; `EXTRA_DEVICE` carries the `ScanResult` and is not
     * needed once the association exists.
     */
    private val pairWithCar =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val ssid = pendingPairSsid
            pendingPairSsid = null
            if (result.resultCode != RESULT_OK || ssid == null) {
                SessionLog.shared.info(TAG, "car Wi-Fi pairing cancelled")
                toast("Pairing cancelled")
                refresh()
                return@registerForActivityResult
            }
            val info = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                android.companion.AssociationInfo::class.java,
            )
            if (info == null) {
                toast("Android returned no pairing; try again")
                refresh()
                return@registerForActivityResult
            }
            val bssid = CarCompanion.of(this).remember(ssid, info)
            AlertDialog.Builder(this)
                .setTitle(if (bssid != null) "Paired with your car" else "Paired, with a caveat")
                .setMessage(
                    if (bssid != null) {
                        "Headway is paired with \"$ssid\" at $bssid.\n\n" +
                            "Android will no longer ask you to approve joining it, so the car " +
                            "can reconnect on its own with the phone in your pocket."
                    } else {
                        "Android recorded the pairing but gave no MAC address for it, so the " +
                            "approval prompt will still appear. Please export the log and " +
                            "report this."
                    },
                )
                .setPositiveButton("OK", null)
                .show()
            refresh()
        }

    /** The SSID being paired, held across the chooser. */
    private var pendingPairSsid: String? = null

    /**
     * The system panel that saves the car's Wi-Fi.
     *
     * `StartActivityForResult` rather than a plain start, because the panel
     * reports what it did in `EXTRA_WIFI_NETWORK_RESULT_LIST` and "already
     * saved" is a success the user should not be left guessing about.
     */
    private val addCarNetwork =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val codes = result.data
                ?.getIntegerArrayListExtra(android.provider.Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
                ?.toList()
                .orEmpty()
            val message = dev.headway.app.link.CarWifiProvisioning
                .describeResult(result.resultCode, codes)
            SessionLog.shared.info(TAG, "add-networks panel: $message")
            AlertDialog.Builder(this)
                .setTitle("Car Wi-Fi")
                .setMessage(
                    message + "\n\nNow open Android's Wi-Fi settings, tap the gear next " +
                        "to the car's network, and set:\n\n" +
                        "  • Privacy → \"Use per-network randomized MAC\"\n" +
                        "  • \"Send device name to network\" → on\n\n" +
                        "Those two are what GrapheneOS turns on for Google's Android Auto " +
                        "and cannot turn on for any other app. They are the most likely " +
                        "reason the car will not hand out an address.",
                )
                .setPositiveButton("Open Wi-Fi settings") { _, _ ->
                    runCatching {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    }.onFailure { toast("No Wi-Fi settings activity on this device") }
                }
                .setNegativeButton("Later", null)
                .show()
            refresh()
        }

    /**
     * Screen-capture consent, asked at Connect rather than when video starts.
     *
     * The timing is forced by the car, not chosen. A real 2021 Chevrolet
     * Infotainment 3 unit gives the phone about fifteen seconds between the last
     * channel opening and the first video frame before it closes the session --
     * measured at 15 s, 16 s and 19 s across three real sessions. A consent
     * dialog inside that window would have to be answered by someone who is
     * usually driving, and a refusal or a slow tap costs the whole session.
     *
     * So consent is obtained first and the session is started with the grant
     * already in hand. Declining is fine: the link still comes up, without
     * video, which is exactly what a first-connection diagnosis needs anyway.
     */
    private val requestProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                SessionLog.shared.info(TAG, "screen capture granted; starting the link with video")
            } else {
                SessionLog.shared.info(
                    TAG,
                    "screen capture declined; starting the link without video. The car will " +
                        "connect but its screen will stay on the connecting page",
                )
            }
            connectWithProjection(result.resultCode, data)
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
        installSpeechModel()
        if (!HeadwaySettings.of(this).getBoolean(HeadwaySettings.KEY_SAFETY_NOTICE_ACCEPTED, false)) {
            showSafetyNotice(firstRun = true)
        }
    }

    /**
     * Unpacks the bundled speech model, once, in the background.
     *
     * Here rather than in the car session on purpose. It writes ~68 MB and takes
     * seconds; doing it lazily on the first voice command would spend those
     * seconds with the driver waiting, and doing it during session bring-up
     * would spend them against the head unit's fifteen-second video deadline.
     *
     * Nothing waits on the result and nothing fails if it does not work — voice
     * degrades to "the car microphone works, nothing is transcribed", which the
     * log says in as many words.
     */
    private fun installSpeechModel() {
        val application = applicationContext
        if (SpeechModelInstaller.isInstalled(application)) return
        CoroutineScope(Dispatchers.IO).launch {
            SpeechModelInstaller.install(application) { note(it) }
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

    /**
     * The page.
     *
     * Ordered by when a user needs each part rather than by how the code is
     * organised: what the link is doing and the one button that changes it,
     * then the things that have to be granted once, then the car-specific
     * settings, then the things that only matter when something is wrong.
     *
     * Every card's explanation is folded away behind a [Phone.disclosure]. The
     * text is unchanged and it is all still here — it is simply no longer
     * between the reader and the button.
     */
    private fun buildContent(): View {
        val column = Phone.page(this)

        column.addView(buildHero())
        column.addView(Phone.sectionLabel(this, "Before the first drive"))
        column.addView(buildChecklist())
        column.addView(Phone.sectionLabel(this, "The car"))
        column.addView(buildCarScreenCard())
        column.addView(buildAllowedAppsCard())
        column.addView(buildAppDisplayCard())
        column.addView(TabsCard(this) { }.also { tabsCard = it }.view)
        column.addView(buildCarWifiCard())
        column.addView(buildQuirksCard())
        column.addView(buildCertificateCard())
        column.addView(Phone.sectionLabel(this, "Your choice"))
        column.addView(buildDrivingCard())
        column.addView(Phone.sectionLabel(this, "If something is wrong"))
        column.addView(buildSelfTestCard())
        column.addView(buildDiagnosticsCard())
        column.addView(buildUpdatesCard())

        Phone.stagger(column)

        return ScrollView(this).apply {
            setBackgroundColor(dev.headway.app.ui.theme.Headway.GROUND)
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /**
     * The mark, the link state, and Connect.
     *
     * This is the only part of the screen a user who has already set the phone
     * up ever looks at, so it is the only part above the fold. The mark
     * animates while the link is coming up — see [HeadwayMark] for why that is
     * a travelling sweep rather than a spinner.
     */
    private fun buildHero(): View {
        val card = Phone.card(this)

        val markRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val markView = HeadwayMark(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(30)).apply {
                marginEnd = dp(14)
            }
        }
        markRow.addView(markView)
        markRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Headway"
                        setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                        letterSpacing = 0.01f
                    },
                )
                addView(
                    Phone.note(
                        this@MainActivity,
                        "Build ${BuildConfig.VERSION_CODE} · nothing leaves this phone",
                    ).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) },
                )
            },
        )
        card.addView(markRow)

        statusValue = TextView(this).apply {
            setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            text = "Not connected"
            layoutParams = Phone.spaced(this@MainActivity, 16f)
        }
        card.addView(statusValue)

        connectButton = Phone.button(this, "Connect to the car", primary = true) {
            toggleConnection()
        }
        card.addView(connectButton)

        mark = markView
        return card
    }

    /**
     * Everything that has to be granted once, and whether it has been.
     *
     * A checklist rather than five paragraphs because the question a user
     * actually has is "is this phone ready", and that is a question about
     * state, not about explanations. Each row carries its own remedy, and the
     * remedy disappears once the row is green.
     *
     * The last three rows are new to this screen and their absence was a real
     * bug rather than an omission: notification access is what makes the Now
     * playing and Messages panes work at all, and there was no way to discover
     * that from inside Headway.
     */
    private fun buildChecklist(): View {
        val card = Phone.card(this, "Ready to connect?")

        permissionStatus = Phone.StatusRow(this, "Bluetooth, Wi-Fi and notifications")
            .withAction("Grant") { requestMissingPermissions() }
        card.addView(permissionStatus.view)

        accessibilityStatus = Phone.StatusRow(this, "Car touchscreen")
            .withAction("Open") { openAccessibilitySettings() }
        card.addView(accessibilityStatus.view)

        notificationStatus = Phone.StatusRow(this, "Now playing and messages")
            .withAction("Turn on") { openNotificationAccess() }
        card.addView(notificationStatus.view)

        phoneStatus = Phone.StatusRow(this, "Calls and contacts")
            .withAction("Grant") {
                requestMissingPermissions(
                    phonePermissions(),
                    "Headway can already show and place calls",
                )
            }
            // Green here can still mean "one of the four is missing", and the
            // button is the only way to ask for the rest.
            .withPersistentAction()
        card.addView(phoneStatus.view)

        // No remedy button: this one installs itself and there is nothing for a
        // user to press. It is on the list because a driver whose voice
        // commands do nothing deserves to be able to see why.
        speechStatus = Phone.StatusRow(this, "Offline speech model")
        card.addView(speechStatus.view)

        card.addView(Phone.divider(this))
        card.addView(
            Phone.disclosure(
                this,
                "What each of these is for",
                "Bluetooth finds the car and collects its Wi-Fi details; nearby " +
                    "devices lets Headway join that network; notifications keep the " +
                    "connection alive while the screen is off. Location is never " +
                    "requested.\n\n" +
                    "The car touchscreen needs Android's accessibility grant, and no " +
                    "app is allowed to give itself that one — a service that could " +
                    "would be a keylogger. The system's wording covers every " +
                    "accessibility service; Headway's is registered without " +
                    "permission to read screen content, so it can inject the taps the " +
                    "car sends and nothing else. If it switches itself off, that is " +
                    "Android: uninstalling clears the grant and force-stopping can " +
                    "too.\n\n" +
                    "Now playing and messages read the same feed the lock screen " +
                    "does. Without this grant those two panes on the car screen have " +
                    "nothing to show, which is the single most common reason the " +
                    "dashboard looks empty.\n\n" +
                    "The voice button is drawn over whatever app is on screen, so it " +
                    "is reachable from the car even when Headway is not the " +
                    "foreground app.\n\n" +
                    "The speech model is bundled in the app and unpacks itself the " +
                    "first time Headway runs. Nothing is downloaded and no audio " +
                    "leaves the phone.",
            ),
        )
        card.addView(
            Phone.button(this, "Open Headway's system settings") { openAppSettings() },
        )
        return card
    }

    /**
     * The one route by which another app renders at the car's size rather than
     * being mirrored.
     *
     * Its own card because it is the only setting on this screen that requires
     * the driver to go somewhere else in Settings first, and because getting the
     * consent dialog wrong afterwards produces a session that looks broken in a
     * way nothing in the log would obviously explain. ADR 0008 has the
     * derivation; this says what to click.
     */
    private fun buildAppDisplayCard(): View {
        val card = Phone.card(this, "How apps reach the car")
        card.addView(
            Phone.body(
                this,
                "When you tap an app on the car screen, Headway can either mirror " +
                    "your phone or hand the app a display the size of the car panel " +
                    "and let it draw itself there.",
            ),
        )
        appDisplayStatus = Phone.StatusRow(this, "Simulated car display")
            .withAction("Set up") { openDeveloperOptions() }
        card.addView(appDisplayStatus.view)

        appDisplaySwitch = SwitchCompat(this).apply {
            text = "Render apps on the car display instead of mirroring"
            setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            isChecked = HeadwaySettings.of(this@MainActivity)
                .getBoolean(HeadwaySettings.KEY_NATIVE_APP_DISPLAY, false)
            setOnCheckedChangeListener { _, checked ->
                HeadwaySettings.of(this@MainActivity).edit()
                    .putBoolean(HeadwaySettings.KEY_NATIVE_APP_DISPLAY, checked)
                    .apply()
                refreshAppDisplay()
                SessionLog.shared.info(
                    TAG,
                    "native app display ${if (checked) "on" else "off"}",
                )
            }
            layoutParams = Phone.spaced(this@MainActivity, 12f)
        }
        card.addView(appDisplaySwitch)
        card.addView(
            Phone.disclosure(
                this,
                "What to turn on, and what to pick",
                "Three steps, all in Settings, none of them needing a computer.\n\n" +
                    "1. Settings, System, Developer options, \"Simulate secondary " +
                    "displays\". Pick 720x480/142. Do NOT pick an entry whose label " +
                    "says (secure) — those cannot be recorded and the car goes black " +
                    "with no error.\n\n" +
                    "2. In the same screen, turn on \"Disable screen-share protections " +
                    "for apps and notifications\". Android 15 and later stop a screen " +
                    "capture when the phone locks and ask again on the next unlock, " +
                    "which costs the car its picture every time; this is the reported " +
                    "way to prevent it. Worth turning on, though Headway has not been " +
                    "able to confirm from Android's own source that this is the toggle " +
                    "that governs it.\n\n" +
                    "3. Turn the switch above on. Then when you press Connect and " +
                    "Android asks what to share, pick the row named for that display " +
                    "— not \"Entire screen\".\n\n" +
                    "Why this works: Android refuses to let an app put another app's " +
                    "window on a display it created, but a display *Settings* creates " +
                    "is trusted, and any app may be launched onto a trusted display. " +
                    "So the app lays itself out for 720 by 480, Headway records that " +
                    "display rather than your phone, and the car gets 720 of its 800 " +
                    "columns at true size. Mirroring your phone fills 216 of them.\n\n" +
                    "Two costs, and they are real. The list of sizes is fixed by " +
                    "Android and has no 800x480, so there is a 40-pixel black bar down " +
                    "each side. And your phone's screen has to stay on for the whole " +
                    "drive, because Android switches the simulated display off with it " +
                    "— turn the brightness right down. A half-size preview window also " +
                    "sits on your phone the whole time; Android offers no setting that " +
                    "hides it, but the switch above covers it.",
            ),
        )
        card.addView(
            SwitchCompat(this).apply {
                text = "Blank the phone screen while driving"
                setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
                minHeight = dp(MIN_TOUCH_TARGET_DP)
                isChecked = HeadwaySettings.of(this@MainActivity)
                    .getBoolean(HeadwaySettings.KEY_BLANK_PHONE_SCREEN, false)
                setOnCheckedChangeListener { _, checked ->
                    HeadwaySettings.of(this@MainActivity).edit()
                        .putBoolean(HeadwaySettings.KEY_BLANK_PHONE_SCREEN, checked)
                        .apply()
                    SessionLog.shared.info(
                        TAG,
                        "blank phone screen ${if (checked) "on" else "off"}",
                    )
                }
                layoutParams = Phone.spaced(this@MainActivity, 12f)
            },
        )
        card.addView(
            Phone.note(
                this,
                "This is how you get rid of the preview window. Android has no setting " +
                    "that hides it and it cannot be closed — it is not a preview, it is the " +
                    "simulated display's actual output surface, and destroying it destroys " +
                    "the display. What Headway can do is cover it, which needs the " +
                    "accessibility service. Tap the black screen to bring the phone back. " +
                    "The screen still has to stay on, and your notification shade still " +
                    "shows that recording is happening.",
            ).apply { layoutParams = Phone.spaced(this@MainActivity, 8f) },
        )
        card.addView(
            Phone.note(
                this,
                "To check which display you picked — and whether it is a (secure) one " +
                    "— run the self-test at the bottom of this screen. It lists every " +
                    "display with its flags.",
            ).apply { layoutParams = Phone.spaced(this@MainActivity, 10f) },
        )
        return card
    }

    private fun openDeveloperOptions() {
        val intent = OverlayDisplay.settingsIntent().setFlags(0)
        if (intent.resolveActivity(packageManager) == null) {
            toast("This device has no Developer options screen")
            return
        }
        startActivity(intent)
        toast("Simulate secondary displays → 720x480/142, not a (secure) one")
    }

    private fun refreshAppDisplay() {
        if (!::appDisplayStatus.isInitialized) return
        val on = HeadwaySettings.of(this)
            .getBoolean(HeadwaySettings.KEY_NATIVE_APP_DISPLAY, false)
        val found = OverlayDisplay.find(this)
        appDisplayStatus.set(
            when {
                found != null && on -> Phone.Level.GOOD
                found != null -> Phone.Level.IDLE
                on -> Phone.Level.WARN
                else -> Phone.Level.IDLE
            },
            when {
                found != null && on -> "$found — apps will render here"
                found != null -> "$found — found, but the switch below is off"
                on -> "None found. Turn it on in Developer options."
                else -> "Off; apps will be mirrored from the phone screen."
            },
        )
    }

    /**
     * What the car shows, which is the choice this build exists to offer.
     *
     * Mirroring was the default until a real drive proved how badly it fits: a
     * 1080x2404 phone inside an 800x480 panel uses 216 of 800 columns and
     * leaves the rest black. The dashboard is drawn at the car's own size
     * instead. The switch is here because the mirror is still the only way to
     * put a third-party app's own pixels on the screen, and that is a trade
     * only the driver can make.
     */
    private fun buildCarScreenCard(): View {
        val card = Phone.card(this, "The car screen")
        card.addView(
            Phone.body(
                this,
                "Headway draws panels at the car's own resolution: what is playing, " +
                    "the map, messages, the clock, your pinned apps — and one panel " +
                    "that shows a real app, running. Arrange them on the car screen " +
                    "itself: the settings button on the rail unlocks the layout.",
            ),
        )

        themeValue = Phone.body(this, describeTheme())
        card.addView(themeValue)
        card.addView(
            Phone.button(this, "Change the theme") { chooseTheme() },
        )

        autoConnectSwitch = SwitchCompat(this).apply {
            text = "Connect on its own when the car appears"
            setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            isChecked = HeadwaySettings.autoConnect(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                HeadwaySettings.of(this@MainActivity).edit()
                    .putBoolean(HeadwaySettings.KEY_AUTO_CONNECT, checked)
                    .apply()
                SessionLog.shared.info(TAG, "auto-connect ${if (checked) "on" else "off"}")
            }
            layoutParams = Phone.spaced(this@MainActivity, 12f)
        }
        card.addView(autoConnectSwitch)

        dashboardSwitch = SwitchCompat(this).apply {
            text = "Draw the car screen (turn off only to diagnose)"
            setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            isChecked = HeadwaySettings.dashboardOnCarScreen(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                HeadwaySettings.of(this@MainActivity).edit()
                    .putBoolean(HeadwaySettings.KEY_CAR_SURFACE_DASHBOARD, checked)
                    .apply()
                SessionLog.shared.info(
                    TAG,
                    "car surface set to ${if (checked) "the drawn car screen" else "a raw capture"}",
                )
            }
            layoutParams = Phone.spaced(this@MainActivity, 12f)
        }
        card.addView(dashboardSwitch)

        card.addView(
            Phone.disclosure(
                this,
                "Why not just mirror the phone?",
                "Because the shapes do not match. This phone is 1080 by 2404 and " +
                    "the car panel is 800 by 480, so a mirrored image fills 216 of " +
                    "the car's 800 columns and the other three quarters are a black " +
                    "bar. Every touch has to be scaled by a fifth, every phone " +
                    "notification lands on the dashboard, and your own screen is on " +
                    "show in the car.\n\n" +
                    "Android Auto does not mirror either, and this is why: the head " +
                    "unit is a display, not a window onto the phone.\n\n" +
                    "An app still reaches the car as pixels — that is the one thing " +
                    "Android will not let Headway draw itself — but those pixels now " +
                    "land inside a panel of the car screen rather than replacing all " +
                    "of it. Turning this switch off falls back to sending the car a " +
                    "raw capture of the phone, with no panels at all. It exists to " +
                    "diagnose a car that will not show the drawn screen.",
            ),
        )
        return card
    }

    /**
     * Which apps may appear on the car screen at all.
     *
     * Nothing is allowed until it is allowed here. See `AllowedApps` for why
     * that is the default rather than "everything you have installed": the car
     * screen is shared over a capture grant, visible to passengers, and touched
     * by a coordinate stream from hardware Headway does not control. That is a
     * decision worth making once per app, parked, on a screen big enough to read.
     */
    private fun buildAllowedAppsCard(): View {
        val card = Phone.card(this, "Apps allowed on the car screen")
        card.addView(
            Phone.body(
                this,
                "Only these can be opened in a panel, pinned to the rail, or launched " +
                    "by voice. Nothing is allowed until you allow it.",
            ),
        )
        allowedAppsSummary = Phone.body(this, describeAllowedApps())
        card.addView(allowedAppsSummary)
        card.addView(Phone.button(this, "Choose apps") { chooseAllowedApps() })
        return card
    }

    private fun describeAllowedApps(): String {
        val allowed = HeadwaySettings.allowedApps(this)
        if (allowed.isEmpty()) return "None yet — the car screen will offer no apps."
        val names = allowed.take(4).map { packageName ->
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0),
                ).toString()
            }.getOrDefault(packageName)
        }.sorted()
        val extra = allowed.size - names.size
        return names.joinToString(", ") + if (extra > 0) " and $extra more" else ""
    }

    /**
     * A checklist of every launchable app, with the allowed ones ticked.
     *
     * A multi-choice dialog rather than a screen of switches: the list is as
     * long as the phone's app drawer, it is read once in a while rather than
     * lived in, and "tick the ones you want, press OK" is the shape everybody
     * already knows for exactly this question.
     */
    private fun chooseAllowedApps() {
        val manager = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            manager.queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != packageName }
                .distinct()
                .map { it to runCatching {
                    manager.getApplicationLabel(manager.getApplicationInfo(it, 0)).toString()
                }.getOrDefault(it) }
                .sortedBy { it.second.lowercase() }
                .toList()
        }.getOrDefault(emptyList())

        if (apps.isEmpty()) {
            toast("No launchable apps were found")
            return
        }
        val allowed = HeadwaySettings.allowedApps(this).toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in allowed }
        AlertDialog.Builder(this)
            .setTitle("Allow on the car screen")
            .setMultiChoiceItems(
                apps.map { it.second }.toTypedArray(),
                checked,
            ) { _, which, isChecked ->
                val target = apps[which].first
                if (isChecked) allowed.add(target) else allowed.remove(target)
            }
            .setPositiveButton("Save") { _, _ ->
                HeadwaySettings.setAllowedApps(this, allowed)
                allowedAppsSummary?.text = describeAllowedApps()
                SessionLog.shared.info(TAG, "${allowed.size} app(s) allowed on the car screen")
                toast(
                    if (allowed.isEmpty()) {
                        "No apps allowed — the car will offer none"
                    } else {
                        "${allowed.size} app(s) allowed"
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun describeTheme(): String {
        val choice = HeadwayTheme.choice
        return "Theme: ${choice.base.displayName.lowercase()}, " +
            "accent ${choice.accent.displayName.lowercase()}"
    }

    /**
     * The palette, in two questions.
     *
     * Two dialogs rather than one list of eighteen combinations, because the
     * question really is two questions — "light or dark" and "with or without
     * the blue" — and a single list makes the second one invisible.
     */
    private fun chooseTheme() {
        val bases = ThemeBase.entries
        AlertDialog.Builder(this)
            .setTitle("Car screen theme")
            .setSingleChoiceItems(
                bases.map { "${it.displayName} — ${it.explanation}" }.toTypedArray(),
                bases.indexOf(HeadwayTheme.choice.base),
            ) { dialog, which ->
                dialog.dismiss()
                HeadwayTheme.set(this, HeadwayTheme.choice.copy(base = bases[which]))
                chooseAccent()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseAccent() {
        val accents = ThemeAccent.entries
        AlertDialog.Builder(this)
            .setTitle("Accent")
            .setSingleChoiceItems(
                accents.map { it.displayName }.toTypedArray(),
                accents.indexOf(HeadwayTheme.choice.accent),
            ) { dialog, which ->
                dialog.dismiss()
                HeadwayTheme.set(this, HeadwayTheme.choice.copy(accent = accents[which]))
                // The whole screen is rebuilt rather than the one label: every
                // colour on it was resolved into a drawable when the view was
                // built, so a repaint is the only way the phone side follows the
                // choice the driver just made. The car screen rebuilds itself
                // from HeadwayTheme's listener and needs nothing from here.
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildCarWifiCard(): View {
        val card = Phone.card(this, "Car Wi-Fi")
        carWifiValue = Phone.body(this, describeCarWifi())
        card.addView(carWifiValue)

        // The row that ends the approval sheet. See pairWithCarWifi and
        // CarCompanion for why this is the single most valuable control on the
        // screen: without it Android asks for approval on every connection, and
        // an automatic reconnection with the phone in a pocket can never
        // happen.
        pairingStatus = Phone.StatusRow(this, "Paired with the car's Wi-Fi")
            .withAction("Pair") { pairWithCarWifi() }
        card.addView(pairingStatus.view)
        card.addView(
            Phone.disclosure(
                this,
                "Why pairing removes the approval prompt",
                "Android will not let an app join a named Wi-Fi network without asking " +
                    "you first, and it asks every single time. That is survivable once and " +
                    "impossible for a car, which has to reconnect on its own while the " +
                    "phone is in your pocket.\n\n" +
                    "There is one sanctioned way out, and it is not a trick: Android's own " +
                    "Wi-Fi code checks whether the app is paired with the access point " +
                    "through the companion-device system, and connects straight through if " +
                    "it is. Pairing shows you a list of nearby networks and you pick the " +
                    "car — that tap is the consent, and it is the last one.\n\n" +
                    "The pairing is listed under Settings, Connected devices, and you can " +
                    "revoke it there or with this button at any time. It grants Headway " +
                    "nothing except the right to join that one access point.\n\n" +
                    "The car has to be on and showing Android Auto when you pair, because " +
                    "the list is built from a live scan.",
            ),
        )
        card.addView(Phone.divider(this))
        card.addView(
            Phone.disclosure(
                this,
                "The car joins but never gives an address",
                "GrapheneOS gives every network Headway joins a brand new MAC " +
                    "address each time, so the car sees an unfamiliar device on every " +
                    "attempt — and GrapheneOS already turns that off for Google's " +
                    "Android Auto, keyed to that app, which Headway cannot reach.\n\n" +
                    "Saving the car's network puts the two controls that fix it in " +
                    "front of you: Privacy → \"Use per-network randomized MAC\", and " +
                    "\"Send device name to network\". The button below fills in the " +
                    "network name and password so you do not have to type them.",
            ),
        )
        card.addView(Phone.button(this, "Set up this car's Wi-Fi") { setUpCarWifi() })
        return card
    }

    private fun buildQuirksCard(): View {
        val card = Phone.card(this, "If the car's Wi-Fi is never joined")
        card.addView(
            Phone.note(
                this,
                "Two things differ between head units and there is no way to tell " +
                    "from the phone which yours needs. Change one at a time and press " +
                    "Connect again; the log says which combination was used.",
            ),
        )
        card.addView(
            quirkSwitch(
                "Probe for a hidden network name",
                "For a head unit that does not broadcast its SSID. It fails " +
                    "exactly like a car that is not there.",
                { it.hiddenSsid },
            ) { q, on -> q.copy(hiddenSsid = on) },
        )
        card.addView(
            quirkSwitch(
                "Tell the car which Wi-Fi channel we accept",
                "For a head unit that hands over credentials and then never " +
                    "brings its Wi-Fi up.",
                { it.announceWifiChannel },
            ) { q, on -> q.copy(announceWifiChannel = on) },
        )
        card.addView(
            Phone.note(
                this,
                "Matching the car's exact radio is alternated automatically on each " +
                    "attempt, because both settings have been needed on real hardware.",
            ).apply { layoutParams = Phone.spaced(this@MainActivity, 10f) },
        )
        card.addView(
            Phone.button(this, "Create the head unit quirk file") { createQuirkTemplate() },
        )
        return card
    }

    private fun buildCertificateCard(): View {
        val card = Phone.card(this, "Phone certificate")
        certificateValue = Phone.body(
            this,
            dev.headway.app.link.PhoneCertificateStore.inAppStorage(this).describe(),
        )
        card.addView(certificateValue)
        card.addView(
            Phone.disclosure(
                this,
                "The car says the clocks disagree",
                "The certificate every open-source Android Auto implementation " +
                    "ships expired in August 2022. A head unit that checks it lets " +
                    "the session get all the way through TLS and then refuses to " +
                    "authenticate — some describe it on screen as the phone and " +
                    "vehicle clocks disagreeing. Import a valid certificate and key " +
                    "once and every session after that uses them.",
            ),
        )
        card.addView(
            Phone.button(this, "Import a certificate and key") {
                toast("Pick the certificate (.pem) first")
                runCatching { pickCertificate.launch(arrayOf("*/*")) }
                    .onFailure { toast("No file picker available") }
            },
        )
        card.addView(
            Phone.button(this, "Go back to the bundled certificate") {
                dev.headway.app.link.PhoneCertificateStore.inAppStorage(this).clear()
                refresh()
                toast("Using the bundled certificate again")
            },
        )
        return card
    }

    private fun buildDrivingCard(): View {
        val card = Phone.card(this, "Video while driving")
        card.addView(
            Phone.body(
                this,
                "What you show on the car screen is your responsibility, and " +
                    "playing video while driving is illegal in many places. Headway " +
                    "does not decide for you.",
            ),
        )
        parkedOnlySwitch = SwitchCompat(this).apply {
            text = "Only allow video apps while parked"
            setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            isChecked = HeadwaySettings.parkedOnlyVideo(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                HeadwaySettings.of(this@MainActivity).edit()
                    .putBoolean(HeadwaySettings.KEY_PARKED_ONLY_VIDEO, checked)
                    .apply()
                SessionLog.shared.info(TAG, "parked-only video set to $checked")
            }
            layoutParams = Phone.spaced(this@MainActivity, 12f)
        }
        card.addView(parkedOnlySwitch)
        card.addView(
            Phone.button(this, "Show the safety notice again") { showSafetyNotice(firstRun = false) },
        )
        return card
    }

    /**
     * The one button that answers everything answerable without a car.
     *
     * Four entries in `BLOCKERS.md` were each written as "one device test
     * settles it" and then sat open, because settling them meant three separate
     * hunts in three different places. The thing that makes them cheap is easy
     * to miss: **binding a car app does not need a car.** A `CarAppService` is
     * bound over local binder, so Organic Maps on this phone accepts or refuses
     * Headway with no head unit involved. Same for the display list, the
     * install collisions and every permission.
     *
     * So this runs the lot and prints one report. See [SelfTest].
     */
    private fun buildSelfTestCard(): View {
        val card = Phone.card(this, "Self-test")
        card.addView(
            Phone.body(
                this,
                "Checks everything that can be checked without the car: which apps " +
                    "will let Headway draw them, which displays this phone has, what " +
                    "Headway has been granted, and which music apps open their library.",
            ),
        )
        selfTestStatus = Phone.body(this, "Not run yet.").apply {
            layoutParams = Phone.spaced(this@MainActivity, 10f)
        }
        card.addView(selfTestStatus)
        card.addView(
            Phone.note(
                this,
                "Takes a few seconds per app, because each one is really connected to " +
                    "and disconnected from. Nothing is changed and no permission is " +
                    "asked for.",
            ).apply { layoutParams = Phone.spaced(this@MainActivity, 8f) },
        )
        selfTestButton = Phone.button(this, "Run the self-test") { runSelfTest() }
        card.addView(selfTestButton)
        return card
    }

    /**
     * Runs it off the main thread, because [SelfTest.run] blocks on binds it
     * posts *to* the main thread and would otherwise deadlock against itself.
     */
    private fun runSelfTest() {
        if (selfTestRunning) return
        // Before anything is disabled: bailing out after the button is greyed
        // would leave it greyed for good.
        val scope = updateScope ?: return
        selfTestRunning = true
        selfTestButton.isEnabled = false
        selfTestButton.alpha = 0.5f
        selfTestStatus.text = "Starting…"

        val application = applicationContext
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    SelfTest.run(application) { step ->
                        // Back to main for the view; the callback arrives on IO.
                        // The destroyed check is not paranoia: the IO block
                        // holds a blocking bind and cannot be cancelled at a
                        // suspension point, so it outlives a rotation.
                        runOnUiThread {
                            if (selfTestRunning && !isFinishing && !isDestroyed) {
                                selfTestStatus.text = step
                            }
                        }
                    }
                }.getOrElse { failure ->
                    "The self-test itself failed: $failure\n\nThat is a Headway bug — " +
                        "please export the log and report it."
                }
            }
            selfTestRunning = false
            selfTestButton.isEnabled = true
            selfTestButton.alpha = 1f
            selfTestStatus.text = summarise(report)
            showSelfTestReport(report)
        }
    }

    /** The one line the card keeps after the dialog is dismissed. */
    private fun summarise(report: String): String {
        val verdict = report.lineSequence()
            .firstOrNull { it.contains(" accepted, ") }
            ?.trim()
            ?: "Report ready."
        return "$verdict Tap to read it again."
    }

    private fun showSelfTestReport(report: String) {
        // Monospace and horizontally scrollable: the report aligns its columns
        // with spaces, and a proportional font in a wrapping dialog turns that
        // into a smear.
        val body = TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
            setTextIsSelectable(true)
            val side = dp(20)
            setPadding(side, dp(12), side, dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(
                HorizontalScrollView(this@MainActivity).apply { addView(body) },
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Self-test")
            .setView(scroll)
            .setPositiveButton("Share") { _, _ -> shareSelfTest(report) }
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Headway self-test", report))
                toast("Copied")
            }
            .setNegativeButton("Close", null)
            .show()
        selfTestStatus.setOnClickListener { showSelfTestReport(report) }
    }

    private fun shareSelfTest(report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Headway self-test (build ${BuildConfig.VERSION_CODE})")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share the self-test")) }
            .onFailure { toast("Nothing on this phone can share text") }
    }

    private fun buildDiagnosticsCard(): View {
        val card = Phone.card(this, "Diagnostics")
        card.addView(
            Phone.note(
                this,
                "If the car refuses to connect, export the log and send it with your " +
                    "report. Wi-Fi passwords are removed from it, and only debug " +
                    "builds can write encryption keys.",
            ),
        )
        card.addView(Phone.button(this, "Export the session log") { exportLog() })
        return card
    }

    private fun buildUpdatesCard(): View {
        val card = Phone.card(this, "Updates")
        updateValue = Phone.body(
            this,
            "Build ${BuildConfig.VERSION_CODE} installed (${BuildConfig.FLAVOR})",
        )
        card.addView(updateValue)

        // Why the variant is on screen at all: it is the difference between a
        // car app refusing Headway because of a bug and refusing it because
        // this APK never declared the permission. Without it the Car apps tab
        // is unreadable, and the driver has no way to know which APK they
        // installed months ago.
        card.addView(
            Phone.note(
                this,
                if (BuildConfig.CAR_APP_HOST) {
                    "This is the host build: it declares the car-app renderer " +
                        "permission, so apps that publish a car interface can draw on " +
                        "the car screen. If a future update ever says \"App not " +
                        "installed\", the compat build in the same release installs " +
                        "anywhere — you would lose only that."
                } else {
                    "This is the compat build. It claims no global permission and no " +
                        "provider authority, so it installs on any phone — including " +
                        "one that has Android Auto. The cost is the car-app host: apps " +
                        "that publish a car interface will refuse Headway by name. " +
                        "Everything else is identical. The host build in the same " +
                        "release upgrades this one with no uninstall."
                },
            ).apply { layoutParams = Phone.spaced(this@MainActivity, 6f) },
        )

        // A failed install reports itself in a Toast that is gone in four
        // seconds, from a broadcast receiver with no window to put a dialog in.
        // This is the record that survives.
        installFailureRow = Phone.StatusRow(this, "Last install")
        card.addView(installFailureRow.view)

        card.addView(
            Phone.note(
                this,
                "Headway is installed from GitHub releases, so it checks there. This " +
                    "only ever happens when you press the button — nothing checks in " +
                    "the background, and the car link never uses the internet.",
            ).apply { layoutParams = Phone.spaced(this@MainActivity, 6f) },
        )
        updateButton = Phone.button(this, "Check for updates") { checkForUpdate() }
        card.addView(updateButton)
        return card
    }

    private fun refreshInstallFailure() {
        if (!::installFailureRow.isInitialized) return
        val failure = UpdateReceiver.lastFailure(this)
        installFailureRow.set(
            if (failure == null) Phone.Level.GOOD else Phone.Level.WARN,
            failure ?: "No failed install to report.",
        )
    }

    // --- state --------------------------------------------------------------

    private fun refresh() {
        tabsCard?.refresh()
        refreshAppDisplay()
        refreshPermissions()
        refreshPhonePermissions()
        refreshAccessibility()
        refreshGrants()
        refreshInstallFailure()
        // Re-read rather than trust the widget: the preferences are shared with
        // the car launcher and with anything else that may have changed them
        // while this activity was stopped.
        parkedOnlySwitch.isChecked = HeadwaySettings.parkedOnlyVideo(this)
        dashboardSwitch.isChecked = HeadwaySettings.dashboardOnCarScreen(this)
        if (::appDisplaySwitch.isInitialized) {
            appDisplaySwitch.isChecked = HeadwaySettings.of(this)
                .getBoolean(HeadwaySettings.KEY_NATIVE_APP_DISPLAY, false)
        }
        if (::certificateValue.isInitialized) {
            certificateValue.text =
                dev.headway.app.link.PhoneCertificateStore.inAppStorage(this).describe()
        }
        if (::carWifiValue.isInitialized) carWifiValue.text = describeCarWifi()
    }

    /**
     * The three grants that are not runtime permissions.
     *
     * Each is a user-granted special access rather than anything privileged —
     * the same class as the accessibility toggle — and none of them can be
     * requested with a dialog. All Headway can do is report the state and open
     * the right Settings page, which is what the rows' buttons do.
     */
    private fun refreshGrants() {
        val notifications = NowPlayingTile.notificationAccessGranted(this)
        notificationStatus.set(
            if (notifications) Phone.Level.GOOD else Phone.Level.WARN,
            if (notifications) {
                "On — the car shows what is playing, and your messages"
            } else {
                "Off — the Now playing and Messages panes will be empty"
            },
        )

        // Checked on every resume rather than once: the unpack runs in the
        // background from onCreate, so the first read of this is usually "not
        // yet" and the true answer arrives seconds later.
        val speech = SpeechModelInstaller.isInstalled(applicationContext)
        speechStatus.set(
            if (speech) Phone.Level.GOOD else Phone.Level.IDLE,
            if (speech) "Installed — voice commands work with no network" else "Unpacking...",
        )

        val companion = CarCompanion.of(this)
        val ssid = HeadwayService.lastCarWifi(this)?.ssid
        val paired = ssid != null && companion.associatedBssid(ssid) != null
        pairingStatus.set(
            when {
                paired -> Phone.Level.GOOD
                // Not a fault before the car has ever been seen: there is
                // nothing to pair with yet, and the row should not be orange
                // for a step the user cannot take.
                ssid == null || !companion.available -> Phone.Level.IDLE
                else -> Phone.Level.WARN
            },
            companion.describe(ssid),
        )
    }

    /** What Headway knows about the car's network, for the settings screen. */
    private fun describeCarWifi(): String {
        val known = dev.headway.app.service.HeadwayService.lastCarWifi(this)
            ?: return "No car network known yet — connect once so the head unit hands " +
                "its Wi-Fi details over Bluetooth, then come back here."
        return "Ready to save \"${known.ssid}\", learned from the car over Bluetooth."
    }

    /**
     * Hands the car's Wi-Fi to the system's add-networks panel.
     *
     * Launched from here rather than from the service on purpose: it is an
     * activity intent, and background-activity-launch rules make it unreliable
     * from a service — which is also the only time Headway holds live
     * credentials, hence the stash this reads from.
     */
    private fun setUpCarWifi() {
        val known = dev.headway.app.service.HeadwayService.lastCarWifi(this)
        if (known == null) {
            AlertDialog.Builder(this)
                .setTitle("No car network yet")
                .setMessage(
                    "Headway learns the car's Wi-Fi name and password from the head " +
                        "unit over Bluetooth. Press Connect once with the car nearby — " +
                        "even if it fails to finish — and this will be ready.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val intent = dev.headway.app.link.CarWifiProvisioning.addNetworksIntent(known)
        runCatching { addCarNetwork.launch(intent) }.onFailure {
            // No panel on this build. Say what to do by hand rather than
            // failing silently -- the passphrase is the part worth not typing,
            // so offer it explicitly.
            AlertDialog.Builder(this)
                .setTitle("This phone has no Wi-Fi setup panel")
                .setMessage(
                    "Join \"${known.ssid}\" by hand in Android's Wi-Fi settings, then set " +
                        "Privacy to \"Use per-network randomized MAC\" and turn \"Send " +
                        "device name to network\" on.",
                )
                .setPositiveButton("Open Wi-Fi settings") { _, _ ->
                    runCatching {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    /**
     * The runtime permissions, as one line.
     *
     * A list of four rows saying "granted" was four rows of nothing on a phone
     * where they are all granted, which is the usual case after the first run.
     * The row names what is missing instead, and says nothing when nothing is.
     */
    /**
     * Opens the companion chooser so the driver can pair with the car's Wi-Fi.
     *
     * One tap, once, and every later join is silent — including the automatic
     * reconnection that happens with the phone in a pocket, which no approval
     * sheet can ever be part of. `CarCompanion` has the AOSP citations for why
     * this works.
     *
     * The chooser needs the access point to be **on the air right now**, because
     * it is populated from a live scan the system runs. That is why this says so
     * rather than silently timing out: the car brings its Wi-Fi up when
     * projection starts, so the reliable moment to pair is during a connection
     * attempt, with the car on.
     */
    private fun pairWithCarWifi() {
        val known = HeadwayService.lastCarWifi(this)
        if (known == null) {
            AlertDialog.Builder(this)
                .setTitle("No car network yet")
                .setMessage(
                    "Headway learns the car's Wi-Fi name from the head unit over Bluetooth. " +
                        "Press Connect once with the car nearby — even if it fails to " +
                        "finish — and this will be ready.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val companion = CarCompanion.of(this)
        if (!companion.available) {
            toast("This phone has no companion-device support")
            return
        }
        if (companion.associatedBssid(known.ssid) != null) {
            AlertDialog.Builder(this)
                .setTitle("Already paired")
                .setMessage(
                    "Headway is paired with \"${known.ssid}\". Unpair it here to go back " +
                        "to Android's approval prompt — Android has no Settings page that " +
                        "lists a Wi-Fi pairing, so this button is the way to undo it.",
                )
                .setPositiveButton("Keep it", null)
                .setNegativeButton("Unpair") { _, _ ->
                    companion.forget(known.ssid)
                    refresh()
                }
                .show()
            return
        }

        pendingPairSsid = known.ssid
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return
        toast("Turn the car on first — the list only shows networks on the air")
        val request = companion.requestFor(
            known.ssid,
            HeadwayService.lastCarBssid(this),
        )
        runCatching {
            manager.associate(
                request,
                // The MAIN executor, not a direct one. `associate`'s callback is
                // an `IAssociationRequestCallback.Stub` dispatched through
                // `mExecutor.execute(...)`, so a direct executor runs it on a
                // binder thread — and `onAssociationPending` below calls
                // `ActivityResultLauncher.launch`, which is main-thread-only.
                // The `runOnUiThread` in `onFailure` shows the hazard was seen
                // in one branch and missed in the other.
                ContextCompat.getMainExecutor(this),
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: android.content.IntentSender) {
                        // On the main thread now, by construction of the
                        // executor above.
                        runCatching {
                            pairWithCar.launch(
                                androidx.activity.result.IntentSenderRequest.Builder(intentSender)
                                    .build(),
                            )
                        }.onFailure {
                            pendingPairSsid = null
                            SessionLog.shared.info(TAG, "could not show the pairing chooser: $it")
                            toast("Could not open the pairing screen")
                        }
                    }

                    override fun onFailure(error: CharSequence?) {
                        pendingPairSsid = null
                        // The common one is "no devices found", which means the
                        // car's access point was not broadcasting. Say that
                        // rather than echoing the platform's phrasing.
                        SessionLog.shared.info(TAG, "car Wi-Fi pairing failed: $error")
                        toast(
                            "No car network found. Turn the car on, start Android Auto " +
                                "on its screen, then try again.",
                        )
                    }
                },
            )
        }.onFailure {
            pendingPairSsid = null
            toast("Pairing is not available on this phone: ${it.message}")
        }
    }

    private fun refreshPermissions() {
        val missing = requiredPermissions().filterNot { isGranted(it.permission) }
        permissionStatus.set(
            if (missing.isEmpty()) Phone.Level.GOOD else Phone.Level.WARN,
            if (missing.isEmpty()) {
                "All granted. Location is never requested."
            } else {
                "Missing: " + missing.joinToString(", ") { it.label.substringAfter(": ") }
            },
        )
    }

    /**
     * The phone pane's row, which is green on a partial grant.
     *
     * Deliberately not "all four or warn". Each of the four buys a distinct
     * piece of the pane and the pane works without any of them — the live call
     * comes from the notification listener, which is a different row entirely.
     * Warning about a missing `ANSWER_PHONE_CALLS` on a phone whose dialer
     * publishes a perfectly good answer action would be crying wolf.
     */
    private fun refreshPhonePermissions() {
        val group = phonePermissions()
        val missing = group.filterNot { isGranted(it.permission) }
        phoneStatus.set(
            when {
                missing.isEmpty() -> Phone.Level.GOOD
                missing.size == group.size -> Phone.Level.IDLE
                else -> Phone.Level.GOOD
            },
            when {
                missing.isEmpty() -> "Recent calls, contacts, dialling and answering."
                missing.size == group.size ->
                    "Optional. Grant these and the car's Phone pane shows recent " +
                        "calls and can dial."
                else -> "Partly granted; missing " +
                    missing.joinToString(", ") { it.label.substringBefore(":").lowercase() }
            },
        )
    }

    private fun refreshAccessibility() {
        val enabled = HeadwayAccessibilityService.isEnabled(this)
        val bound = HeadwayAccessibilityService.instance.value != null
        accessibilityStatus.set(
            when {
                enabled && bound -> Phone.Level.GOOD
                // Enabled in Settings but not yet bound is normal for a second
                // or two after the toggle, and permanent if the service
                // crashed.
                enabled -> Phone.Level.IDLE
                else -> Phone.Level.WARN
            },
            when {
                enabled && bound -> "On — the car's touchscreen controls the phone"
                enabled -> "Switched on, waiting for Android to start it"
                else -> "Off — touches on a mirrored app will do nothing"
            },
        )
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
                is LinkState.Connected -> dev.headway.app.ui.theme.Headway.GOOD
                is LinkState.GaveUp -> dev.headway.app.ui.theme.Headway.FAULT
                else -> dev.headway.app.ui.theme.Headway.TEXT
            },
        )

        // The mark sweeps while the link is being brought up and rests once it
        // is either up or given up on. It is the only moving thing on the
        // screen, so it says "something is happening" without a label.
        if (::mark.isInitialized) {
            mark.travelling = state is LinkState.Connecting || state is LinkState.WaitingToRetry
        }

        val idle = state is LinkState.Idle || state is LinkState.GaveUp
        connectButton.text = if (idle) "Connect to the car" else "Disconnect"
        // Filled while there is something to start, outlined while the only
        // thing it can do is stop: the accent is reserved for the action the
        // user came here to take.
        Phone.applyPill(connectButton, primary = idle)

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
        // The car screen already does this for itself; the phone
        // screen needs it for exactly as long as the join does.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask for screen capture before the session, not during it. See
        // requestProjection: the car's patience is about fifteen seconds.
        val projectionManager =
            getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val consent = runCatching {
            projectionManager?.let { ProjectionRequestActivity.captureIntentFor(this, it) }
        }.getOrNull()
        if (consent == null) {
            SessionLog.shared.info(TAG, "no screen capture available on this device; " +
                "connecting without video")
            connectWithProjection(RESULT_CANCELED, null)
            return
        }
        runCatching { requestProjection.launch(consent) }.onFailure {
            SessionLog.shared.info(TAG, "could not ask for screen capture: ${it.message}")
            connectWithProjection(RESULT_CANCELED, null)
        }
    }

    /** Starts the service, with the projection grant if the user gave one. */
    private fun connectWithProjection(resultCode: Int, data: android.content.Intent?) {
        HeadwayService.start(
            this,
            projectionResultCode = resultCode,
            projectionData = data,
        )
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

    private fun requestMissingPermissions(
        group: List<PermissionNeed> = requiredPermissions(),
        allGrantedMessage: String = "All the permissions Headway needs are granted",
    ) {
        val missing = group
            .filterNot { isGranted(it.permission) }
            .map { it.permission }
        if (missing.isEmpty()) {
            toast(allGrantedMessage)
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

    /**
     * Opens the notification-access list, which is what makes the media panes work.
     *
     * This grant is the answer to "the car shows an empty Now playing pane", and
     * until this build there was no way to find that out from inside Headway:
     * the tile said to turn it on "in Headway on the phone", and Headway on the
     * phone did not mention it at all.
     *
     * `getActiveSessions` does not take a permission string — it takes the
     * `ComponentName` of one of the caller's own enabled
     * `NotificationListenerService`s. Headway has one, so this single toggle
     * serves both the Now playing pane and the Messages pane.
     */
    private fun openNotificationAccess() {
        val intent = NowPlayingTile.notificationAccessIntent().setFlags(0)
        if (intent.resolveActivity(packageManager) == null) {
            toast("This device has no notification access screen")
            return
        }
        startActivity(intent)
        toast("Find Headway in the list and switch it on")
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
                "Headway puts content on your car's display, and can mirror this " +
                    "phone's screen there. What you choose to show is your " +
                    "responsibility.\n\n" +
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

    /**
     * The phone pane's four, kept out of [requiredPermissions] on purpose.
     *
     * They are genuinely optional: nothing about connecting to a car needs
     * them, and a driver who only wants music should not be asked for their
     * call log to get it. Putting them in the required list would also have put
     * them in [essentialPermissions], which gates the Connect button — refusing
     * to connect a car because contacts were declined would be absurd.
     *
     * So they get their own row, asked for only when it is pressed, and the
     * phone pane says which one is missing when one is.
     */
    private fun phonePermissions(): List<PermissionNeed> = listOf(
        PermissionNeed(Manifest.permission.READ_CALL_LOG, "Call log: recent calls"),
        PermissionNeed(Manifest.permission.READ_CONTACTS, "Contacts: name the caller"),
        PermissionNeed(Manifest.permission.CALL_PHONE, "Calling: dial from the car"),
        PermissionNeed(Manifest.permission.ANSWER_PHONE_CALLS, "Answering: pick up from the car"),
    )

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
                setTextColor(dev.headway.app.ui.theme.Headway.TEXT)
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
            Phone.spaced(this, 12f),
        )
        row.addView(Phone.note(this, explanation))
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

        // The palette lives in `dev.headway.app.ui.theme.Headway`, which is
        // derived from the launcher icon and shared with every car surface. The
        // ARGB literals that used to be here were a second, slightly different
        // set of the same colours, and the two had already drifted: this
        // screen's accent was #7EC8FF against the icon's #4FC3F7.
    }
}
