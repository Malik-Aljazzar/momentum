package com.momentum.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.graphics.Typeface
import com.momentum.app.R

object Px {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF13151D)
    val Card2 = Color(0xFF1B1E2B)
    val Sheet = Color(0xFF14161F)
    val Line = Color(0x14FFFFFF)
    val Faint = Color(0x12FFFFFF)
    val Text = Color(0xFFF4F4F4)
    val Muted = Color(0x8CF4F4F4)
    val Yellow = Color(0xFFFFCD75)
    val Orange = Color(0xFFEF7D57)
    val Red = Color(0xFFB13E53)
    val Lime = Color(0xFFA7F070)
    val Cyan = Color(0xFF73EFF7)
    val Silver = Color(0xFF94B0C2)
}

val Silkscreen: FontFamily get() = Fonts.silkscreen
val Pixelify: FontFamily get() = Fonts.pixelify

/**
 * The app's fonts. On Android 10+ each one is a typeface chain with the pixel emoji font (res/font/pixel_emoji.ttf,
 * made by pixelart/emoji.py) as its fallback: every emoji in texts, inputs and rule icons is pixel art, like in the
 * PWA (theme.css lists "Pixel Emoji" last in every font stack). Older phones show the system's emojis.
 * [MomentumApplication] calls [init] before anything is drawn.
 */
object Fonts {
    var silkscreen: FontFamily = FontFamily(
        Font(R.font.silkscreen_regular, FontWeight.Normal),
        Font(R.font.silkscreen_bold, FontWeight.Bold),
    )
        private set

    // Static instances of Pixelify Sans with Silkscreen digits (Pixelify's "5" reads like an "8").
    var pixelify: FontFamily = FontFamily(
        Font(R.font.pixelify_regular, FontWeight.Normal),
        Font(R.font.pixelify_medium, FontWeight.Medium),
        Font(R.font.pixelify_semibold, FontWeight.SemiBold),
        Font(R.font.pixelify_bold, FontWeight.Bold),
    )
        private set

    /** The phone's font (setting "system font" for user text), with the pixel emojis too. */
    var system: FontFamily = FontFamily.Default
        private set

    private var emoji: android.graphics.fonts.FontFamily? = null

    fun init(ctx: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        runCatching {
            val res = ctx.resources
            emoji = android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(res, R.font.pixel_emoji).build()).build()
            silkscreen = family(res, R.font.silkscreen_regular to 400, R.font.silkscreen_bold to 700)
            pixelify = family(res, R.font.pixelify_regular to 400, R.font.pixelify_medium to 500, R.font.pixelify_semibold to 600, R.font.pixelify_bold to 700)
            roboto()?.let { system = FontFamily(chain(it)) }
        }.onFailure { android.util.Log.w("Momentum", "pixel emoji font not usable", it) }
    }

    @androidx.annotation.RequiresApi(29)
    private fun family(res: android.content.res.Resources, vararg fonts: Pair<Int, Int>): FontFamily {
        val b = android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(res, fonts[0].first).setWeight(fonts[0].second).build())
        for ((id, weight) in fonts.drop(1)) b.addFont(android.graphics.fonts.Font.Builder(res, id).setWeight(weight).build())
        return FontFamily(chain(b.build()))
    }

    @androidx.annotation.RequiresApi(29)
    private fun chain(main: android.graphics.fonts.FontFamily): Typeface =
        Typeface.CustomFallbackBuilder(main).addCustomFallback(emoji!!).setSystemFallback("sans-serif").build()

    /** The system's Roboto as one family (one font per weight / slant), null when the phone has none. */
    @androidx.annotation.RequiresApi(29)
    private fun roboto(): android.graphics.fonts.FontFamily? {
        val fonts = android.graphics.fonts.SystemFonts.getAvailableFonts()
            .filter { it.file?.name?.startsWith("Roboto") == true && it.axes.isNullOrEmpty() }
            .distinctBy { it.style.weight to it.style.slant }
        if (fonts.isEmpty()) return null
        val b = android.graphics.fonts.FontFamily.Builder(fonts[0])
        for (f in fonts.drop(1)) runCatching { b.addFont(f) }
        return b.build()
    }

    /** A typeface for Paint (widgets): the font with the emoji fallback on Android 10+. */
    fun typeface(ctx: android.content.Context, resId: Int): Typeface? {
        if (android.os.Build.VERSION.SDK_INT >= 29 && emoji != null) runCatching {
            return chain(android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(ctx.resources, resId).build()).build())
        }
        return androidx.core.content.res.ResourcesCompat.getFont(ctx, resId)
    }
}

/** Text styles used across the app. */
object T {
    // "liga" off: Pixelify's fi-ligature looks like an "A"
    val body = TextStyle(fontFamily = Pixelify, fontSize = 16.sp, color = Px.Text, lineHeight = 21.sp, fontFeatureSettings = "liga 0")
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = body.copy(fontSize = 13.sp, lineHeight = 17.sp, color = Px.Muted)
    val label = TextStyle(fontFamily = Silkscreen, fontSize = 11.sp, letterSpacing = 0.7.sp, color = Px.Muted)
    val block = TextStyle(fontFamily = Silkscreen, fontWeight = FontWeight.Bold, color = Px.Text)
    val title = block.copy(fontSize = 28.sp, lineHeight = 30.sp)
    val button = TextStyle(fontFamily = Silkscreen, fontSize = 13.sp, letterSpacing = 0.4.sp)
}

@Composable
fun MomentumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Px.Bg, surface = Px.Card, primary = Px.Text, onPrimary = Px.Bg,
            onSurface = Px.Text, onBackground = Px.Text, surfaceContainer = Px.Card2,
        ),
        typography = Typography(
            bodyLarge = T.body, bodyMedium = T.body, bodySmall = T.small,
            labelLarge = T.button, titleLarge = T.title,
        ),
        content = content,
    )
}


/** Setting: texts people write (chat, comments, captions, bios) in the phone's own font. */
val LocalSystemFont = androidx.compose.runtime.compositionLocalOf { false }

/** Style for user-written text: the pixel font, or the system font if the setting is on. */
@Composable
fun TextStyle.userText(): TextStyle =
    if (LocalSystemFont.current) copy(fontFamily = Fonts.system, fontFeatureSettings = null, letterSpacing = 0.sp) else this
