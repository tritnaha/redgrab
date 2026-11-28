package app.redgrab

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.redgrab.ui.RedGrabTheme

/**
 * What Android's settings open in place of their one-tap "Clear storage" for this app. Clearing
 * storage would delete every clip kept inside the app, so it is offered here behind a warning and
 * a second confirmation. It shows no clip and no name.
 */
class ManageSpaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inside = PrivateDocs.dir(this).walkTopDown().count { it.isFile }
        setContent {
            RedGrabTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var confirm by remember { mutableStateOf(false) }
                    if (confirm) AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Delete everything?") },
                        text = { Text("All clips kept inside the app, your sign-ins and settings are removed. This can't be undone.") },
                        confirmButton = { TextButton(onClick = { getSystemService(ActivityManager::class.java).clearApplicationUserData() }) { Text("Delete everything") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    )
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("RedGrab storage", style = MaterialTheme.typography.titleLarge)
                        Text(if (inside > 0) "%,d files are kept inside the app. Clearing storage deletes them. Export them first (Settings → Storage → Export all).".format(inside)
                            else "Clearing storage removes your sign-ins and settings.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
                        Button(onClick = { startActivity(Intent(this@ManageSpaceActivity, MainActivity::class.java)); finish() }) { Text("Open RedGrab") }
                        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Clear storage") }
                    }
                }
            }
        }
    }
}
