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

package dev.headway.app

import android.app.Application
import dev.headway.app.log.CrashLog
import dev.headway.app.log.SessionLog
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.theme.HeadwayTheme

/**
 * The one thing that has to happen before anything is drawn.
 *
 * Headway had no `Application` subclass and did not need one until the palette
 * became a choice. It does now, for a reason that is visible rather than
 * theoretical: the car screen's first frame is a startup animation, and an
 * animation that plays in the default theme and corrects itself a third of the
 * way through is the most conspicuous possible way to get a theme wrong. The
 * palette is loaded here, before any activity, service or `Presentation`
 * exists.
 *
 * The one other thing here is a preference migration that has to run before any
 * surface reads the setting it changes.
 *
 * Nothing else belongs in this class. It runs on every process start including
 * ones that only deliver a broadcast, so anything expensive here is a cost paid
 * by a phone that is not in a car — the load is one `SharedPreferences` read of
 * one string.
 */
class HeadwayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // First, so a crash in anything below is still written down. See
        // `CrashLog`: the session log lives in memory, so before this a process
        // that died took the whole drive's evidence with it.
        CrashLog.install(this)
        CrashLog.reportPrevious(this)
        HeadwayTheme.load(this)
        // One preference migration, once. See HeadwaySettings.migrateAppSource
        // for why an existing choice is being changed at all.
        HeadwaySettings.migrateAppSource(this) { note ->
            SessionLog.shared.info("HeadwayApp", note)
        }
    }
}
