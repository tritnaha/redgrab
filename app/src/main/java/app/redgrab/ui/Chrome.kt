package app.redgrab.ui

import androidx.compose.foundation.layout.fillMaxSize
import app.redgrab.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Height of the compact bottom navigation (without the system gesture area below it). */
val NavBarHeight = 52.dp

/** Height of the compact top bars. */
val TopBarHeight = 52.dp

/** The bars that slide away while you scroll a grid: 0 = fully shown, 1 = fully hidden. */
class ChromeState { var hidden by mutableFloatStateOf(0f) }

val LocalChrome = staticCompositionLocalOf { ChromeState() }

/** Space the bottom navigation covers (bar + gesture area). Grids pad their end by this. */
val LocalNavPad = compositionLocalOf { 0.dp }

/**
 * Spacing for the two layouts. Grid: small gaps and soft corners. Wall: tiles run edge to edge
 * with hairline gaps and square corners, so the screen is nearly all picture. [head] is the
 * extra side padding for the rows above the tiles (search, chips), which never run to the edge.
 */
data class GridMetrics(val side: Dp, val gap: Dp, val corner: Dp, val head: Dp)

fun gridMetrics(wall: Boolean) =
    if (wall) GridMetrics(side = 0.dp, gap = 2.dp, corner = 0.dp, head = 10.dp)
    else GridMetrics(side = 6.dp, gap = 4.dp, corner = 8.dp, head = 4.dp)

/**
 * Top-bar behaviour for the clip grids: the bar slides away as you scroll down and returns as
 * soon as you scroll up, and the bottom navigation follows it. [pinned] keeps both in place
 * (while selecting or searching). Nothing hides when the list is too short to scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberChromeScroll(grid: LazyStaggeredGridState, pinned: Boolean): TopAppBarScrollBehavior {
    val chrome = LocalChrome.current
    val top = rememberTopAppBarState()
    val pinnedNow by rememberUpdatedState(pinned)
    val canScroll = remember(grid, top) {
        { !pinnedNow && (grid.canScrollForward || grid.canScrollBackward || top.heightOffset < 0f) }
    }
    val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(top, canScroll)
    LaunchedEffect(pinned) { if (pinned) top.heightOffset = 0f }
    LaunchedEffect(top) { snapshotFlow { top.collapsedFraction }.collect { chrome.hidden = it } }
    return behavior
}

/**
 * Pinch on a grid to change how many previews fit per row: spread two fingers for bigger
 * previews ([onStep] -1), pinch for smaller ones (+1). One-finger scrolling is untouched.
 */
@Composable
fun pinchColumns(onStep: (Int) -> Unit): Modifier {
    val step by rememberUpdatedState(onStep)
    val haptic = LocalHapticFeedback.current
    return Modifier.pointerInput(Unit) {
        awaitEachGesture {
            var zoom = 1f
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.count { it.pressed } >= 2) {
                    zoom *= ev.calculateZoom()
                    ev.changes.forEach { it.consume() } // the grid must not scroll while pinching
                    if (zoom > 1.3f || zoom < 0.77f) {
                        step(if (zoom > 1f) -1 else 1)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        zoom = 1f
                    }
                }
            } while (ev.changes.any { it.pressed })
        }
    }
}

data class NavItem(val key: Int, val label: String, val icon: ImageVector, val badge: Boolean = false)

/**
 * A compact bottom navigation: 52 dp instead of Material's 80 dp, small icon over a small
 * label, the selected destination in the accent colour. [systemInset] is the gesture-bar
 * space below it.
 */
@Composable
fun CompactNavBar(items: List<NavItem>, selected: Int, systemInset: Dp, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(bottom = systemInset).height(NavBarHeight).selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEach { item ->
                val on = item.key == selected
                val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(selected = on, role = Role.Tab) { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        if (item.badge) Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                    }
                    Text(item.label, color = tint, fontSize = 10.sp, lineHeight = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A slim (40 dp) search field for top bars and grid headers. [onClose] adds a close button
 * that is always there; otherwise a clear button shows while there is text.
 */
@Composable
fun BarSearchField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    autoFocus: Boolean = false, leadingIcon: Boolean = false, onSearch: (() -> Unit)? = null, onClose: (() -> Unit)? = null,
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    val style = MaterialTheme.typography.bodyMedium
    Row(
        modifier.fillMaxWidth().height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .padding(start = if (leadingIcon) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon) {
            Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (onClose != null || value.isNotEmpty()) {
            androidx.compose.material3.IconButton(onClick = { if (onClose != null) onClose() else onChange("") }, modifier = Modifier.size(40.dp)) {
                Icon(androidx.compose.material.icons.Icons.Default.Close, if (onClose != null) "Close search" else "Clear", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A message that takes a screen's place when its content can't be shown: optional icon, a line or two, optional button. */
@Composable
fun Notice(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, title: String, body: String? = null,
           action: String? = null, onAction: () -> Unit = {}) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        }
        androidx.compose.material3.Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) androidx.compose.material3.Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (action != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = onAction) { androidx.compose.material3.Text(action) }
        }
    }
}

/** Rows of chips sit closer together than Material's 48 dp touch targets allow: 40 dp inside this. */
@Composable
fun CompactTouch(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 40.dp, content = content)

