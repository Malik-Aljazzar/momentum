package com.momentum.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Same pixel palette as the game (android/app ui/Theme.kt). */
object Px {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF13151D)
    val Card2 = Color(0xFF1B1E2B)
    val Line = Color(0x24FFFFFF)
    val Faint = Color(0x12FFFFFF)
    val Text = Color(0xFFF4F4F4)
    val Muted = Color(0x8CF4F4F4)
    val Yellow = Color(0xFFFFCD75)
    val Orange = Color(0xFFEF7D57)
    val Red = Color(0xFFB13E53)
    val Lime = Color(0xFFA7F070)
    val Cyan = Color(0xFF73EFF7)
}

/** Storage groups: fixed colors (validated categorical order for dark surfaces), "other" stays gray. */
val GroupColors = linkedMapOf(
    "posts" to Color(0xFF3987E5), "stories" to Color(0xFFD95926), "chat" to Color(0xFF199E70),
    "profiles" to Color(0xFFC98500), "reports" to Color(0xFFD55181), "database" to Color(0xFF9085E9),
    "other" to Color(0xFF6E7387),
)

val StatusColors = mapOf("new" to Px.Yellow, "todo" to Px.Cyan, "done" to Px.Lime)

val Silkscreen = FontFamily(Font(R.font.silkscreen_regular, FontWeight.Normal), Font(R.font.silkscreen_bold, FontWeight.Bold))
val Pixelify = FontFamily(
    Font(R.font.pixelify_regular, FontWeight.Normal), Font(R.font.pixelify_semibold, FontWeight.SemiBold),
    Font(R.font.pixelify_bold, FontWeight.Bold),
)

object T {
    val body = TextStyle(fontFamily = Pixelify, fontSize = 16.sp, color = Px.Text, lineHeight = 21.sp, fontFeatureSettings = "liga 0")
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = body.copy(fontSize = 13.sp, lineHeight = 17.sp, color = Px.Muted)
    val label = TextStyle(fontFamily = Silkscreen, fontSize = 11.sp, letterSpacing = 0.7.sp, color = Px.Muted)
    val block = TextStyle(fontFamily = Silkscreen, fontWeight = FontWeight.Bold, color = Px.Text)
    val number = block.copy(fontSize = 22.sp, lineHeight = 24.sp)
    val hero = block.copy(fontSize = 34.sp, lineHeight = 36.sp)
    val button = TextStyle(fontFamily = Silkscreen, fontSize = 13.sp, letterSpacing = 0.4.sp, color = Px.Text)
}

/** Texts in German or English (the phone's language). */
object S {
    val lang: String = if (Locale.getDefault().language == "de") "de" else "en"
    fun t(de: String, en: String) = if (lang == "de") de else en

    fun status(s: String) = when (s) {
        "new" -> t("NEU", "NEW"); "todo" -> "TODO"; else -> t("ERLEDIGT", "DONE")
    }

    fun group(g: String) = when (g) {
        "posts" -> "Posts"; "stories" -> "Stories"; "chat" -> t("Nachrichten", "Messages")
        "profiles" -> t("Profile & Gruppen", "Profiles & groups"); "reports" -> "Reports"
        "database" -> t("Datenbank", "Database"); else -> t("Sonstiges", "Other")
    }

    fun category(k: String) = when (k) {
        "post_image" -> t("Post-Bilder", "Post images"); "post_video" -> t("Post-Videos", "Post videos")
        "post_sound" -> t("Post-Sounds", "Post sounds")
        "story_image" -> t("Story-Bilder", "Story images"); "story_video" -> t("Story-Videos", "Story videos")
        "story_sound" -> t("Story-Sounds", "Story sounds")
        "chat_image" -> t("Chat-Bilder", "Chat images"); "chat_video" -> t("Chat-Videos", "Chat videos")
        "chat_voice" -> t("Sprachnachrichten", "Voice messages")
        "avatar" -> t("Profilbilder", "Profile pictures"); "group_image" -> t("Gruppenbilder", "Group pictures")
        "report_image" -> t("Report-Screenshots", "Report screenshots")
        "draft" -> t("Entwürfe (nie gepostet)", "Drafts (never posted)")
        "other_media" -> t("Andere Medien", "Other media"); "orphan" -> t("Verwaiste Dateien", "Orphaned files")
        "db_users" -> t("DB: Nutzerprofile", "DB: user profiles"); "db_rules" -> t("DB: Regeln & Decays", "DB: rules & decays")
        "db_events" -> t("DB: Momentum-Verlauf", "DB: momentum history"); "db_posts" -> t("DB: Posts & Kommentare", "DB: posts & comments")
        "db_stories" -> t("DB: Stories", "DB: stories"); "db_messages" -> t("DB: Nachrichten & Chats", "DB: messages & chats")
        "db_challenges" -> t("DB: Challenges", "DB: challenges")
        "db_notifications" -> t("DB: Benachrichtigungen", "DB: notifications"); "db_other" -> t("DB: Sonstiges", "DB: other")
        else -> k
    }
}

