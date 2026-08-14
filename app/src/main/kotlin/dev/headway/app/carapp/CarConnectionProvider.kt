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

package dev.headway.app.carapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.headway.app.log.SessionLog
import dev.headway.transport.LinkState

private const val TAG = "HeadwayCarConnection"

/**
 * Tells car apps that the phone is projecting, because on this phone it is.
 *
 * ## What asks, and why the answer matters
 *
 * `androidx.car.app.connection.CarConnection` is the API an app uses to find out
 * whether it is being projected. It does not ask the system: it queries a
 * `ContentProvider` at the authority `androidx.car.app.connection` and reads an
 * int out of the `CarConnectionState` column — 0 not connected, 1 native
 * (Automotive), 2 projection. On a phone with Android Auto that provider belongs
 * to Gearhead. On a de-Googled phone nothing answers it at all, so every app
 * concludes it is not in a car.
 *
 * That is not cosmetic. Apps use it to decide whether to start their car
 * service, to keep a route running in the background, to suppress a
 * "please connect to Android Auto" screen, and to enable projected-only
 * behaviour. An app that believes it is not projected can and does refuse to do
 * useful work while Headway is showing its templates.
 *
 * ## Why Headway may legitimately answer it
 *
 * Because it is true. When Headway holds a live AAP session the phone *is*
 * projecting to a car, over the real Android Auto protocol, to a real head unit.
 * The provider reports the session state and nothing else: with no car
 * connected it answers NOT_CONNECTED, which is exactly as honest.
 *
 * There is no signature check anywhere on this contract, by design — the
 * authority is a well-known string precisely so that whichever host is present
 * can own it. Headway owning it on a phone where nothing else does is the
 * contract working, not a hole in it.
 *
 * ## The collision
 *
 * A `ContentProvider` authority is exclusive. If Google's Android Auto is
 * installed it already owns this one, and Headway will fail to install with
 * `INSTALL_FAILED_CONFLICTING_PROVIDER`. That is recorded in BLOCKERS as B-014
 * with the two ways out: uninstall Android Auto, or build without this provider
 * (nothing else depends on it — templates render either way).
 *
 * ## Read-only, and exported on purpose
 *
 * Exported because the entire point is that *other* apps read it, and there is
 * nothing here to protect: the answer is one integer describing whether a car is
 * plugged into the phone, which any app can already infer from the car's
 * Bluetooth connection. Every write path is refused rather than left to the
 * default, so the surface is exactly one query.
 */
class CarConnectionProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * The one supported query.
     *
     * The projection the caller asks for is honoured rather than assumed: the
     * library asks for `CarConnectionState` alone, but a cursor whose columns do
     * not match the requested projection is a cursor the caller reads garbage
     * from, and this is the kind of contract that is cheaper to satisfy than to
     * argue with.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val columns = projection?.takeIf { it.isNotEmpty() } ?: arrayOf(CAR_CONNECTION_STATE)
        if (CAR_CONNECTION_STATE !in columns) {
            // A caller asking for columns this provider does not have gets an
            // empty cursor rather than null: null means "no such provider",
            // which is a different and more alarming answer.
            return MatrixCursor(columns)
        }
        val state = currentState()
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { if (it == CAR_CONNECTION_STATE) state else null })
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /**
     * PROJECTION while a car session is up, NOT_CONNECTED otherwise.
     *
     * Never NATIVE: that value means the app is running *on* an Automotive head
     * unit, which is a different situation with different rules, and claiming it
     * would make apps take a code path that assumes hardware Headway is not.
     */
    private fun currentState(): Int =
        if (dev.headway.app.service.HeadwayService.linkState.value is LinkState.Connected) {
            CONNECTION_TYPE_PROJECTION
        } else {
            CONNECTION_TYPE_NOT_CONNECTED
        }

    companion object {

        /** `CarConnection.CAR_CONNECTION_STATE`. */
        const val CAR_CONNECTION_STATE = "CarConnectionState"

        /** `CarConnectionTypeLiveData.CAR_CONNECTION_AUTHORITY`. */
        const val AUTHORITY = "androidx.car.app.connection"

        /** `CarConnection.ACTION_CAR_CONNECTION_UPDATED`. */
        const val ACTION_CAR_CONNECTION_UPDATED =
            "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"

        const val CONNECTION_TYPE_NOT_CONNECTED = 0
        const val CONNECTION_TYPE_PROJECTION = 2

        private val CONTENT_URI: Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .build()

        /**
         * Tells every listening app that the answer changed.
         *
         * Two signals, because the library listens for both: a
         * `ContentResolver` change notification for an already-open cursor, and
         * the broadcast for apps that registered a receiver. Called from the
         * session lifecycle — an app that learns about a connection ninety
         * seconds late has already decided it is not in a car.
         */
        fun announce(context: Context) {
            val application = context.applicationContext
            runCatching { application.contentResolver.notifyChange(CONTENT_URI, null) }
                .onFailure { SessionLog.shared.warn(TAG, "could not notify car connection: $it") }
            runCatching {
                application.sendBroadcast(Intent(ACTION_CAR_CONNECTION_UPDATED))
            }.onFailure {
                SessionLog.shared.warn(TAG, "could not broadcast car connection: $it")
            }
        }
    }
}
