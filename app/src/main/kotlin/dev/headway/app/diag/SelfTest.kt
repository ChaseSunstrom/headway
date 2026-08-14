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

package dev.headway.app.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.headway.app.BuildConfig
import dev.headway.app.carapp.CarAppSession
import dev.headway.app.carapp.CarConnectionProvider
import dev.headway.app.carapp.HostState
import dev.headway.app.carapp.TemplateApp
import dev.headway.app.carapp.TemplateApps
import dev.headway.app.dash.tiles.NowPlayingTile
import dev.headway.app.input.HeadwayAccessibilityService
import dev.headway.app.log.SessionLog
import dev.headway.app.media.BrowseState
import dev.headway.app.media.CarMediaBrowser
import dev.headway.app.media.MediaApp
import dev.headway.app.media.MediaApps
import dev.headway.app.phone.CarPhone
import dev.headway.app.video.OverlayDisplay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "HeadwaySelfTest"

/**
 * Everything about Headway that can be answered without a car.
 *
 * ## Why this exists
 *
 * Four entries in `BLOCKERS.md` — B-012, B-013, B-014 and half of B-015 — were
 * written as "one device test settles it", and then sat open, because settling
 * them meant three separate manual hunts in three different places: open the Car
 * apps tab and read a log line, press a button on the setup screen, export the
 * log and grep it. Nobody does all three, so nobody did any.
 *
 * The thing that makes them all cheap is easy to miss: **binding a car app does
 * not need a car.** A `CarAppService` is bound over local binder, so Organic
 * Maps on the phone will accept or refuse Headway as a host with no head unit
 * involved, no Wi-Fi, and no drive. The same is true of the display list, the
 * install-collision questions and every permission. Only the screen-capture
 * chooser and the actual rendering on the panel need the car.
 *
 * So this runs all of it at once and prints one report. One screenshot settles
 * four blockers.
 *
 * ## What it deliberately does not do
 *
 * It does not touch the car link, request any permission, or change any setting.
 * It reads state and it binds services that are already bindable. A driver can
 * run it at any time, including mid-drive, without affecting a session — with
 * one caveat it states in its own output: binding a media app briefly connects
 * to that app, which a fussy player may notice.
 *
 * ## Threading
 *
 * [run] blocks and must be called off the main thread. Both `CarAppSession` and
 * `CarMediaBrowser` are main-thread-only — the latter because `MediaBrowser`
 * binds its internal handler to the constructing thread's looper — so every
 * connect and close is posted to main and waited on here.
 */
object SelfTest {

    /**
     * Runs every check and returns the report.
     *
     * @param onProgress called with each section heading as it starts, so a
     *   caller can show something during the binds; invoked on this thread.
     */
    fun run(context: Context, onProgress: (String) -> Unit = {}): String {
        // Not a style rule. Every bind below is posted to main and then waited
        // on from here, so running this *on* main deadlocks it for the full
        // timeout, once per installed app, and the driver sees an ANR rather
        // than a report.
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "SelfTest.run blocks on binds posted to the main looper; call it off the main thread"
        }
        val app = context.applicationContext
        val out = StringBuilder()

        fun section(title: String) {
            onProgress(title)
            out.append('\n').append(title).appendLine().append("-".repeat(title.length)).appendLine()
        }

