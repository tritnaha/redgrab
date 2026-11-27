package app.redgrab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Clips per "page" in the Saved grid, which has no pages of its own. */
const val LIBRARY_PAGE = 50

/** Which page a position in a list falls on (first page = 1), for pages of [size] items. */
fun pageOf(index: Int, size: Int = LIBRARY_PAGE): Int = index.coerceAtLeast(0) / size + 1

/** How many pages [count] items make. */
fun pageCount(count: Int, size: Int = LIBRARY_PAGE): Int = if (count <= 0) 1 else (count - 1) / size + 1

/**
 * The page a position falls on when pages have different lengths (online feeds). [starts] holds
 * the position each loaded page begins at, in order; the first of them is page [firstPage].
 */
fun pageAt(index: Int, starts: List<Int>, firstPage: Int): Int = firstPage + (starts.count { it <= index } - 1).coerceAtLeast(0)

/**
 * Keeps a dragged position inside its container: [offset] is how far the thing has been moved
 * from where it normally sits ([base], its top-left corner), [size] its own size, [room] the
 * container's.
 */
fun clampOffset(offset: Offset, base: Offset, size: IntSize, room: IntSize): Offset {
    // "0f - x" rather than "-x": a plain zero, not a negative one, when it sits at the edge.
    val minX = 0f - base.x
    val minY = 0f - base.y
    return Offset(
        offset.x.coerceIn(minX, (room.width - size.width - base.x).coerceAtLeast(minX)),
        offset.y.coerceIn(minY, (room.height - size.height - base.y).coerceAtLeast(minY)),
    )
}

/**
 * Where you are in a grid, and the way back up: an arrow that jumps to the top and "page / pages".
 * Tapping the numbers opens "Go to page". [pages] is the last page known ([more] = there are
 * further ones not counted yet); [reach] is the last page that can be jumped to.
 *
 * It is small and see-through so it covers little, and it can be dragged anywhere on the grid;
 * [offset] is where it was left (pixels from its usual place) and [onMoved] stores a new one.
 */
@Composable
fun PositionPill(page: Int, pages: Int, more: Boolean, reach: Int, atTop: Boolean, modifier: Modifier = Modifier,
                 offset: Offset = Offset.Zero, onMoved: (Offset) -> Unit = {}, onTop: () -> Unit, onJump: (Int) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    var moved by remember(offset) { mutableStateOf(offset) }
    // Where it sits when not moved, its size and the grid's size: what a drag is kept within.
    var base by remember { mutableStateOf(Offset.Zero) }
    var own by remember { mutableStateOf(IntSize.Zero) }
    var room by remember { mutableStateOf(IntSize.Zero) }
    Surface(
        modifier = modifier
            .offset { IntOffset(moved.x.roundToInt(), moved.y.roundToInt()) }
            .onGloballyPositioned { c ->
                own = c.size
                room = c.parentLayoutCoordinates?.size ?: IntSize.Zero
                base = c.positionInParent() - moved
                // A position stored on another screen size may now be outside: pull it back in.
                val inside = clampOffset(moved, base, own, room)
                if (room != IntSize.Zero && inside != moved) moved = inside
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onMoved(moved) }, onDragCancel = { onMoved(moved) }) { change, drag ->
                    change.consume()
                    moved = clampOffset(moved + drag, base, own, room)
                }
            },
        shape = RoundedCornerShape(50), shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTop, enabled = !atTop, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Back to the top", modifier = Modifier.size(18.dp))
            }
            Text("%,d/%,d".format(page, pages) + if (more) "+" else "",
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = reach > 1) { asking = true }
                    .padding(start = 1.dp, end = 10.dp, top = 8.dp, bottom = 8.dp))
        }
    }
    if (asking) {
        var text by remember { mutableStateOf(page.coerceIn(1, reach).toString()) }
        val chosen = text.toIntOrNull()?.coerceIn(1, reach)
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Go to page") },
            text = {
                Column {
                    OutlinedTextField(text, { v -> text = v.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text("1 – %,d".format(reach)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Slider((chosen ?: 1).toFloat(), { text = it.toInt().toString() }, valueRange = 1f..reach.toFloat(),
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(enabled = chosen != null, onClick = { asking = false; chosen?.let(onJump) }) { Text("Go") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
