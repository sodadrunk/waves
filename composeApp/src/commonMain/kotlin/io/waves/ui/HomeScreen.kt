package io.waves.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    playback: PlaybackState,
    onOpenPodcast: (Podcast) -> Unit,
    onPlay: (Episode, Podcast) -> Unit,
    onPause: () -> Unit,
    onDiscover: () -> Unit,
    /** Null hides the per-episode download control. */
    downloads: DownloadsState? = null,
    /** Marks an episode complete or not; null hides the per-episode button. */
    onSetCompleted: ((Episode, Boolean) -> Unit)? = null,
    /** Streams on air from followed channels, listed first; null lists none. */
    liveNow: LiveNowState? = null,
) {
    val ui by state.state.collectAsState()
    val live by (liveNow?.items ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    Scaffold(
        topBar = {
            WavesTopBar(
                title = "Home",
                icon = if (ui.offline) Icons.Filled.CloudOff else Tab.Home.icon,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (ui.loaded && ui.subscriptions.isEmpty() && ui.upNext.isEmpty() && live.isEmpty()) {
            Box(Modifier.padding(padding)) {
                Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Find shows in Discover and tap Subscribe.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Button(onClick = onDiscover) { Text("Discover podcasts") }
                    }
                }
            }
        } else Column(Modifier.padding(padding).fillMaxSize()) {
            // Held at its height from the first frame, while the library is still being read, so its covers fill in
            // where they'll stay instead of arriving and pushing the list down.
            val pinned = !ui.loaded || ui.shelf.isNotEmpty()
            if (pinned) ShowShelf(ui.shelf, onOpenPodcast, Modifier.height(ShelfBandHeight).padding(top = 8.dp, bottom = 4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Only the list moves with a pull; the shelf and its divider stay put above the gap.
                val pull = rememberPullProgress()
                WavesPullToRefreshBox(
                    isRefreshing = ui.refreshing,
                    onRefresh = {
                        if (!ui.offline) {
                            state.refreshAll()
                            liveNow?.refresh()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    gapTop = if (pinned) ListDividerHeight else 0.dp,
                    progress = pull,
                    // A finished refresh re-sorts Up next; the rows slide into their new order before the list springs back.
                    settleMillis = RowMoveMillis,
                ) {
                    val list = rememberLazyListState()
                    // Rows come to rest with their divider on the shelf's, not a sliver above or below it.
                    val guides = rememberSnapGuides()
                    if (pinned) SnapToTopLine(list, reach = EpisodeSnapReach, guides = guides)
                    // A row arriving above the first one (a stream going on air, a new episode) would land out of
                    // sight above it, as a list holds on to the row it shows first: resting at its top, the list
                    // stays there instead, and the rows move down to let the new one in.
                    val firstKey = when {
                        ui.error != null -> "error"
                        live.isNotEmpty() -> "live ${live.first().episode.id}"
                        else -> ui.upNext.firstOrNull()?.row?.episode?.id?.toString()
                    }
                    val shownFirst = remember { mutableStateOf(firstKey) }
                    if (shownFirst.value != firstKey) {
                        if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) list.requestScrollToItem(0)
                        SideEffect { shownFirst.value = firstKey }
                    }
                    val skeleton = !ui.loaded && ui.upNext.isEmpty() && live.isEmpty() && ui.error == null
                    // One clock for all the outline rows, and none once the list is read.
                    val skeletonClock = if (skeleton) rememberSkeletonClock() else null
                    LazyColumn(
                        Modifier.fillMaxSize()
                            // Under the shelf, rows scrolling up end at its divider's line instead of fading out. On
                            // the list, not the pull's box: the waves the pull opens come down through that line.
                            .then(if (pinned) Modifier.clip(BelowListDivider) else Modifier)
                            .snapGuideList(guides)
                            .verticalFadingEdge(
                                fadeLength = if (pinned) 0.dp else 8.dp,
                                bottomFadeLength = floatingBarBottomGap(),
                            ),
                        state = list,
                        // Under the shelf, rows start below its divider.
                        contentPadding = listContentPadding(top = if (pinned) ListDividerHeight else 0.dp),
                    ) {
                        // Rows coming and going (new episodes, finished ones) fade while the rest slide to make room.
                        ui.error?.let {
                            item(key = "error") {
                                Text(
                                    it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.animateItem().contentInset().padding(horizontal = ScreenGutter),
                                )
                            }
                        }
                        // A cold start: until the library has been read, outlines of rows, which only show if that
                        // takes a moment (no flash on a quick one); the real rows fade in as they go.
                        if (skeleton) {
                            items(SkeletonRows, key = { "skeleton $it" }) { index ->
                                EpisodeSkeleton(index, divider = index > 0, clock = skeletonClock!!, modifier = Modifier.animateItem(fadeOutSpec = null))
                            }
                        }
                        // Until the library has been read, no status line: rows ease in once it has, without a
                        // "loading" line flashing first on every launch.
                        if (ui.upNext.isEmpty() && live.isEmpty() && ui.loaded) {
                            item(key = "status") {
                                Text(
                                    when {
                                        ui.offline && ui.loaded && !ui.refreshing -> "No downloaded or fully cached episodes."
                                        ui.refreshing -> "Loading episodes…"
                                        !ui.hasEpisodes -> "Pull down to load the latest episodes."
                                        else -> "You're all caught up."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.animateItem().contentInset().padding(horizontal = ScreenGutter, vertical = 8.dp),
                                )
                            }
                        }
                        // Streams on air from followed channels, ahead of everything that can wait.
                        itemsIndexed(live, key = { _, it -> "live ${it.episode.id}" }) { index, item ->
                            val isCurrent = playback.episode?.id == item.episode.id
                            EpisodeItem(
                                row = EpisodeRow(item.episode, progress = null, live = true),
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && playback.isPlaying,
                                onPlay = { onPlay(item.episode, item.podcast) },
                                onPause = onPause,
                                show = item.podcast.takeIf { it.title.isNotBlank() },
                                onOpenShow = { onOpenPodcast(item.podcast) },
                                badge = "Live now",
                                divider = index > 0,
                                dividerBelow = index < live.lastIndex || ui.upNext.isNotEmpty(),
                                dividerAbove = pinned && index == 0 && ui.error == null,
                                modifier = Modifier.animateItem(),
                                snapGuides = guides.takeIf { pinned },
                            )
                        }
                        itemsIndexed(ui.upNext, key = { _, it -> it.row.episode.id.toString() }) { index, item ->
                            val isCurrent = playback.episode?.id == item.row.episode.id
                            EpisodeItem(
                                row = item.row,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && playback.isPlaying,
                                onPlay = { onPlay(item.row.episode, item.podcast) },
                                onPause = onPause,
                                show = item.podcast.takeIf { it.title.isNotBlank() },
                                onOpenShow = { onOpenPodcast(item.podcast) },
                                badge = item.reason.label,
                                downloadControl = downloads?.let { d -> { m -> DownloadButton(item.row.episode, d, m, compact = true, played = item.row.played) } },
                                divider = index > 0 || live.isNotEmpty(),
                                dividerBelow = index < ui.upNext.lastIndex,
                                // Right under the shelf's divider, the first row's ripple fills up to its zigzag, where the
                                // clip around the list cuts it.
                                dividerAbove = pinned && index == 0 && ui.error == null && live.isEmpty(),
                                onToggleComplete = onSetCompleted?.let { { it(item.row.episode, !item.row.played) } },
                                modifier = Modifier.animateItem(),
                                snapGuides = guides.takeIf { pinned },
                            )
                        }
                    }
                }
                // Stays put as rows scroll under it.
                // Takes on the waves' colour as a pull opens the gap under it.
                if (pinned) ListDivider(tint = { pull.value })
            }
        }
    }
}

@Composable
private fun ShowShelf(shows: List<Podcast>, onOpenPodcast: (Podcast) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        // Scrolls the window's whole width, starting and ending in line with the content column.
        LazyRow(
            // Stretches at either end like the app's other lists, without handing the pager the leftover.
            modifier = Modifier.horizontalScrollRegion().horizontalOverscroll(),
            contentPadding = PaddingValues(horizontal = 8.dp + LocalContentInset.current),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shows, key = { it.feedUrl }) { podcast ->
                val anchor = rememberCoverAnchor(podcast.feedUrl, corner = 9.dp)
                Box(
                    // Covers arriving at start-up fade in where they'll stay; later ones slide over to make room.
                    Modifier.animateItem().clip(RoundedCornerShape(10.dp)).clickable {
                        anchor.mark()
                        onOpenPodcast(podcast)
                    },
                ) {
                    Artwork(
                        podcast.artworkUrl, podcast.title, anchor.modifier, size = ShelfCoverSize, corner = 9.dp,
                        onLoaded = anchor.onLoaded, liveArchive = podcast.isLiveArchive,
                        playlist = podcast.playlistId, fadeInOnLoad = true,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private val ShelfCoverSize = 92.dp

/** About how long `animateItem`'s default placement spring takes to bring a row to its new place. */
private const val RowMoveMillis = 400L

/**
 * Top bar to the shelf's divider: the shelf's padding (8 dp, 4 dp), covers and spacer. Discover's and History's pinned
 * headers match it, so the divider sits on the same line on every tab.
 */
internal val ShelfBandHeight = 8.dp + ShelfCoverSize + 8.dp + 4.dp

/** How far above the divider a pinned header's label (Discover's list, History's day) is lifted, alike on both. */
internal val BandLabelLift = 6.dp

internal val UpNextReason.label: String?
    get() = when (this) {
        UpNextReason.ContinueListening -> "Continue"
        UpNextReason.New -> "New"
        UpNextReason.FrequentShow -> "You listen often"
        UpNextReason.Downloaded -> "Downloaded"
        UpNextReason.Latest -> null
    }