/** 1.2 GB / 340 MB / 12 KB (decimal units, like the disk). */
fun bytes(b: Long): String {
    val v = b.toDouble()
    return when {
        v >= 1e12 -> String.format(Locale.US, "%.2f TB", v / 1e12)
        v >= 1e9 -> String.format(Locale.US, "%.2f GB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "%.1f MB", v / 1e6)
        v >= 1e3 -> String.format(Locale.US, "%.0f KB", v / 1e3)
        else -> "$b B"
    }
}

fun count(n: Long): String = String.format(Locale.GERMANY.takeIf { S.lang == "de" } ?: Locale.US, "%,d", n)

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")

/** Server timestamps ("2026-10-20T12:00:00Z") in the phone's time zone. */
fun date(iso: String?): String = iso?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(dateFmt) }.getOrNull()
} ?: "–"

/** A card with a hard 2 dp border: pixel style, no rounded corners. */
@Composable
fun PixelCard(modifier: Modifier = Modifier, border: Color = Px.Line, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Px.Card)
            .border(2.dp, border)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) { content() }
}

@Composable
fun PixelButton(text: String, modifier: Modifier = Modifier, color: Color = Px.Text, filled: Boolean = false,
                enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (filled && enabled) color else Px.Card2)
            .border(2.dp, if (enabled) color else Px.Line)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = T.button.copy(color = if (!enabled) Px.Muted else if (filled) Px.Bg else color))
    }
}

/** Segmented choice (tabs inside a screen). */
@Composable
fun Segments(options: List<Pair<String, String>>, selected: String, colors: Map<String, Color> = emptyMap(),
             modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, label) in options) {
            val on = key == selected
            val c = colors[key] ?: Px.Text
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) c else Px.Card)
                    .border(2.dp, if (on) c else Px.Line)
                    .clickable { onSelect(key) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = T.button.copy(fontSize = 12.sp, color = if (on) Px.Bg else Px.Text), maxLines = 1) }
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Box(Modifier.border(2.dp, color).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = T.label.copy(color = color, fontSize = 10.sp))
    }
}

@Composable
fun Swatch(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color))
}

@Composable
fun PixelField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
               password: Boolean = false, number: Boolean = false, singleLine: Boolean = true, minLines: Int = 1) {
    Box(modifier.fillMaxWidth().background(Px.Card2).border(2.dp, Px.Line).padding(12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = T.body.copy(color = Px.Muted))
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth(), textStyle = T.body, singleLine = singleLine, minLines = minLines,
            cursorBrush = SolidColor(Px.Yellow),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                password -> KeyboardType.Password; number -> KeyboardType.Decimal; else -> KeyboardType.Text
            }),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = T.label, modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

/** A horizontal bar of segments (stacked share), each segment separated by a 2 dp gap. */
@Composable
fun StackedBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 22.dp, track: Color = Px.Faint) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(track)
        val gap = 2.dp.toPx()
        var x = 0f
        for ((share, color) in parts) {
            val w = share.coerceIn(0f, 1f) * size.width
            if (w <= 0f) continue
            val drawn = (w - gap).coerceAtLeast(1f)
            drawRect(color, Offset(x, 0f), Size(drawn.coerceAtMost(size.width - x), size.height))
            x += w
            if (x >= size.width) break
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, style = T.small, modifier = Modifier.weight(0.4f))
        Text(value, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun RowScope.Spacer1() = androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

@Composable
fun Message(text: String, color: Color = Px.Muted) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = T.body.copy(color = color))
    }
}
