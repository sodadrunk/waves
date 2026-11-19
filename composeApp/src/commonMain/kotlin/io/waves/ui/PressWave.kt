package io.waves.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A list row's press effect, drawn by the app in place of the platform ripple, which goes out on its own schedule
 * and can't be held: a wave in the press colour spreads from the touch until it fills the row ([spread]), shows as
 * strongly as [glow] says, and can empty from the left ([drain]). [rememberPressWave] runs it for a plain tap: in
 * with the press, and once it has filled the row and the press is let go, faded out. A row that does more with a press
 * (History's, turning over and emptying from the left as it waits) drives [glow] and [drain] itself.
 */
@Stable
internal class PressWave {
    val interactions = MutableInteractionSource()

    /** Whether the row is being pressed. */
    var pressed by mutableStateOf(false)
        private set

    private var at by mutableStateOf(Offset.Unspecified)
    val spread = Animatable(0f)
    val glow = Animatable(0f)
    val drain = Animatable(0f)

    /** Follows the row's presses: each starts a wave where it touched. */
    suspend fun track() = coroutineScope {
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressed = true
                    at = interaction.pressPosition
                    launch {
                        drain.snapTo(0f)
                        spread.snapTo(0f)
                        spread.animateTo(1f, tween(SPREAD_MS, easing = FastOutSlowInEasing))
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
            }
        }
    }

    /** A plain tap's wave: in with the press; let go, it fills the row and then fades out. */
    suspend fun followPresses() {
        snapshotFlow { pressed }.collect { down ->
            if (down) {
                glow.animateTo(1f, tween(GLOW_IN_MS))
            } else if (glow.value > 0f) {
                snapshotFlow { spread.value }.first { it >= 1f }
                glow.animateTo(0f, tween(TAP_FADE_MS))
            }
        }
    }

    /** Draws the wave over the row, whose top left corner is at [origin], in [color] at Material's pressed opacity. */
    fun DrawScope.draw(origin: Offset, color: Color) {
        val alpha = PRESSED_ALPHA * glow.value
        if (alpha <= 0f) return
        val from = if (at.isSpecified) at + origin else center
        // Far enough to reach the farthest corner once spread.
        val reach = maxOf(
            from.getDistance(), (from - Offset(size.width, 0f)).getDistance(),
            (from - Offset(0f, size.height)).getDistance(), (from - Offset(size.width, size.height)).getDistance(),
        )
        clipRect(left = size.width * drain.value) {
            drawCircle(color.copy(alpha = alpha), radius = reach * spread.value, center = from)
        }
    }

    companion object {
        const val SPREAD_MS = 300
        const val GLOW_IN_MS = 75

        /** How long a tapped row's wave takes to fade once it's let go. */
        const val TAP_FADE_MS = 300

        /** How strongly a press colours a row: Material's pressed opacity, as its ripple has. */
        const val PRESSED_ALPHA = 0.1f
    }
}

/** A [PressWave] that tracks its presses; with [plainTap], one that also runs itself as a tap's. */
@Composable
internal fun rememberPressWave(plainTap: Boolean = true): PressWave {
    val wave = remember { PressWave() }
    LaunchedEffect(wave) { wave.track() }
    if (plainTap) LaunchedEffect(wave) { wave.followPresses() }
    return wave
}
