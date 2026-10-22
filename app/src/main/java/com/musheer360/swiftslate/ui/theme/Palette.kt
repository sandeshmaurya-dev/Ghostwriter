package com.musheer360.swiftslate.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Ghostwriter's accent tokens, extracted from the reference design: a deep
 * blue-black world lit up by four neons — cyan (identity), mint (growth),
 * purple (intelligence) and a rose→orange heat ramp (action, charts).
 *
 * These deliberately live OUTSIDE MaterialTheme: they are decorative (icon
 * tiles, gradients, glows), never the semantic role colors the scheme above
 * already carries. Screens read them directly instead of threading them
 * through CompositionLocals the rhythm/motion use.
 */
object GhostPalette {
    /** Identity cyan — the logo tiles, key highlights. */
    val Cyan = Color(0xFF22D3EE)
    val CyanDeep = Color(0xFF0891B2)

    /** Growth mint — the "This Month" card, active glow. */
    val Mint = Color(0xFF34D399)
    val MintDeep = Color(0xFF10B981)

    /** AI purple — the "Top Command" card. */
    val Purple = Color(0xFF8B5CF6)
    val PurpleDeep = Color(0xFF6D28D9)

    /** Heat ramp — bar chart, action pills, the active nav flame. */
    val Amber = Color(0xFFF59E0B)
    val Orange = Color(0xFFF97316)
    val Vermilion = Color(0xFFEF4444)
    val Rose = Color(0xFFF43F5E)
    val RoseLight = Color(0xFFFB7185)

    /** Tile/chart bases. */
    val BarBase = Color(0xFF3F2A22)      // dim stub of zero-count days
    val TileInset = Color(0xFF1A1D28)    // icon-tile wells inside cards
}

/** Vertical heat ramp used by bar fills and pills: amber → orange → vermilion. */
fun heatBarBrush(): Brush = Brush.verticalGradient(
    listOf(GhostPalette.Amber, GhostPalette.Orange, GhostPalette.Vermilion)
)

/** Rose pill: lighter top → deeper base. */
fun rosePillBrush(): Brush = Brush.verticalGradient(
    listOf(GhostPalette.RoseLight, GhostPalette.Rose)
)

/** Warm rim for the selected nav item: vermilion → amber → orange → deep red. */
fun flameRimBrush(): Brush = Brush.sweepGradient(
    listOf(GhostPalette.Vermilion, GhostPalette.Amber, GhostPalette.Orange, GhostPalette.Vermilion)
)
