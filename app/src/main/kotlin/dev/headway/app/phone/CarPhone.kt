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

package dev.headway.app.phone

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dev.headway.app.log.SessionLog
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "HeadwayPhone"

/** One entry in the recent-calls list. */
data class RecentCall(
    val number: String,
    /** The contact's name when the provider knows one, else the number. */
    val name: String,
    /** `CallLog.Calls.INCOMING_TYPE` and friends. */
    val type: Int,
    val whenMillis: Long,
) {
    val missed: Boolean get() = type == CallLog.Calls.MISSED_TYPE
    val outgoing: Boolean get() = type == CallLog.Calls.OUTGOING_TYPE
}

/** A call in progress, as much of it as a notification will say. */
data class LiveCall(
    val who: String,
    /** True while it is still ringing and can be answered. */
    val ringing: Boolean,
    val answer: PendingIntent?,
    val hangUp: PendingIntent?,
    val source: String,
)

/**
 * The phone, as a model rather than as somebody else's screen.
 *
 * ## Why a notification reader and not an `InCallService`
 *
 * Because the `InCallService` a car wants is out of reach and the notification
 * is not.
 *
 * Telecom classifies every `InCallService` into three kinds. The one Android
 * Auto uses is `CAR_MODE_UI`, which needs `CONTROL_INCALL_EXPERIENCE` —
 * `signature|privileged|role`, granted only through
 * `SYSTEM_AUTOMOTIVE_PROJECTION`, a role marked `systemOnly="true"` and
 * populated from an OEM config overlay. There is no route to it for a sideloaded
 * app, and there is not meant to be. `DEFAULT_DIALER_UI` is available but
 * obliges Headway to *become* the phone's dialer, emergency calling and all,
 * which is the wrong trade for a car app.
 *
 * What is left is the notification, and since Android 12 it is a genuinely
 * structured model. `Notification.CallStyle` publishes the caller as a `Person`
 * and the answer, decline and hang-up actions as `PendingIntent`s under
 * documented extras — everything this pane needs, through the listener Headway
 * already runs for messages, with no new permission at all.
 *
 * ## The fallback, and why it is not optional
 *
 * `CallStyle` is what a *modern* dialer uses. The AOSP Dialer that ships on
 * GrapheneOS still builds its call notification the old way, with plain
 * `addAction` entries and no `CallStyle` at all. So the extras will simply be
 * absent on the target phone, and reading them alone would produce a pane that
 * works on a Pixel with Google Dialer and never on the device this is for.
 *
 * [readLiveCall] therefore tries the structured path first and falls back to
 * the actions array, matching by the action title. That is uglier and it is
 * also the path that will actually run.
 *
 * ## Answering and ending
 *
 * Two routes, again. The notification's own `PendingIntent`s work with no
 * permission and are preferred, because they do exactly what tapping the
 * notification shade would. `TelecomManager.acceptRingingCall()` and `endCall()`
 * are the backstop for a dialer that publishes no actions; both need
 * `ANSWER_PHONE_CALLS`, which is an ordinary runtime prompt.
 */
object CarPhone {

    fun interface Listener {
        fun onCallChanged(call: LiveCall?)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var current: LiveCall? = null

    val call: LiveCall? get() = current

    fun observe(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onCallChanged(current)
    }

    fun unobserve(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Offers a notification to the phone model.
     *
     * @return true when it was a call, so the caller does not also file it as a
     *   conversation.
     */
    fun offer(context: Context, sbn: StatusBarNotification): Boolean {
        val parsed = readLiveCall(context, sbn) ?: return false
        publish(parsed)
        return true
    }

    fun withdraw(sbn: StatusBarNotification) {
        if (current?.source != sbn.packageName) return
        publish(null)
    }

    fun clear() {
        if (current == null) return
        publish(null)
    }

    private fun publish(call: LiveCall?) {
        current = call
        listeners.forEach { runCatching { it.onCallChanged(call) } }
    }

    /**
     * Reads a call out of a notification, structured path first.
     *
     * Exposed for testing: this is guesswork over other people's formatting and
     * it needs fixtures more than most things here do.
     */
    fun readLiveCall(context: Context, sbn: StatusBarNotification): LiveCall? {
        val notification = sbn.notification ?: return null
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) == 0 &&
            notification.category != Notification.CATEGORY_CALL
        ) {
            return null
        }
        if (notification.category != Notification.CATEGORY_CALL) return null

        val extras = notification.extras
        // --- the structured path, Android 12+ CallStyle ------------------------
        val callType = extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) ?: 0
        val person = runCatching {
            extras?.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        }.getOrNull()
        val answerIntent = runCatching {
            extras?.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
        }.getOrNull()
        val hangUpIntent = runCatching {
            extras?.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
        }.getOrNull()

