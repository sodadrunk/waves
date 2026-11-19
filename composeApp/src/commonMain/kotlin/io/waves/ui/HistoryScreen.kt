package io.waves.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateFloatAsState
import io.waves.model.Episode
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.waves.currentTimeMillis
import io.waves.platformName
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.sync.AuthState
import io.waves.sync.SyncStatus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryState,
    deviceId: String,
    onPlay: (HistoryEntry) -> Unit,
    /** Null hides the download state. */
    downloads: DownloadsState? = null,
    /** Pulling the list down syncs through it while signed in with sync on; null leaves out pull to sync. */
    sync: SettingsState? = null,
    /** The episode behind an entry, for its download button; none when it is no longer known. */
    episodeOf: suspend (HistoryEntry) -> Episode? = { null },
    /** The episode playing now (not one paused), whose button pauses it. */
    playing: EpisodeId? = null,
    onPause: () -> Unit = {},
    /** Marks an entry's episode played, or not played again. */
    onSetPlayed: (HistoryEntry, Boolean) -> Unit = { _, _ -> },
) {
    val ui by state.state.collectAsState()
    val syncUi = sync?.let { it.state.collectAsState().value }
    // Signed in with sync on: the list syncs when pulled, and the header tells when it last did (else that sync is off).
    val syncs = sync != null && syncUi != null && syncUi.auth is AuthState.SignedIn && syncUi.settings.syncEnabled
    Scaffold(
        topBar = { WavesTopBar("History", icon = Tab.History.icon) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val listState = rememberLazyListState()
            val today = HistoryState.localDate(currentTimeMillis(), TimeZone.currentSystemDefault())
            // The day the list shows at its top, named just above the divider instead of on the list. A day's own label
            // takes over only once it has gone all the way under the divider.
            val line = with(LocalDensity.current) { ListDividerHeight.toPx() }
            val topDay by remember(ui.days, line) {
                derivedStateOf {
                    val below = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > line }?.index
                        ?: listState.firstVisibleItemIndex
                    dayAtTop(ui, if (isDayLabel(ui, below)) below - 1 else below)
                }
            }
            // A list holds on to the row it shows first. So a row moving or arriving above the first one (an episode
            // played again) would land out of sight above it: resting at its top, the list stays there instead. And
            // further down, when the row it shows first is the one that moves to the top, the list would go up
            // with it: it stays where it is, and the rows around close the gap.
            val firstKey = ui.days.firstOrNull()?.entries?.firstOrNull()?.episodeId
            val shownFirst = remember { mutableStateOf(firstKey) }
            if (shownFirst.value != firstKey) {
                val index = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                val held = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
                when {
                    index == 0 && offset == 0 -> listState.requestScrollToItem(0)
                    firstKey != null && held == entryKey(firstKey) -> listState.requestScrollToItem(index, offset)
                }
                SideEffect { shownFirst.value = firstKey }
            }
            // Pinned like Home's shelf, its divider on the same line.
            Column(Modifier.height(ShelfBandHeight)) {
                // Its top on the line Home's shelf covers and Discover's search field start on.
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                    HistorySummary(ui, showDownloads = downloads != null, onToggleDownloaded = state::toggleDownloadedOnly)
                }
                // A narrowed or widened list starts over from its top.
                LaunchedEffect(ui.downloadedOnly) { listState.scrollToItem(0) }
                // Where Discover names its list, so the label holds its place when swiping between the tabs; at the
                // line's end, when the library last synced.
                Row(
                    Modifier.fillMaxWidth().contentInset().padding(start = ScreenGutter, end = ScreenGutter, bottom = 4.dp)
                        .offset(y = -BandLabelLift),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionLabel(topDay?.let { dayLabel(it, today) }.orEmpty(), Modifier.weight(1f))
                    if (syncUi != null) SyncStamp(if (syncs) syncUi.sync else null, ui.now)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val pull = rememberPullProgress()
                // Rows scrolling up end at the divider's line; on the list, so a pull's waves come down through it.
                val list = Modifier.clip(BelowListDivider)
                if (syncs) {
                    WavesPullToRefreshBox(
                        // Only syncs asked for here or in Settings spin; background ones run often while playing.
                        isRefreshing = syncUi!!.busy,
                        onRefresh = sync!!::syncNow,
                        modifier = Modifier.fillMaxSize(),
                        gapTop = ListDividerHeight,
                        progress = pull,
                    ) { HistoryList(ui, deviceId, onPlay, downloads, listState, today, episodeOf, playing, onPause, onSetPlayed, state::remove, list) }
                } else {
                    HistoryList(ui, deviceId, onPlay, downloads, listState, today, episodeOf, playing, onPause, onSetPlayed, state::remove, list)
                }
                ListDivider(tint = { pull.value })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(
    ui: HistoryUiState,
    deviceId: String,
    onPlay: (HistoryEntry) -> Unit,
    downloads: DownloadsState?,
    listState: LazyListState,
    today: LocalDate,
    episodeOf: suspend (HistoryEntry) -> Episode?,
    playing: EpisodeId?,
    onPause: () -> Unit,
    onSetPlayed: (HistoryEntry, Boolean) -> Unit,
    onRemove: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The row turned over, and to which of its other sides: tapped, to its buttons; held, to its deletion. It turns
    // back by itself when left alone, as its highlight fades out.
    var turned by remember { mutableStateOf<Pair<EpisodeId, RowSide>?>(null) }
    // Scrolled out of sight, a turned row turns back: it's met the right way up when scrolled back.
    LaunchedEffect(listState) {
        snapshotFlow {
            turned?.first?.let { id -> listState.layoutInfo.visibleItemsInfo.any { it.key == entryKey(id) } }
        }.collect { inSight -> if (inSight == false) turned = null }
    }
    LazyColumn(
        modifier.fillMaxSize()
            .verticalFadingEdge(fadeLength = 0.dp, bottomFadeLength = floatingBarBottomGap()),
        // Rows start below the divider.
        contentPadding = listContentPadding(top = ListDividerHeight),
        state = listState,
    ) {
        // A list even when empty, so it can still be pulled to sync.
        if (ui.loaded && ui.days.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillParentMaxSize().padding(ScreenGutter), contentAlignment = Alignment.Center) {
                    Text(
                        if (ui.downloadedOnly) "No downloaded episodes played yet" else "Nothing played yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ui.days.forEach { day ->
            // The first day is named above the divider; the others where they start, until they reach the top.
            if (day != ui.days.first()) {
                item(key = day.date.toString()) {
                    SectionLabel(
                        dayLabel(day.date, today),
                        Modifier.animateItem().contentInset().padding(start = ScreenGutter, end = ScreenGutter, top = 10.dp, bottom = 4.dp),
                    )
                }
            }
            // Keyed by episode (each is listed once), so a row that comes, goes or moves up animates there.
            itemsIndexed(day.entries, key = { _, entry -> entryKey(entry.episodeId) }) { index, entry ->
                val thisDevice = entry.deviceId == deviceId
                val played = ui.played[entry.episodeId]
                val flip by animateFloatAsState(if (turned?.first == entry.episodeId) 180f else 0f, tween(FLIP_MS), label = "flip")
                // The row's own press effect, in place of the ripple, which goes out by itself and can't be kept on
                // (each attempt to hand over from it made the colour rise and fall): a wave from the touch fills the
                // row, stays while the row turns over, and once it has turned empties from left to right; the row starts
                // turning back by itself once it has all emptied, unless used first.
                val isTurned = turned?.first == entry.episodeId
                val press = rememberPressWave(plainTap = false)
                val glow = press.glow
                val drain = press.drain
                val pressed = press.pressed
                // Whether the row turned back by itself, so the fade carries on as it was going.
                var timedOut by remember { mutableStateOf(false) }
                LaunchedEffect(isTurned, pressed) {
                    when {
                        isTurned -> {
                            timedOut = false
                            glow.animateTo(1f, tween(PressWave.GLOW_IN_MS))
                            delay(FLIP_MS.toLong())
                            drain.animateTo(TURN_BACK_AT, tween((LIT_FADE_MS * (TURN_BACK_AT - drain.value)).toInt(), easing = LinearEasing))
                            if (turned?.first == entry.episodeId) {
                                timedOut = true
                                turned = null
                            }
                        }
                        pressed -> {
                            drain.snapTo(0f)
                            glow.animateTo(1f, tween(PressWave.GLOW_IN_MS))
                        }
                        timedOut -> {
                            drain.animateTo(1f, tween((LIT_FADE_MS * (1f - drain.value)).toInt(), easing = LinearEasing))
                            glow.snapTo(0f)
                            drain.snapTo(0f)
                        }
                        else -> {
                            glow.animateTo(0f, tween(FLIP_MS))
                            drain.snapTo(0f)
                        }
                    }
                }
                // The side last turned to, still shown while the row turns back.
                var side by remember { mutableStateOf(RowSide.Actions) }
                turned?.takeIf { it.first == entry.episodeId }?.let { side = it.second }
                // Tapped, the row turns over to what can be done with its episode; held, to deleting its record.
                Box(
                    Modifier.animateItem().fillMaxWidth().topDivider(index > 0)
                        .zigzagClickable(
                            top = index > 0, bottom = index < day.entries.lastIndex,
                            above = index == 0 && day == ui.days.first(),
                            onLongClick = { turned = entry.episodeId to RowSide.Deletion },
                            // Turned over, the row's other side takes the taps.
                            enabled = flip == 0f && turned?.first != entry.episodeId,
                            wave = press,
                        ) { turned = entry.episodeId to RowSide.Actions },
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            // Only what's on the row turns over; its divider and press ripple stay as they are.
                            .graphicsLayer {
                                rotationX = flip
                                cameraDistance = 16f * density
                                alpha = if (flip > 90f) 0f else 1f
                            }
                            .contentInset()
                            .padding(horizontal = ScreenGutter, vertical = ListRowVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListRowGap),
                    ) {
                        Box {
                            Icon(
                                if (thisDevice) ThisDeviceIcon else Icons.Default.Devices,
                                contentDescription = if (thisDevice) "This device" else "Other device",
                                modifier = Modifier.size(24.dp),
                                // This device's sessions stand out by the icon's colour.
                                tint = if (thisDevice) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            if (downloads != null) {
                                HistoryDownloadState(entry, downloads, Modifier.align(Alignment.BottomEnd).offset(5.dp, 5.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                entry.episodeTitle, style = listTitleStyle(),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            // How much of the episode has been played (a tick once all of it), and the show.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (played == 100) {
                                    Icon(
                                        Icons.Rounded.Check, "Completed", Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                    MetaText(" · ")
                                } else if (played != null) {
                                    MetaText("$played% · ")
                                }
                                MetaText(entry.podcastTitle, Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                    if (flip > 90f) {
                        // The row's other side, upright once the turn is done, and over all of it, so nothing under it is tapped.
                        val back = Modifier.matchParentSize()
                            .graphicsLayer {
                                rotationX = flip - 180f
                                cameraDistance = 16f * density
                            }
                            .contentInset().padding(horizontal = ScreenGutter)
                        if (side == RowSide.Deletion) {
                            RowDeletion(
                                onClose = { turned = null },
                                onDelete = {
                                    turned = null
                                    onRemove(entry)
                                },
                                back,
                            )
                        } else {
                            RowBack(onClose = { turned = null }, back) {
                                val isPlaying = playing == entry.episodeId
                                RowIconButton(
                                    when {
                                        isPlaying -> Icons.Rounded.Pause
                                        played == 100 -> Icons.Rounded.Replay
                                        else -> Icons.Rounded.PlayArrow
                                    },
                                    when {
                                        isPlaying -> "Pause"
                                        played == 100 -> "Replay"
                                        else -> "Play"
                                    },
                                    onClick = {
                                        if (isPlaying) onPause() else onPlay(entry)
                                        turned = null
                                    },
                                )
                                // Only to mark it played: a completed episode just starts over when played again.
                                if (played != 100) {
                                    RowIconButton(
                                        Icons.Rounded.Check, "Mark played",
                                        onClick = {
                                            onSetPlayed(entry, true)
                                            turned = null
                                        },
                                    )
                                }
                                // Only a download there is, to remove: History isn't where episodes are downloaded from.
                                if (downloads != null) {
                                    val control by remember(entry.episodeId) { downloads.control(entry.episodeId) }
                                        .collectAsState(initial = DownloadControl.NotDownloaded)
                                    if (control == DownloadControl.Downloaded) {
                                        RowIconButton(
                                            Icons.Outlined.DeleteOutline, "Remove download",
                                            onClick = {
                                                downloads.remove(entry.episodeId)
                                                turned = null
                                            },
                                            // The download it removes, marked as on the device icon.
                                            badge = {
                                                DownloadStateIcon(
                                                    DownloadControl.Downloaded, 14.dp,
                                                    Modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.5.dp),
                                                )
                                            },
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
}

/** The key of an episode's row in the list. */
private fun entryKey(id: EpisodeId): String = "episode $id"

/** Whether the list's item at [index] is a day's label (every day's but the first's), as [HistoryList] lays them out. */
private fun isDayLabel(ui: HistoryUiState, index: Int): Boolean {
    var start = 0
    ui.days.forEachIndexed { n, day ->
        if (n > 0 && index == start) return true
        start += day.entries.size + if (n == 0) 0 else 1
    }
    return false
}

/** The day of the list's item at [index], in the order [HistoryList] lays them out. */
private fun dayAtTop(ui: HistoryUiState, index: Int): LocalDate? {
    var i = index
    ui.days.forEachIndexed { n, day ->
        val size = day.entries.size + if (n == 0) 0 else 1
        if (i < size) return day.date
        i -= size
    }
    return ui.days.lastOrNull()?.date
}

/**
 * This week's listening time (every device) and how many episodes are downloaded here, side by side. The downloads
 * tile is a toggle: on, the list keeps only the entries with a download here.
 */
@Composable
private fun HistorySummary(
    ui: HistoryUiState,
    showDownloads: Boolean,
    onToggleDownloaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().contentInset().padding(horizontal = ScreenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryTile(formatListened(ui.listenedThisWeekMs), "listened this week", Modifier.weight(1f))
        if (showDownloads) {
            SummaryTile(
                "${ui.downloaded} ep", "downloaded", Modifier.weight(1f),
                selected = ui.downloadedOnly, onClick = onToggleDownloaded,
            )
        }
    }
}

/** A figure over its caption; with [onClick], a toggle, marked in its top corner while [selected]. */
@Composable
private fun SummaryTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // The tile looks the same on and off: only the mark in its corner tells.
    val container = colors.onSurface.copy(alpha = 0.06f)
    val content = colors.onSurface
    val body: @Composable () -> Unit = {
        Box {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
            // A toggle says so, and which way it is: an empty ring while off, filled and ticked while on.
            if (onClick != null) {
                val fill by animateColorAsState(if (selected) colors.primary else Color.Transparent, label = "tile mark")
                val ring by animateColorAsState(if (selected) colors.primary else colors.outline, label = "tile ring")
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp)
                        .background(fill, CircleShape).border(1.5.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, null, Modifier.size(10.dp), tint = colors.onPrimary)
                }
            }
        }
    }
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Surface(selected, onClick, modifier, shape = shape, color = container, contentColor = content, content = body)
    } else {
        Surface(modifier, shape = shape, color = container, contentColor = content, content = body)
    }
}

/**
 * When the library last synced ([sync]; null while sync is off): a cloud icon that's always there, and words beside it
 * shown only for a few seconds after it changes, and for as long as a sync runs.
 */
@Composable
private fun SyncStamp(sync: SyncStatus?, now: Long) {
    var shown by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(sync) {
        // Not on first showing History: only an update brings it up.
        if (first) {
            first = false
            return@LaunchedEffect
        }
        shown = true
        if (sync == SyncStatus.Syncing) return@LaunchedEffect
        delay(SyncStampMillis)
        shown = false
    }
    // Lighter than the label's text: an icon's strokes read heavier than letters of the same colour.
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Eases in and, slower still, out, so it comes and goes without catching the eye.
        AnimatedVisibility(shown, enter = fadeIn(tween(500)), exit = fadeOut(tween(900))) {
            SectionLabel(if (sync == null) "Sync off" else syncedLabel(sync, now))
        }
        // Its icon stays, so the state of sync is always plain to see; the words come with each update.
        Crossfade(cloudIcon(sync), label = "sync icon") { icon ->
            Icon(
                icon,
                contentDescription = if (sync == null) "Sync off" else syncedLabel(sync, now),
                modifier = Modifier.size(17.dp),
                tint = if (sync is SyncStatus.Failed) MaterialTheme.colorScheme.error else muted,
            )
        }
    }
}

private const val SyncStampMillis = 4_000L

private fun cloudIcon(sync: SyncStatus?) = when (sync) {
    null -> Icons.Outlined.CloudOff
    SyncStatus.Syncing -> Icons.Outlined.CloudSync
    is SyncStatus.Done -> Icons.Outlined.CloudDone
    // Failed (tinted red) or not synced yet.
    else -> Icons.Outlined.Cloud
}

/** When the library last synced, as of [now] (History's state ticks over with the minutes); else what it's doing instead. */
private fun syncedLabel(sync: SyncStatus, now: Long): String = when (sync) {
    SyncStatus.Idle -> "Not synced yet"
    SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Failed -> "Sync failed"
    is SyncStatus.Done -> "Synced ${formatSince(sync.atEpochMs, now).replaceFirstChar { it.lowercase() }}"
}

/** "Just now", "5 min ago", "2 h ago", "Yesterday", "3 days ago"; older as [formatShortDate]. Short, for a label. */
internal fun formatSince(
    epochMs: Long,
    now: Long = currentTimeMillis(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val minutes = (now - epochMs) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> formatShortDate(epochMs, timeZone)
    }
}

/** "0 min", "45 min", "3 h 20 min". */
internal fun formatListened(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return if (hours == 0L) "$minutes min" else "$hours h ${minutes % 60} min"
}

/** History rows played here: a phone on Android, a computer on macOS. */
internal val ThisDeviceIcon = if (platformName.startsWith("Android")) Icons.Default.PhoneAndroid else Icons.Default.Computer

/** A row's other sides: what can be done with its episode, and deleting its record. */
private enum class RowSide { Actions, Deletion }

/** A row's other side: [content] in its middle; tapping anywhere else on it turns it back. */
@Composable
private fun RowBack(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Close together, without the room a button otherwise keeps around itself.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        // Side by side in the middle of the row, [RowButtonPitch] apart by their middles, the icons' (a caption is
        // centred under its icon, whatever its width).
        Layout(content, modifier.pointerInput(Unit) { detectTapGestures { onClose() } }) { measurables, constraints ->
            val width = constraints.maxWidth
            val pitch = minOf(RowButtonPitch.roundToPx(), width / (measurables.size + 1))
            // Each no wider than the room between two middles, so captions don't run into each other.
            val room = if (measurables.size > 1) (pitch - 4.dp.roundToPx()).coerceAtLeast(0) else width
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = room)) }
            val height = constraints.maxHeight.takeIf { it != Constraints.Infinity }
                ?: (placeables.maxOfOrNull { it.height } ?: 0)
            layout(width, height) {
                placeables.forEachIndexed { i, p ->
                    val middle = width / 2 + ((2 * i - (placeables.size - 1)) * pitch) / 2
                    p.place(middle - p.width / 2, (height - p.height) / 2)
                }
            }
        }
    }
}

/** The side a held row turns to: whether to delete its record from the history. */
@Composable
private fun RowDeletion(onClose: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    RowBack(onClose, modifier) {
        // Outlined in red like the other side's circles, as wide as its label.
        Surface(
            onClick = onDelete,
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = colors.error,
            border = BorderStroke(1.dp, colors.error),
            modifier = Modifier.height(RowButtonSize),
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Fine strokes, to go with the hairline outline: a smaller icon draws its lines thinner, and the
                // label is in the regular weight, not the labels' medium.
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(17.dp))
                Text(
                    "Remove from history", maxLines = 1,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                )
            }
        }
    }
}

private const val FLIP_MS = 360

/** How long a turned row's highlight takes to empty, and so about how long the row waits to be used before it turns back. */
private const val LIT_FADE_MS = 3_750

/** How much of a turned row's highlight has emptied when the row starts turning back by itself. */
private const val TURN_BACK_AT = 1f



/**
 * A button on a row's other side: [icon] in an outlined circle, all of them alike, with [description] as a caption
 * under it when [caption]; in [color] for a removal.
 */
@Composable
private fun RowIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    color: Color = Color.Unspecified,
    caption: Boolean = false,
    /** A mark at the circle's bottom end, saying what the button acts on. */
    badge: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val ink = if (color.isSpecified) color else colors.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = ink,
                border = BorderStroke(1.dp, if (color.isSpecified) color else colors.outlineVariant),
                modifier = Modifier.size(RowButtonSize),
            ) {
                // A caption says it: no second description for screen readers.
                Box(contentAlignment = Alignment.Center) { Icon(icon, description.takeUnless { caption }, Modifier.size(20.dp)) }
            }
            badge?.let { Box(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp)) { it() } }
        }
        if (caption) {
            Text(
                description, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center,
                color = if (color.isSpecified) color else colors.onSurfaceVariant,
            )
        }
    }
}

private val RowButtonSize = 36.dp

/** How far apart a row's buttons are, middle to middle: 28 dp between two 36 dp circles. */
private val RowButtonPitch = 64.dp

/**
 * Badge on the device icon: the episode's download state when it has a local copy (or one is on the way);
 * nothing otherwise. The ring in the background colour keeps it apart from the icon underneath.
 */
@Composable
private fun HistoryDownloadState(entry: HistoryEntry, downloads: DownloadsState, modifier: Modifier = Modifier) {
    val control by remember(entry.episodeId) { downloads.control(entry.episodeId) }
        .collectAsState(initial = DownloadControl.NotDownloaded)
    val description = when (control) {
        DownloadControl.NotDownloaded -> return
        is DownloadControl.InProgress -> "Downloading"
        DownloadControl.Downloaded -> "Downloaded"
        DownloadControl.Cached -> "Cached"
        is DownloadControl.Failed -> "Download failed"
    }
    DownloadStateIcon(
        control, 13.dp,
        modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.5.dp)
            .semantics { contentDescription = description },
    )
}

private val monthNamesLong = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

internal fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minus(DatePeriod(days = 1)) -> "Yesterday"
    else -> buildString {
        append(date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() })
        append(", ").append(monthNamesLong[date.monthNumber - 1]).append(' ').append(date.dayOfMonth)
        if (date.year != today.year) append(", ").append(date.year)
    }
}
