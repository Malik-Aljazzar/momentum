package com.momentum.app.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/*
 * Videos and post sounds behave like on Instagram:
 *  - one sound switch for everything: turned on once (tap, or the phone's volume-up key), every
 *    following video / sound plays with sound until it's turned off again
 *  - only one plays at a time: the most visible one on the screen that is on top (a screen opened
 *    above the feed, e.g. the comments, pauses the feed)
 *  - nothing plays while the app is in the background or the screen is off
 *  - the position carries over (feed -> comments shows the same moment); scrolled away and back, a
 *    video starts over
 *  - audio focus: another app's sound pauses ours, unplugging headphones mutes
 */
object Videos {
    var soundOn by mutableStateOf(false)
    /** visible fraction of each medium that may play (on the top screen) */
    internal val candidates = mutableStateMapOf<Any, Float>()
    internal val positions = HashMap<String, Long>()

    internal fun winner(): Any? = candidates.filter { it.value >= .5f }.maxByOrNull { it.value }?.key
}

/** False for screens covered by another one (their media must not play). */
val LocalOnTop = compositionLocalOf { true }

/**
 * Full-screen layers over the screens (story, big view of photos / videos): only media on the topmost one may
 * play, and there only the most visible one - so exactly one sound plays, the one of what is shown
 * (reports #38, #42: a feed post's sound went on under a story / the big view of the same post).
 */
object MediaCovers {
    internal val open = androidx.compose.runtime.mutableStateListOf<Any>()
    fun isTop(cover: Any?) = if (cover == null) open.isEmpty() else open.lastOrNull() === cover
}

/** The layer this content lies on (null: the screens). */
val LocalCover = compositionLocalOf<Any?> { null }

/** Puts [content] on its own layer above everything before it. */
@Composable
fun CoverLayer(content: @Composable () -> Unit) {
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(key) {
        MediaCovers.open.add(key)
        onDispose { MediaCovers.open.remove(key) }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalCover provides key) { content() }
}

@OptIn(UnstableApi::class)
private fun player(context: android.content.Context, url: String) = ExoPlayer.Builder(context).build().apply {
    setMediaItem(MediaItem.fromUri(url))
    repeatMode = Player.REPEAT_MODE_ONE
    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), /* handleAudioFocus = */ true)
    setHandleAudioBecomingNoisy(true)
    volume = 0f
    Videos.positions[url]?.let { seekTo(it) }
    prepare()
}

/**
 * Where a medium stands in "only the most visible medium plays": [active] = it plays; [held] = it may not
 * play now (covered by another screen / layer, in the background, not its turn) - see rememberTrack;
 * [measure] measures how much of it is visible.
 */
private class Focus(val active: Boolean, val held: Boolean, val measure: Modifier)

@Composable
private fun mediaFocus(playing: Boolean): Focus {
    val onTop = LocalOnTop.current
    val key = remember { Any() }
    var resumed by remember { mutableStateOf(true) }
    var visible by remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) resumed = false           // background / screen off
            if (e == Lifecycle.Event.ON_RESUME) resumed = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); Videos.candidates.remove(key) }
    }
    val eligible = playing && onTop && resumed && MediaCovers.isTop(LocalCover.current)
    LaunchedEffect(eligible, visible) {
        if (eligible && visible > 0f) Videos.candidates[key] = visible else Videos.candidates.remove(key)
    }
    val view = LocalView.current
    val measure = Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        val full = c.size.width.toFloat() * c.size.height
        val screenH = view.rootView.height.toFloat()
        val shown = (minOf(b.bottom, screenH) - maxOf(b.top, 0f)).coerceAtLeast(0f) * b.width
        visible = if (full > 0) (shown / full).coerceIn(0f, 1f) else 0f
    }
    return Focus(eligible && Videos.winner() == key, !eligible, measure)
}

/**
 * Plays [url] (looping) while [active]; volume follows the sound switch. Stopped while [held] (covered,
 * in the background, not its turn), it goes on at that moment next time - also in another player of the
 * same url (feed -> comments / big view and back). Gone from view (scrolled away, another medium more
 * visible), it starts over, like on Instagram.
 */
@Composable
private fun rememberTrack(url: String?, active: Boolean, held: Boolean, audible: Boolean): ExoPlayer? {
    val context = LocalContext.current
    val p = remember(url) { url?.let { player(context, it) } } ?: return null
    val was = remember(p) { booleanArrayOf(false) }                     // it was playing
    val now by androidx.compose.runtime.rememberUpdatedState(active || held)
    DisposableEffect(p) {
        // closed while playing (comments -> feed): the feed goes on at this moment; gone before the effect
        // below saw it stop (a fast scroll): from the start
        onDispose { if (was[0]) Videos.positions[url!!] = if (now) p.currentPosition else 0L; p.release() }
    }
    LaunchedEffect(active) {
        if (active) Videos.positions[url!!]?.let { if (kotlin.math.abs(it - p.currentPosition) > 300) p.seekTo(it) }
        else if (was[0]) Videos.positions[url!!] = if (held) p.currentPosition else 0L
        was[0] = active
        p.playWhenReady = active
    }
    LaunchedEffect(active, audible, Videos.soundOn) { p.volume = if (active && audible && Videos.soundOn) 1f else 0f }
    return p
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(url: String, thumb: String?, playing: Boolean, modifier: Modifier = Modifier.fillMaxSize(),
                /** the post's own sound: plays instead of the video's sound */
                soundUrl: String? = null,
                /** a preview (making a post, report #65): always muted, taps go to what's around it */
                preview: Boolean = false) {
    var firstFrame by remember { mutableStateOf(false) }
    val focus = mediaFocus(playing)
    val active = focus.active
    val video = rememberTrack(url, active, focus.held, audible = soundUrl == null && !preview)!!
    rememberTrack(soundUrl, active, focus.held, audible = true)
    DisposableEffect(video) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
        }
        video.addListener(listener)
        onDispose { video.removeListener(listener) }
    }
    Box(modifier.clipToBounds().then(focus.measure).then(if (preview) Modifier else Modifier.clickable { Videos.soundOn = !Videos.soundOn })) {
        AndroidView({
            (android.view.LayoutInflater.from(it).inflate(com.momentum.app.R.layout.video_player, null) as PlayerView)
                .also { v -> v.player = video }
        }, Modifier.fillMaxSize())
        if (!firstFrame && thumb != null) RemoteImage(thumb, Modifier.fillMaxSize())
        if (!preview) SoundBadge(Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

/**
 * A post's sound over photos or text: plays while this area is the most visible medium. [content]
 * is the area itself (a tap toggles the sound).
 */
@Composable
fun SoundArea(url: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val focus = mediaFocus(playing = true)
    rememberTrack(url, focus.active, focus.held, audible = true)
    Box(modifier.then(focus.measure).clickable { Videos.soundOn = !Videos.soundOn }) {
        content()
        SoundBadge(Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

@Composable
private fun SoundBadge(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(5.dp)) {
        PixelIcon(if (Videos.soundOn) "note" else "ui_muted", 14.dp, alpha = .9f)
    }
}

/** "♪ title" under a post with a sound. */
@Composable
fun SoundLabel(title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PixelIcon("note", 14.dp, alpha = .8f)
        Spacer(Modifier.width(6.dp))
        Text(title.ifBlank { LocalStrings.current.originalSound }, style = T.small.copy(fontSize = 12.sp, color = Px.Text),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
