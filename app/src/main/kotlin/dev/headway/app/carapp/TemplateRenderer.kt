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

// Several of the templates drawn here are deprecated in androidx.car.app, and
// every one of them is still sent by shipping apps: a template is deprecated
// when the library stops recommending it to *authors*, which does nothing to
// the thousands of installed apps that already build one. A host that refused
// to draw a deprecated template would show a blank pane for exactly the older,
// stabler apps most likely to be on a de-Googled phone. The suppression is
// file-wide because Kotlin reports the deprecation at the import as well as at
// the use, and this file has no other reason to touch a deprecated API.
@file:Suppress("DEPRECATION")

package dev.headway.app.carapp

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.Item
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.OnCheckedChangeDelegate
import androidx.car.app.model.OnClickDelegate
import androidx.car.app.model.OnSelectedDelegate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import dev.headway.app.dash.tiles.CarStyle
import dev.headway.app.log.SessionLog
import dev.headway.app.ui.theme.Headway

private const val TAG = "HeadwayTemplates"

/**
 * Draws a car-app template with Headway's own widgets.
 *
 * ## The whole idea, in one paragraph
 *
 * A template is a *model*: "a list with these six rows, this one has an icon and
 * a chevron, tapping it sends this click back". Nothing in it says how tall a
 * row is, what font it uses, or what colour the background is — those are the
 * host's to choose, which is why every Android Auto screen looks like Android
 * Auto and not like the app. This class makes them look like Headway, at the
 * head unit's own density, with Headway's touch targets. The app supplies the
 * content; the car decides how content looks.
 *
 * ## What is drawn and what is not
 *
 * Every template the two shipping open-source car apps send, plus the rest of
 * the common set: lists, grids, panes, messages, search, sections, tabs, and the
 * five navigation templates. The parts deliberately left out are named where
 * they occur, and each is a case where drawing something would be worse than
 * drawing nothing: lane guidance needs a renderer of its own.
 *
 * A `Toggle` used to be on that list, on the stated grounds that "the row's own
 * click already covers" it. That was false against the library, not merely
 * optimistic: `Row.Builder.build()` throws if a row carries both a toggle and a
 * click listener, so a toggle row *never* has a click to cover it, and every
 * settings switch in every car app drew as inert text. The same is true of a
 * selectable list, whose selection lives on the `ItemList` because its items
 * are forbidden clicks of their own. Both are rendered and dispatched now.
 *
 * ## Loading is a state, not an empty screen
 *
 * Nearly every template carries `isLoading`. An app that sets it is telling the
 * host "draw a spinner, I will invalidate when I have content" — and a host that
 * ignores it draws an empty list instead, which is exactly the "the pane is
 * broken" report this project has already had once.
 */
