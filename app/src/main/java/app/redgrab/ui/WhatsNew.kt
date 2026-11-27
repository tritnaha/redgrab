package app.redgrab.ui

import app.redgrab.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redgrab.BuildConfig
import app.redgrab.Changelog
import app.redgrab.ChangelogEntry
import app.redgrab.Updater

/** Opens a web address in the browser. */
fun openLink(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The changes of one version: its sections, one line per change, and where to read more. */
@Composable
fun ChangelogVersion(e: ChangelogEntry, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier) {
        Text(listOf(e.version, e.date).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        for ((section, items) in e.sections) {
            Text(section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            for (item in items) {
                Row(Modifier.padding(top = 2.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodySmall)
                    Text(item, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = { openLink(ctx, Changelog.linkFor(e, Updater.PAGE, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA)) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("More on GitHub") }
    }
}

/** Shown once after an update (the versions since the one you had), and from Settings → About (all of them). */
@Composable
fun WhatsNewDialog(entries: List<ChangelogEntry>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What's new") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                entries.forEachIndexed { i, e -> ChangelogVersion(e, Modifier.padding(top = if (i == 0) 0.dp else 12.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** A newer version has been downloaded: what it changes, and the choice to install it now. Installing restarts the app. */
@Composable
fun UpdateReadyDialog(release: Updater.Release, onInstall: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Update ${release.version}") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                // Release notes are this version's part of the changelog.
                Text(release.notes.ifBlank { "Ready to install." }, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text("Install") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}
