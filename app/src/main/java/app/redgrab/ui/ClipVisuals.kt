package app.redgrab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.redgrab.Clip
import app.redgrab.Library
import app.redgrab.LibraryState
import app.redgrab.MainViewModel
import kotlin.math.ceil

/**
 * The one status icon every clip carries. Shape says where the clip comes from, fill and colour
 * say whether it's on the phone:
 *
 *   heart     = in your likes          bookmark = only in one of your collections
 *   globe     = online only (not yours)   folder   = a file you imported
 *   outline, white = not on the phone   filled, green = saved on the phone
 */
@Composable
fun SourceIcon(liked: Boolean, inCollection: Boolean, saved: Boolean, size: Dp = 20.dp, imported: Boolean = false) {
    val tint = if (saved) RgGreen else Color.White
    val (icon, what) = when {
        imported -> androidx.compose.material.icons.Icons.Default.Folder to "Imported"
        liked -> (if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder) to "Liked"
        inCollection -> (if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder) to "In a collection"
        else -> Icons.Default.Public to "Online only"
    }
    Icon(icon, "$what, ${if (saved) "saved on this phone" else "not saved"}", tint = tint, modifier = Modifier.size(size))
}

/** Thumbnail to show: the copy stored on the phone when there is one (works offline), else RedGifs' image. */
fun thumbModel(s: LibraryState, lib: Library, clip: Clip): Any? =
    if (clip.id in s.localThumbs) lib.thumbFile(clip.id) else clip.thumb

/** Tile shape: 3:4 in the even grid; the clip's own shape (within limits) on the wall. */
fun tileAspect(clip: Clip, wall: Boolean): Float =
    if (!wall || clip.width <= 0 || clip.height <= 0) 0.75f
    else (clip.width.toFloat() / clip.height).coerceIn(0.5f, 1.8f)

/** Layout options for the clip grids: even grid or wall, and how many previews per row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSheet(vm: MainViewModel, onClose: () -> Unit) {
    val cfg = LocalConfiguration.current
    // Rough count of previews visible at once on this screen (below the header, above the tab bar).
    fun perScreen(cols: Int): Int {
        val tileW = (cfg.screenWidthDp - 24 - 6 * (cols - 1)) / cols.toFloat()
        val tileH = tileW / (if (vm.wall) 0.7f else 0.75f)
        val rows = (cfg.screenHeightDp - 150) / (tileH + 6)
        return cols * ceil(rows).toInt().coerceAtLeast(1)
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Text("View", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!vm.wall, { vm.changeWall(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Grid") }
                SegmentedButton(vm.wall, { vm.changeWall(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Wall") }
            }
            Text(if (vm.wall) "Wall: every preview keeps its own shape (tall clips are tall), packed like a mosaic."
                else "Grid: every preview is the same size, in even rows.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview size", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${vm.columns} per row · about ${perScreen(vm.columns)} on screen",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Large", style = MaterialTheme.typography.labelSmall)
                Slider(vm.columns.toFloat(), { vm.changeColumns(it.toInt()) }, valueRange = 2f..6f, steps = 3, modifier = Modifier.weight(1f))
                Text("Small", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(1.dp))
            Text("Applies to both the Saved and the Online view.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
