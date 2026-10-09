package com.jeremy.dashcam

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.jeremy.dashcam.data.ScreenOrientation
import com.jeremy.dashcam.ui.screens.SideRail
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jeremy.dashcam.data.LayoutDir
import com.jeremy.dashcam.data.SettingsStore
import com.jeremy.dashcam.ui.components.FullScreenBackground
import com.jeremy.dashcam.ui.screens.BottomBar
import com.jeremy.dashcam.ui.screens.CameraScreen
import com.jeremy.dashcam.ui.screens.EventDetailScreen
import com.jeremy.dashcam.ui.screens.EventsScreen
import com.jeremy.dashcam.ui.screens.GuideScreen
import com.jeremy.dashcam.ui.screens.HomeScreen
import com.jeremy.dashcam.ui.screens.Routes
import com.jeremy.dashcam.ui.screens.SettingsScreen
import com.jeremy.dashcam.ui.screens.SplashScreen
import com.jeremy.dashcam.ui.theme.JeremyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import android.graphics.Color as AColor

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_EVENT_ID = "event_id"
    }

    private val pendingNav = MutableStateFlow<Pair<String?, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // Screen-orientation setting applies to the whole app (auto / always portrait / always landscape).
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                SettingsStore.state.map { it.orientation }.distinctUntilChanged().collect { o ->
                    requestedOrientation = when (o) {
                        ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                        ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
            }
        }
        setContent {
            JeremyTheme { JeremyRoot(pendingNav, showSplash = savedInstanceState == null) }
        }
    }

    /** Volume keys control events while Jeremy is open (no accessibility service — Play Protect blocks those). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.jeremy.dashcam.service.VolumeKeyHandler.onKey(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val route = i?.getStringExtra(EXTRA_ROUTE) ?: return
        pendingNav.value = route to i.getStringExtra(EXTRA_EVENT_ID)
        i.removeExtra(EXTRA_ROUTE)
    }
}

@Composable
private fun JeremyRoot(pendingNav: MutableStateFlow<Pair<String?, String?>?>, showSplash: Boolean) {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val sysDir = LocalLayoutDirection.current
    val dir = when (settings.layoutDir) {
        LayoutDir.AUTO -> sysDir
        LayoutDir.RTL -> LayoutDirection.Rtl
        LayoutDir.LTR -> LayoutDirection.Ltr
    }
    var splash by rememberSaveable { mutableStateOf(showSplash) }
    LaunchedEffect(Unit) { if (splash) { delay(8000); splash = false } } // safety net only

    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        Box(Modifier.fillMaxSize()) {
            FullScreenBackground { AppScaffold(pendingNav) }
            AnimatedVisibility(visible = splash, exit = fadeOut()) { SplashScreen(onFinished = { splash = false }) }
        }
    }
}

@Composable
private fun AppScaffold(pendingNav: MutableStateFlow<Pair<String?, String?>?>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pending by pendingNav.collectAsStateWithLifecycle()

    LaunchedEffect(pending) {
        val (r, id) = pending ?: return@LaunchedEffect
        when (r) {
            "camera" -> nav.navigateTab(Routes.CAMERA)
            "event" -> if (id != null) { nav.navigateTab(Routes.EVENTS); nav.navigate(Routes.event(id)) }
        }
        pendingNav.value = null
    }

    // Portrait: bottom bar. Landscape: a side rail, so the screen height stays free (car mount).
    val showNav = route != Routes.EVENT && route != Routes.PRO
    val content: @Composable (Modifier) -> Unit = { m ->
        NavHost(nav, startDestination = Routes.HOME, modifier = m) {
            composable(Routes.HOME) { HomeScreen(onShowCamera = { nav.navigateTab(Routes.CAMERA) }, onSettings = { nav.navigateTab(Routes.SETTINGS) }) }
            composable(Routes.EVENTS) { EventsScreen(onOpen = { nav.navigate(Routes.event(it)) }) }
            composable(Routes.CAMERA) { CameraScreen(onSettings = { nav.navigateTab(Routes.SETTINGS) }, onStartDrive = { nav.navigateTab(Routes.HOME) }) }
            composable(Routes.GUIDE) { GuideScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(onOpenPro = { nav.navigate(Routes.PRO) }) }
            composable(Routes.PRO) { com.jeremy.dashcam.ui.screens.ProScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.EVENT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                EventDetailScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (showNav) SideRail(route) { nav.navigateTab(it) }
            Box(Modifier.weight(1f).fillMaxHeight()) { content(Modifier.fillMaxSize()) }
        }
    } else {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = { if (showNav) BottomBar(route) { nav.navigateTab(it) } },
        ) { pad -> content(Modifier.padding(pad)) }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

