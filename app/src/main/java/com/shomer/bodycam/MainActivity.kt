package com.shomer.bodycam

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shomer.bodycam.core.ShiftController
import com.shomer.bodycam.data.Mounting
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.service.VolumeKeyHandler
import com.shomer.bodycam.ui.components.FullScreenBackground
import com.shomer.bodycam.ui.screens.BottomBar
import com.shomer.bodycam.ui.screens.CameraScreen
import com.shomer.bodycam.ui.screens.OnboardingScreen
import com.shomer.bodycam.ui.screens.PocketMode
import com.shomer.bodycam.ui.screens.RecordDetailScreen
import com.shomer.bodycam.ui.screens.RecordsScreen
import com.shomer.bodycam.ui.screens.Routes
import com.shomer.bodycam.ui.screens.SettingsScreen
import com.shomer.bodycam.ui.screens.ShiftScreen
import com.shomer.bodycam.ui.screens.SideRail
import com.shomer.bodycam.ui.screens.SplashScreen
import com.shomer.bodycam.ui.theme.ShomerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.graphics.Color as AColor

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_ID = "id"
    }

    private val pendingNav = MutableStateFlow<Pair<String?, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SettingsStore.state.map { it.mounting }.distinctUntilChanged().collect { m ->
                        requestedOrientation = when (m) {
                            Mounting.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            Mounting.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            Mounting.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                        }
                    }
                }
                // Pocket mode: screen stays on (so the volume key keeps working) but black and at minimum brightness.
                launch {
                    ShiftController.pocket.collect { on ->
                        val lp = window.attributes
                        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                // Pocket mode only makes sense on shift.
                launch { ShiftController.state.map { it.onShift }.distinctUntilChanged().collect { if (!it) ShiftController.pocket.value = false } }
            }
        }
        setContent { ShomerTheme { Root(pendingNav, showSplash = savedInstanceState == null) } }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (VolumeKeyHandler.onKey(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        // end-to-end tests (emulator builds only): sign in as a test user / start a shift
        if (com.shomer.bodycam.BuildConfig.EMULATOR) {
            i?.getStringExtra("e2e_email")?.let { e ->
                SettingsStore.update { it.copy(onboardingDone = true) }
                lifecycleScope.launch { com.shomer.bodycam.cloud.AccountStore.signInForTest(e) }
            }
            if (i?.getBooleanExtra("e2e_shift", false) == true) ShiftController.startShift(this)
        }
        val route = i?.getStringExtra(EXTRA_ROUTE) ?: return
        pendingNav.value = route to i.getStringExtra(EXTRA_ID)
        i.removeExtra(EXTRA_ROUTE)
    }
}

@Composable
private fun Root(pendingNav: MutableStateFlow<Pair<String?, String?>?>, showSplash: Boolean) {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val pocket by ShiftController.pocket.collectAsStateWithLifecycle()
    var splash by rememberSaveable { mutableStateOf(showSplash) }
    LaunchedEffect(Unit) { if (splash) { delay(6000); splash = false } }

    Box(Modifier.fillMaxSize()) {
        FullScreenBackground {
            if (!settings.onboardingDone) OnboardingScreen(onDone = { SettingsStore.update { it.copy(onboardingDone = true) } })
            else AppScaffold(pendingNav)
        }
        if (pocket) PocketMode()
        AnimatedVisibility(visible = splash, exit = fadeOut()) { SplashScreen(onFinished = { splash = false }) }
    }
}

@Composable
private fun AppScaffold(pendingNav: MutableStateFlow<Pair<String?, String?>?>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pending by pendingNav.collectAsStateWithLifecycle()
    val shift by ShiftController.state.collectAsStateWithLifecycle()
    val org by com.shomer.bodycam.cloud.OrgStore.state.collectAsStateWithLifecycle()
    val manager = org.isManager

    LaunchedEffect(pending) {
        val (r, id) = pending ?: return@LaunchedEffect
        when (r) {
            "camera" -> nav.navigateTab(Routes.CAMERA)
            "control" -> nav.navigateTab(Routes.CONTROL)
            "record" -> if (id != null) { nav.navigateTab(Routes.RECORDS); nav.navigate(Routes.record(id)) }
        }
        pendingNav.value = null
    }

    val showNav = route != Routes.RECORD && route != Routes.ACCOUNT
    val content: @Composable (Modifier) -> Unit = { m ->
        NavHost(nav, startDestination = Routes.SHIFT, modifier = m) {
            composable(Routes.SHIFT) { ShiftScreen(onCamera = { nav.navigateTab(Routes.CAMERA) }, onOpenRecord = { nav.navigate(Routes.record(it)) }) }
            composable(Routes.CAMERA) { CameraScreen(onGoShift = { nav.navigateTab(Routes.SHIFT) }) }
            composable(Routes.RECORDS) { RecordsScreen(onOpen = { nav.navigate(Routes.record(it)) }) }
            composable(Routes.SETTINGS) { SettingsScreen(onOpenAccount = { nav.navigate(Routes.ACCOUNT) }) }
            composable(Routes.ACCOUNT) { com.shomer.bodycam.ui.screens.AccountScreen(onBack = { nav.popBackStack() }, onOpenControl = { nav.popBackStack(); nav.navigateTab(Routes.CONTROL) }) }
            composable(Routes.CONTROL) { com.shomer.bodycam.ui.screens.ControlScreen() }
            composable(Routes.RECORD, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                RecordDetailScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (showNav) SideRail(route, shift.recording, manager) { nav.navigateTab(it) }
            Box(Modifier.weight(1f).fillMaxHeight()) { content(Modifier.fillMaxSize()) }
        }
    } else {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { if (showNav) BottomBar(route, shift.recording, manager) { nav.navigateTab(it) } },
        ) { pad -> content(Modifier.padding(pad)) }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.SHIFT) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
