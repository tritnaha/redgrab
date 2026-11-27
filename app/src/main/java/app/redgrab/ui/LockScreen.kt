package app.redgrab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Asks you to prove it's you with the phone's own unlock, under the given title; the callback
 * gets true when you did. Provided by the main screen (the prompt needs it); the default refuses.
 */
val LocalConfirmIdentity = staticCompositionLocalOf<(String, (Boolean) -> Unit) -> Unit> { { _, done -> done(false) } }

/**
 * Covers the whole app while it is locked (see AppLock). It is its own window, opened last, so it
 * also lies over any dialog that was open. Back leaves the app instead of closing it.
 */
@Composable
fun LockScreen(asking: Boolean, onUnlock: () -> Unit, onLeave: () -> Unit) {
    Dialog(onDismissRequest = onLeave,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Locked", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onUnlock, enabled = !asking) { Text("Unlock") }
            }
        }
    }
}
