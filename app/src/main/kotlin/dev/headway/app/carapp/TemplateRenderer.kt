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
import androidx.car.app.model.OnClickDelegate
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
 * drawing nothing: lane guidance needs a renderer of its own, and a `Toggle`
 * inside a row needs a state round trip that the row's own click already covers
 * for every app that has been looked at.
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

    fun render(state: HostState, template: Template?) {
        root.removeAllViews()
        mapWanted = false
        val context = root.context

        if (state != HostState.RUNNING || template == null) {
            root.gravity = Gravity.TOP
            root.addView(chrome(context))
            root.addView(
                CarStyle.emptyState(context, messageFor(state)),
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
            lastShape = null
            releaseMap()
            return
        }
        root.gravity = Gravity.TOP
        runCatching { draw(context, template) }
            .onFailure { error ->
                SessionLog.shared.warn(TAG, "could not draw ${template.javaClass.simpleName}: $error")
                root.removeAllViews()
                root.gravity = Gravity.TOP
                // Chrome first, for the reason the fallback branch gives: the
                // one state where the driver most needs a way out is the one
                // where Headway has just admitted it cannot draw the screen.
                root.addView(chrome(context))
                root.addView(
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
                root.addView(chrome(context))
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
                    // The app's own tabs, above Headway's. Nested tabs read
                    // badly but the alternative is losing half the app's
                    // navigation, and the row is drawn small and tight so the
                    // hierarchy is obvious.
                    root.addView(tabStrip(context, template))
                    val contents = runCatching { template.tabContents }.getOrNull()
                    val nested = contents?.template
                    if (nested == null) {
                        body(context, loading(context))
                    } else {
                        draw(context, nested)
                    }
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
                    root.addView(actionStripView(context, it))
                }
            }

            is MapWithContentTemplate -> {
                attachMap(context)
                template.actionStrip?.let { root.addView(actionStripView(context, it)) }
                draw(context, template.contentTemplate)
                template.mapController?.mapActionStrip?.let {
                    root.addView(actionStripView(context, it))
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
                root.addView(chrome(context))
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
        root.addView(chrome(context))

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

        root.addView(guidance)
        // A spacer, so the map shows through between the guidance card and the
        // action strip rather than the two meeting in the middle of the screen.
        root.addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        template.mapActionStrip?.let { root.addView(actionStripView(context, it)) }
        template.actionStrip?.let { root.addView(actionStripView(context, it)) }
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
        // Always present, and first. It leaves the app entirely, which is the
        // one control the app cannot supply and the driver always needs: an app
        // whose own Back is missing, or whose root screen has none, would
        // otherwise be a pane with no exit until the tab is switched.
        bar.addView(
            CarStyle.button(context, "Apps") { onLeave() }.apply {
                minWidth = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
                minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 0.75f)
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginEnd = CarStyle.gutter(context) / 2
                }
            },
        )
        // The app's own Back becomes Headway's Back, which unwinds the app's
        // screen stack rather than leaving the pane. Most templates carry one;
        // for the ones that do not, the library's own `onBackPressed` does the
        // same job — it pops the app's screen stack, and an app already at its
        // root answers by finishing, which lands back on the picker. A car app
        // with no way back is a car app the driver has to close and reopen.
        if (start != null) {
            bar.addView(actionView(context, start, compact = true))
        } else {
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
        bar.addView(
            CarStyle.label(context, 17f, CarStyle.TEXT, bold = true).apply {
                this.text = text.orEmpty()
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    marginStart = CarStyle.gutter(context) / 2
                }
            },
        )
        end.forEach { bar.addView(actionView(context, it, compact = true)) }
        strip?.actions?.forEach { bar.addView(actionView(context, it, compact = true)) }
        root.addView(bar)
    }

    /**
     * The one control Headway always owns: the way back out of the app.
     *
     * Used where a template has no header to hang it on — the navigation
     * templates, and every non-running state. A driver looking at "Organic Maps
     * did not answer" with no exit is stuck until they think to switch tabs.
     */
    private fun chrome(context: Context): View = LinearLayout(context).apply {
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
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
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
        root.addView(bar)
    }

    private fun actionStripView(context: Context, strip: ActionStrip): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val gap = CarStyle.gutter(context)
            setPadding(gap, gap / 4, gap, gap / 4)
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
        val label = title ?: if (icon != null) "" else standardName(action.type)
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
        if (compact) {
            pill.minWidth = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
            pill.minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 0.75f)
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
        items.forEach { item -> into.addView(itemView(context, item)) }
    }

    private fun itemView(context: Context, item: Item): View = when (item) {
        is Row -> rowView(context, item)
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
    private fun rowView(context: Context, row: Row): View {
        val gap = CarStyle.gutter(context)
        val target = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
        val title = row.title.plain() ?: ""
        val delegate = row.onClickDelegate

        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = target
            setPadding(gap, gap / 2, gap, gap / 2)
            isFocusable = delegate != null
            contentDescription = title
            if (delegate != null) {
                Headway.pressable(this, CarStyle.radius(context)) { click(delegate, title) }
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
        if (!row.isEnabled) {
            line.alpha = DISABLED_ALPHA
            line.isClickable = false
        }
        return line
    }

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

    private fun tabStrip(context: Context, template: TabTemplate): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = CarStyle.gutter(context) / 2
            setPadding(gap, gap, gap, 0)
            template.tabs.forEach { tab ->
                val active = tab.contentId == template.activeTabContentId
                addView(
                    CarStyle.label(
                        context,
                        14f,
                        if (active) CarStyle.ACCENT else CarStyle.DIM,
                        bold = active,
                    ).apply {
                        text = tab.title.plain() ?: tab.contentId
                        gravity = Gravity.CENTER
                        minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP * 0.6f)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                        // No click handler. Switching an app's tab needs
                        // TabCallbackDelegate, which is a one-way send with no
                        // acknowledgement, and an app that ignores it leaves the
                        // strip highlighting a tab that never opened. Better to
                        // show where the app *is* than to offer a control that
                        // may do nothing.
                    },
                )
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
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
        val target = delegate ?: return
        runCatching {
            target.sendClick(object : androidx.car.app.OnDoneCallback {
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
