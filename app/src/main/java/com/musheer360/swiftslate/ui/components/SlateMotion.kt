package com.musheer360.swiftslate.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Shared motion tokens, the temporal counterpart of [SlateRhythm].
 *
 * Where SlateRhythm agrees on space, [SlateMotion] agrees on time: entrance/exit
 * transitions reuse [std] (250 ms — the cadence already used by the tab slide, the
 * commands chevron and the card expandions), small feedback settles in [brief] and
 * data changes land with [prominent]. Keeping these as one object prevents the same
 * drift across tabs that motivated SlateRhythm in the first place.
 */
@Immutable
data class SlateMotion(
    /** Small feedback: icon fades, tag appearances. */
    val brief: Int = 150,
    /** Standard entrance/exit, matching the existing 250 ms transitions. */
    val std: Int = 250,
    /** Growth/landing emphasis: stat count-ups, chart bars. */
    val prominent: Int = 400
) {
    companion object {
        /** Fallback for subtrees without a provider; matches the app's established cadence. */
        val Default: SlateMotion = SlateMotion()
    }
}

/**
 * Provided once for the whole app, next to [LocalSlateRhythm], in `SwiftSlateMainScreen`.
 */
val LocalSlateMotion = staticCompositionLocalOf { SlateMotion.Default }

/**
 * Gentle alpha breathing for "alive" indicators — the Dashboard service-status dot.
 *
 * Runs an infinite reverse between [min] and 1f at [durationMs]; cheap (one Float
 * recomposition per frame) and short enough that it reads as breathing, not blink.
 * Immediately stable at full alpha when the feature is off — callers pass 1f.
 */
@Composable
fun rememberPulseAlpha(
    min: Float = 0.45f,
    durationMs: Int = 1400
): Float {
    val transition = rememberInfiniteTransition(label = "slate_pulse")
    val alpha by transition.animateFloat(
        initialValue = min,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs),
            repeatMode = RepeatMode.Reverse
        ),
        label = "slate_pulse_alpha"
    )
    return alpha
}