        if (callType != 0 || answerIntent != null || hangUpIntent != null) {
            val who = person?.name?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: "Unknown caller"
            return LiveCall(
                who = who,
                // CALL_TYPE_INCOMING is 1. Comparing to the constant rather
                // than to "has an answer intent" because a screening
                // notification carries one too.
                ringing = callType == CALL_TYPE_INCOMING,
                answer = answerIntent,
                hangUp = hangUpIntent,
                source = sbn.packageName,
            )
        }

        // --- the fallback, for a dialer that never adopted CallStyle -----------
        val actions = notification.actions ?: return null
        val who = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown caller"
        var answer: PendingIntent? = null
        var hangUp: PendingIntent? = null
        for (action in actions) {
            val title = action.title?.toString()?.lowercase().orEmpty()
            val intent = action.actionIntent ?: continue
            when {
                ANSWER_WORDS.any { title.contains(it) } -> answer = answer ?: intent
                END_WORDS.any { title.contains(it) } -> hangUp = hangUp ?: intent
            }
        }
        if (answer == null && hangUp == null) return null
        return LiveCall(
            who = who,
            ringing = answer != null,
            answer = answer,
            hangUp = hangUp,
            source = sbn.packageName,
        )
    }

    // --- actions ---------------------------------------------------------------

    /** Answers, by the notification's own action where there is one. */
    fun answer(context: Context): Boolean {
        val live = current ?: return false
        live.answer?.let { pending ->
            val sent = runCatching { pending.send() }
            if (sent.isSuccess) return true
            SessionLog.shared.warn(TAG, "answer action refused: ${sent.exceptionOrNull()}")
        }
        if (!granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        // Deprecated in favour of an InCallService, which is the thing Headway
        // cannot be (see the class KDoc). Until that changes this is the only
        // route left, and it still works.
        @Suppress("DEPRECATION")
        return runCatching { telecom.acceptRingingCall(); true }.getOrDefault(false)
    }

    /** Ends or declines. */
    fun hangUp(context: Context): Boolean {
        val live = current ?: return false
        live.hangUp?.let { pending ->
            val sent = runCatching { pending.send() }
            if (sent.isSuccess) return true
            SessionLog.shared.warn(TAG, "hang-up action refused: ${sent.exceptionOrNull()}")
        }
        if (!granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return runCatching { telecom.endCall() }.getOrDefault(false)
    }

    /**
     * Places a call.
     *
     * `ACTION_CALL` and not `ACTION_DIAL`: dialling only fills the keypad in,
     * which on a car screen leaves the driver to press the green button on a
     * phone they should not be holding. With `CALL_PHONE` this connects; without
     * it, the dial intent is the honest fallback rather than nothing.
     */
    fun dial(context: Context, number: String, onStep: (String) -> Unit): Boolean {
        val uri = Uri.fromParts("tel", number, null)
        val direct = granted(context, Manifest.permission.CALL_PHONE)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { context.startActivity(intent) }
        if (started.isFailure) {
            onStep("phone: could not call $number (${started.exceptionOrNull()})")
            return false
        }
        onStep(if (direct) "phone: calling $number" else "phone: opened the dialer for $number")
        return true
    }

    // --- the call log ------------------------------------------------------------

    /**
     * The most recent calls, newest first.
     *
     * One cursor over `CallLog.Calls`, which is the whole of Android Auto's
     * phone screen minus the keypad. Empty without `READ_CALL_LOG`, which is a
     * plain runtime permission — the caller reports that state rather than
     * showing an empty list.
     */
    fun recentCalls(context: Context, limit: Int = MAX_RECENTS): List<RecentCall> {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val columns = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
        )
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                columns,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val out = mutableListOf<RecentCall>()
                val seen = mutableSetOf<String>()
                while (cursor.moveToNext() && out.size < limit) {
                    val number = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    // One row per number. A call log is mostly the same three
                    // people over and over, and a car list of twenty rows that
                    // are four contacts is a list nobody can use.
                    if (!seen.add(number)) continue
                    out += RecentCall(
                        number = number,
                        name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: number,
                        type = cursor.getInt(2),
                        whenMillis = cursor.getLong(3),
                    )
                }
                out
            }.orEmpty()
        }.getOrElse {
            SessionLog.shared.warn(TAG, "could not read the call log: $it")
            emptyList()
        }
    }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** `Notification.CALL_TYPE_INCOMING`, spelled out because it is API 31+. */
    private const val CALL_TYPE_INCOMING = 1

    /**
     * Words a dialer puts on its answer and hang-up actions.
     *
     * Only reached when a dialer publishes no `CallStyle`, which on the target
     * phone is every time. Lowercased substring matching, and deliberately
     * short: a longer list would start matching "answer machine".
     */
    private val ANSWER_WORDS = listOf("answer", "accept", "pick up")
    private val END_WORDS = listOf("hang up", "end call", "decline", "reject", "dismiss")

    private const val MAX_RECENTS = 12
}
