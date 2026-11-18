package io.waves.player

import io.waves.model.Episode
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop (JVM/macOS) player backed by JavaFX Media (bundled native GStreamer-lite/AVFoundation backend,
 * so no external install such as VLC is needed). Streams http(s) MP3 and AAC/M4A, supports seek and
 * playback speed (JavaFX `rate`).
 *
 * A live stream ([PlaybackState.isLive]) plays from what's on air, through [LiveRelay], which is what lets it be
 * rewound: JavaFX won't seek in a live stream, so a seek (and a resume, back where the pause was) opens the stream
 * again from that point.
 *
 * Threading: all [MediaPlayer] access happens on the JavaFX application thread; public methods may be
 * called from any thread. [state] is a thread-safe [StateFlow].
 */
class DesktopAudioPlayer(
    /** Told, as it plays, the URL it plays and where in it it is (position and duration, ms): the relay paces by it. */
    private val onProgress: (url: String, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    /** Where an episode's address plays from; [LocatedMedia.live] for a stream on air (an HLS playlist). */
    private val resolveUrl: suspend (String) -> LocatedMedia = { LocatedMedia(RedirectResolver.resolve(it)) },
) : AudioPlayer {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadJob: Job? = null
    private val ticker = PositionTicker(scope) { fx { publishFromPlayer() } }

    // ---- confined to the JavaFX thread (or written before the player exists) ----
    @Volatile private var generation = 0L
    @Volatile private var player: MediaPlayer? = null
    private var loadedUrl: String? = null
    private var live = false
    /** The live stream being played, for rewinding it; null for a recording, or a stream that can't be rewound. */
    @Volatile private var liveSession: LiveRelay.Session? = null
    /** The stream time ([LiveRelay.Window]) last played, where a resume picks up. */
    @Volatile private var liveTimeMs: Long? = null
    /** Counts [reopenLive] calls: only the latest opens a player. */
    @Volatile private var liveReopening = 0L
    /** False from a [reopenLive] until its player is ready: the one before it no longer says where the stream plays. */
    @Volatile private var liveReopened = true
    private var reopenJob: Job? = null
    private var ready = false
    private var ended = false
    @Volatile private var wantPlaying = false
    @Volatile private var pendingSeekMs = 0L
    @Volatile private var speed = 1f
    /** Where the engine was when last looked at, and when (nanoTime) it last moved or was told to do something. */
    private var engineAtMs = -1.0
    @Volatile private var movedAt = 0L

    override fun play(episode: Episode, startPositionMs: Long) = play(episode, startPositionMs, null)

    override fun play(episode: Episode, startPositionMs: Long, podcastTitle: String?) {
        val gen = ++generation
        loadJob?.cancel()
        wantPlaying = true
        movedAt = System.nanoTime()
        pendingSeekMs = startPositionMs.coerceAtLeast(0)
        _state.value = PlaybackState(
            episode = episode,
            isPlaying = true,
            positionMs = pendingSeekMs,
            durationMs = PlayerMath.durationMs(null, episode),
            speed = speed,
            isBuffering = true,
        )
        // Silence the previous episode now: resolving the new URL can take seconds, and until load() runs the
        // old player would keep playing and the ticker would publish its position/ended flag into the new state.
        fx {
            if (gen == generation) {
                disposePlayer()
                closeLive()
                ended = false
            }
        }
        loadJob = scope.launch {
            val media = try {
                withContext(Dispatchers.IO) { resolveUrl(episode.audioUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(gen, e.message ?: "Couldn't open this episode")
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { JavaFxRuntime.ensureStarted() }
            } catch (e: CancellationException) {
                throw e // superseded by a newer play() or release()
            } catch (t: Throwable) {
                fail(gen, "Audio engine unavailable: ${t.message ?: t::class.simpleName}")
                return@launch
            }
            // A live stream plays through the relay, to be rewound; straight from its address if that can't be set up.
            val session = if (media.live) withContext(Dispatchers.IO) { runCatching { LiveRelay.open(media.url) }.getOrNull() } else null
            val url = session?.let { withContext(Dispatchers.IO) { it.urlAt(null) } } ?: media.url
            fx {
                if (gen != generation) return@fx session?.close() ?: Unit
                liveSession = session
                liveTimeMs = null
                load(gen, url, episode, media.live)
            }
        }
        ticker.start()
    }

    /**
     * Opens the live stream being played again at stream time [atMs], or at what's on air for null: how it is
     * rewound, and resumed where it was paused.
     */
    private fun reopenLive(atMs: Long?) {
        val session = liveSession ?: return
        val gen = generation
        val episode = _state.value.episode ?: return
        // Until the stream plays from there, the player still running reports where it was: the place asked for
        // stands meanwhile, so a second skip counts from it, and only the last of several reopenings goes through.
        val reopening = ++liveReopening
        liveReopened = false
        movedAt = System.nanoTime()
        reopenJob?.cancel()
        reopenJob = scope.launch(Dispatchers.IO) {
            val url = runCatching { session.urlAt(atMs) }.getOrNull()
            fx {
                if (gen != generation || liveSession !== session || reopening != liveReopening) return@fx
                if (url != null) load(gen, url, episode, live = true) else liveReopened = true
            }
        }
    }

    /** FX thread. */
    private fun load(gen: Long, url: String, episode: Episode, live: Boolean) {
        disposePlayer()
        val mp = try {
            MediaPlayer(Media(url))
        } catch (e: Exception) {
            fail(gen, "Cannot play this episode: ${e.message ?: e::class.simpleName}")
            return
        }
        player = mp
        loadedUrl = url
        this.live = live
        ready = false
        ended = false
        movedAt = System.nanoTime()
        if (live) _state.update { it.copy(isLive = true) }
        mp.setOnReady {
            if (gen != generation) return@setOnReady
            ready = true
            movedAt = System.nanoTime()
            // What's on air can't be played faster than it comes.
            mp.rate = if (live) 1.0 else speed.toDouble()
            if (!live && pendingSeekMs > 0) mp.seek(Duration.millis(pendingSeekMs.toDouble()))
            liveReopened = true
            if (wantPlaying) mp.play()
            publishFromPlayer()
        }
        mp.setOnEndOfMedia {
            if (gen != generation) return@setOnEndOfMedia
            ended = true
            wantPlaying = false
            mp.pause()
            val duration = if (live) _state.value.durationMs else PlayerMath.durationMs(DesktopStateMapping.millisOrNull(mp.totalDuration), episode)
            _state.update { it.copy(isPlaying = false, isBuffering = false, isEnded = true, positionMs = duration, durationMs = duration) }
            ticker.stop()
        }
        mp.setOnError {
            if (gen != generation) return@setOnError
            fail(gen, mp.error?.message ?: "Playback error")
        }
        mp.statusProperty().addListener { _, _, _ -> if (gen == generation) publishFromPlayer() }
    }

    override fun pause() {
        wantPlaying = false
        ticker.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        fx {
            player?.pause()
            publishFromPlayer()
        }
    }

    override fun resume() {
        val current = _state.value
        if (current.episode == null) return
        if (player == null && current.error != null) {
            // Last load failed: retry from where we were.
            play(current.episode, current.positionMs)
            return
        }
        if (current.isLive) {
            val session = liveSession
            if (session == null) {
                // It can't be rewound: back to what's on air now.
                play(current.episode, 0)
                return
            }
            // JavaFX would carry on with the pieces it holds and then skip to what's on air: open it where it paused
            // instead, or at the oldest it still has when that has gone.
            wantPlaying = true
            movedAt = System.nanoTime()
            _state.update { it.copy(isPlaying = true, isBuffering = true, isEnded = false, error = null) }
            ticker.start()
            reopenLive(liveTimeMs?.coerceAtLeast(session.window().startMs))
            return
        }
        wantPlaying = true
        movedAt = System.nanoTime()
        _state.update { it.copy(isPlaying = true, isEnded = false, error = null) }
        ticker.start()
        fx {
            val mp = player ?: return@fx
            if (ended) {
                ended = false
                mp.seek(Duration.ZERO)
            }
            if (ready) mp.play()
            publishFromPlayer()
        }
    }

    override fun seekTo(positionMs: Long) {
        val current = _state.value
        if (current.isLive) {
            val session = liveSession ?: return
            val target = PlayerMath.clampSeek(positionMs, current.durationMs)
            val onAir = PlayerMath.seeksOnAir(target, current.positionMs, current.durationMs)
            // Already on air (a skip forward there): nothing to open again.
            if (onAir && PlayerMath.isAtLiveEdge(current.positionMs, current.durationMs) && !current.isEnded) return
            val at = if (onAir) null else session.window().startMs + target
            liveTimeMs = at
            _state.update { it.copy(positionMs = if (onAir) it.durationMs else target, isBuffering = wantPlaying, isEnded = false) }
            reopenLive(at)
            return
        }
        val target = PlayerMath.clampSeek(positionMs, _state.value.durationMs)
        pendingSeekMs = target
        _state.update { it.copy(positionMs = target, isEnded = false) }
        fx {
            val mp = player ?: return@fx
            ended = false
            if (ready) mp.seek(Duration.millis(target.toDouble()))
            // At once, not at the next tick (none while paused): the relay paces by it, and a jump back must hold
            // it back again.
            if (ready) loadedUrl?.let { onProgress(it, target, _state.value.durationMs) }
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = PlayerMath.clampSpeed(speed)
        this.speed = clamped
        _state.update { it.copy(speed = clamped) }
        fx { if (!live) player?.rate = clamped.toDouble() }
    }

    override fun release() {
        generation++
        loadJob?.cancel()
        ticker.stop()
        fx {
            disposePlayer()
            closeLive()
        }
        scope.cancel()
        _state.value = PlaybackState()
    }

    /** FX thread: re-reads position/duration/status from the engine into [state]. */
    private fun publishFromPlayer() {
        val mp = player ?: return
        if (stalled(mp)) {
            fail(generation, "The connection was lost")
            return
        }
        if (!ready && mp.status == MediaPlayer.Status.UNKNOWN) {
            _state.update { it.copy(isBuffering = wantPlaying) }
            return
        }
        val flags = DesktopStateMapping.flags(mp.status, wantPlaying, ended)
        // A live stream: where it plays within what the stream still holds, by the relay's reckoning.
        val window = if (live) liveSession?.window() else null
        val liveAt = if (live && liveReopened) {
            loadedUrl?.let { url -> DesktopStateMapping.millisOrNull(mp.currentTime)?.let { liveSession?.timeOf(url, it) } }
        } else null
        if (liveAt != null && mp.status == MediaPlayer.Status.PLAYING) liveTimeMs = liveAt
        val published = _state.updateAndGet { s ->
            val duration = when {
                !live -> PlayerMath.durationMs(DesktopStateMapping.millisOrNull(mp.totalDuration), s.episode)
                window != null -> window.endMs - window.startMs
                else -> 0
            }
            val position = when {
                ended -> duration
                !live -> DesktopStateMapping.millisOrNull(mp.currentTime) ?: s.positionMs
                window != null && liveAt != null -> liveAt - window.startMs
                else -> s.positionMs.coerceAtMost(duration)
            }
            s.copy(
                isPlaying = flags.isPlaying,
                isBuffering = flags.isBuffering,
                positionMs = PlayerMath.clampSeek(position, duration),
                durationMs = duration,
                isEnded = ended,
                isLive = live,
            )
        }
        if (ready && !live) loadedUrl?.let { onProgress(it, published.positionMs, published.durationMs) }
    }

    /**
     * FX thread: whether the engine, asked to play, has stood still for [STALL_MS]. Without a connection JavaFX
     * mostly just stops, saying nothing, and doesn't pick up again when it's back; failing lets playback be tried again.
     */
    private fun stalled(mp: MediaPlayer): Boolean {
        val now = System.nanoTime()
        val at = mp.currentTime?.toMillis() ?: -1.0
        if (!wantPlaying || ended || at != engineAtMs) {
            engineAtMs = at
            movedAt = now
            return false
        }
        return now - movedAt > STALL_MS * 1_000_000
    }

    /** FX thread: forgets the live stream that was playing. */
    private fun closeLive() {
        liveSession?.close()
        liveSession = null
        liveTimeMs = null
    }

    private fun fail(gen: Long, message: String) {
        if (gen != generation) return
        wantPlaying = false
        ticker.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false, error = message) }
        fx { if (gen == generation) disposePlayer() }
    }

    /** FX thread. */
    private fun disposePlayer() {
        player?.let {
            it.setOnReady(null)
            it.setOnEndOfMedia(null)
            it.setOnError(null)
            it.dispose()
        }
        player = null
        loadedUrl = null
        live = false
        ready = false
    }

    private fun fx(block: () -> Unit) {
        if (JavaFxRuntime.isStarted) JavaFxRuntime.runOnFx(block)
    }
}

/** How long the engine may stand still, asked to play, before playback counts as having lost its connection. */
private const val STALL_MS = 20_000L
