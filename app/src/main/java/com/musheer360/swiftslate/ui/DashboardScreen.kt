package com.musheer360.swiftslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.SwiftSlateApp
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.model.ProviderType
import com.musheer360.swiftslate.ui.components.LocalSlateMotion
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateDivider
import com.musheer360.swiftslate.ui.components.rememberPulseAlpha
import com.musheer360.swiftslate.ui.theme.GhostPalette
import com.musheer360.swiftslate.ui.theme.flameRimBrush
import com.musheer360.swiftslate.ui.theme.heatBarBrush
import com.musheer360.swiftslate.ui.theme.rosePillBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

private fun checkServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName
    }
}

/**
 * Best-effort read of the framework's hidden `crashed` flag: a service stuck in the "crashed
 * services" limbo still reports as enabled, so [checkServiceEnabled] cannot see it. Returns
 * false whenever the reflection is unavailable — everything here is guarded (#125). Deliberate
 * hidden-API reflection: on API 36+ the read throws, the catch returns false, and the banner
 * simply falls back to the crash-marker pref.
 */
@SuppressLint("SoonBlockedPrivateApi")
private fun isServiceCrashed(context: Context): Boolean {
    return try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val field = AccessibilityServiceInfo::class.java.getDeclaredField("crashed")
        am.getInstalledAccessibilityServiceList().any {
            try {
                it.resolveInfo.serviceInfo.packageName == context.packageName && field.getBoolean(it)
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        false
    }
}

/** Timestamp of the last uncaught crash recorded by [SwiftSlateApp], or 0. */
private fun readCrashMarker(context: Context): Long =
    try {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getLong(SwiftSlateApp.PREF_SERVICE_DIED_AT, 0L)
    } catch (_: Exception) {
        0L
    }

private fun clearCrashMarker(context: Context) {
    try {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().remove(SwiftSlateApp.PREF_SERVICE_DIED_AT).apply()
    } catch (_: Exception) {
    }
}

@Composable
fun DashboardScreen(
    keyManager: KeyManager,
    commandManager: CommandManager,
    statsManager: StatsManager,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isServiceEnabled by remember { mutableStateOf(checkServiceEnabled(context)) }
    // Cloud readiness replaces the old key-count display: true when the active
    // provider is the hosted backend and a (placeholder) identity exists in the
    // keystore. Never read synchronously — filled by the RESUMED effect on IO.
    var cloudReady by remember { mutableStateOf(false) }
    // Set when the process died unexpectedly (crash marker) or the framework holds the service
    // in the crashed limbo (hidden flag) — the enabled-state check cannot see either.
    var showKilledBanner by remember { mutableStateOf(false) }

    // Stats state
    var monthlyRequests by remember { mutableIntStateOf(statsManager.monthlyRequests) }
    var favoriteCommand by remember { mutableStateOf(statsManager.favoriteCommand) }
    var dailyCounts by remember { mutableStateOf(statsManager.dailyCounts()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            isServiceEnabled = checkServiceEnabled(context)
        }
        am.addAccessibilityStateChangeListener(listener)
        onDispose { am.removeAccessibilityStateChangeListener(listener) }
    }

    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val (newEnabled, newCloudReady, killed) = withContext(Dispatchers.IO) {
                Triple(
                    checkServiceEnabled(context),
                    ProviderType.sanitize(
                        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            .getString(PrefKeys.PROVIDER_TYPE, null)
                    ) == ProviderType.CLOUD && keyManager.getKeys().isNotEmpty(),
                    readCrashMarker(context) > 0L || isServiceCrashed(context)
                )
            }
            isServiceEnabled = newEnabled
            cloudReady = newCloudReady
            monthlyRequests = statsManager.monthlyRequests
            favoriteCommand = statsManager.favoriteCommand
            dailyCounts = statsManager.dailyCounts()
            showKilledBanner = killed
        }
    }

    val noData = stringResource(R.string.dashboard_no_data)
    val rhythm = LocalSlateRhythm.current
    val motion = LocalSlateMotion.current
    // Alive signal: the active status dot breathes. Inactive stays solid so a
    // *broken* state reads as static, not sleeping.
    val statusPulse = if (isServiceEnabled) rememberPulseAlpha() else 1f
    // Usage count-ups: on resume (or whenever the service logs a request) the
    // number climbs instead of snapping, which makes a successful replacement
    // visibly "arrive" on the next visit to the tab.
    val animatedMonthly by animateIntAsState(
        targetValue = monthlyRequests,
        animationSpec = tween(motion.prominent, easing = FastOutSlowInEasing),
        label = "monthlyCountUp"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { }
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        // ── Header: logo tile + two-tone title ───────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile()
            Spacer(modifier = Modifier.width(rhythm.groupGap))
            Column(modifier = Modifier.weight(1f)) {
                val title = stringResource(R.string.dashboard_title)
                // Two-tone like the reference: leading chunk white, tail cyan.
                // Split at the first syllable boundary-ish ~40% of the word, biased
                // so "Dashboard" cuts exactly "Dash|board".
                val cut = (title.length * 4 + 9) / 10
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) {
                            append(title.take(cut))
                        }
                        withStyle(SpanStyle(color = GhostPalette.Cyan, fontWeight = FontWeight.Bold)) {
                            append(title.drop(cut))
                        }
                    },
                    fontSize = rhythm.titleSize,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.dashboard_subtitle),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.padding(bottom = rhythm.titleGap))

        // ── Service status + API keys ────────────────────────────────────────────
        StaggerIn(0) {
            SlateCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Halo well + breathing dot, like the reference's glowing orb.
                        Box(
                            modifier = Modifier.size(46.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(
                                        (if (isServiceEnabled) GhostPalette.Mint
                                        else GhostPalette.Rose).copy(alpha = 0.13f)
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(CircleShape)
                                    .graphicsLayer { alpha = statusPulse }
                                    .background(
                                        if (isServiceEnabled) GhostPalette.Mint
                                        else GhostPalette.Rose
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(rhythm.groupGap))
                        Column {
                            Text(
                                text = if (isServiceEnabled) stringResource(R.string.service_status_active)
                                else stringResource(R.string.service_status_inactive),
                                fontSize = rhythm.emphasisSize,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isServiceEnabled)
                                    stringResource(R.string.dashboard_status_desc_active)
                                else stringResource(R.string.dashboard_status_desc_inactive),
                                fontSize = rhythm.captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!isServiceEnabled) {
                        // Gradient action pill (rose→deep rose) with a power glyph,
                        // replacing the flat default button. The shadow casts the
                        // pill's own rose light onto the card, a neon aura on API 28+.
                        Box(
                            modifier = Modifier
                                .shadow(
                                    elevation = 16.dp,
                                    shape = RoundedCornerShape(24.dp),
                                    ambientColor = GhostPalette.Rose,
                                    spotColor = GhostPalette.Rose
                                )
                                .clip(RoundedCornerShape(24.dp))
                                .background(rosePillBrush())
                                .clickable(interactionSource = null, indication = null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PowerGlyph(
                                    modifier = Modifier.size(15.dp),
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.service_enable),
                                    fontSize = rhythm.bodySize,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(rhythm.groupGap))
                SlateDivider()
                Spacer(modifier = Modifier.height(rhythm.groupGap))

                // Ghostwriter Cloud status row — the AI backend lives here. There
                // used to be an "API Keys" entry; the hosted worker made it vanish.
                // The row is status-only: nothing to configure, nothing to tap.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = rhythm.tightGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhostPalette.TileInset)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CloudGlyph(
                                color = GhostPalette.Purple,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(rhythm.groupGap))
                        Column {
                            Text(
                                text = stringResource(R.string.dashboard_cloud_title),
                                fontSize = rhythm.emphasisSize,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.dashboard_cloud_subtitle),
                                fontSize = rhythm.captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (cloudReady) GhostPalette.Mint else GhostPalette.Amber)
                        )
                        Spacer(modifier = Modifier.width(rhythm.tightGap))
                        Text(
                            text = stringResource(
                                if (cloudReady) R.string.dashboard_cloud_connected
                                else R.string.dashboard_cloud_starting
                            ),
                            fontSize = rhythm.bodySize,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // ── Keep-alive: survival levers for OEM ecosystems that kill background services ──
        // Hidden by default when everything is already handled (battery exempt, no known
        // killing OEM autostart pending, service healthy) so a happy phone shows no nag.
        StaggerIn(1) {
            KeepAliveCard(serviceAlive = isServiceEnabled)
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Interrupted-service banner: the toggle can still read "on" while the process is dead.
        if (showKilledBanner) {
            SlateCard {
                Text(
                    text = stringResource(R.string.dashboard_service_killed_title),
                    fontSize = rhythm.emphasisSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                Text(
                    text = stringResource(R.string.dashboard_service_killed_message),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(rhythm.groupGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(rhythm.groupGap)
                ) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            clearCrashMarker(context)
                            showKilledBanner = false
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.service_enable))
                    }
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            clearCrashMarker(context)
                            showKilledBanner = false
                        }
                    ) {
                        Text(stringResource(R.string.dashboard_service_killed_dismiss))
                    }
                }
            }
            Spacer(modifier = Modifier.height(rhythm.cardGap))
        }

        // ── Stat mini-cards: This Month (mint) + Top Command (purple) ────────────
        Spacer(modifier = Modifier.height(rhythm.cardGap))
        StaggerIn(1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(rhythm.cardGap)
            ) {
                MiniStatCard(
                    modifier = Modifier.weight(1f),
                    tile = {
                        MiniBarsGlyph(GhostPalette.Mint)
                    },
                    caption = stringResource(R.string.dashboard_monthly_requests),
                    value = "$animatedMonthly",
                    waveColor = GhostPalette.Mint,
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                MiniStatCard(
                    modifier = Modifier.weight(1f),
                    tile = {
                        Text(
                            text = ">_",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    caption = stringResource(R.string.dashboard_favorite_command),
                    value = favoriteCommand ?: noData,
                    waveColor = GhostPalette.Purple,
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // ── 7-day chart card ──────────────────────────────────────────────────────
        StaggerIn(2, wrapperModifier = Modifier.weight(1f)) {
            SlateCard(modifier = Modifier.fillMaxSize(), fillHeight = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhostPalette.TileInset)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            MiniBarsGlyph(GhostPalette.Amber)
                        }
                        Spacer(modifier = Modifier.width(rhythm.groupGap))
                        Column {
                            Text(
                                text = stringResource(R.string.dashboard_last_7_days),
                                fontSize = rhythm.emphasisSize,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.dashboard_chart_subtitle),
                                fontSize = rhythm.captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Passive chip from the reference (the dashboard is always the
                    // running week; a real picker would need history the stats
                    // store does not keep).
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.dashboard_week_chip),
                                fontSize = rhythm.captionSize,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(rhythm.groupGap))

                val maxCount = dailyCounts.maxOfOrNull { it.second } ?: 0
                val dayNameFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
                val dateParseFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Minimal y-axis hint like the reference: peak at top, zero at base.
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(bottom = rhythm.tightGap, top = 14.dp, end = rhythm.tightGap),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$maxCount", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("0", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        // Dashed gridlines sit behind the bars, like the reference chart.
                        val gridColor = MaterialTheme.colorScheme.outline
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                            for (frac in listOf(0.10f, 0.35f, 0.60f, 0.85f)) {
                                val y = size.height * (1f - frac)
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    pathEffect = dash,
                                    strokeWidth = 1.2f
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            dailyCounts.forEach { (dateStr, count) ->
                                val dayLabel = try {
                                    val date = dateParseFmt.parse(dateStr)
                                    dayNameFmt.format(date!!).take(3)
                                } catch (_: Exception) { "?" }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Bars grow in on load and re-ease when a day's count changes;
                                    // a thin floor stub on zero days mirrors the reference chart.
                                    val dayFraction = if (maxCount > 0)
                                        (count.toFloat() / maxCount).coerceAtLeast(0.06f)
                                    else 0.06f
                                    val animatedFraction by animateFloatAsState(
                                        targetValue = dayFraction,
                                        animationSpec = tween(motion.prominent, easing = FastOutSlowInEasing),
                                        label = "dayBar"
                                    )
                                    Text(
                                        text = if (count > 0) "$count" else "",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(rhythm.tightGap))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight(animatedFraction)
                                                .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                                                .then(
                                                    if (count > 0) Modifier.background(heatBarBrush())
                                                    else Modifier.background(GhostPalette.BarBase.copy(alpha = 0.55f))
                                                )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(rhythm.tightGap))
                                    Text(
                                        text = dayLabel,
                                        fontSize = 10.sp,
                                        color = if (count in 1..maxCount && count == maxCount)
                                            GhostPalette.Amber
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (count == maxCount && count > 0) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Ghostwriter dashboard visual helpers ─────────────────────────────────────

/**
 * The 2x2 cyan logo tile from the reference header — four rounded squares with
 * the bottom-left one slightly deeper, drawn as views rather than an image asset
 * so it inherits the palette if it ever changes.
 */
@Composable
private fun LogoTile() {
    val tile = 13.dp
    Column {
        Row {
            Box(Modifier.size(tile).clip(RoundedCornerShape(4.dp)).background(GhostPalette.Cyan))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(tile).clip(RoundedCornerShape(4.dp)).background(GhostPalette.Cyan))
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Box(Modifier.size(tile).clip(RoundedCornerShape(4.dp)).background(GhostPalette.CyanDeep))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(tile).clip(RoundedCornerShape(4.dp)).background(GhostPalette.Cyan))
        }
    }
}

/** The classic power-symbol glyph: gap-topped circle + vertical stem, drawn to match. */
@Composable
private fun PowerGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height * 0.48f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Small cloud silhouette for the Ghostwriter Cloud status row. Material-icons-core
 * has no Cloud icon, so it's drawn: two bumps + a rounded base bar, like the
 * reference design's soft shapes.
 */
@Composable
private fun CloudGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val h = size.height
        drawCircle(
            color = color,
            radius = h * 0.20f,
            center = Offset(size.width * 0.38f, h * 0.44f)
        )
        drawCircle(
            color = color,
            radius = h * 0.28f,
            center = Offset(size.width * 0.60f, h * 0.40f)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.12f, h * 0.46f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.74f, h * 0.30f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.15f, h * 0.15f)
        )
    }
}

/** Three rounded bars of differing heights — the "stats" glyph of the reference tiles. */
@Composable
private fun MiniBarsGlyph(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val barWidth = size.width / 4.6f
        val heights = listOf(0.45f, 0.95f, 0.68f)
        heights.forEachIndexed { i, frac ->
            val barHeight = size.height * frac
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + barWidth * 0.55f), size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * One of the two compact stat tiles: icon tile + caption + big value up top, and a
 * soft sine wave flowing across the bottom edge (line + translucent fill), like the
 * reference. Fixed height so the pair stays symmetric regardless of values.
 */
@Composable
private fun MiniStatCard(
    modifier: Modifier = Modifier,
    tile: @Composable () -> Unit,
    caption: String,
    value: String,
    waveColor: Color,
    valueColor: Color
) {
    Surface(
        modifier = modifier
            .height(136.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            WaveCanvas(
                colorLine = waveColor,
                colorFill = waveColor.copy(alpha = 0.10f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
            )
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(waveColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                        content = { tile() }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = caption,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = value,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Layered sine wave drawn to width, seeded so it looks organic but is fully
 * deterministic — no per-frame randomness that would make the chart jitter.
 */
@Composable
private fun WaveCanvas(
    colorLine: Color,
    colorFill: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val base = size.height * 0.55f
        val amp = size.height * 0.28f
        val stroke = 2.5.dp.toPx()
        fun vertical(x: Float): Float =
            base + amp * sin((x / size.width) * 2.1 * PI + 0.7).toFloat() +
                amp * 0.45f * sin((x / size.width) * 5.3 * PI + 2.1).toFloat()

        val line = Path()
        line.moveTo(0f, vertical(0f))
        var x = 0f
        while (x < size.width) {
            x += 10f
            line.lineTo(x, vertical(x))
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, colorFill)
        drawPath(line, colorLine, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * Staggered entrance: each wrapped section slides up and fades in, offset by its
 * [index] * 80ms. Cheap, deterministic, and only runs once per dashboard visit.
 */
@Composable
private fun StaggerIn(
    index: Int,
    wrapperModifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val motion = LocalSlateMotion.current
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80L * index)
        placed = true
    }
    val t by animateFloatAsState(
        targetValue = if (placed) 0f else 1f,
        animationSpec = tween(motion.prominent, easing = FastOutSlowInEasing),
        label = "staggerIn"
    )
    Box(
        modifier = wrapperModifier.graphicsLayer {
            alpha = 1f - t
            translationY = 26f * t
        }
    ) {
        content()
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Keep-alive card — the survival levers for OEM ecosystems that kill
// accessibility services. Kept out of the service/ package's face: reading a
// PowerManager and vendor-activity discovery is dashboard work, service code
// does not depend on it.
// ═════════════════════════════════════════════════════════════════════════════

private const val PREF_KEEPALIVE_DISMISS = "keepalive_dismiss_"

/** True when the system will not aggressively kill the app's background work. */
private fun isBatteryExempt(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        // Unresolvable system behavior degrades to "not exempt" → the card shows the
        // lever again, which at worst is a second look at an already-set setting.
        false
    }
}

/**
 * Vendor Autostart settings page for the manufacturers known to kill accessibility
 * services (Xiaomi/Realme/Oppo/OnePlus/Vivo/iQoo classes). Each OEM shuffles its own
 * activity names across firmware versions, so every candidate goes through
 * resolveActivity first and the function returns null when nothing matches — the row
 * simply doesn't render on stock Android or unknown OEMs instead of dying in
 * ActivityNotFoundException.
 */
private fun keepaliveOemHint(context: Context): Pair<String, Intent>? {
    val manufacturer = try { Build.MANUFACTURER } catch (_: Exception) { return null } ?: return null
    val mfrKey = manufacturer.lowercase()
    val candidates: List<Intent> = when {
        mfrKey.contains("xiaomi") || mfrKey.contains("redmi") || mfrKey.contains("poco") -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        mfrKey.contains("realme") || mfrKey.contains("oppo") || mfrKey.contains("oneplus") -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startup.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        )
        mfrKey.contains("vivo") || mfrKey.contains("iqoo") -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")
        )
        else -> emptyList()
    }.mapNotNull {
        try { Intent().setComponent(it) } catch (_: Exception) { null }
    }
    val resolvable = candidates.firstOrNull { candidate ->
        try {
            context.packageManager.resolveActivity(candidate, 0) != null
        } catch (_: Exception) {
            false
        }
    } ?: return null
    // Present the name the user actually sees: "Xiaomi", not "xiaomi".
    val displayName = manufacturer.replaceFirstChar { it.uppercase() }
    return displayName to resolvable
}

@Composable
private fun KeepAliveCard(serviceAlive: Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val rhythm = LocalSlateRhythm.current

    // Defaults assume the healthy state so the first frame never flashes fake problems;
    // the load below corrects them in place on IO.
    var batteryExempt by remember { mutableStateOf(true) }
    var oemHint by remember { mutableStateOf<Pair<String, Intent>?>(null) }
    var autostartDismissed by remember { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-read on every RESUME, not just first composition: the user leaves through these
    // rows into system settings and returns with the state already changed — a first-run-only
    // read made the card keep asking for fixes that were already applied (seen on-device:
    // battery exemption done in Settings, row still said "Fix" on return).
    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val loaded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val exempt = isBatteryExempt(context)
                val hint = keepaliveOemHint(context)
                val dismissed = hint == null || try {
                    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getBoolean(PREF_KEEPALIVE_DISMISS + hint.first.lowercase(), false)
                } catch (_: Exception) {
                    false
                }
                Triple(exempt, hint, dismissed)
            }
            batteryExempt = loaded.first
            oemHint = loaded.second
            autostartDismissed = loaded.third
        }
    }

    val showBatteryRow = !batteryExempt
    val showAutostartRow = oemHint != null && !autostartDismissed
    if (!showBatteryRow && !showAutostartRow && serviceAlive) return

    SlateCard {
        Column {
            // Title row: the shield glyph signals "this is armor, not a command."
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GhostPalette.TileInset)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    ShieldGlyph(
                        color = GhostPalette.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(rhythm.groupGap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.keepalive_card_title),
                        fontSize = rhythm.emphasisSize,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.keepalive_card_subtitle),
                        fontSize = rhythm.captionSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showBatteryRow) {
                Spacer(modifier = Modifier.height(rhythm.groupGap))
                SlateDivider()
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Direct exemption dialog first (has its own confirm); the
                            // flat list screen is the fallback for devices that refuse.
                            val direct = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + context.packageName)
                            )
                            try {
                                context.startActivity(direct)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    )
                                } catch (_: Exception) {
                                    // No battery UI at all — give up silently, the row
                                    // re-renders "not exempt" on the next resume.
                                }
                            }
                        }
                        .padding(vertical = rhythm.tightGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhostPalette.TileInset),
                            contentAlignment = Alignment.Center
                        ) {
                            BatteryGlyph(
                                color = GhostPalette.Amber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(rhythm.tightGap))
                        Column {
                            Text(
                                text = stringResource(R.string.keepalive_battery_title),
                                fontSize = rhythm.bodySize,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.keepalive_battery_subtitle),
                                fontSize = rhythm.captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = rhythm.tightGap)) {
                        Text(
                            text = stringResource(R.string.keepalive_fix),
                            fontSize = rhythm.bodySize,
                            fontWeight = FontWeight.SemiBold,
                            color = GhostPalette.Amber
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.keepalive_fix),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (showAutostartRow) {
                val hint = oemHint
                if (hint != null) {
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                SlateDivider()
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                context.startActivity(hint.second)
                            } catch (_: Exception) {
                                // Vendor reshuffled their activity — degrade to the app's
                                // own system-settings page where Autostart also lives.
                                try {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        }
                        .padding(vertical = rhythm.tightGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhostPalette.TileInset),
                            contentAlignment = Alignment.Center
                        ) {
                            LaunchGlyph(
                                color = GhostPalette.Mint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(rhythm.tightGap))
                        Column {
                            Text(
                                text = stringResource(R.string.keepalive_autostart_title, hint.first),
                                fontSize = rhythm.bodySize,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.keepalive_autostart_subtitle),
                                fontSize = rhythm.captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = rhythm.tightGap)) {
                        Text(
                            text = stringResource(R.string.keepalive_fix),
                            fontSize = rhythm.bodySize,
                            fontWeight = FontWeight.SemiBold,
                            color = GhostPalette.Mint
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.keepalive_fix),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        // MIUI provides no API to read Autostart state (proprietary setting),
                        // so the app cannot know it was granted. The row stays claimable by the
                        // user instead of nagging forever: dismiss is remembered per manufacturer.
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.keepalive_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = rhythm.tightGap)
                                .size(18.dp)
                                .clip(CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    try {
                                        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                                            .edit()
                                            .putBoolean(
                                                PREF_KEEPALIVE_DISMISS + hint.first.lowercase(),
                                                true
                                            )
                                            .apply()
                                    } catch (_: Exception) {
                                    }
                                    autostartDismissed = true
                                }
                        )
                    }
                }
                }
            }

            if (!serviceAlive) {
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                SlateDivider()
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Exception) {
                            }
                        }
                        .padding(vertical = rhythm.tightGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhostPalette.TileInset),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                        }
                        Spacer(modifier = Modifier.width(rhythm.tightGap))
                        Text(
                            text = stringResource(R.string.keepalive_row_stopped),
                            fontSize = rhythm.bodySize,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.keepalive_row_enable),
                        fontSize = rhythm.bodySize,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Shield silhouette drawn by hand — the armor glyph, matching the Cloud drawing style. */
