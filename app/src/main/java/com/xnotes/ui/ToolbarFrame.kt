package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The toolbar look from Preferences, provided once above both panes. */
val LocalToolbarLook = staticCompositionLocalOf { ToolbarLook() }

/** What every toolbar widget sizes itself by, and which way the bar runs. */
internal data class BarMetrics(
    val button: Dp,
    val circle: Dp,
    val icon: Dp,
    val swatch: Dp,
    val rule: Dp,
    val label: TextUnit,
    val vertical: Boolean = false,
) {
    /** Across the bar: one button with a little air either side. */
    val thickness: Dp get() = button + 8.dp
}

internal fun barMetrics(size: ToolbarSize): BarMetrics = when (size) {
    ToolbarSize.COMPACT -> BarMetrics(36.dp, 30.dp, 20.dp, 24.dp, 22.dp, 11.sp)
    ToolbarSize.REGULAR -> BarMetrics(42.dp, 34.dp, 22.dp, 28.dp, 26.dp, 12.sp)
    ToolbarSize.COMFORTABLE -> BarMetrics(50.dp, 40.dp, 26.dp, 34.dp, 30.dp, 13.sp)
}

internal val LocalBar = staticCompositionLocalOf { barMetrics(ToolbarSize.REGULAR) }

/** Where a menu opens from its anchor; a side rail sets it so its menus open beside the rail. */
internal val LocalMenuOffset = compositionLocalOf { DpOffset.Zero }

/** How far a floating bar sits in from the canvas edge. */
private val FLOAT_MARGIN = 8.dp

/** What a floating bar covers of the canvas, for overlays that must stay out from under it. */
internal val LocalToolbarCover = compositionLocalOf { PaddingValues(0.dp) }

/** Shared with the layout owner so a collapsed vertical float can pin to the top screen corner. */
private val LocalToolbarCollapsedState = compositionLocalOf<MutableState<Boolean>?> { null }

/**
 * Lays a pane out with its [bar] along the edge Preferences chose and [content] in the rest. A
 * floating bar is handed to [content] to lay over its canvas instead, and [onCover] learns how many
 * px of each edge (left, top, right, bottom) it covers so the canvas keeps its pages clear of it.
 */
