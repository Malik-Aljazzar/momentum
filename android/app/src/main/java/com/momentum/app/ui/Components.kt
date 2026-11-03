package com.momentum.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.Toast
import com.momentum.app.data.UserInfo
import kotlinx.coroutines.delay

val CardShape = RoundedCornerShape(28.dp)

/**
 * Single-line text that shrinks to fit instead of wrapping.
 * [fitFor] is the text used for sizing (e.g. the final value of a count-up animation).
 */
@Composable
fun FitText(text: String, style: TextStyle, maxSize: TextUnit, modifier: Modifier = Modifier, fitFor: String = text) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val size = remember(fitFor, maxW, style, maxSize) {
            var s = maxSize.value
            while (s > 12f && measurer.measure(fitFor, style.copy(fontSize = s.sp), maxLines = 1, softWrap = false).size.width > maxW) {
                s -= 1f
            }
            s
        }
        HyperText(text, style = style.copy(fontSize = size.sp, lineHeight = (size * 1.05f).sp), maxLines = 1, softWrap = false)
    }
}

/**
 * One line of text; when it doesn't fit, it slides to the end and back (pausing at both ends) so the
 * whole text can be read - instead of wrapping.
 */
@Composable
fun SlidingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    // a plain Layout (no BoxWithConstraints / SubcomposeLayout): the rule card measures its height
    // with IntrinsicSize.Min, which SubcomposeLayout doesn't support (it crashed the app)
    var over by remember(text) { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shift = if (over > 0) {
        val slideMs = (over / density.density / 40f * 1000).toInt().coerceAtLeast(600)       // ~40 dp per second
        androidx.compose.animation.core.rememberInfiniteTransition(label = "slide").animateFloat(
            0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.keyframes {
                durationMillis = 2 * slideMs + 2400
                0f at 0; 0f at 1200; 1f at 1200 + slideMs; 1f at 2400 + slideMs; 0f at 2400 + 2 * slideMs
            }), label = "shift")
    } else null
    androidx.compose.ui.layout.Layout(
        content = { Text(text, style = style, maxLines = 1, softWrap = false) },
        modifier = modifier.clipToBounds(),
    ) { measurables, c ->
        val p = measurables.first().measure(c.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
        val width = if (c.hasBoundedWidth) minOf(p.width, c.maxWidth).coerceAtLeast(c.minWidth) else p.width
        val o = (p.width - width).coerceAtLeast(0)
        if (o != over) over = o
        layout(width, p.height) { p.place((-o * (shift?.value ?: 0f)).toInt(), 0) }
    }
}

/** Empty contact lists: a person waving at the friend that is still missing. */
@Composable
fun EmptyFriends(title: String? = null, sub: String? = null, showSearch: Boolean = true) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        VehicleSprite("friends", 3f)
        Spacer(Modifier.height(14.dp))
        Text(title ?: S.noContacts, style = T.bodyStrong.copy(fontSize = 18.sp), textAlign = TextAlign.Center)
        Text(sub ?: S.noContactsSub, style = T.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (showSearch) {
            Spacer(Modifier.height(16.dp))
            PxButton(S.findPeople, style = BtnStyle.Ghost) { nav.push(Route.Search) }
        }
    }
}

@Composable
fun PxCard(
    modifier: Modifier = Modifier,
    brush: Brush = Brush.verticalGradient(listOf(Px.Card2, Px.Card)),
    padding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(brush)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Px.Muted) =
    Text(text.uppercase(), style = T.label.copy(color = color), modifier = modifier)

/** Blocky segmented progress bar. */
@Composable
fun SegBar(fraction: Double, segments: Int = 20, color: Color = Px.Yellow, modifier: Modifier = Modifier) {
    val on = (fraction * segments).toInt().coerceIn(0, segments)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < on) color else Px.Faint),
            )
        }
    }
}

enum class BtnStyle { Primary, Ghost, Danger }

@Composable
fun PxButton(text: String, modifier: Modifier = Modifier, style: BtnStyle = BtnStyle.Primary, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val primary = style == BtnStyle.Primary
    val shadow = if (primary) (if (pressed) 2.dp else 4.dp) else 0.dp
    Box(
        modifier
            .offset(y = if (pressed) 2.dp else 0.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Px.Silver else Color.Transparent)
            .padding(bottom = shadow)
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Px.Text else Px.Faint)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = T.button.copy(
                color = when (style) {
                    BtnStyle.Primary -> Color.Black
                    BtnStyle.Ghost -> Px.Text
                    BtnStyle.Danger -> Px.Orange
                },
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Text field state over a plain String: typing keeps the cursor where it is, a change from
 * outside (mention completion, cleared after sending) puts it at the end.
 */
@Composable
fun rememberCursorValue(value: String): androidx.compose.runtime.MutableState<androidx.compose.ui.text.input.TextFieldValue> {
    val state = remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    if (state.value.text != value) {
        state.value = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    }
    return state
}

@Composable
fun PxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    /** text people write for others (chat, comments): follows the system-font setting */
    userContent: Boolean = false,
    /** a smiley button next to the field: the emoji picker puts its pick at the caret */
    emoji: Boolean = false,
) {
    val textStyle = if (userContent) T.body.userText() else T.body
    var focused by remember { mutableStateOf(false) }
    var tfv by rememberCursorValue(value)
    if (!emoji) {
        PxTextFieldInner(tfv, { tfv = it; if (it.text != value) onValueChange(it.text) }, modifier, value, placeholder, password, keyboardType, imeAction, onDone, textStyle, focused) { focused = it }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            PxTextFieldInner(tfv, { tfv = it; if (it.text != value) onValueChange(it.text) }, modifier, value, placeholder, password, keyboardType, imeAction, onDone, textStyle, focused) { focused = it }
        }
        EmojiButton(tfv) { tfv = it; onValueChange(it.text) }
    }
}