@Composable
private fun ShieldGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.92f, h * 0.20f)
            lineTo(w * 0.92f, h * 0.55f)
            quadraticBezierTo(w * 0.92f, h * 0.80f, w * 0.5f, h * 0.96f)
            quadraticBezierTo(w * 0.08f, h * 0.80f, w * 0.08f, h * 0.55f)
            lineTo(w * 0.08f, h * 0.20f)
            close()
        }
        drawPath(shield, color)
        // Kite cutout inside the shield, drawn in O K-weight so the inset glyph on the
        // TileInset background stays as soft as the reference's filled-and-hollow pair.
        drawCircle(
            color = color,
            radius = h * 0.13f,
            center = Offset(w * 0.5f, h * 0.44f),
            style = Stroke(width = h * 0.06f, cap = StrokeCap.Round)
        )
    }
}

/** Battery glyph: body + tip, hollow ring optional — filled keeps it readable at 18dp. */
@Composable
private fun BatteryGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, h * 0.30f),
            size = Size(w * 0.80f, h * 0.40f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.10f, h * 0.10f)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.84f, h * 0.42f),
            size = Size(w * 0.16f, h * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f, h * 0.05f)
        )
        drawRect(
            color = GhostPalette.TileInset,
            topLeft = Offset(w * 0.10f, h * 0.42f),
            size = Size(w * 0.60f, h * 0.16f)
        )
    }
}

/** Launch/play-triangle glyph for the vendor Autostart row. */
@Composable
private fun LaunchGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            moveTo(w * 0.30f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.82f)
            close()
        }
        drawPath(triangle, color)
    }
}