@Composable
internal fun ColumnScope.ToolbarAround(
    bar: @Composable () -> Unit,
    onCover: (Double, Double, Double, Double) -> Unit,
    moveDockToLeftInLandscape: Boolean = false,
    onToolbarStylusContact: ((Boolean) -> Unit)? = null,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    val look = LocalToolbarLook.current
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val position = if (moveDockToLeftInLandscape && landscape) ToolbarPosition.LEFT else look.position
    val resolvedLook = look.copy(position = position)
    val collapsedDock = rememberSaveable { mutableStateOf(false) }
    val rest = Modifier.weight(1f).fillMaxWidth()
    if (!look.floating) {
        SideEffect { onCover(0.0, 0.0, 0.0, 0.0) }
        val docked: @Composable BoxScope.() -> Unit = {}
        // Left and right are the screen's, as the canvas insets are, whatever the language.
        val across = Arrangement.Absolute.Left
        CompositionLocalProvider(
            LocalToolbarLook provides resolvedLook,
            LocalToolbarCollapsedState provides collapsedDock,
        ) {
            when (position) {
                ToolbarPosition.TOP -> { bar(); Box(rest) { content(docked) } }
                ToolbarPosition.BOTTOM -> { Box(rest) { content(docked) }; bar() }
                ToolbarPosition.LEFT -> Row(rest, across) { bar(); Box(Modifier.weight(1f).fillMaxHeight()) { content(docked) } }
                ToolbarPosition.RIGHT -> Row(rest, across) { Box(Modifier.weight(1f).fillMaxHeight()) { content(docked) }; bar() }
            }
        }
        return
    }
    val depth = barMetrics(look.size).thickness + FLOAT_MARGIN
    val px = with(LocalDensity.current) { depth.toPx().toDouble() }
    SideEffect {
        onCover(
            if (position == ToolbarPosition.LEFT) px else 0.0,
            if (position == ToolbarPosition.TOP) px else 0.0,
            if (position == ToolbarPosition.RIGHT) px else 0.0,
            if (position == ToolbarPosition.BOTTOM) px else 0.0,
        )
    }
    val (cover, edge) = when (position) {
        ToolbarPosition.TOP -> PaddingValues(top = depth) to AbsoluteAlignment.TopLeft
        ToolbarPosition.BOTTOM -> PaddingValues(bottom = depth) to Alignment.BottomCenter
        ToolbarPosition.LEFT -> PaddingValues.Absolute(left = depth) to
            if (collapsedDock.value) AbsoluteAlignment.TopLeft else AbsoluteAlignment.CenterLeft
        ToolbarPosition.RIGHT -> PaddingValues.Absolute(right = depth) to
            if (collapsedDock.value) AbsoluteAlignment.TopRight else AbsoluteAlignment.CenterRight
    }
    CompositionLocalProvider(
        LocalToolbarLook provides resolvedLook,
        LocalToolbarCover provides cover,
        LocalToolbarCollapsedState provides collapsedDock,
    ) {
        Box(rest) {
            content {
                Box(
                    Modifier
                        .align(edge)
                        .padding(FLOAT_MARGIN)
                        .then(if (onToolbarStylusContact == null) Modifier else Modifier.pointerInput(onToolbarStylusContact) {
                            var stylusDown = false
                            try {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        for (change in event.changes) {
                                            if (change.type != PointerType.Stylus) continue
                                            if (change.pressed && !stylusDown) {
                                                stylusDown = true
                                                onToolbarStylusContact(true)
                                            } else if (!change.pressed && stylusDown) {
                                                stylusDown = false
                                                onToolbarStylusContact(false)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                if (stylusDown) onToolbarStylusContact(false)
                            }
                        }),
                ) { bar() }
            }
        }
    }
}

/**
 * The strip both toolbars are laid out in: a row along the top or bottom, a column down either
 * side. [content] runs along it and scrolls when it runs long, [trailing] stays pinned at the end,
 * and [armed] is the tool the gliding circle sits under.
 */
@Composable
internal fun ToolbarFrame(
    armed: Any?,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val look = LocalToolbarLook.current
    val sharedCollapsed = LocalToolbarCollapsedState.current
    var localCollapsed by rememberSaveable { mutableStateOf(false) }
    val collapsed = sharedCollapsed?.value ?: localCollapsed
    val setCollapsed: (Boolean) -> Unit = { value ->
        if (sharedCollapsed == null) localCollapsed = value else sharedCollapsed.value = value
    }
    val bar = barMetrics(look.size).copy(vertical = look.position.vertical)
    val glide = remember { ToolGlide() }
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.extraLarge
    // Docked, the strip fills its edge; floating, a pill only as long as its tools, up to that edge.
    val surface = if (!look.floating) {
        Modifier.background(palette.panel.toComposeColor())
    } else {
        Modifier
            .shadow(6.dp, shape)
            .clip(shape)
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
    }
    val stretch = when {
        look.floating -> Modifier
        bar.vertical -> Modifier.fillMaxHeight()
        else -> Modifier.fillMaxWidth()
    }
    if (collapsed) {
        val container = when {
            look.floating -> Modifier.size(bar.button)
            bar.vertical -> Modifier.width(bar.thickness).height(bar.thickness)
            else -> Modifier.fillMaxWidth().height(bar.thickness)
        }
        val bubbleShape = RoundedCornerShape(12.dp)
        Box(container, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(bar.button)
                    .shadow(4.dp, bubbleShape)
                    .clip(bubbleShape)
                    .background(palette.panel.toComposeColor())
                    .border(1.dp, palette.border.toComposeColor(), bubbleShape),
                contentAlignment = Alignment.Center,
            ) {
                ToolbarDockToggle(collapsed = true) { setCollapsed(false) }
            }
        }
        return
    }
    // Material places a menu below its anchor; shifting it one bar across and one button up puts it
    // beside the rail, and on a right rail material mirrors it to the left side on its own.
    val menuOffset = if (bar.vertical) DpOffset(bar.thickness, -bar.button) else DpOffset.Zero
    val tipSide = when (look.position) {
        ToolbarPosition.TOP -> TipSide.BELOW
        ToolbarPosition.BOTTOM -> TipSide.ABOVE
        ToolbarPosition.LEFT -> TipSide.RIGHT
        ToolbarPosition.RIGHT -> TipSide.LEFT
    }
    CompositionLocalProvider(LocalToolGlide provides glide, LocalBar provides bar, LocalMenuOffset provides menuOffset, LocalTipSide provides tipSide) {
        if (bar.vertical) {
            Column(
                stretch.width(bar.thickness).then(surface),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                leading?.invoke()
                Column(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                        .toolGlide(glide, armed),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { content() }
                ToolbarDockToggle(collapsed) { setCollapsed(!collapsed) }
                trailing?.invoke()
            }
        } else {
            Row(
                stretch.height(bar.thickness).then(surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
                Row(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp)
                        .toolGlide(glide, armed),
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
                trailing?.invoke()
                ToolbarDockToggle(collapsed) { setCollapsed(!collapsed) }
            }
        }
    }
}

@Composable
private fun ToolbarDockToggle(collapsed: Boolean, onClick: () -> Unit) {
    val vertical = LocalBar.current.vertical
    ToolbarIcon(
        icon = when {
            collapsed -> XnotesIcons.hammer
            vertical -> XnotesIcons.arrowUp
            else -> XnotesIcons.collapse
        },
        contentDescription = stringResource(if (collapsed) R.string.toolbar_expand else R.string.toolbar_collapse),
        onClick = onClick,
    )
}