@Composable
private fun PxTextFieldInner(
    tfv: androidx.compose.ui.text.input.TextFieldValue, onChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, modifier: Modifier,
    value: String, placeholder: String, password: Boolean, keyboardType: KeyboardType, imeAction: ImeAction, onDone: () -> Unit,
    textStyle: TextStyle, focused: Boolean, onFocus: (Boolean) -> Unit,
) {
    BasicTextField(
        value = tfv,
        onValueChange = onChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(Px.Yellow),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }, onSend = { onDone() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocus(it.isFocused) },
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x0DFFFFFF))
                    .border(1.dp, if (focused) Px.Yellow.copy(alpha = .6f) else Px.Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = Px.Muted))
                inner()
            }
        },
    )
}

/** Resolves server-relative media paths to absolute URLs (set at the app root). */
val LocalUrl = staticCompositionLocalOf<(String?) -> String?> { { it } }

@Composable
fun Avatar(user: UserInfo, size: Dp) = Avatar(user.displayName, user.avatar, size)

/** Bumped by the view model after each successful sync: failed images try again. */
val LocalImageRetry = androidx.compose.runtime.compositionLocalOf { 0 }

/**
 * Network image that doesn't give up: a failed load (server/storage briefly unreachable) is
 * retried with growing pauses and on every successful sync. [fallback] shows until it loads.
 */
@Composable
fun RemoteImage(
    url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit = {},
) {
    var attempt by remember(url) { androidx.compose.runtime.mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    val signal = LocalImageRetry.current
    if (failed) {
        LaunchedEffect(attempt, signal) {
            if (attempt > 0) kotlinx.coroutines.delay(minOf(60_000L, 2_000L shl minOf(attempt - 1, 5)))
            failed = false
            attempt++
        }
    }
    Box(modifier) {
        if (!loaded) fallback()
        if (url != null) {
            androidx.compose.runtime.key(attempt) {      // new key = new request (errors aren't cached)
                AsyncImage(url, null, contentScale = contentScale, modifier = Modifier.matchParentSize(),
                    onSuccess = { loaded = true }, onError = { failed = true })
            }
        }
    }
}

@Composable
fun Avatar(name: String, avatar: String?, size: Dp) {
    val url = LocalUrl.current(avatar)
    val shape = RoundedCornerShape(size * 0.28f)
    if (url != null) {
        RemoteImage(url, Modifier.size(size).clip(shape)) { AvatarInitial(name, size) }
    } else {
        AvatarInitial(name, size)
    }
}

@Composable
private fun AvatarInitial(name: String, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF3B5DC9), Color(0xFF5D275D)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name.firstOrNull() ?: '?').uppercase(),
            style = T.block.copy(fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Normal),
        )
    }
}

@Composable
fun ToastHost(toast: Toast?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(toast?.nonce) {
        if (toast == null) return@LaunchedEffect
        visible = true
        delay(2600)
        visible = false
    }
    AnimatedVisibility(
        visible && toast != null, modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (toast?.error == true) Px.Orange else Px.Text)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Text(toast?.text.orEmpty(), style = T.bodyStrong.copy(color = Color.Black))
        }
    }
}


/** Players in the community competition: a slowly shimmering golden frame around their card. */
@Composable
fun Modifier.competitionGlow(on: Boolean, shape: androidx.compose.ui.graphics.Shape = CardShape): Modifier {
    if (!on) return this
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "glow")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearEasing)),
        label = "glow")
    val brush = Brush.linearGradient(
        listOf(Color(0xFF8A5A1E), Color(0xFFFFCD75), Color(0xFFFFF4C8), Color(0xFFFFCD75), Color(0xFF8A5A1E)),
        start = androidx.compose.ui.geometry.Offset(-600f + t * 1600f, 0f),
        end = androidx.compose.ui.geometry.Offset(-200f + t * 1600f, 400f),
        tileMode = androidx.compose.ui.graphics.TileMode.Mirror,
    )
    return this.border(2.dp, brush, shape)
}

/** "MOMENTUM · 🏆 COMPETITION" or "FREE MOMENTUM · UNRANKED" above the big number. */
@Composable
fun ScoreLabel(competing: Boolean) {
    val S = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (competing) {
            Label("MOMENTUM")
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x33FFCD75)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelIcon("trophy", 12.dp)
                Spacer(Modifier.width(4.dp))
                Label(S.competition.uppercase(), color = Px.Yellow)
            }
        } else {
            Label(S.freeMomentum.uppercase())
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Label(S.unranked.uppercase(), color = Px.Muted)
            }
        }
    }
}