        out.append("Headway self-test").appendLine()
        // Stamped, because this report exists to be pasted into an issue, and a
        // report without the build and the device is a report nobody can act on.
        out.append("build ").append(BuildConfig.VERSION_CODE)
            .append(" ").append(BuildConfig.FLAVOR)
            .append(" · ").append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE)
            .appendLine()
        out.append("Everything here is answerable without a car. What is not, is listed at the end.")
            .appendLine()

        section("Car apps — does anything accept Headway as a host?")
        out.append(carApps(app, onProgress))

        section("Displays")
        out.append(OverlayDisplay.diagnose(app))

        section("Grants")
        out.append(grants(app))

        section("Install collisions")
        out.append(collisions(app))

        section("Media apps — whose library can Headway walk?")
        out.append(mediaApps(app, onProgress))

        section("Still needs the car")
        out.append(REMAINING)

        val report = out.toString()
        SessionLog.shared.info(TAG, "self-test run:\n$report")
        return report
    }

    // --- the car-app host ------------------------------------------------------

    /**
     * Binds every installed car app and reports what it said.
     *
     * This is the whole of B-012. `HostValidator` runs inside the app's own
     * process and decides there; the only way to know its verdict is to ask, and
     * asking costs one local bind per app.
     *
     * Each session is closed before the next is opened, so the test never holds
     * several third-party processes alive at once.
     */
    private fun carApps(context: Context, onProgress: (String) -> Unit): String {
        val apps = TemplateApps.installed(context)
        if (apps.isEmpty()) {
            return "No app on this phone exports a CarAppService, so there is nothing to " +
                "ask. Install one — Organic Maps and OsmAnd both ship one — and run this " +
                "again.\n"
        }
        val out = StringBuilder()
        var accepted = 0
        var refused = 0
        apps.forEach { app ->
            onProgress("Asking ${app.label}…")
            val verdict = ask(context, app)
            if (verdict.state == HostState.RUNNING) accepted++
            if (verdict.state == HostState.REFUSED) refused++
            out.append("  ").append(app.label)
                .append(" (").append(app.packageName).append("): ")
                .append(verdict.line).appendLine()
        }
        out.appendLine()
        out.append("  ").append(accepted).append(" accepted, ").append(refused)
            .append(" refused, ").append(apps.size - accepted - refused)
            .append(" neither.").appendLine()
        out.append(
            when {
                accepted > 0 ->
                    "  B-012 is answered: the TEMPLATE_RENDERER route works against a real app.\n"
                !BuildConfig.CAR_APP_HOST ->
                    "  This is the compat build, which does not declare the renderer\n" +
                        "  permission, so a refusal here is expected and says nothing about\n" +
                        "  B-012. Install the host APK from the same release to test it.\n"
                else ->
                    "  B-012 is answered the other way: no installed app took the permission\n" +
                        "  route. A locally built debug APK of Organic Maps or OsmAnd accepts any\n" +
                        "  host and is the way to tell a Headway bug from an allowlist refusal.\n"
            },
        )
        return out.toString()
    }

    private class Verdict(val state: HostState, val line: String)

    /**
     * One bind, one handshake, one answer.
     *
     * Waits for any state that is not WORKING. `CarAppSession` bounds every step
     * of the handshake itself, so the latch below is a backstop rather than the
     * real timeout — but it has to exist, because a session that somehow settled
     * before the listener was attached would otherwise wait forever.
     */
    private fun ask(context: Context, app: TemplateApp): Verdict {
        val main = Handler(Looper.getMainLooper())
        val settled = CountDownLatch(1)
        val seen = AtomicReference(HostState.IDLE)
        val held = AtomicReference<CarAppSession?>(null)

        main.post {
            val opened = CarAppSession(context, app) { }
            held.set(opened)
            opened.connect { state, _ ->
                if (state == HostState.WORKING) return@connect
                seen.set(state)
                settled.countDown()
            }
        }
        val answered = settled.await(ASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Read the session's account of itself on main, where every one of
        // those fields was written, and close it in the same post: doing both
        // in one hop means the next app's bind cannot race this one's teardown.
        val fault = AtomicReference<String?>(null)
        val described = AtomicReference<String?>(null)
        val closed = CountDownLatch(1)
        main.post {
            val session = held.get()
            fault.set(session?.fault)
            described.set(session?.describe())
            runCatching { session?.close() }
            closed.countDown()
        }
        closed.await(ASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!answered) {
            // The session's own watchdogs fire in well under this, so reaching
            // here means the main looper was busy rather than the app silent —
            // worth distinguishing, since the advice is completely different.
            val why = fault.get()?.let { " ($it)" }.orEmpty()
            return Verdict(HostState.TIMED_OUT, "no answer in ${ASK_TIMEOUT_SECONDS}s$why")
        }
        val state = seen.get()
        // describe() leads with the app's label, which this line has already
        // printed.
        val detail = described.get()?.removePrefix("${app.label}: ")
        return Verdict(
            state,
            when (state) {
                HostState.RUNNING -> "ACCEPTED — ${detail ?: "handshake completed"}"
                HostState.REFUSED -> "refused — ${fault.get() ?: "no reason given"}"
                HostState.TIMED_OUT -> "timed out — ${fault.get() ?: "no answer"}"
                HostState.FAILED -> "failed — ${fault.get() ?: "no reason given"}"
                else -> "finished without answering (${state.name.lowercase()})"
            },
        )
    }

    // --- grants ------------------------------------------------------------------

    private fun grants(context: Context): String {
        val out = StringBuilder()
        fun line(name: String, ok: Boolean, detail: String = "") {
            out.append("  ").append(if (ok) "yes" else "NO ").append("  ").append(name)
            if (detail.isNotEmpty()) out.append(" — ").append(detail)
            out.appendLine()
        }

        line(
            "android.car.permission.TEMPLATE_RENDERER",
            context.packageManager.checkPermission(TEMPLATE_RENDERER, context.packageName) ==
                PackageManager.PERMISSION_GRANTED,
            if (BuildConfig.CAR_APP_HOST) {
                "without it every car app answers \"Unknown host\""
            } else {
                // Not a fault in this build, and saying so matters: a bare "NO"
                // here would send someone hunting a grant that was deliberately
                // never asked for.
                "not declared by the compat build, by design — the host build has it"
            },
        )
        line("car-connection provider answers", providerState(context) != null, describeProvider(context))
        line("notification listener", NowPlayingTile.notificationAccessGranted(context))
        line("accessibility (car touchscreen)", HeadwayAccessibilityService.isEnabled(context))
        listOf(
            "read the call log" to Manifest.permission.READ_CALL_LOG,
            "read contacts" to Manifest.permission.READ_CONTACTS,
            "place calls" to Manifest.permission.CALL_PHONE,
            "answer calls" to Manifest.permission.ANSWER_PHONE_CALLS,
        ).forEach { (name, permission) -> line(name, CarPhone.granted(context, permission)) }
        return out.toString()
    }

    /** The provider's own answer, or null when nothing answered the authority. */
    private fun providerState(context: Context): Int? = runCatching {
        context.contentResolver.query(
            android.net.Uri.parse("content://${CarConnectionProvider.AUTHORITY}"),
            arrayOf(CarConnectionProvider.CAR_CONNECTION_STATE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getInt(cursor.getColumnIndexOrThrow(CarConnectionProvider.CAR_CONNECTION_STATE))
        }
    }.getOrNull()

    private fun describeProvider(context: Context): String = when (providerState(context)) {
        CarConnectionProvider.CONNECTION_TYPE_PROJECTION -> "says PROJECTION — a car is connected"
        CarConnectionProvider.CONNECTION_TYPE_NOT_CONNECTED -> "says NOT_CONNECTED, which is honest"
        null -> "nothing answered the authority"
        else -> "answered something unexpected"
    }

    // --- install collisions --------------------------------------------------------

    /**
     * Whether anything else on this phone owns what Headway had to claim.
     *
     * B-013 and B-014 both say Headway will fail to *install* alongside a
     * package that already declares the permission or owns the provider
     * authority. That is checkable directly, and the answer is far more useful
     * than the prediction: if Headway is running at all, no collision happened,
     * and this names whatever else is in the neighbourhood.
     */
    private fun collisions(context: Context): String {
        val packages = context.packageManager
        val out = StringBuilder()

        val definer = runCatching {
            packages.getPermissionInfo(TEMPLATE_RENDERER, 0).packageName
        }.getOrNull()
        out.append("  TEMPLATE_RENDERER is defined by: ")
            .append(definer ?: "nothing (unexpected — Headway declares it)")
            .append(if (definer == context.packageName) " — Headway itself, as designed" else "")
            .appendLine()
        if (definer != null && definer != context.packageName) {
            out.append("    B-013: another package owns it. Headway installed anyway, so it\n")
                .append("    is not using its own declaration — check the grant above.\n")
        }

        val provider = runCatching {
            packages.resolveContentProvider(CarConnectionProvider.AUTHORITY, 0)?.packageName
        }.getOrNull()
        out.append("  androidx.car.app.connection is owned by: ")
            .append(provider ?: "nothing")
            .append(if (provider == context.packageName) " — Headway itself, as designed" else "")
            .appendLine()
        if (provider != null && provider != context.packageName) {
            out.append("    B-014: another package owns the authority, so Headway's provider\n")
                .append("    is not the one apps are reading.\n")
        }
        return out.toString()
    }

    // --- media -----------------------------------------------------------------------

    /**
     * Connects to each media app's browser and reports whether it serves a tree.
     *
     * Not a blocker, but the same shape of question and the same cost: the
     * media pane's reach is "whichever apps allow browsing", and until now the
     * only way to find out was to open the pane and tap through every app.
     */
    private fun mediaApps(context: Context, onProgress: (String) -> Unit): String {
        val apps = MediaApps.installed(context)
        if (apps.isEmpty()) return "  No app on this phone exports a media browser service.\n"
        val out = StringBuilder()
        apps.forEach { app ->
            onProgress("Browsing ${app.label}…")
            out.append("  ").append(app.label).append(": ").append(browse(context, app).name.lowercase())
                .appendLine()
        }
        out.appendLine()
        out.append("  \"open\" means the library is browsable from the car. \"refused\" is the\n")
            .append("  app's own choice and leaves it fully controllable in Now playing.\n")
        return out.toString()
    }

    private fun browse(context: Context, app: MediaApp): BrowseState {
        val main = Handler(Looper.getMainLooper())
        val settled = CountDownLatch(1)
        val seen = AtomicReference(BrowseState.UNKNOWN)
        val held = AtomicReference<CarMediaBrowser?>(null)

        main.post {
            val opened = CarMediaBrowser(context, app)
            held.set(opened)
            opened.connect { state, _ ->
                if (state == BrowseState.WORKING) return@connect
                seen.set(state)
                settled.countDown()
            }
        }
        val answered = settled.await(ASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val closed = CountDownLatch(1)
        main.post {
            runCatching { held.get()?.close() }
            closed.countDown()
        }
        closed.await(ASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return if (answered) seen.get() else BrowseState.TIMED_OUT
    }

    // --- constants -------------------------------------------------------------------

    private const val TEMPLATE_RENDERER = "android.car.permission.TEMPLATE_RENDERER"

    /**
     * The backstop, not the real deadline.
     *
     * Both session classes bound every step themselves; this only catches a
     * settle that happened before the listener was attached. Generous, because
     * the alternative is reporting a timeout for an app that was merely cold.
     */
    private const val ASK_TIMEOUT_SECONDS = 20L

    private val REMAINING: String = buildString {
        appendLine("  These cannot be answered on the phone alone:")
        appendLine()
        appendLine("  - Whether Android's screen-capture chooser offers the simulated display")
        appendLine("    as its own row (the rest of B-015). Press Connect with \"Render apps on")
        appendLine("    the car display\" on and look at the dialog: a row named for that")
        appendLine("    display means the route works; only \"Entire screen\" and \"A single")
        appendLine("    app\" means the platform flag is off and this phone cannot do it.")
        appendLine()
        appendLine("  - Everything drawn on the panel: the tabs, the phone, maps and car-app")
        appendLine("    panes, touch coming back, audio, and voice. One drive's exported log")
        appendLine("    covers all of it.")
    }
}
