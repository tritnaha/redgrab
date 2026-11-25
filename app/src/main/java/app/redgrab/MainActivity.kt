package app.redgrab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.redgrab.ui.ChromeState
import app.redgrab.ui.CompactNavBar
import app.redgrab.ui.LocalChrome
import app.redgrab.ui.LocalNavPad
import app.redgrab.ui.NavBarHeight
import app.redgrab.ui.NavItem
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.redgrab.ui.AppThemes
import app.redgrab.ui.BrowseScreen
import app.redgrab.ui.DeleteScreen
import app.redgrab.ui.DiscoverScreen
import app.redgrab.ui.EditorScreen
import app.redgrab.ui.InfoEditor
import app.redgrab.ui.LibraryScreen
import app.redgrab.ui.PlayerScreen
import app.redgrab.ui.LogScreen
import app.redgrab.ui.RedGrabTheme
import app.redgrab.ui.SettingsScreen
import app.redgrab.ui.copyText

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onResume()
        if (it.resultCode == RESULT_OK) vm.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            RedGrabTheme(vm.themeId) {
                // Status/navigation bar icons follow the theme (dark icons on light themes).
                val dark = AppThemes.byId(vm.themeId).isDark
                LaunchedEffect(dark) {
                    val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    c.isAppearanceLightStatusBars = !dark
                    c.isAppearanceLightNavigationBars = !dark
                }
                // RedGifs asked for a human check: open the verification screen once.
                LaunchedEffect(vm.verifyNeeded) {
                    if (vm.verifyNeeded) {
                        vm.verificationHandled()
                        startActivity(Intent(this@MainActivity, VerifyActivity::class.java))
                    }
                }
                val snack = remember { SnackbarHostState() }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val logVersion by AppLog.version.collectAsStateWithLifecycle()
                val errorCount by AppLog.errors.collectAsStateWithLifecycle()
                var seenErrors by rememberSaveable { mutableIntStateOf(0) }
                val hasNewErrors = errorCount > seenErrors

                LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
                LaunchedEffect(tab, errorCount) { if (tab == 2) seenErrors = errorCount }

                val openSignIn = {
                    // When a session existed before, open in renew mode: it closes by itself if RedGifs still knows you.
                    signIn.launch(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_RENEW, vm.library.prefs.token != null))
                }

                // The navigation bar sits on top of the content (so it can slide away while a grid
                // scrolls) instead of taking a fixed slice of the screen.
                val chrome = remember { ChromeState() }
                LaunchedEffect(tab) { chrome.hidden = 0f }
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val navPad = NavBarHeight + navInset
                val navPx = with(LocalDensity.current) { navPad.toPx() }
                val gridTab = tab == 0 || tab == 4 // these pad their own scrolling content
                // The log is opened from Settings; Back returns there.
                BackHandler(tab == 2) { tab = 3 }
                val nav = listOf(
                    NavItem(0, "Saved", Icons.Default.Smartphone),
                    NavItem(1, "Browse", Icons.Default.Explore),
                    NavItem(4, "Online", Icons.Default.Public),
                    NavItem(3, "Settings", Icons.Default.Settings, badge = hasNewErrors),
                )
                CompositionLocalProvider(LocalChrome provides chrome, LocalNavPad provides navPad) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.fillMaxSize().padding(bottom = if (gridTab) 0.dp else navPad).consumeWindowInsets(WindowInsets.navigationBars)) {
                            when (tab) {
                                0 -> LibraryScreen(vm, snack, onSignIn = openSignIn, onOpenSettings = { tab = 3 })
                                1 -> BrowseScreen(vm, onOpen = { tab = 0 })
                                2 -> LogScreen(snack, onBack = { tab = 3 })
                                4 -> DiscoverScreen(vm, onSignIn = openSignIn, onOpenSettings = { tab = 3 })
                                else -> SettingsScreen(vm, onSignIn = openSignIn, onOpenLog = { tab = 2 }, logHasErrors = hasNewErrors)
                            }
                        }
                        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = navPad))
                        CompactNavBar(nav, selected = if (tab == 2) 3 else tab, systemInset = navInset, onSelect = { tab = it },
                            modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, (chrome.hidden * navPx).roundToInt()) })
                    }
                }

                // Full-screen player on top of everything. Tapping a tag/performer in it jumps to Library.
                vm.deleteRequest?.let { DeleteScreen(vm, it) }
                vm.player?.let { PlayerScreen(vm, it) }
                vm.editing?.let { EditorScreen(vm, it) }
                vm.editingInfo?.let { InfoEditor(vm, it) }
                LaunchedEffect(vm.facet) { if (vm.facet != null) tab = 0 }
                LaunchedEffect(vm.discoverRequests) { if (vm.discoverRequests > 0) tab = 4 }

                vm.shareChoice?.let { (clips, apps) ->
                    AlertDialog(
                        onDismissRequest = vm::cancelShareChoice,
                        title = { Text("Share to") },
                        text = { Text("Only WhatsApp apps are offered.") },
                        confirmButton = {
                            Row {
                                apps.forEach { (pkg, label) -> TextButton(onClick = { vm.shareTo(clips, pkg) }) { Text(label) } }
                            }
                        },
                        dismissButton = { TextButton(onClick = vm::cancelShareChoice) { Text("Cancel") } },
                    )
                }

                vm.lastCrash?.let { crash ->
                    AlertDialog(
                        onDismissRequest = vm::dismissCrash,
                        title = { Text("RedGrab closed unexpectedly") },
                        text = {
                            Text(crash.take(1500), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                        },
                        confirmButton = {
                            TextButton(onClick = { copyText(this, "RedGrab crash", crash); vm.dismissCrash() }) { Text("Copy details") }
                        },
                        dismissButton = { TextButton(onClick = vm::dismissCrash) { Text("Dismiss") } },
                    )
                }
            }
        }
    }

    /** Pressing the phone's volume keys is a manual override for the speaker volume guard. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (VolumeGuard.onPhoneSpeaker(this)) {
                if (!MainViewModel.volumeOverride) AppLog.i("Volume keys pressed on speaker: volume guard off until the output changes")
                MainViewModel.volumeOverride = true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onStart() { super.onStart(); RedGrabApp.inFront = true }
    override fun onStop() { RedGrabApp.inFront = false; super.onStop() }
}
