package io.waves.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What the placeholder rows of a cold start share: how far they have faded in, only once the wait outlasts
 * [ShowAfterMs], so a quick read never flashes them. They don't move after that: a cold start keeps the main thread
 * too busy for a shimmer to run smoothly, and a still placeholder can't stutter.
 */
internal class SkeletonClock(val shown: Animatable<Float, *>)

@Composable
internal fun rememberSkeletonClock(): SkeletonClock {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(ShowAfterMs)
        shown.animateTo(1f, tween(FadeInMs))
    }
    return remember(shown) { SkeletonClock(shown) }
}

/**
 * An episode row's placeholder while the list is still being read on a cold start: still grey shapes where its show,
 * title, description and play pill will be. Drawn in one go, without a layout of its own for each shape: a cold start
 * has little time to spare per frame. [index] varies the line lengths so the rows don't
 * look stamped out.
 */
@Composable
internal fun EpisodeSkeleton(index: Int, divider: Boolean, clock: SkeletonClock, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val titleShare = TitleShares[index % TitleShares.size]
    Canvas(
        modifier.fillMaxWidth().topDivider(divider).contentInset()
            .padding(horizontal = ScreenGutter, vertical = ListRowVerticalPadding).height(RowHeight),
    ) {
        val alpha = clock.shown.value
        if (alpha <= 0f) return@Canvas
        val base = ink.copy(alpha = 0.09f * alpha)
        fun DrawScope.bone(x: Float, y: Float, w: Float, h: Float, corner: Float = h / 2) {
            drawRoundRect(base, Offset(x, y), Size(w, h), CornerRadius(corner))
        }
        val gap = ListRowGap.toPx()
        var y = 0f
        // The show: its cover, its name and the episode's age beside it.
        val cover = 28.dp.toPx()
        bone(0f, y, cover, cover, 5.dp.toPx())
        bone(cover + gap, y + 4.dp.toPx(), 110.dp.toPx(), 9.dp.toPx())
        bone(cover + gap, y + 18.dp.toPx(), 60.dp.toPx(), 8.dp.toPx())
        y += cover + 8.dp.toPx()
        // The title, and two lines of description.
        bone(0f, y, size.width * titleShare, 12.dp.toPx())
        y += 20.dp.toPx()
        bone(0f, y, size.width * 0.92f, 8.dp.toPx())
        y += 16.dp.toPx()
        bone(0f, y, size.width * 0.7f, 8.dp.toPx())
        y += 18.dp.toPx()
        // The play pill.
        bone(0f, y, 96.dp.toPx(), 30.dp.toPx())
    }
}

/** How many outline rows stand in for the list. */
internal const val SkeletonRows = 5

/** The outline's height inside the row's padding: the shapes laid out in [EpisodeSkeleton]. */
private val RowHeight = 128.dp

private val TitleShares = listOf(0.86f, 0.64f, 0.78f, 0.7f, 0.9f)
private const val ShowAfterMs = 250L
private const val FadeInMs = 300
