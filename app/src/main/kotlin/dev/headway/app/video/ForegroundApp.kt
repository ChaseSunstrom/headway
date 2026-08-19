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

package dev.headway.app.video

/**
 * Which app the car pane should believe is in front.
 *
 * ## Why this is its own file with no imports
 *
 * It belongs beside [AppPaneHost], which is where it started. But `AppPaneHost`
 * is an object whose initializer touches the platform, so loading it on a plain
 * JVM throws `ExceptionInInitializerError` — and the whole point of writing the
 * rule as a pure function was that CI could hold it without an emulator. A rule
 * that can only be tested where it is hard to test is a rule nobody tests.
 *
 * So: no Android types, no imports, nothing to initialize.
 */
object ForegroundApp {

    /**
     * What the remembered foreground app becomes when [window] comes forward.
     *
     * An assistant window is an overlay *over* the driver's app, not the driver
     * leaving it, so it leaves the remembered app alone. Anything else replaces
     * it.
     *
     * ## Unless the driver asked for the assistant
     *
     * The assistant is also an ordinary app with an icon, and a driver may pin
     * it to the rail and tap it. Treating that window as an overlay too would
     * leave `previous` naming the app before it, so the pane would compare the
     * app it was asked to show against a package that can never arrive, decide
     * it was closed, and cover itself — permanently, because no later window
     * event can name the assistant either. That is the same never-uncovering
     * pane this rule exists to remove, moved rather than fixed.
     *
     * So [opened] settles it: an assistant window is an overlay only when the
     * assistant is not what the driver opened.
     *
     * @param window the package of the window that just came to the front.
     * @param previous what was remembered until now; null means "cannot tell",
     *   which callers read as "show the picture" rather than as an answer.
     * @param assistant the phone's configured assistant, or null when it has
     *   none — in which case no package is treated as special.
     * @param opened what the driver last opened into a pane, if anything.
     */
    fun after(window: String, previous: String?, assistant: String?, opened: String?): String? =
        if (assistant != null && window == assistant && opened != assistant) previous else window

    /**
     * Whether [window] coming forward is the assistant listening.
     *
     * The same distinction as [after] and it has to be made the same way: an
     * assistant the driver opened as an app is an app on the car screen, not a
     * voice session, and putting "Assistant is listening" over it would be
     * covering the thing they asked to see.
     */
    fun isAssistantSession(window: String, assistant: String?, opened: String?): Boolean =
        assistant != null && window == assistant && opened != assistant
}