class TemplateRenderer(
    context: Context,
    private val session: CarAppSession,
    private val onStep: (String) -> Unit = {},
    /** Leaves the app entirely and goes back to the picker. */
    private val onLeave: () -> Unit = {},
    /**
     * How much larger than the pane the map buffer is. See [CarAppSurfaceView].
     *
     * Passed through rather than read here because this class owns the map
     * surface's construction and nothing else does, and because it is a
     * per-app setting the pane already reads for the density.
     */
    private val mapPixelScale: Float = 1f,
    /**
     * Whether to draw the bar carrying "Apps" and the app's name.
     *
     * False when the pane was pointed at this app rather than choosing it — a
     * Maps pane, which resolves its own navigator — because then the button
     * leads to a picker the driver did not come from and the label names an app
     * they did not pick. A driver reported both, over their map: "theres a
     * 'Back' button, really called 'Apps' and then the name of it ... instead
     * of just not displaying those in the panel".
     *
     * It is the pane's only in-pane exit, so this is only false where there is
     * somewhere else to go: the rail switches tabs, and the pane's own menu
     * changes what it shows.
     */
    private val showChrome: Boolean = true,
) {

    private val appContext: Context = context.applicationContext

    /** The root, filled and refilled in place so the pane never flickers. */
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    /**
     * Where the app draws its own map, when it asks for one.
     *
     * Behind the template rather than beside it: a `NavigationTemplate` is a
     * strip of guidance over a full-bleed map, and that is the layout the app
     * built its content for.
     */
    private var mapView: CarAppSurfaceView? = null

    /**
     * The template class last drawn, so a refresh of the same screen is not
     * animated. See the end of [render].
     */
    private var lastShape: Class<*>? = null

    /**
     * Whether the template just drawn asked for a map.
     *
     * Set by [attachMap] and read at the end of [render]: a template with no
     * map must have the surface taken away, or the app keeps rendering into it
     * behind an opaque list, burning GPU for a view nobody sees and showing
     * through every transparent gap.
     */
    private var mapWanted = false

    val view: View get() = root

    /**
     * Where [chrome], [header], [body] and [actions] put what they build.
     *
     * Normally [root] itself. The tab branch points it at the content half of
     * a split for the duration of the nested draw, which is the whole reason a
     * side strip can sit *beside* the app's screen rather than above it: a
     * `LinearLayout` cannot be half-horizontal, so the nested template has to
     * be drawn into a child rather than into the same column as the strip.
     *
     * Reset in [render] on both the success and the failure path -- a draw that
     * threw part way through the tab branch would otherwise leave this aimed at
     * a detached holder and render the error state into nothing.
     *
     * Named `target` rather than `column` because several draw helpers build a
     * local `column` of their own and a field of that name would be shadowed by
     * exactly the ones that must not use it.
     */
    private var target: LinearLayout = root

    fun render(state: HostState, template: Template?) {
        root.removeAllViews()
        target = root
        // Cleared with the view tree it describes. The delegates in it belong
        // to the template being replaced, and keeping them would grow a map of
        // dead references for the life of the session.
        selectedByDelegate.clear()
        mapWanted = false
        val context = root.context

        if (state != HostState.RUNNING || template == null) {
            root.gravity = Gravity.TOP
            target.addView(chrome(context))
            target.addView(
                CarStyle.emptyState(context, messageFor(state)),
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
            lastShape = null
            releaseMap()
            return
        }
        root.gravity = Gravity.TOP
        mapUnderneath = false
        runCatching { draw(context, template) }
            .onFailure { error ->
                SessionLog.shared.warn(TAG, "could not draw ${template.javaClass.simpleName}: $error")
                root.removeAllViews()
                target = root
                root.gravity = Gravity.TOP
                // Chrome first, for the reason the fallback branch gives: the
                // one state where the driver most needs a way out is the one
                // where Headway has just admitted it cannot draw the screen.
                target.addView(chrome(context))
                target.addView(
                    CarStyle.emptyState(
                        context,
                        "${session.app.label} sent a screen Headway could not draw " +
                            "(${template.javaClass.simpleName}).",
                    ),
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
                )
            }
        // Only when the screen has actually changed. render() runs on every
        // invalidate — once a second for a navigating app, and the host tells
        // apps to invalidate freely — and revealIn sets alpha to 0 and animates
        // back over 260 ms, so animating every refresh strobes the whole pane
        // for the length of the drive. An app faster than about 4 Hz would
        // reset alpha before the previous animation finished and sit
        // permanently near-invisible.
        // A template with no map must not leave one running behind it.
        if (!mapWanted) releaseMap()

        val shape = template.javaClass
        if (shape != lastShape) {
            lastShape = shape
            Headway.revealIn(root)
        }
    }

    /** Drops the surface; call when the pane goes away. */
    fun release() = releaseMap()

    private fun messageFor(state: HostState): String = when (state) {
        HostState.WORKING -> "Opening ${session.app.label}…"
        HostState.REFUSED ->
            "${session.app.label} does not accept Headway as a car host.\n" +
                (session.fault ?: "That is the app's own allowlist, not a Headway limit.")
        HostState.TIMED_OUT -> "${session.app.label} did not answer."
        HostState.FAILED -> session.fault ?: "${session.app.label} stopped responding."
        else -> "Nothing open."
    }

    // --- templates ------------------------------------------------------------

    private fun draw(context: Context, template: Template) {
        // Said once per distinct template, because a car app's layout complaint
        // is unanswerable without it. Two rounds were spent fixing the chrome
        // of the wrong template -- the driver's report named what they saw on
        // screen, and nothing in the log named what had drawn it.
        // Before the `when`, because `MapWithContentTemplate` recurses into this
        // function for its content and the flag has to be set by the time the
        // inner template draws its header.
        if (template.drawsAMap()) mapUnderneath = true
        if (seenTemplates.add(template.javaClass.simpleName)) {
            onStep(
                "car app: ${session.app.label} drew ${template.javaClass.simpleName}" +
                    if (iconsOnly) " (pinned pane: no chrome, icon-only actions)" else ""
            )
        }
        when (template) {
            is ListTemplate -> {
                header(context, template.header, template.title, template.actionStrip)
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val list = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    template.singleList?.let { fillItems(context, list, it) }
                    template.sectionedLists.forEach { section ->
                        section.header.plain()?.let { list.addView(sectionHeading(context, it)) }
                        fillItems(context, list, section.itemList)
                    }
                    body(context, scrolling(context, list))
                }
                actions(context, template.actions)
            }

            is GridTemplate -> {
                header(context, template.header, template.title, template.actionStrip)
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val grid = gridOf(context, template.singleList)
                    body(context, scrolling(context, grid))
                }
                actions(context, template.actions)
            }

            is PaneTemplate -> {
                header(context, template.header, template.title, template.actionStrip)
                drawPane(context, template.pane)
            }

            is MessageTemplate -> {
                header(context, template.header, template.title, template.actionStrip)
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val column = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                    }
                    template.icon?.let { column.addView(iconView(context, it, ICON_LARGE_DP)) }
                    column.addView(
                        paragraph(context, template.message.plain().orEmpty()),
                    )
                    body(context, scrolling(context, column))
                }
                actions(context, template.actions)
            }

            is LongMessageTemplate -> {
                header(context, null, template.title, template.actionStrip)
                body(
                    context,
                    scrolling(context, paragraph(context, template.message.plain().orEmpty())),
                )
                actions(context, template.actions)
            }

            is SearchTemplate -> {
                // The search *field* is not drawn, and the reason is mechanical
                // rather than a preference: the dashboard lives on a private
                // virtual display and the platform does not guarantee an IME on
                // one, so a text field here may put its keyboard on the phone.
                // The results the app has already returned are drawn, and voice
                // is the input route that works from a car seat anyway.
                header(
                    context,
                    null,
                    CarText.create(template.searchHint ?: "Search"),
                    template.actionStrip,
                )
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val list = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    template.itemList?.let { fillItems(context, list, it) }
                    body(context, scrolling(context, list))
                }
            }

            is SectionedItemTemplate -> {
                header(context, template.header, null, null)
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val list = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    template.sections.forEach { section ->
                        section.title.plain()?.let { list.addView(sectionHeading(context, it)) }
                        // The items come through a ListDelegate — an async
                        // fetch, because a section can be longer than a binder
                        // transaction. Requested here and filled in when it
                        // answers, rather than blocking the draw.
                        requestSection(context, list, section)
                    }
                    body(context, scrolling(context, list))
                }
                actions(context, template.actions)
            }

            is TabTemplate -> {
                // Chrome first, because the nested content template supplies
                // the only other exit and in the loading state there is no
                // nested template at all.
                target.addView(chrome(context))
                // `getTabContents()` is annotated @NonNull and is null in
                // exactly this state: Builder.build() rejects a loading
                // template that *has* tabs, so a loading one has neither
                // contents nor tabs. Reading it under Kotlin's strict jspecify
                // handling throws a null-check assertion, which the caller
                // catches and reports as "Headway could not draw TabTemplate" —
                // a wrong and alarming message for a template it supports.
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val contents = runCatching { template.tabContents }.getOrNull()
                    val nested = contents?.template
                    // The split takes the rest of the pane and measures itself.
                    // It is the only thing here that knows the panel's shape,
                    // and it learns it in onMeasure rather than by asking a view
                    // for a width it has not been given yet.
                    val split = TabSplit(context)
                    val holder = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    split.addView(tabStrip(context, template))
                    split.addView(holder)
                    target.addView(split, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                    // The nested template draws into the content half. Restored
                    // afterwards, or everything that follows this branch lands
                    // in a holder inside a split.
                    val outer = target
                    target = holder
                    if (nested == null) {
                        body(context, loading(context))
                    } else {
                        draw(context, nested)
                    }
                    target = outer
                }
            }

            is NavigationTemplate -> drawNavigation(context, template)

            is MapTemplate -> {
                attachMap(context)
                header(context, template.header, null, template.actionStrip)
                template.pane?.let { drawPane(context, it) }
                template.itemList?.let { list ->
                    val column = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    fillItems(context, column, list)
                    body(context, scrolling(context, column))
                }
                template.mapController?.mapActionStrip?.let {
                    target.addView(actionStripView(context, it))
                }
            }

            is MapWithContentTemplate -> {
                attachMap(context)
                template.actionStrip?.let { target.addView(actionStripView(context, it)) }
                draw(context, template.contentTemplate)
                template.mapController?.mapActionStrip?.let {
                    target.addView(actionStripView(context, it))
                }
            }

            is PlaceListNavigationTemplate -> {
                attachMap(context)
                header(context, template.header, template.title, template.actionStrip)
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val list = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    template.itemList?.let { fillItems(context, list, it) }
                    body(context, scrolling(context, list))
                }
            }

            is RoutePreviewNavigationTemplate -> {
                attachMap(context)
                header(context, template.header, template.title, template.actionStrip)
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val list = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    template.itemList?.let { fillItems(context, list, it) }
                    body(context, scrolling(context, list))
                }
                template.navigateAction?.let { actions(context, listOf(it)) }
            }

            is PlaceListMapTemplate -> {
                attachMap(context)
                header(context, null, template.title, template.actionStrip)
                if (template.isLoading) {
                    body(context, loading(context))
                } else {
                    val list = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    template.itemList?.let { fillItems(context, list, it) }
                    body(context, scrolling(context, list))
                }
            }

            else -> {
                // Not a failure. The template set grows with every library
                // release and an app may send one this build has never seen;
                // naming it is more useful than a blank pane, and it is the
                // report that says which one to add next.
                //
                // The chrome is not optional here. A pane that says "Headway
                // cannot draw this" and offers no way out is worse than one
                // that crashes, because the driver has no reason to think
                // switching tabs would help. Two shipping templates land here
                // today: SignInTemplate and MediaPlaybackTemplate.
                target.addView(chrome(context))
                body(
                    context,
                    CarStyle.emptyState(
                        context,
                        "${session.app.label} sent a ${template.javaClass.simpleName}, " +
                            "which this build of Headway does not draw yet.",
                    ),
                )
            }
        }
    }

    private fun drawPane(context: Context, pane: Pane) {
        if (pane.isLoading) {
            body(context, loading(context))
            return
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pane.image?.let { column.addView(iconView(context, it, ICON_LARGE_DP)) }
        pane.rows.forEach { column.addView(rowView(context, it)) }
        body(context, scrolling(context, column))
        actions(context, pane.actions)
    }

    /**
     * The guidance screen: the next turn, large, over the app's own map.
     *
     * This is the template that makes the whole host worth building. The app
     * draws the map into a surface Headway owns, Headway draws the instruction
     * on top at a size a driver can read at a glance, and the result is car UI
     * from an app that never knew what a Chevrolet head unit was.
     */
    private fun drawNavigation(context: Context, template: NavigationTemplate) {
        attachMap(context)
        // A navigation template carries no header of its own — it is meant to
        // be the whole screen — so the way out has to be added explicitly.
        target.addView(chrome(context))

        val info = template.navigationInfo
        val guidance = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = CarStyle.gutter(context)
            setPadding(pad, pad, pad, pad)
            background = Headway.panel(
                radiusPx = CarStyle.radius(context),
                fill = Headway.SURFACE_RAISED,
                stroke = Headway.OUTLINE,
            )
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                val gap = CarStyle.gutter(context)
                setMargins(gap, gap, gap, gap / 2)
            }
        }

        when (info) {
            is RoutingInfo -> {
                if (info.isLoading) {
                    guidance.addView(loading(context))
                } else {
                    info.currentDistance?.let { distance ->
                        guidance.addView(
                            CarStyle.label(context, 32f, CarStyle.ACCENT, bold = true).apply {
                                text = CarAppTrips.describeDistance(distance)
                            },
                        )
                    }
                    val step = info.currentStep
                    val cue = step?.cue.plain()
                        ?: step?.maneuver?.type?.let { CarAppTrips.describeManeuver(it) }
                    guidance.addView(
                        CarStyle.label(context, 22f, CarStyle.TEXT, bold = true).apply {
                            text = cue.orEmpty()
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                    )
                    step?.road.plain()?.let { road ->
                        guidance.addView(
                            CarStyle.label(context, 15f, CarStyle.DIM).apply { text = road },
                        )
                    }
                    info.nextStep?.cue.plain()?.let { next ->
                        guidance.addView(
                            CarStyle.label(context, 13f, CarStyle.DIM).apply {
                                text = "then $next"
                            },
                        )
                    }
                }
            }

            is MessageInfo -> {
                info.title.plain()?.let {
                    guidance.addView(
                        CarStyle.label(context, 22f, CarStyle.TEXT, bold = true).apply { text = it },
                    )
                }
                info.text.plain()?.let {
                    guidance.addView(CarStyle.label(context, 15f, CarStyle.DIM).apply { text = it })
                }
            }

            else -> guidance.addView(
                CarStyle.label(context, 15f, CarStyle.DIM).apply { text = "Navigating" },
            )
        }

        template.destinationTravelEstimate?.let { estimate ->
            val parts = listOfNotNull(
                CarAppTrips.describeEta(estimate.remainingTimeSeconds),
                estimate.remainingDistance?.let { CarAppTrips.describeDistance(it) },
            )
            if (parts.isNotEmpty()) {
                guidance.addView(
                    CarStyle.label(context, 15f, CarStyle.GOOD).apply {
                        text = parts.joinToString(" · ")
                    },
                )
            }
        }

        target.addView(guidance)
        // A spacer, so the map shows through between the guidance card and the
        // action strip rather than the two meeting in the middle of the screen.
        target.addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        template.mapActionStrip?.let { target.addView(actionStripView(context, it)) }
        template.actionStrip?.let { target.addView(actionStripView(context, it)) }
    }

    // --- the map surface --------------------------------------------------------

    /**
     * Makes sure a surface exists for the app to draw its map on.
     *
     * Created once and then left exactly where it is. It is deliberately *not*
     * added to this renderer's column: a `SurfaceView` re-parented is a
     * `SurfaceView` whose surface is destroyed and recreated, and since
     * [render] rebuilds the column from scratch on every template, adding it
     * here would tear the map down and hand the app a fresh surface once per
     * refresh — which for a navigating app is once a second. The pane owns the
     * parenting; see `CarAppTile`, which puts it behind everything in a
     * `FrameLayout` because a `LinearLayout` cannot overlap its children.
     */
    private fun attachMap(context: Context) {
        mapWanted = true
        if (mapView == null) mapView = CarAppSurfaceView(context, session, mapPixelScale)
    }

    private fun releaseMap() {
        val existing = mapView ?: return
        mapView = null
        existing.detach()
    }

    /**
     * The surface the pane hands the app, if the app asked for one.
     *
     * Exposed so the tile can put it *behind* the template rather than inside
     * it: a `LinearLayout` cannot overlap its children, and the guidance card
     * has to sit on top of the map rather than under it.
     */
    fun mapSurface(): View? = mapView

    // --- pieces ----------------------------------------------------------------

    private fun header(
        context: Context,
        header: Header?,
        title: CarText?,
        strip: ActionStrip?,
    ) {
        val text = header?.title.plain() ?: title.plain()
        val start = header?.startHeaderAction
        val end = header?.endHeaderActions.orEmpty()

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val gap = CarStyle.gutter(context)
            setPadding(gap, gap / 2, gap, gap / 2)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        // On a pinned pane this whole opening section is skipped. `chrome()`
        // was gated on [showChrome] and this was not, so a Maps pane still drew
        // an "Apps" button, a "Back" button and the app's name across the top
        // of the map -- which is the report that came back after the first fix.
        // Only the app's own controls survive here, and they are drawn as
        // icons. See [showChrome].
        val ownChrome = !iconsOnly
        if (ownChrome) {
            // Always present, and first. It leaves the app entirely, which is
            // the one control the app cannot supply and the driver always
            // needs: an app whose own Back is missing, or whose root screen has
            // none, would otherwise be a pane with no exit until the tab is
            // switched. Over a map it is the exception -- the pane is the map,
            // and the rail and tab strip are the way out.
            bar.addView(
                CarStyle.button(context, "Apps") { onLeave() }.apply {
                    minWidth = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
                    minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 0.75f)
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        marginEnd = CarStyle.gutter(context) / 2
                    }
                },
            )
        }
        // The app's own Back becomes Headway's Back, which unwinds the app's
        // screen stack rather than leaving the pane. Most templates carry one;
        // for the ones that do not, the library's own `onBackPressed` does the
        // same job — it pops the app's screen stack, and an app already at its
        // root answers by finishing, which lands back on the picker. A car app
        // with no way back is a car app the driver has to close and reopen.
        if (start != null) {
            bar.addView(actionView(context, start, compact = true))
        } else if (ownChrome) {
            bar.addView(
                CarStyle.button(context, "Back") { session.back() }.apply {
                    minWidth = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
                    minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 0.75f)
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        marginEnd = CarStyle.gutter(context) / 2
                    }
                },
            )
        }
        if (ownChrome) {
            bar.addView(
                CarStyle.label(context, 17f, CarStyle.TEXT, bold = true).apply {
                    this.text = text.orEmpty()
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        marginStart = CarStyle.gutter(context) / 2
                    }
                },
            )
        } else {
            // Nothing to name, but the app's own controls still belong at the
            // end rather than jammed against the start.
            bar.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
        end.forEach { bar.addView(actionView(context, it, compact = true)) }
        strip?.let { describeStrip(it) }
        strip?.actions?.forEach { bar.addView(actionView(context, it, compact = true)) }
        // An empty bar is still padding across the top of a map. On a pinned
        // pane with an app that supplies no header controls there is genuinely
        // nothing to show, and showing nothing means adding nothing.
        if (bar.childCount > 1 || ownChrome) target.addView(bar)
    }

    /**
     * The one control Headway always owns: the way back out of the app.
     *
     * Used where a template has no header to hang it on — the navigation
     * templates, and every non-running state. A driver looking at "Organic Maps
     * did not answer" with no exit is stuck until they think to switch tabs.
     */
    private fun chrome(context: Context): View = if (!showChrome) {
        // A zero-height nothing rather than a skipped `addView`, because the
        // bar is added from six branches and each one would need the same
        // conditional. See [showChrome].
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0)
        }
    } else LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val gap = CarStyle.gutter(context)
        setPadding(gap, gap / 2, gap, gap / 4)
        addView(
            CarStyle.button(context, "Apps") { onLeave() }.apply {
                minWidth = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
                minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 0.75f)
            },
        )
        addView(
            CarStyle.label(context, 15f, CarStyle.DIM).apply {
                text = session.app.label
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    marginStart = gap / 2
                }
            },
        )
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun body(context: Context, content: View) {
        if (!mapUnderneath) {
            target.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            return
        }
        // Over a map, content gets a panel rather than the pane.
        //
        // Weight 1 means "take everything left", and everything left over a map
        // template is the map. So a navigation app's own list -- Shortcuts,
        // Favourites, Recents -- was drawn across the whole pane with the map
        // behind it and invisible, which is what a driver meant by the bars
        // "taking up all of the view". Android Auto gives content a panel and
        // keeps the map beside it.
        //
        // The spacer goes first and carries the rest of the weight, so the
        // panel sits against the bottom edge and the map shows through above
        // it. Transparent by construction: it is a bare `View` with no
        // background, and the map surface is behind this whole template.
        target.addView(
            View(context),
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f - MAP_CONTENT_SHARE),
        )
        target.addView(
            content,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, MAP_CONTENT_SHARE),
        )
    }

    private fun actions(context: Context, actions: List<Action>) {
        if (actions.isEmpty()) return
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val gap = CarStyle.gutter(context)
            setPadding(gap, gap / 2, gap, gap / 2)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        actions.forEach { bar.addView(actionView(context, it, compact = false)) }
        target.addView(bar)
    }

    /**
     * Whether an action draws as an icon with no word beside it.
     *
     * Tied to [showChrome] rather than being its own flag: both answer the same
     * question -- is this pane showing an app the driver pointed it at, whose
     * whole content is the map. A Maps pane wants the app's controls small and
     * out of the way; a pane the driver navigated into wants them legible.
     */
    /**
     * Whether an action draws as an icon with no word beside it.
     *
     * ## Why this is not simply "is the pane pinned"
     *
     * It was, and it was wrong twice. `showChrome` is false only for a Maps
     * pane, because only `CarShell.mapsTileFor` passes `pinned`. A driver whose
     * map is in a **Car app** pane -- the same navigation app, picked from the
     * picker instead of resolved by the Maps pane -- got `showChrome = true`
     * and therefore the full chrome and a row of labelled pills across their
     * map, which is exactly the report that came back after both attempts.
     *
     * So the condition is what is on the screen rather than how the pane was
     * configured. A template that draws a map fills the pane with a map, and a
     * bar of words over it is the complaint whichever pane kind it is. The way
     * out of the app is the rail and the tab strip, which are always there.
     */
    private val iconsOnly: Boolean get() = !showChrome || mapUnderneath

    /** True once this render has reached a template that draws a map. */
    private var mapUnderneath = false

    /**
     * Whether this template puts a map behind its content.
     *
     * The same list as the branches in [draw] that call `attachMap`, and it has
     * to stay that way -- a template that shows a map and is missing here gets
     * a bar of pills over it.
     */
    private fun Template.drawsAMap(): Boolean =
        this is NavigationTemplate ||
            this is MapTemplate ||
            this is MapWithContentTemplate ||
            this is PlaceListNavigationTemplate ||
            this is RoutePreviewNavigationTemplate ||
            this is PlaceListMapTemplate

    /** Template names already named in the log, so each is said once. */
    private val seenTemplates = mutableSetOf<String>()

    /**
     * How much of a map pane an app's own content may take.
     *
     * Half. Less and a list of place names is unreadable on an 800x480 head
     * unit; more and the map it is laid over stops being a map. Not a setting,
     * because the pane's own scale control already exists and this is the
     * division between two things rather than the size of one.
     */
    private val MAP_CONTENT_SHARE: Float get() = 0.5f

    /**
     * Names what an app put in a strip, once per distinct set.
     *
     * Two rounds of fixes for "the bars are still horizontal" missed because
     * nothing recorded what the bar was *made of*. Whether an action carries an
     * icon decides how it can be drawn, and it is invisible from the outside:
     * an icon-less titled action and an icon-bearing one look identical in a
     * screenshot and need opposite treatment.
     */
    private fun describeStrip(strip: ActionStrip) {
        val shape = strip.actions.joinToString(", ") { action ->
            val title = action.title.plain()
            val icon = if (action.icon != null) "icon" else "no icon"
            "${title ?: standardName(action.type)} ($icon)"
        }
        if (shape.isBlank() || !seenStrips.add(shape)) return
        onStep(
            "car app: ${session.app.label} strip of ${strip.actions.size} — $shape" +
                if (iconsOnly) " [drawn compact over a map]" else " [drawn as pills]"
        )
    }

    /** Strips already named in the log, so each shape is said once. */
    private val seenStrips = mutableSetOf<String>()

    private fun actionStripView(context: Context, strip: ActionStrip): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val gap = CarStyle.gutter(context)
            setPadding(gap, gap / 4, gap, gap / 4)
            describeStrip(strip)
            strip.actions.forEach { addView(actionView(context, it, compact = true)) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

    /**
     * One action, as a pill.
     *
     * `FLAG_PRIMARY` gets the accent fill — it is the app saying "this is the
     * one", and honouring it is what makes an app's own emphasis survive being
     * redrawn by somebody else's widgets.
     *
     * ## A standard action is the host's job, not the app's
     *
     * `Action.BACK`, `PAN`, `APP_ICON` and `COMPOSE_MESSAGE` carry **no**
     * `OnClickDelegate` — `Action`'s private single-int constructor stores null
     * for both the title and the delegate — and a header action cannot carry
     * one at all: `ACTIONS_CONSTRAINTS_HEADER` is built with
     * `setOnClickListenerAllowed(false)`. So routing a standard action's tap to
     * its delegate is routing it to null, every time, and the first version of
     * this drew a "Back" pill that silently did nothing on every screen of
     * every app using the modern `Header` API. The host is *required* to
     * interpret the type itself, and [standardAction] is where that happens.
     *
     * ## Icons
     *
     * Drawn, because an action strip is mostly icons and nothing else. A map
     * action strip may legally contain zero titled actions —
     * `ACTIONS_CONSTRAINTS_MAP` never calls `setMaxCustomTitles`, so it defaults
     * to 0 — which is how four pills all reading "Action" ended up side by side
     * across the guidance screen.
     */
    private fun actionView(context: Context, action: Action, compact: Boolean): View {
        val icon = action.icon?.let { drawable(it) }
        val title = action.title.plain()
        // A titled action shows its title. An icon-only action shows its icon
        // and no word at all, because the word would be a guess. Only an action
        // with neither gets a name, and then only a standard one has a name
        // worth giving.
        // On a pinned pane an action with an icon shows only the icon. A
        // navigation app's strip is Routes / Recents / Favourites / Settings
        // and more, and as labelled pills that is a bar of words the full width
        // of the screen, laid over the map the pane exists to show -- which is
        // what a driver reported twice. The title survives as the content
        // description, so nothing is lost to a screen reader or to the log.
        // Over a map, a titled action with no icon is shortened rather than
        // left alone. The first attempt only dropped the *label of an action
        // that had an icon*, which does nothing at all for an app whose strip
        // is titles with no icons -- and a driver reported the bar unchanged.
        // The full title stays as the content description either way.
        val label = when {
            iconsOnly && icon != null -> ""
            iconsOnly && title != null -> title.take(1).uppercase()
            title != null -> title
            icon != null -> ""
            else -> standardName(action.type)
        }
        val primary = (action.flags and Action.FLAG_PRIMARY) != 0
        val handler = standardAction(action) ?: { click(action.onClickDelegate, label) }
        val pill = CarStyle.button(context, label, emphasised = primary, onClick = handler)
        if (icon != null) {
            val size = CarStyle.dp(context, ICON_ACTION_DP)
            icon.setBounds(0, 0, size, size)
            action.icon?.tint?.let { tint -> resolve(tint)?.let { icon.setTint(it) } }
            pill.setCompoundDrawablesRelative(icon, null, null, null)
            if (title != null) {
                pill.compoundDrawablePadding = CarStyle.gutter(context) / 2
            }
            // Named for the driver's screen reader and for the log, since the
            // pill itself may now carry no text.
            pill.contentDescription = title ?: standardName(action.type)
        }
        if (pill.contentDescription == null) {
            pill.contentDescription = title ?: standardName(action.type)
        }
        if (compact) {
            pill.minWidth = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
            pill.minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 0.75f)
        }
        if (iconsOnly) {
            // Square, and the same size as a tab cell, so a strip of them reads
            // as one row of controls rather than as pills that lost their text.
            val side = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
            pill.minWidth = side
            pill.minHeight = side
            pill.width = side
            pill.height = side
            pill.setPadding(0, 0, 0, 0)
        }
        if (!action.isEnabled) {
            pill.isEnabled = false
            pill.alpha = DISABLED_ALPHA
        }
        action.backgroundColor?.let { colour ->
            resolve(colour)?.let { pill.background = Headway.panel(
                radiusPx = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP / 2f).toFloat(),
                fill = it,
                stroke = null,
            ) }
        }
        pill.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = CarStyle.gutter(context) / 2
        }
        return pill
    }

    private fun standardName(type: Int): String = when (type) {
        Action.TYPE_BACK -> "Back"
        Action.TYPE_PAN -> "Pan"
        Action.TYPE_APP_ICON -> session.app.label
        Action.TYPE_COMPOSE_MESSAGE -> "Message"
        else -> "Action"
    }

    /**
     * What a standard action does, or null when the app owns the behaviour.
     *
     * `TYPE_BACK` is the app's own screen stack, popped through
     * `IAppManager.onBackPressed` — the same call Headway's own Back pill makes,
     * which is the point: an app that asks for a Back button gets a working one
     * rather than a decoration.
     *
     * `TYPE_APP_ICON` is a label, not a control, and Google's own host draws it
     * inert; a no-op keeps it from looking tappable-but-broken. `TYPE_PAN` needs
     * a pan-mode state machine over `PanModeDelegate` that this build does not
     * have, so it says so rather than pretending.
     */
    private fun standardAction(action: Action): (() -> Unit)? = when (action.type) {
        Action.TYPE_BACK -> ({ session.back(); Unit })
        Action.TYPE_APP_ICON -> ({})
        Action.TYPE_PAN -> ({ onStep("car app: pan mode is not implemented yet") })
        else -> null
    }

    private fun fillItems(context: Context, into: LinearLayout, list: ItemList) {
        val items = list.items
        if (items.isEmpty()) {
            into.addView(
                CarStyle.label(context, 15f, CarStyle.DIM).apply {
                    text = list.noItemsMessage.plain() ?: "Nothing here."
                    gravity = Gravity.CENTER
                    val gap = CarStyle.gutter(context)
                    setPadding(gap, gap * 2, gap, gap * 2)
                },
            )
            return
        }
        // A selectable list is a radio group: the selection lives on the *list*,
        // not on its rows, because `ItemList.Builder.build()` refuses a row
        // click on any item of one. Without this every settings choice a car app
        // offers -- route type, avoidances, units -- drew as inert text.
        val selection = runCatching { list.onSelectedDelegate }.getOrNull()
        selection?.let {
            selectedByDelegate[it] = runCatching { list.selectedIndex }.getOrDefault(-1)
        }
        items.forEachIndexed { index, item ->
            into.addView(itemView(context, item, selection, index))
        }
    }

    private fun itemView(
        context: Context,
        item: Item,
        selection: OnSelectedDelegate? = null,
        index: Int = -1,
    ): View = when (item) {
        is Row -> rowView(context, item, selection, index)
        is GridItem -> gridItemView(context, item)
        else -> CarStyle.label(context, 15f, CarStyle.DIM).apply {
            text = item.javaClass.simpleName
        }
    }

    /**
     * A row: image, title, the app's extra lines, and a chevron when it browses.
     *
     * `getTexts()` and not just a subtitle: the model allows several secondary
     * lines and apps use them — an address on one line and a distance on the
     * next. Capped, because a row that grows to five lines pushes everything
     * below it off a 480-pixel screen.
     */
    private fun rowView(context: Context, row: Row): View = rowView(context, row, null, -1)

    /**
     * One row, including the two shapes a settings screen is built from.
     *
     * ## Why a click delegate was never enough
     *
     * A driver reported that HERE WeGo's route settings "arent actually
     * editable like they are in the app". They were not, and could not have
     * been: this drew only `Row.getOnClickDelegate()`, and the library
     * *guarantees* that is null on exactly the two rows a settings screen uses.
     *
     *  - `Row.Builder.build()` throws "If a row contains a toggle, it must not
     *    have an onClickListener set". So every switch in every car app was
     *    drawn as inert text, with no background, no listener and no focus.
     *  - `ItemList.Builder.build()` throws "Items that belong to selectable
     *    lists can't have an onClickListener". So a radio group -- "Route type:
     *    Fastest / Shortest / Eco" -- was a list of inert rows too, because the
     *    selection lives on the *list* via `getOnSelectedDelegate()`.
     *
     * The old KDoc claimed a toggle was safe to skip because "the row's own
     * click already covers" it. Against the library that is not merely a
     * judgement call, it is false: a toggle row cannot have a row click.
     *
     * @param selection the list's selection delegate, when this row is one of a
     *   selectable group; null otherwise.
     * @param index this row's position in that group, or -1.
     */
    private fun rowView(
        context: Context,
        row: Row,
        selection: OnSelectedDelegate?,
        index: Int,
    ): View {
        val gap = CarStyle.gutter(context)
        val target = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
        val title = row.title.plain() ?: ""
        val delegate = row.onClickDelegate
        // Read under runCatching for the reason `tabStrip` gives: these getters
        // are `@NonNull` and implemented as `requireNonNull` of fields the
        // library's own `Bundler` leaves null, and a host receives reflected
        // objects rather than constructed ones.
        val toggle = runCatching { row.toggle }.getOrNull()
        val checked = toggle?.let { runCatching { it.isChecked }.getOrDefault(false) } ?: false
        val onChecked = toggle?.let { runCatching { it.onCheckedChangeDelegate }.getOrNull() }
        val enabled = runCatching { row.isEnabled }.getOrDefault(true) &&
            (toggle == null || runCatching { toggle.isEnabled }.getOrDefault(true))
        val knob = toggle?.let { switchView(context, checked) }
        val selectable = selection != null && index >= 0 && enabled

        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = target
            setPadding(gap, gap / 2, gap, gap / 2)
            isFocusable = delegate != null || (onChecked != null && enabled) || selectable
            contentDescription = title
            when {
                delegate != null ->
                    Headway.pressable(this, CarStyle.radius(context)) { click(delegate, title) }

                onChecked != null && enabled ->
                    // The whole row, not just the switch. A 64 dp control at the
                    // far right of an 800-pixel panel is the hardest target on
                    // the car screen; the row is the easiest.
                    Headway.pressable(this, CarStyle.radius(context)) {
                        // Painted before the send, deliberately. The app answers
                        // a checked-change by invalidating -- a oneway binder
                        // send, the app's own main-thread hop, a doorbell back
                        // and a getTemplate pull -- which is far longer than the
                        // press animation. A switch that does not move under the
                        // finger reads as a switch that did not work, which is
                        // the report being fixed here.
                        val next = !checked
                        knob?.let { paintSwitch(it, next) }
                        sendChecked(onChecked, next, title)
                    }

                selectable ->
                    Headway.pressable(this, CarStyle.radius(context)) {
                        sendSelected(selection, index, title)
                    }
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 4
            }
        }

        row.image?.let { image ->
            val size = (target * ROW_IMAGE_FRACTION).toInt()
            line.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(drawable(image))
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                },
            )
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(CarStyle.label(context, 16f, CarStyle.TEXT).apply { this.text = title })
        row.texts.take(MAX_ROW_TEXTS).forEach { extra ->
            extra.plain()?.let {
                column.addView(CarStyle.label(context, 13f, CarStyle.DIM).apply { this.text = it })
            }
        }
        line.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // The app's own row actions, which is how a list row gets a play button
        // beside it without the whole row becoming ambiguous.
        row.actions.forEach { line.addView(actionView(context, it, compact = true)) }

        if (row.isBrowsable) {
            line.addView(
                CarStyle.label(context, 20f, CarStyle.ACCENT).apply {
                    // U+203A: present in every Latin font, unlike the arrows.
                    this.text = "›"
                    gravity = Gravity.CENTER
                },
            )
        }
        knob?.let { line.addView(it) }
        // The mark on the chosen row of a selectable list. A ring rather than a
        // filled dot when unselected, so the group reads as a group even at a
        // glance -- every row shows where its mark would go.
        if (selection != null && index >= 0) {
            line.addView(
                CarStyle.label(context, 20f, CarStyle.ACCENT).apply {
                    this.text = if (index == selectedIndexOf(selection)) "\u25cf" else "\u25cb"
                    gravity = Gravity.CENTER
                    val pad = CarStyle.gutter(context) / 2
                    setPadding(pad, 0, pad, 0)
                },
            )
        }
        if (!enabled) {
            line.alpha = DISABLED_ALPHA
            line.isClickable = false
        }
        return line
    }

    /**
     * A switch, drawn rather than inflated.
     *
     * `android.widget.Switch` would carry the phone's theme, its own text sizes
     * and a ripple sized for a finger held six inches from the eye. Everything
     * else on this screen is drawn from `CarStyle` at the car's density, and a
     * control that is not would be the one thing on the panel that looks
     * borrowed.
     */
    private fun switchView(context: Context, checked: Boolean): View {
        val height = CarStyle.dp(context, SWITCH_HEIGHT_DP)
        val width = (height * SWITCH_ASPECT).toInt()
        val track = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                marginStart = CarStyle.gutter(context)
            }
        }
        val thumb = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(height, height)
        }
        track.addView(thumb)
        paintSwitch(track, checked)
        return track
    }

    /** Moves and recolours a [switchView] to [checked]. */
    private fun paintSwitch(view: View, checked: Boolean) {
        val track = view as? FrameLayout ?: return
        val context = track.context
        val height = CarStyle.dp(context, SWITCH_HEIGHT_DP)
        val width = (height * SWITCH_ASPECT).toInt()
        track.background = Headway.panel(
            radiusPx = height / 2f,
            fill = if (checked) Headway.ACCENT else Headway.SURFACE_RAISED,
            stroke = Headway.OUTLINE,
        )
        val thumb = track.getChildAt(0) ?: return
        val inset = CarStyle.dp(context, SWITCH_INSET_DP)
        thumb.background = Headway.panel(
            radiusPx = (height - inset * 2) / 2f,
            fill = Headway.GROUND,
            stroke = null,
        )
        thumb.layoutParams = FrameLayout.LayoutParams(height - inset * 2, height - inset * 2).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = if (checked) width - height + inset else inset
        }
        thumb.requestLayout()
    }

    /** Tells the app a toggle moved. */
    private fun sendChecked(delegate: OnCheckedChangeDelegate, checked: Boolean, label: String) {
        runCatching {
            delegate.sendCheckedChange(
                checked,
                object : androidx.car.app.OnDoneCallback {
                    override fun onSuccess(
                        response: androidx.car.app.serialization.Bundleable?,
                    ) = Unit

                    override fun onFailure(
                        response: androidx.car.app.serialization.Bundleable,
                    ) {
                        onStep("car app: ${session.app.label} refused '$label'")
                    }
                },
            )
        }.onFailure {
            onStep("car app: could not toggle '$label' in ${session.app.label}: $it")
        }
    }

    /** Tells the app a row of a selectable list was chosen. */
    private fun sendSelected(delegate: OnSelectedDelegate, index: Int, label: String) {
        runCatching {
            delegate.sendSelected(
                index,
                object : androidx.car.app.OnDoneCallback {
                    override fun onSuccess(
                        response: androidx.car.app.serialization.Bundleable?,
                    ) = Unit

                    override fun onFailure(
                        response: androidx.car.app.serialization.Bundleable,
                    ) {
                        onStep("car app: ${session.app.label} refused '$label'")
                    }
                },
            )
        }.onFailure {
            onStep("car app: could not select '$label' in ${session.app.label}: $it")
        }
    }

    /**
     * Which row of the group is marked.
     *
     * Kept beside the delegate rather than looked up from the list, because
     * [rowView] is handed one row at a time and the list it came from is not in
     * scope by then. Set by [fillItems] before it draws any of them.
     */
    private val selectedByDelegate: MutableMap<OnSelectedDelegate, Int> = mutableMapOf()

    private fun selectedIndexOf(delegate: OnSelectedDelegate): Int =
        selectedByDelegate[delegate] ?: -1

    private fun gridOf(context: Context, list: ItemList?): View {
        val grid = GridLayout(context).apply {
            columnCount = GRID_COLUMNS
            val gap = CarStyle.gutter(context)
            setPadding(gap, gap / 2, gap, gap / 2)
        }
        val items = list?.items.orEmpty()
        if (items.isEmpty()) {
            return CarStyle.emptyState(context, list?.noItemsMessage.plain() ?: "Nothing here.")
        }
        items.forEach { item ->
            val cell = itemView(context, item)
            cell.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(
                    CarStyle.gutter(context) / 2,
                    CarStyle.gutter(context) / 2,
                    CarStyle.gutter(context) / 2,
                    CarStyle.gutter(context) / 2,
                )
            }
            grid.addView(cell)
        }
        return grid
    }

    private fun gridItemView(context: Context, item: GridItem): View {
        val title = item.title.plain() ?: ""
        val delegate = item.onClickDelegate
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 1.4f)
            val gap = CarStyle.gutter(context) / 2
            setPadding(gap, gap, gap, gap)
            contentDescription = title
            if (delegate != null) {
                Headway.pressable(this, CarStyle.radius(context)) { click(delegate, title) }
            }
        }
        if (item.isLoading) {
            cell.addView(loading(context))
            return cell
        }
        item.image?.let { cell.addView(iconView(context, it, ICON_GRID_DP)) }
        cell.addView(
            CarStyle.label(context, 14f, CarStyle.TEXT).apply {
                text = title
                gravity = Gravity.CENTER
            },
        )
        item.text.plain()?.let {
            cell.addView(
                CarStyle.label(context, 12f, CarStyle.DIM).apply {
                    text = it
                    gravity = Gravity.CENTER
                },
            )
        }
        return cell
    }

    /**
     * The app's own tabs, as icons.
     *
     * Icons and nothing else, because this strip floats *over* the app's map
     * rather than beside it -- the map surface is a full-pane sibling behind
     * the whole template column, so nothing the renderer draws can take space
     * from it, only cover it. The old strip gave every tab an equal weighted
     * share of a full-width row, which on HERE WeGo's home screen laid a 44 dp
     * band across the entire top of the map on every screen the app drew, and a
     * driver reported exactly that. An icon carries the same information in a
     * square, and squares fit down a side.
     *
     * Every accessor is read under `runCatching`, for the reason the
     * `getTabContents()` comment in [draw] gives at length: these getters are
     * annotated `@NonNull` and implemented as `requireNonNull` of fields the
     * library's own `Bundler` leaves null when the bundle does not carry them.
     * A host does not receive constructed objects; it receives reflected ones,
     * and a throw here was being reported to the driver as "Headway could not
     * draw TabTemplate" -- the wrong message for a template it supports.
     */
    private fun tabStrip(context: Context, template: TabTemplate): LinearLayout =
        LinearLayout(context).apply {
            // A starting value; TabSplit.onMeasure sets the real one, because
            // it is the only thing that knows the panel's shape.
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val gap = CarStyle.gutter(context) / 2
            setPadding(gap, gap, gap, gap)
            // A ground for the glyphs. The old strip painted nothing, and
            // unbacked content over a moving map is unreadable in both
            // directions. One small opaque panel costs a corner of the map
            // instead of a band of it.
            background = Headway.panel(
                radiusPx = CarStyle.radius(context),
                fill = Headway.SURFACE,
                stroke = Headway.OUTLINE,
            )
            val activeId = runCatching { template.activeTabContentId }.getOrNull()
            runCatching { template.tabs }.getOrDefault(emptyList()).forEach { tab ->
                val contentId = runCatching { tab.contentId }.getOrNull()
                val title = runCatching { tab.title }.getOrNull().plain()
                val icon = runCatching { tab.icon }.getOrNull()
                addView(
                    tabCell(
                        context = context,
                        title = title,
                        icon = icon,
                        active = contentId != null && contentId == activeId,
                        onTap = contentId?.let { id -> ({ selectTab(template, id) }) },
                    ),
                )
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }

    /**
     * One tab: a square, its icon, and a ring when it is the open one.
     *
     * The active tab is marked by its border and its opacity rather than by
     * filling the square with the accent or tinting the glyph. An app supplies
     * its own already-coloured artwork; filling behind it can hide it outright
     * when the two colours are close, and tinting flattens a full-colour icon
     * to a silhouette.
     */
    private fun tabCell(
        context: Context,
        title: String?,
        icon: CarIcon?,
        active: Boolean,
        onTap: (() -> Unit)?,
    ): View {
        val side = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
        val inset = CarStyle.gutter(context) / 2
        val cell = FrameLayout(context).apply {
            background = Headway.panel(
                radiusPx = side / 2f,
                fill = Headway.SURFACE_RAISED,
                stroke = if (active) Headway.ACCENT else Headway.OUTLINE,
            )
            alpha = if (active) 1f else INACTIVE_TAB_ALPHA
            // The strip carries no text now, so the title is the only name this
            // control has left -- for a screen reader, and for the log.
            contentDescription = title
            isFocusable = onTap != null
            onTap?.let { tap -> setOnClickListener { tap() } }
            layoutParams = LinearLayout.LayoutParams(side, side).apply {
                val gap = CarStyle.gutter(context) / 4
                setMargins(gap, gap, gap, gap)
            }
        }
        // A null from `drawable()` is not the same as the tab having no icon.
        // `Tab.Builder` refuses to build one without an icon, but every standard
        // `CarIcon` -- ALERT, APP_ICON, BACK, COMPOSE_MESSAGE, ERROR, PAN --
        // carries no `IconCompat` at all, so a perfectly legal tab can yield
        // nothing to draw.
        val glyph = icon?.let { drawable(it) }
            ?: icon?.takeIf { runCatching { it.type }.getOrNull() == CarIcon.TYPE_APP_ICON }
                ?.let { session.app.icon(appContext) }
        if (glyph != null) {
            runCatching { icon?.tint }.getOrNull()?.let { tint ->
                resolve(tint)?.let { glyph.setTint(it) }
            }
            cell.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(glyph)
                    layoutParams = FrameLayout.LayoutParams(
                        side - inset * 2,
                        side - inset * 2,
                    ).apply { gravity = Gravity.CENTER }
                },
            )
        } else {
            // A blank square reads as a broken tab; the title's first letter
            // reads as a tab. The title is present in the model even when the
            // icon resolves to nothing.
            cell.addView(
                CarStyle.label(context, 22f, CarStyle.TEXT, bold = true).apply {
                    text = (title ?: "\u2022").take(1).uppercase()
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                },
            )
        }
        return cell
    }

    /**
     * Asks the app to switch tabs.
     *
     * The strip used to carry no click handler at all, on the stated grounds
     * that `TabCallbackDelegate` is "a one-way send with no acknowledgement".
     * That is not what the library declares: `sendTabSelected(String,
     * OnDoneCallback)` takes a callback, the same shape as every other click
     * here.
     *
     * The sound half of the old objection is kept. The highlight is *not* moved
     * locally -- the app answers by invalidating, and the next template carries
     * the new `activeTabContentId`. So the strip keeps showing where the app
     * *is* rather than where it was asked to go, and an app that ignores the
     * request simply leaves the strip alone.
     */
    private fun selectTab(template: TabTemplate, contentId: String) {
        runCatching {
            template.tabCallbackDelegate.sendTabSelected(
                contentId,
                object : androidx.car.app.OnDoneCallback {
                    override fun onSuccess(
                        response: androidx.car.app.serialization.Bundleable?,
                    ) = Unit

                    override fun onFailure(
                        response: androidx.car.app.serialization.Bundleable,
                    ) {
                        onStep("car app: ${session.app.label} refused tab '$contentId'")
                    }
                },
            )
        }.onFailure {
            onStep("car app: could not switch ${session.app.label} to '$contentId': $it")
        }
    }

    /**
     * The tab strip and the app's screen, split whichever way the panel is shaped.
     *
     * `onMeasure` is the only place this decision can honestly be made. It is
     * handed the exact space this container has been given -- the pane minus
     * the chrome bar, which is the area the strip and the app's screen actually
     * share -- and it runs before the first child is measured, so the first
     * frame is already right. Reading a width at draw time is not an option:
     * the first template can arrive before `root` has ever been laid out, which
     * is the same race `CarAppTile` defers around for the session geometry.
     *
     * The guard matters. `setOrientation` and `setLayoutParams` both call
     * `requestLayout`, and calling that from inside a measure pass costs an
     * extra traversal. With the guard it happens at most once per shape, and
     * the `super.onMeasure` below runs in the same pass with the new params, so
     * nothing flashes.
     */
    private class TabSplit(context: Context) : LinearLayout(context) {

        private var sideways: Boolean? = null

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val height = MeasureSpec.getSize(heightSpec)
            // Down the side once the panel is meaningfully taller than it is
            // wide. A square-ish panel keeps the top row: that is where the
            // driver's eye already is, and a side column on a wide panel would
            // eat the dimension the app's own content has least of.
            val wanted = width > 0 && height > width * SIDE_STRIP_RATIO
            if (sideways != wanted) {
                sideways = wanted
                orientation = if (wanted) HORIZONTAL else VERTICAL
                (getChildAt(STRIP) as? LinearLayout)?.orientation =
                    if (wanted) VERTICAL else HORIZONTAL
                getChildAt(CONTENT)?.layoutParams = if (wanted) {
                    LayoutParams(0, MATCH_PARENT, 1f)
                } else {
                    LayoutParams(MATCH_PARENT, 0, 1f)
                }
            }
            super.onMeasure(widthSpec, heightSpec)
        }

        private companion object {
            const val STRIP = 0
            const val CONTENT = 1

            /**
             * How much taller than wide before the strip moves to the side.
             *
             * Not 1.0: a panel within a few pixels of square would otherwise
             * flip arrangement back and forth across a divider drag.
             */
            const val SIDE_STRIP_RATIO = 1.15f
        }
    }

    private fun sectionHeading(context: Context, text: String): View =
        CarStyle.label(context, 13f, CarStyle.ACCENT, bold = true).apply {
            this.text = text.uppercase()
            letterSpacing = 0.08f
            val gap = CarStyle.gutter(context)
            setPadding(gap, gap, gap, gap / 4)
        }

    /**
     * Fetches a section's items, which arrive over a second binder round trip.
     *
     * `ListDelegate` exists because a long section does not fit in one
     * transaction, so the model carries a size and a fetch rather than the
     * items. Only the first screenful is asked for: the rest costs another
     * round trip each, and a car list nobody scrolls past row twelve of is not
     * worth the binder traffic.
     */
    private fun requestSection(
        context: Context,
        into: LinearLayout,
        section: androidx.car.app.model.Section<*>,
    ) {
        val delegate = section.itemsDelegate
        val count = minOf(delegate.size, CarAppSession.LIST_LIMIT)
        if (count <= 0) {
            section.noItemsMessage.plain()?.let {
                into.addView(CarStyle.label(context, 15f, CarStyle.DIM).apply { text = it })
            }
            return
        }
        // A placeholder that is replaced in place, so the section keeps its slot
        // in the column while the fetch is in flight and the list does not jump.
        val slot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        into.addView(slot)
        runCatching {
            delegate.requestItemRange(
                0,
                count - 1,
                object : androidx.car.app.OnDoneCallback {
                    override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {
                        val items = response?.let { runCatching { it.get() }.getOrNull() }
                            as? List<*> ?: return
                        slot.post {
                            slot.removeAllViews()
                            items.filterIsInstance<Item>().forEach {
                                slot.addView(itemView(slot.context, it))
                            }
                        }
                    }

                    override fun onFailure(response: androidx.car.app.serialization.Bundleable) {
                        slot.post { slot.removeAllViews() }
                    }
                },
            )
        }
    }

    private fun scrolling(context: Context, content: View): View = ScrollView(context).apply {
        isFillViewport = true
        addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun loading(context: Context): View =
        CarStyle.emptyState(context, "Loading…")

    private fun paragraph(context: Context, text: String): View =
        CarStyle.label(context, 16f, CarStyle.TEXT).apply {
            this.text = text
            maxLines = MAX_PARAGRAPH_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
            gravity = Gravity.CENTER
            val gap = CarStyle.gutter(context)
            setPadding(gap, gap, gap, gap)
        }

    private fun iconView(context: Context, icon: CarIcon, sizeDp: Float): View {
        val size = CarStyle.dp(context, sizeDp)
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(drawable(icon))
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = CarStyle.gutter(context) / 2
            }
        }
    }

    /**
     * A [CarIcon] as something drawable.
     *
     * `IconCompat.loadDrawable` does the work, including resolving a resource
     * out of the *app's* package — which is the whole reason the model carries
     * an `IconCompat` and not a bitmap. The standard icons carry no image at all
     * and fall through to null, which every caller treats as "no image".
     */
    private fun drawable(icon: CarIcon): Drawable? = runCatching {
        icon.icon?.loadDrawable(appContext)
    }.getOrNull()

    /**
     * A [CarColor] as an ARGB int, or null to leave Headway's own colour alone.
     *
     * The named types are mapped onto the Headway palette rather than to their
     * literal names: an app asking for RED means "this is destructive", and the
     * palette already has a colour for that which is legible against this
     * background. TYPE_CUSTOM uses the app's dark variant, because the car
     * surface is dark and the light one is what makes text vanish.
     */
    private fun resolve(colour: CarColor): Int? = when (colour.type) {
        CarColor.TYPE_CUSTOM -> colour.colorDark.takeIf { it != 0 } ?: colour.color
        CarColor.TYPE_PRIMARY -> Headway.ACCENT
        CarColor.TYPE_SECONDARY -> Headway.SURFACE_RAISED
        CarColor.TYPE_RED -> Headway.FAULT
        CarColor.TYPE_GREEN -> Headway.GOOD
        CarColor.TYPE_BLUE -> Headway.ACCENT
        CarColor.TYPE_YELLOW -> Headway.WARN
        else -> null
    }

    /**
     * Sends a click back to the app.
     *
     * `isParkedOnly` is honoured by *asking*, not by blocking: CLAUDE.md is
     * explicit that this is a user-freedom project and the parked-only
     * restriction is the driver's own toggle. Headway has no reliable way to
     * know the car is moving anyway — it has no vehicle speed — so refusing
     * here would be theatre.
     */
    private fun click(delegate: OnClickDelegate?, label: String) {
        // Not `target`: that is now a field naming the column being drawn into,
        // and a local of the same name here would shadow it for anyone who
        // later needed both.
        val sink = delegate ?: return
        runCatching {
            sink.sendClick(object : androidx.car.app.OnDoneCallback {
                override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) = Unit
                override fun onFailure(response: androidx.car.app.serialization.Bundleable) {
                    onStep("car app: ${session.app.label} refused '$label'")
                }
            })
        }.onFailure {
            onStep("car app: could not send '$label' to ${session.app.label}: $it")
        }
    }

    private companion object {

        /** How far an unselected tab fades. Enough to read, not enough to pick. */
        const val INACTIVE_TAB_ALPHA = 0.55f

        /** A drawn switch's track height. Its width follows [SWITCH_ASPECT]. */
        const val SWITCH_HEIGHT_DP = 30f

        /** How much wider than tall a switch track is. */
        const val SWITCH_ASPECT = 1.8f

        /** The gap between a switch's track and its thumb. */
        const val SWITCH_INSET_DP = 3f

        const val ROW_IMAGE_FRACTION = 0.72f
        const val ICON_LARGE_DP = 56f

        /** An action pill's icon; sized against the pill, not the row. */
        const val ICON_ACTION_DP = 24f
        const val ICON_GRID_DP = 40f
        const val GRID_COLUMNS = 3
        const val MAX_ROW_TEXTS = 2
        const val MAX_PARAGRAPH_LINES = 12
        const val DISABLED_ALPHA = 0.4f
    }
}
