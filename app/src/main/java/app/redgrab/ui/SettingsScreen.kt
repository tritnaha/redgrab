package app.redgrab.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material3.Slider
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.redgrab.BuildConfig
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Compress
import app.redgrab.Format
import app.redgrab.PreviewMaker
import app.redgrab.MainViewModel
import app.redgrab.RedditLoginActivity
import app.redgrab.Updater

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onSignIn: () -> Unit, onOpenLog: () -> Unit, logHasErrors: Boolean) {
    val s by vm.state.collectAsStateWithLifecycle()
    var newFolder by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    /** The text typed into "Report a problem" while that dialog is open, else null. */
    var reporting by remember { mutableStateOf<String?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(uri)
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val redditSignIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onResume() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(title = { Text("Settings") }, expandedHeight = TopBarHeight) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Account") {
                Row_(Icons.Default.AccountCircle, if (vm.signedIn) "Signed in to RedGifs" else "Not signed in",
                    if (vm.signedIn) vm.sessionText + ". Renewed by itself in the background."
                    else "Needed to read your likes and collections.")
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSignIn) { Text(if (vm.signedIn) "Renew session" else "Sign in") }
                    if (vm.signedIn) OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
                }
            }

            Section("Reddit") {
                // The ID of the "installed app" you create at reddit.com/prefs/apps; FikFap and sex.com need nothing.
                var id by remember(vm.redditClientId) { mutableStateOf(vm.redditClientId) }
                OutlinedTextField(id, { id = it.trim() }, singleLine = true, label = { Text("Client ID") },
                    supportingText = { Text("reddit.com/prefs/apps → installed app, redirect URI redgrab://reddit") },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp))
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    when {
                        id != vm.redditClientId -> Button(onClick = { vm.changeRedditClientId(id) }) { Text("Save") }
                        vm.redditUser != null -> OutlinedButton(onClick = vm::redditSignOut) { Text("Sign out") }
                        else -> Button(enabled = id.isNotBlank(),
                            onClick = { redditSignIn.launch(android.content.Intent(ctx, RedditLoginActivity::class.java)) }) { Text("Sign in") }
                    }
                    Text(when (val u = vm.redditUser) { null -> "Not signed in"; "" -> "Signed in"; else -> "u/$u" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Section("Save folder") {
                Row_(Icons.Default.Folder, vm.folderLabel ?: "No folder chosen",
                    "Any folder works, including folders inside Secure Folder and hidden (dot) folders.")
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
                    if (vm.hasFolder) OutlinedButton(onClick = { newFolder = "" }) {
                        Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("New subfolder")
                    }
                }
                SwitchRow(Icons.Default.VisibilityOff, "Hide from Gallery",
                    "On by default. Adds a .nomedia file so clips stay out of Gallery and other apps' pickers; watch them here instead.",
                    vm.hiddenFromGallery, enabled = vm.hasFolder, onChange = vm::changeHiddenFromGallery)
                if (vm.hasFolder) {
                    Row_(Icons.Default.Folder, "Import a folder",
                        vm.importStatus ?: "Moves the GIFs and videos of another folder into RedGrab.")
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        OutlinedButton(onClick = { pickImport.launch(null) }, enabled = vm.importStatus == null) {
                            Text(if (vm.importStatus == null) "Choose folder to import" else "Importing")
                        }
                    }
                }
            }

            Section("Appearance") {
                Text("Theme", fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                // Two theme cards per row.
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppThemes.all.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { t ->
                                ThemePreviewSwatch(t, selected = AppThemes.byId(vm.themeId).id == t.id, onClick = { vm.changeTheme(t.id) },
                                    modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Section("Privacy") {
                SwitchRow(Icons.Default.VisibilityOff, "SFW mode",
                    "Blurs every preview, blocks playback and mutes sound. Also pauses online browsing. The eye icon on the Saved tab toggles it too.",
                    vm.sfw, onChange = vm::changeSfw)
                SwitchRow(Icons.Default.Shield, "Start in SFW mode",
                    "Turn SFW mode on every time RedGrab opens.", vm.sfwOnStart, onChange = vm::changeSfwOnStart)
                SwitchRow(Icons.AutoMirrored.Filled.VolumeDown, "Speaker volume guard",
                    "When sound plays through the phone's own speaker (not Bluetooth or headphones), lower the volume to " +
                        "${vm.volumeLimit}% if it's higher. Pressing the volume keys overrides it until the app restarts.",
                    vm.volumeGuard, onChange = vm::changeVolumeGuard)
                if (vm.volumeGuard) {
                    Slider(vm.volumeLimit.toFloat(), { vm.changeVolumeLimit(it.toInt()) }, valueRange = 10f..80f, steps = 6,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            Section("Downloads") {
                Row_(Icons.Default.HighQuality, "HD first",
                    "Clips are saved in HD. SD is used only when a clip has no HD version, or when you pick \"Download in SD\" (selection menu) or tap the SD icon in the player.")
                SwitchRow(Icons.Default.CollectionsBookmark, "Include my collections",
                    "Clips in your collections are listed alongside your likes (each clip only once).",
                    vm.includeCollections, onChange = vm::changeIncludeCollections)
                Row_(Icons.Default.Downloading, "Interrupted downloads resume",
                    "A download that's stopped or fails keeps its partial file and continues from there next time.")
            }

            Section("Previews") {
                SwitchRow(Icons.Default.Animation, "Moving previews",
                    "Previews on screen play silently in a loop. Saved clips play from the phone. Paused in SFW mode and battery saver.",
                    vm.previews, onChange = vm::changePreviews)
                if (vm.previews) {
                    SwitchRow(Icons.Default.Public, "Also for clips that aren't saved",
                        "Streams a small version from RedGifs for unsaved clips and the Online tab. Uses mobile data.",
                        vm.previewUnsaved, onChange = vm::changePreviewUnsaved)
                    SwitchRow(Icons.Default.Compress, "Small preview copies",
                        "Saved clips get a short, small copy for the grid. Lighter on battery; made as clips come up.",
                        vm.previewCopies, onChange = vm::changePreviewCopies)
                    if (vm.previewCopies) {
                        val waiting by PreviewMaker.waiting.collectAsStateWithLifecycle()
                        LaunchedEffect(s.localPreviews.size) { vm.measurePreviewCopies() }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("%,d made · %s".format(s.localPreviews.size, Format.size(vm.previewCopyBytes)) + if (waiting > 0) " · %,d waiting".format(waiting) else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::makeAllPreviewCopies, enabled = waiting == 0) { Text("Make all") }
                            TextButton(onClick = vm::clearPreviewCopies, enabled = s.localPreviews.isNotEmpty() || waiting > 0) { Text("Clear") }
                        }
                    }
                    Text("How many play at once", fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // 0 = everything on screen, up to what the phone can decode at once.
                        listOf(2, 4, 6, 0).forEachIndexed { i, n ->
                            SegmentedButton(vm.previewCount == n, { vm.changePreviewCount(n) }, SegmentedButtonDefaults.itemShape(i, 4)) { Text(if (n == 0) "All" else "$n") }
                        }
                    }
                    Text("All: everything on screen, as far as this phone can decode at once (HD files count for more than small ones). Fewer saves battery; the rest stay as stills.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
                }
            }

            Section("Player") {
                SwitchRow(Icons.AutoMirrored.Filled.VolumeOff, "Start clips muted",
                    "You can also toggle sound in the player; the choice is remembered.",
                    vm.playMuted, onChange = vm::changePlayMuted)
                SwitchRow(Icons.Default.Fullscreen, "Fill the screen",
                    "Off: a clip opens as a larger view over the grid; tap beside it to go back. On: upright clips cover the whole screen, edges cropped.",
                    vm.playerFill, onChange = vm::changePlayerFill)
            }

            Section("Auto-sync") {
                Row_(Icons.Default.Sync, "Synced collections",
                    if (s.watched.isEmpty()) "None yet. Switch collections on in Browse → Collections; new clips in them are saved automatically after every sync."
                    else "${s.watched.size} synced. New clips in them are saved automatically after every sync.")
                Text("Check in the background", fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val options = listOf(0 to "Off", 6 to "6 h", 12 to "12 h", 24 to "Daily")
                    options.forEachIndexed { i, (h, label) ->
                        SegmentedButton(vm.autoSyncHours == h, { vm.changeAutoSyncHours(h) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                    }
                }
                Text(if (vm.autoSyncHours == 0) "Off: syncing happens when you open the app or pull down on the Saved tab."
                    else "Android runs the check roughly every ${vm.autoSyncHours} hours, even when the app is closed. If the RedGifs session has expired you get a notification to sign in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
                if (vm.autoSyncHours > 0) SwitchRow(Icons.Default.Wifi, "Only on Wi-Fi",
                    "Background checks and their downloads wait for Wi-Fi.", vm.syncWifiOnly, onChange = vm::changeSyncWifiOnly)
            }

            Section("Storage") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Default.Downloading, null) },
                    headlineContent = { Text(if (vm.partialInfo.first == 0) "No partial downloads" else "${vm.partialInfo.first} partial download(s) · ${Format.size(vm.partialInfo.second)}") },
                    supportingContent = { Text("Interrupted downloads resume next time. Partials older than 7 days, or for clips no longer in your library, are removed automatically when the app opens.") },
                    trailingContent = { if (vm.partialInfo.first > 0) TextButton(onClick = vm::clearPartials) { Text("Clear") } },
                )
                if (s.duplicates.isNotEmpty()) {
                    Row_(Icons.Default.ContentCopy, "${s.duplicates.size} exact duplicate file(s)",
                        s.duplicates.entries.take(5).joinToString("\n") { "${it.key} = ${it.value}" } +
                            "\nThese files are byte-for-byte identical (the same video under two RedGifs IDs). Delete one in the app if you like.")
                }
            }

            Section("Sync") {
                SwitchRow(Icons.Default.Sync, "Sync when the app opens",
                    "Last synced: ${Format.ago(s.lastSynced)}. Pull down on the Saved tab to sync any time.",
                    vm.refreshOnStart, onChange = vm::changeRefreshOnStart)
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Default.History, null) },
                    headlineContent = { Text("Forget download history") },
                    supportingContent = { Text("RedGrab remembers clips it saved, even if you move or delete the files. Forget this to fetch missing clips again.") },
                    trailingContent = { TextButton(onClick = { confirmForget = true }) { Text("Forget") } },
                )
            }

            if (s.history.isNotEmpty()) {
                Section("Sync history") {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        s.history.take(15).forEach { h ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(h.time)),
                                    Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                                Text("%,d clips · +%,d new%s · %,d saved".format(h.total, h.added,
                                    if (h.removed > 0) " · −%,d gone".format(h.removed) else "", h.saved),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            val hiddenClips = remember(s.hidden) { vm.library.hiddenClips() }
            if (hiddenClips.isNotEmpty()) {
                Section("Hidden clips") {
                    Text("Deleted clips you chose to hide. Unhide to list them again (as not saved yet).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                    hiddenClips.take(50).forEach { c ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(c.id) },
                            supportingContent = { Text(c.user) },
                            trailingContent = { TextButton(onClick = { vm.unhide(listOf(c.id)) }) { Text("Unhide") } },
                        )
                    }
                    if (hiddenClips.size > 1) TextButton(onClick = { vm.unhide(hiddenClips.map { it.id }) },
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) { Text("Unhide all ${hiddenClips.size}") }
                }
            }

            Section("About") {
                Row_(Icons.Default.Info, "RedGrab ${BuildConfig.VERSION_NAME}",
                    "Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_SHA} · ${BuildConfig.BUILD_DATE}\n" +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                        "Unofficial app. Not affiliated with RedGifs.")
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Updater.PAGE))) }
                    },
                    leadingContent = { Icon(Icons.Default.Public, null) },
                    headlineContent = { Text("GitHub") },
                    supportingContent = { Text(BuildConfig.GITHUB_REPO) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )

                // Updates from the releases on GitHub.
                val update by Updater.state.collectAsStateWithLifecycle()
                val (headline, action) = when (val u = update) {
                    is Updater.State.Idle -> "Updates" to "Check"
                    is Updater.State.Checking -> "Checking…" to null
                    is Updater.State.UpToDate -> "Up to date" to "Check"
                    is Updater.State.Available -> "Version ${u.release.version} is available" to "Update"
                    is Updater.State.Downloading -> "Downloading ${u.release.version} · ${u.percent}%" to null
                    is Updater.State.Ready -> "Version ${u.release.version} is ready" to "Install"
                    is Updater.State.Installing -> "Installing ${u.release.version}…" to null
                    is Updater.State.Failed -> u.message to "Try again"
                }
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Default.Downloading, null) },
                    headlineContent = { Text(headline) },
                    supportingContent = {
                        val notes = (update as? Updater.State.Available)?.release?.notes ?: (update as? Updater.State.Ready)?.release?.notes
                        Text(notes?.takeIf { it.isNotBlank() }?.lines()?.take(8)?.joinToString("\n") ?: "Last checked: ${Format.ago(vm.library.prefs.lastUpdateCheck)}")
                    },
                    trailingContent = {
                        if (action != null) TextButton(onClick = {
                            if (update is Updater.State.Available || update is Updater.State.Ready) vm.installUpdate() else vm.checkForUpdate()
                        }) { Text(action) }
                    },
                )
                SwitchRow(Icons.Default.Sync, "Update automatically",
                    "Looks for a new release twice a day and installs it when the app isn't in use.",
                    vm.autoUpdate, onChange = vm::changeAutoUpdate)
                // Needed to send reports, and to read releases while the repository is private.
                var token by remember(vm.githubToken) { mutableStateOf(vm.githubToken) }
                OutlinedTextField(token, { token = it.trim() }, singleLine = true, label = { Text("GitHub token") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    supportingText = { Text("For sending reports (Issues: read and write). Also needed for updates if the repository is private (Contents: read).") },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp))
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (token != vm.githubToken) Button(onClick = { vm.changeGithubToken(token) }) { Text("Save") }
                    else if (token.isNotEmpty()) OutlinedButton(onClick = { vm.changeGithubToken("") }) { Text("Remove") }
                }
            }
            Section("Troubleshooting") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { reporting = ""; vm.checkRepoVisibility() },
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    headlineContent = { Text("Report a problem") },
                    supportingContent = { Text("Gathers the app and phone details and the recent log.") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = onOpenLog),
                    leadingContent = { Icon(Icons.Default.Terminal, null) },
                    headlineContent = { Text("Activity log") },
                    supportingContent = { Text(if (logHasErrors) "There are new errors. Open to read or copy them." else "What the app did, and any errors. One tap copies it.") },
                    trailingContent = {
                        if (logHasErrors) Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape))
                        else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    reporting?.let { text ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text("Report a problem") },
            text = {
                Column {
                    OutlinedTextField(text, { reporting = it }, label = { Text("What happened?") }, minLines = 3, maxLines = 6,
                        modifier = Modifier.fillMaxWidth())
                    if (vm.githubToken.isNotEmpty() && vm.repoPublic != false) {
                        Text(if (vm.repoPublic == true) "The repository is public: a report sent to GitHub can be read by anyone. Copy keeps it private."
                            else "If the repository is public, a report sent to GitHub can be read by anyone.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Added automatically:", style = MaterialTheme.typography.labelMedium)
                    Text(remember { app.redgrab.BugReport.details(ctx) } + "\n+ the recent Activity log (sign-in tokens removed)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(140.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.copyReport(text); reporting = null }) { Text("Copy") }
                    TextButton(enabled = vm.githubToken.isNotEmpty() && !vm.sendingReport, onClick = { vm.sendReport(text); reporting = null }) { Text("Send to GitHub") }
                }
            },
            dismissButton = { TextButton(onClick = { reporting = null }) { Text("Cancel") } },
        )
    }

    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("New subfolder") },
            text = {
                Column {
                    Text("Created inside the current save folder, which then becomes the save folder. Start the name with a dot (e.g. .clips) to make it a hidden folder.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(name, { newFolder = it }, singleLine = true, label = { Text("Folder name") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.createSubfolder(name); newFolder = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } },
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget download history?") },
            text = { Text("Clips that aren't in the save folder right now will show as New again.") },
            confirmButton = { TextButton(onClick = { vm.forgetHistory(); confirmForget = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column { content() }
        }
    }
}

@Composable
private fun Row_(icon: ImageVector, title: String, body: String) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, body: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked, onChange, enabled = enabled) },
    )
}
