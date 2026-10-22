package com.musheer360.swiftslate

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musheer360.swiftslate.ui.CommandsScreen
import com.musheer360.swiftslate.ui.DashboardScreen
import com.musheer360.swiftslate.ui.SettingsScreen
import com.musheer360.swiftslate.ui.components.LocalSlateMotion
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.SlateMotion
import com.musheer360.swiftslate.ui.components.SlateRhythm
import com.musheer360.swiftslate.ui.theme.GhostPalette
import com.musheer360.swiftslate.ui.theme.flameRimBrush
import com.musheer360.swiftslate.ui.theme.SwiftSlateTheme

enum class Tab(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    Dashboard(R.string.dashboard_title, Icons.Default.Home),
    Commands(R.string.commands_title, Icons.AutoMirrored.Filled.List),
    Settings(R.string.settings_title, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwiftSlateTheme {
                SwiftSlateMainScreen()
            }
        }
    }
}

@Composable
fun SwiftSlateMainScreen(vm: SwiftSlateViewModel = viewModel()) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Dashboard) }

    // Request notification permission on first launch (Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> // Result not needed — we just need to prompt once
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("notification_permission_requested", true).apply()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean("notification_permission_requested", false)
                if (!alreadyRequested) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Exception) {
                // A corrupted pref must not crash this activity — it shares the process with
                // the accessibility service (#125).
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            SlateBottomNav(
                selectedTab = selectedTab,
                onSelect = { tab ->
                    if (selectedTab != tab) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedTab = tab
                    }
                }
            )
        }
    ) { innerPadding ->
        val screens = remember {
            Tab.entries.associateWith { tab ->
                movableContentOf {
                    when (tab) {
                        Tab.Dashboard -> DashboardScreen(
                            vm.keyManager, vm.commandManager, vm.statsManager,
                            onOpenSettings = { selectedTab = Tab.Settings }
                        )
                        Tab.Commands -> CommandsScreen(vm.commandManager, vm.statsManager)
                        Tab.Settings -> SettingsScreen(vm.commandManager, vm.prefs, vm.keyManager)
                    }
                }
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier.padding(innerPadding),
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal)
                    AnimatedContentTransitionScope.SlideDirection.Left
                else
                    AnimatedContentTransitionScope.SlideDirection.Right
                slideIntoContainer(direction, tween(250, easing = FastOutSlowInEasing)) togetherWith
                    slideOutOfContainer(direction, tween(250, easing = FastOutSlowInEasing))
            },
            label = "tab_transition"
        ) { tab ->
            // One rhythm for every tab, derived from the height the content area
            // actually has after the nav bar and system insets. Resolving it here
            // rather than per screen is what keeps padding, gaps and type identical
            // across Dashboard, Keys, Commands and Settings.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val rhythm = remember(maxHeight) { SlateRhythm.forHeight(maxHeight) }
                CompositionLocalProvider(
                    LocalSlateRhythm provides rhythm,
                    LocalSlateMotion provides SlateMotion.Default
                ) {
                    screens[tab]?.invoke()
                }
            }
        }
    }
}

/**
 * The floating bottom nav from the reference design: one inset pill holding the three
 * destinations, where the selected one sits in its own inner pill outlined by a warm
 * flame gradient (rose→amber) with a tinted fill, and the rest stay quiet icons.
 *
 * Icon+label sit in a Column inside the selected pill (the reference design stacks
 * them); colors cross-fade via [animateColorAsState] so switching tabs never pops.
 */
@Composable
private fun SlateBottomNav(selectedTab: Tab, onSelect: (Tab) -> Unit) {
    val rhythm = LocalSlateRhythm.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rhythm.screenPaddingH, vertical = 10.dp),
        shape = RoundedCornerShape(30.dp),
        color = GhostNavigationSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Tab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val iconTint by animateColorAsState(
                    targetValue = if (selected) GhostPalette.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                    label = "navIcon"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .then(
                            if (selected) Modifier
                                .border(
                                    width = 1.5.dp,
                                    brush = flameRimBrush(),
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .background(GhostRimTint, RoundedCornerShape(24.dp))
                            else Modifier
                        )
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) { onSelect(tab) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = stringResource(tab.titleRes),
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(tab.titleRes),
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = iconTint
                    )
                }
            }
        }
    }
}

/** Pill fill under the selected item — warm, but barely-there. */
private val GhostRimTint = Color(0xFFFF6B351A)
private val GhostNavigationSurface = Color(0xFF11131C)
