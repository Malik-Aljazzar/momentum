package com.momentum.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import com.momentum.app.ui.Emoji
import com.momentum.app.ui.Fonts
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.momentum.app.MainActivity
import com.momentum.app.R
import com.momentum.app.data.Api
import com.momentum.app.data.ApiException
import com.momentum.app.data.AppState
import com.momentum.app.data.LocalStore
import com.momentum.app.data.Meta
import com.momentum.app.data.Prefs
import com.momentum.app.data.RankEntry
import com.momentum.app.data.RankingResponse
import com.momentum.app.data.StateResponse
import com.momentum.app.fmt
import com.momentum.app.ui.Lang
import com.momentum.app.ui.stringsFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Home screen widgets, drawn as pixel art (bitmaps), so they look like the app:
 *   MomentumWidget  2x2  vehicle scene + momentum + progress to the next milestone
 *   TodayWidget     4x1  collected today vs. goal, tonight's decay, daily limit left
 *   QuickWidget     4x1  four rules to tap right from the home screen
 *   MiniWidget      2x1  momentum and the vehicle, small
 *   OverviewWidget  4x2  vehicle scene, momentum, daily limit, tonight's decay, challenge credit
 *   GroupRankingWidget / FriendsRankingWidget  ranking of a chosen group / of my friends
 *   GroupDeltaWidget / FriendsDeltaWidget      the same, ranked by today since 0:00 (+/-, reports #40, #45)
 * All of them can be resized; they draw for the size they get. They read the state the app caches
 * (works offline) and are refreshed whenever it changes; rankings are fetched in the background.
 */

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

internal class WidgetData(val state: AppState?, val meta: Meta?)

internal fun load(ctx: Context): WidgetData {
    val store = LocalStore(ctx, json)
    val state = store.read("state")?.let { runCatching { json.decodeFromString(AppState.serializer(), it) }.getOrNull() }
    val meta = store.read("meta")?.let { runCatching { json.decodeFromString(Meta.serializer(), it) }.getOrNull() }
    return WidgetData(state, meta)
}

object Widgets {
    /** Re-render every placed widget (cheap; called when the app's state changes). */
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val data = load(ctx)
        for ((cls, draw) in listOf<Pair<Class<*>, (Context, AppWidgetManager, Int, WidgetData) -> Unit>>(
            MomentumWidget::class.java to ::drawMomentum, TodayWidget::class.java to ::drawToday,
            QuickWidget::class.java to ::drawQuick, MiniWidget::class.java to ::drawMini,
            OverviewWidget::class.java to ::drawOverview)) {
            mgr.getAppWidgetIds(ComponentName(ctx, cls)).forEach { draw(ctx, mgr, it, data) }
        }
        mgr.getAppWidgetIds(ComponentName(ctx, GroupRankingWidget::class.java)).forEach { drawGroupRanking(ctx, mgr, it) }
        mgr.getAppWidgetIds(ComponentName(ctx, FriendsRankingWidget::class.java)).forEach { drawFriendsRanking(ctx, mgr, it) }
        mgr.getAppWidgetIds(ComponentName(ctx, GroupDeltaWidget::class.java)).forEach { drawGroupRanking(ctx, mgr, it, day = true) }
        mgr.getAppWidgetIds(ComponentName(ctx, FriendsDeltaWidget::class.java)).forEach { drawFriendsRanking(ctx, mgr, it, day = true) }
    }
}

// ---------------------------------------------------------------- drawing helpers

private class Px(ctx: Context, val d: Float) {
    val block: Typeface = Fonts.typeface(ctx, R.font.silkscreen_bold) ?: Typeface.MONOSPACE
    val label: Typeface = Fonts.typeface(ctx, R.font.silkscreen_regular) ?: Typeface.MONOSPACE
    val body: Typeface = Fonts.typeface(ctx, R.font.pixelify_medium) ?: Typeface.DEFAULT
    fun text(face: Typeface, sizeDp: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = sizeDp * d; this.color = color
    }
}

private fun asset(ctx: Context, path: String): Bitmap? =
    runCatching { ctx.assets.open("sprites/$path").use { BitmapFactory.decodeStream(it) } }.getOrNull()

private val NEAREST = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

private fun Canvas.pixels(img: Bitmap, src: Rect, x: Float, y: Float, scale: Float) =
    drawBitmap(img, src, RectF(x, y, x + src.width() * scale, y + src.height() * scale), NEAREST)

private fun Canvas.tileX(img: Bitmap, y: Float, scale: Float) {
    var x = 0f
    while (x < width) { pixels(img, Rect(0, 0, img.width, img.height), x, y, scale); x += img.width * scale }
}

/** Rounded card with a stepped-band sky like the app's scenes. */
private fun Canvas.card(bands: List<Pair<Float, Int>>, radius: Float) {
    val path = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, android.graphics.Path.Direction.CW) }
    clipPath(path)
    bands.forEachIndexed { i, (start, color) ->
        val end = if (i + 1 < bands.size) bands[i + 1].first else 1f
        drawRect(0f, start * height, width.toFloat(), end * height + 1, Paint().apply { this.color = color })
    }
}

private fun Canvas.segBar(x: Float, y: Float, w: Float, h: Float, fraction: Double, segments: Int, on: Int, d: Float) {
    val gap = 2 * d
    val sw = (w - gap * (segments - 1)) / segments
    val filled = (fraction.coerceIn(0.0, 1.0) * segments).roundToInt()
    for (i in 0 until segments) {
        val p = Paint().apply { color = if (i < filled) on else Color.argb(40, 255, 255, 255) }
        drawRoundRect(RectF(x + i * (sw + gap), y, x + i * (sw + gap) + sw, y + h), 2 * d, 2 * d, p)
    }
}

private fun size(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
    val o: Bundle = mgr.getAppWidgetOptions(id)
    val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW).takeIf { it > 0 } ?: defW
    val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defH).takeIf { it > 0 } ?: defH
    return w to h
}

private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

private fun bitmapFor(ctx: Context, wDp: Int, hDp: Int): Pair<Bitmap, Float> {
    // widgets have a bitmap size budget: keep it around 2x density
    val d = min(ctx.resources.displayMetrics.density, 2.5f)
    return Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(10), (hDp * d).toInt().coerceAtLeast(10), Bitmap.Config.ARGB_8888) to d
}

private val LAND = listOf(0f to 0xFF232A4F.toInt(), .22f to 0xFF2E2B55.toInt(), .36f to 0xFF48295A.toInt(),
    .48f to 0xFF6F2F57.toInt(), .58f to 0xFF96435A.toInt(), .66f to 0xFFB86455.toInt())
private val AIR = listOf(0f to 0xFF22305E.toInt(), .20f to 0xFF2B4282.toInt(), .38f to 0xFF3459A0.toInt(),
    .56f to 0xFF3F73B8.toInt(), .72f to 0xFF5791C8.toInt())
private val SPACE = listOf(0f to 0xFF07080F.toInt(), .30f to 0xFF0D0F1D.toInt(), .55f to 0xFF141832.toInt(), .75f to 0xFF1C2246.toInt())
private val CARD = listOf(0f to 0xFF1B1E2B.toInt(), 1f to 0xFF13151D.toInt())

// ---------------------------------------------------------------- 1) momentum

internal fun drawMomentum(ctx: Context, mgr: AppWidgetManager, id: Int, data: WidgetData) {
    val (wDp, hDp) = size(mgr, id, 160, 160)
    if (hDp < 110) return drawMini(ctx, mgr, id, data)          // resized down to one row: the small layout
    val (bmp, d) = bitmapFor(ctx, wDp, hDp)
    val c = Canvas(bmp)
    val px = Px(ctx, d)
    val st = data.state
    val vehicles = data.meta?.vehicles.orEmpty()
    val idx = (st?.vehicle?.index ?: 0).coerceIn(0, max(0, vehicles.lastIndex))
    val v = vehicles.getOrNull(idx)
    val cat = v?.category ?: "land"
    c.card(when (cat) { "air" -> AIR; "space" -> SPACE; else -> LAND }, 22 * d)
    val ground = c.height - 26 * d
    when (cat) {
        "land" -> {
            asset(ctx, "icons/scene_mountains_far.png")?.let { c.tileX(it, ground - it.height * 2 * d, 2 * d) }
            asset(ctx, "icons/scene_mountains_near.png")?.let { c.tileX(it, ground - it.height * 2 * d, 2 * d) }
            c.drawRect(0f, ground, c.width.toFloat(), c.height.toFloat(), Paint().apply { color = 0xFF1A1C2C.toInt() })
            c.drawRect(0f, ground, c.width.toFloat(), ground + 3 * d, Paint().apply { color = 0xFF2A2F45.toInt() })
        }
        "air" -> asset(ctx, "icons/scene_cloud.png")?.let { c.tileX(it, c.height * .45f, 1.5f * d) }
        else -> {
            val p = Paint().apply { color = Color.WHITE }
            val r = java.util.Random(7)
            repeat(40) {                                                  // stars
                val x = r.nextFloat() * c.width
                val y = r.nextFloat() * c.height
                c.drawRect(x, y, x + 1.5f * d, y + 1.5f * d, p)
            }
        }
    }
    // vehicle (first frame of its sheet), sized to the widget
    if (v != null) asset(ctx, "${v.key}/sheet.png")?.let { sheet ->
        val scale = min(c.width * .7f / 64f, c.height * .32f / 32f)
        val x = (c.width - 64 * scale) / 2
        val y = if (cat == "land") ground - 30 * scale else c.height - 32 * scale - 10 * d
        c.pixels(sheet, Rect(0, 0, 64, 32), x, y, scale)
    }
    val pad = 12 * d
    c.drawText(if (st?.settings?.dailyLimit != false) "MOMENTUM" else "FREI", pad, pad + 9 * d, px.text(px.label, 9f, 0xFFFFCD75.toInt()))
    val num = fmt(st?.momentum ?: 0.0)
    val numPaint = px.text(px.block, 26f, Color.WHITE)
    while (numPaint.measureText(num) > c.width - 2 * pad && numPaint.textSize > 10 * d) numPaint.textSize -= d
    c.drawText(num, pad, pad + 9 * d + numPaint.textSize, numPaint)
    // progress to the next vehicle
    val next = st?.vehicle?.nextAt
    if (st != null && next != null) {
        c.segBar(pad, pad + 16 * d + numPaint.textSize, c.width - 2 * pad, 4 * d, st.vehicle.progress(st.momentum), 12, 0xFFFFCD75.toInt(), d)
    }
    mgr.updateAppWidget(id, RemoteViews(ctx.packageName, R.layout.widget_image).apply {
        setImageViewBitmap(R.id.widget_image, bmp)
        setOnClickPendingIntent(R.id.widget_image, openApp(ctx))
    })
}

// ---------------------------------------------------------------- 1b) momentum, small (2x1)

internal fun drawMini(ctx: Context, mgr: AppWidgetManager, id: Int, data: WidgetData) {
    val (wDp, hDp) = size(mgr, id, 160, 64)
    val (bmp, d) = bitmapFor(ctx, wDp, hDp)
    val c = Canvas(bmp)
    val px = Px(ctx, d)
    val st = data.state
    val vehicles = data.meta?.vehicles.orEmpty()
    val idx = (st?.vehicle?.index ?: 0).coerceIn(0, max(0, vehicles.lastIndex))
    val v = vehicles.getOrNull(idx)
    val cat = v?.category ?: "land"
    c.card(when (cat) { "air" -> AIR; "space" -> SPACE; else -> LAND }, 18 * d)
    // a calm scene: the number gets the left side, the vehicle stands on the right
    val split = c.width * .52f
    val ground = c.height - 12 * d
    when (cat) {
        "land" -> {
            asset(ctx, "icons/scene_mountains_far.png")?.let { c.tileX(it, ground - it.height * 1.5f * d, 1.5f * d) }
            c.drawRect(0f, ground, c.width.toFloat(), c.height.toFloat(), Paint().apply { color = 0xFF1A1C2C.toInt() })
            c.drawRect(0f, ground, c.width.toFloat(), ground + 2 * d, Paint().apply { color = 0xFF2A2F45.toInt() })
        }
        "air" -> asset(ctx, "icons/scene_cloud.png")?.let { c.tileX(it, c.height * .55f, 1f * d) }
        else -> {
            val p = Paint().apply { color = Color.WHITE; alpha = 200 }
            val r = java.util.Random(7)
            repeat(24) {
                val x = r.nextFloat() * c.width
                val y = r.nextFloat() * c.height
                c.drawRect(x, y, x + 1.2f * d, y + 1.2f * d, p)
            }
        }
    }
    // the left side darkened a little, so the number reads well over any scene
    c.drawRect(0f, 0f, split, c.height.toFloat(), Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, split, 0f, Color.argb(150, 10, 12, 22), Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP)
    })
    if (v != null) asset(ctx, "${v.key}/sheet.png")?.let { sheet ->
        val scale = min((c.width - split) / 64f, c.height * .58f / 32f)
        val x = c.width - 64 * scale - 6 * d
        val y = if (cat == "land") ground - 30 * scale else (c.height - 32 * scale) / 2
        c.pixels(sheet, Rect(0, 0, 64, 32), x, y, scale)
    }
    val pad = 12 * d
    val label = px.text(px.label, 8f, 0xFFFFCD75.toInt())
    val num = fmt(st?.momentum ?: 0.0)
    val numPaint = px.text(px.block, 22f, Color.WHITE).apply { setShadowLayer(3 * d, 0f, d, Color.argb(160, 0, 0, 0)) }
    while (numPaint.measureText(num) > split - pad && numPaint.textSize > 9 * d) numPaint.textSize -= d
    val next = st?.vehicle?.nextAt
    val barH = if (st != null && next != null) 7 * d else 0f
    val top = (c.height - (10 * d + numPaint.textSize + barH)) / 2
    c.drawText(if (st?.settings?.dailyLimit != false) "MOMENTUM" else "FREI", pad, top + 8 * d, label)
    c.drawText(num, pad, top + 10 * d + numPaint.textSize, numPaint)
    // progress to the next vehicle
    if (st != null && next != null) {
        c.segBar(pad, top + 14 * d + numPaint.textSize, split - pad - 4 * d, 3 * d, st.vehicle.progress(st.momentum), 10, 0xFFFFCD75.toInt(), d)
    }
    mgr.updateAppWidget(id, RemoteViews(ctx.packageName, R.layout.widget_image).apply {
        setImageViewBitmap(R.id.widget_image, bmp)
        setOnClickPendingIntent(R.id.widget_image, openApp(ctx))
    })
}

// ---------------------------------------------------------------- 2) today

internal fun drawToday(ctx: Context, mgr: AppWidgetManager, id: Int, data: WidgetData) {
    val (wDp, hDp) = size(mgr, id, 300, 80)
    val (bmp, d) = bitmapFor(ctx, wDp, hDp)
    val c = Canvas(bmp)
    val px = Px(ctx, d)
    val S = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)
    c.card(CARD, 20 * d)
    val st = data.state
    val pad = 12 * d
    asset(ctx, "icons/calendar.png")?.let { c.pixels(it, Rect(0, 0, 16, 16), pad, (c.height - 32 * d) / 2, 2 * d) }
    val x0 = pad + 32 * d + 10 * d
    val gain = st?.decay?.todayGain ?: 0.0
    val goal = st?.decay?.threshold ?: 0.0
    c.drawText(S.collectedToday(fmt(gain), fmt(goal)), x0, pad + 12 * d, px.text(px.body, 12f, Color.WHITE))
    c.segBar(x0, pad + 18 * d, c.width - x0 - pad, 5 * d, if (goal > 0) gain / goal else 1.0, 16,
        if (gain >= goal) 0xFFA7F070.toInt() else 0xFFFFCD75.toInt(), d)
    val tonight = "−" + fmt(st?.decay?.tonight ?: 0.0)
    c.drawText(tonight, x0, c.height - pad, px.text(px.block, 14f, 0xFFEF7D57.toInt()))
    val limit = st?.limit
    if (limit != null && st.settings.dailyLimit && limit.cap < 1e8) {
        val txt = S.limitLeft(fmt(kotlin.math.floor(limit.remaining)), fmt(limit.cap))
        val p = px.text(px.body, 10f, Color.argb(150, 244, 244, 244))
        while (p.measureText(txt) > c.width - x0 - pad - 60 * d && p.textSize > 7 * d) p.textSize -= .5f * d
        c.drawText(txt, c.width - pad - p.measureText(txt), c.height - pad, p)
    }
    mgr.updateAppWidget(id, RemoteViews(ctx.packageName, R.layout.widget_image).apply {
        setImageViewBitmap(R.id.widget_image, bmp)
        setOnClickPendingIntent(R.id.widget_image, openApp(ctx))
    })
}

// ---------------------------------------------------------------- 3) quick taps

private val SLOTS = intArrayOf(R.id.quick_1, R.id.quick_2, R.id.quick_3, R.id.quick_4)

internal fun drawQuick(ctx: Context, mgr: AppWidgetManager, id: Int, data: WidgetData) {
    val (wDp, hDp) = size(mgr, id, 300, 80)
    val views = RemoteViews(ctx.packageName, R.layout.widget_quick)
    val rules = data.state?.rules.orEmpty().filter { it.challengeId == null && it.tappable }
        .sortedBy { if (it.isAcc) 0 else 1 }.take(4)
    val tileW = wDp / 4
    SLOTS.forEachIndexed { i, slot ->
        val r = rules.getOrNull(i)
        val (bmp, d) = bitmapFor(ctx, tileW, hDp)
        val c = Canvas(bmp)
        val px = Px(ctx, d)
        val inset = 3 * d
        val acc = r?.isAcc != false
        val bg = Paint().apply { color = if (r == null) 0x22FFFFFF else if (acc) 0xFF1B3532.toInt() else 0xFF3A1A2A.toInt() }
        c.drawRoundRect(RectF(inset, inset, c.width - inset, c.height - inset), 16 * d, 16 * d, bg)
        if (r != null) {
            val s = min(c.height * .4f, c.width * .45f) / 16f
            if (Emoji.isEmojiIcon(r.icon)) {
                // an emoji icon: the pixel emoji font, its 1.125 em box as big as the 16 px sprite box
                val p = px.text(px.body, 0f, 0xFFFFFFFF.toInt()).apply { textSize = 16 * s / 1.125f; textAlign = Paint.Align.CENTER }
                c.drawText(r.icon, c.width / 2f, inset + 6 * d + p.textSize * .875f, p)
            } else asset(ctx, "icons/${r.icon}.png")?.let {
                c.pixels(it, Rect(0, 0, 16, 16), (c.width - 16 * s) / 2, inset + 6 * d, s)
            }
            val value = (if (acc) "+" else "−") + fmt(r.value)
            val p = px.text(px.block, 11f, if (acc) 0xFFA7F070.toInt() else 0xFFEF7D57.toInt())
            while (p.measureText(value) > c.width - 2 * inset - 4 * d && p.textSize > 7 * d) p.textSize -= .5f * d
            c.drawText(value, (c.width - p.measureText(value)) / 2, c.height - inset - 7 * d, p)
            val tap = Intent(ctx, QuickWidget::class.java).setAction(QuickWidget.ACTION_TAP).putExtra(QuickWidget.EXTRA_RULE, r.id)
            views.setOnClickPendingIntent(slot, PendingIntent.getBroadcast(ctx, r.id, tap,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            views.setOnClickPendingIntent(slot, openApp(ctx))
        }
        views.setImageViewBitmap(slot, bmp)
    }
    mgr.updateAppWidget(id, views)
}

// ---------------------------------------------------------------- providers

class MomentumWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { val d = load(ctx); ids.forEach { drawMomentum(ctx, mgr, it, d) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawMomentum(ctx, mgr, id, load(ctx))
}

class MiniWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { val d = load(ctx); ids.forEach { drawMini(ctx, mgr, it, d) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawMini(ctx, mgr, id, load(ctx))
}

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { val d = load(ctx); ids.forEach { drawToday(ctx, mgr, it, d) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawToday(ctx, mgr, id, load(ctx))
}

class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { val d = load(ctx); ids.forEach { drawQuick(ctx, mgr, it, d) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawQuick(ctx, mgr, id, load(ctx))

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TAP) {
            val rule = intent.getIntExtra(EXTRA_RULE, 0)
            if (rule > 0) WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<TapWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                // the tap's time and id are fixed now: a retry after a lost connection is deduplicated
                .setInputData(workDataOf(EXTRA_RULE to rule, "at" to OffsetDateTime.now().toString(),
                    "op" to UUID.randomUUID().toString())).build())
        }
    }

    companion object {
        const val ACTION_TAP = "com.momentum.app.QUICK_TAP"
        const val EXTRA_RULE = "rule"
    }
}

/** A tap on the quick widget: sent to the server like a tap in the app (same rules, deduplicated). */
class TapWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val token = prefs.token ?: return Result.success()
        val rule = inputData.getInt(QuickWidget.EXTRA_RULE, 0)
        val api = Api(prefs.serverUrl, token, prefs.language)
        val body = buildJsonObject {
            put("at", inputData.getString("at") ?: OffsetDateTime.now().toString())
            put("op_id", inputData.getString("op") ?: UUID.randomUUID().toString())
        }
        return try {
            val text = api.raw("POST", "/rules/$rule/trigger", body)
            val res = api.json.decodeFromString(StateResponse.serializer(), text)
            LocalStore(ctx, json).write("state", json.encodeToString(AppState.serializer(), res.state))
            Widgets.updateAll(ctx)
            Result.success()
        } catch (e: ApiException) {
            if (e.status == 0) Result.retry() else Result.success()        // offline: try again later
        }
    }

    override suspend fun getForegroundInfo() = androidx.work.ForegroundInfo(4242,
        androidx.core.app.NotificationCompat.Builder(applicationContext, com.momentum.app.data.Notifier.CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_stat_momentum).setContentTitle("Momentum").build())
}

// ---------------------------------------------------------------- 4) overview (4x2 and larger)

internal fun drawOverview(ctx: Context, mgr: AppWidgetManager, id: Int, data: WidgetData) {
    val (wDp, hDp) = size(mgr, id, 320, 160)
    val (bmp, d) = bitmapFor(ctx, wDp, hDp)
    val c = Canvas(bmp)
    val px = Px(ctx, d)
    val S = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)
    val st = data.state
    val vehicles = data.meta?.vehicles.orEmpty()
    val idx = (st?.vehicle?.index ?: 0).coerceIn(0, max(0, vehicles.lastIndex))
    val v = vehicles.getOrNull(idx)
    val cat = v?.category ?: "land"
    c.card(when (cat) { "air" -> AIR; "space" -> SPACE; else -> LAND }, 22 * d)
    // left half: the scene with the vehicle; right half: the numbers on a dark panel
    val split = c.width * .48f
    val ground = c.height - 22 * d
    if (cat == "land") {
        asset(ctx, "icons/scene_mountains_far.png")?.let { c.tileX(it, ground - it.height * 2 * d, 2 * d) }
        c.drawRect(0f, ground, c.width.toFloat(), c.height.toFloat(), Paint().apply { color = 0xFF1A1C2C.toInt() })
    }
    if (v != null) asset(ctx, "${v.key}/sheet.png")?.let { sheet ->
        val scale = min(split * .8f / 64f, c.height * .42f / 32f)
        val y = if (cat == "land") ground - 30 * scale else (c.height - 32 * scale) / 2
        c.pixels(sheet, Rect(0, 0, 64, 32), (split - 64 * scale) / 2, y, scale)
    }
    c.drawRect(split, 0f, c.width.toFloat(), c.height.toFloat(), Paint().apply { color = 0xE0141620.toInt() })
    val x0 = split + 12 * d
    val w = c.width - x0 - 12 * d
    var y = 12 * d + 9 * d
    c.drawText(if (st?.settings?.dailyLimit != false) "MOMENTUM" else "FREI", x0, y, px.text(px.label, 9f, 0xFFFFCD75.toInt()))
    val num = fmt(st?.momentum ?: 0.0)
    val numPaint = px.text(px.block, 24f, Color.WHITE)
    while (numPaint.measureText(num) > w && numPaint.textSize > 10 * d) numPaint.textSize -= d
    y += numPaint.textSize + 2 * d
    c.drawText(num, x0, y, numPaint)
    val small = px.text(px.body, 11f, Color.argb(190, 244, 244, 244))
    fun line(text: String, color: Int = small.color) {
        val p = Paint(small).apply { this.color = color }
        while (p.measureText(text) > w && p.textSize > 7 * d) p.textSize -= .5f * d
        y += 15 * d
        c.drawText(text, x0, y, p)
    }
    val limit = st?.limit
    if (limit != null && st.settings.dailyLimit && limit.cap < 1e8) {
        line(S.limitLeft(fmt(kotlin.math.floor(limit.remaining)), fmt(limit.cap)))
        y += 5 * d
        c.segBar(x0, y, w, 4 * d, if (limit.cap > 0) limit.gained / limit.cap else 1.0, 12,
            if (limit.remaining < 1) 0xFFEF7D57.toInt() else 0xFFA7F070.toInt(), d)
        y += 2 * d
    }
    st?.decay?.let { line("−" + fmt(it.tonight) + " · " + S.dailyDecay, 0xFFEF7D57.toInt()) }
    val credit = st?.challengeCredit ?: 0.0
    if (credit >= 1 && y + 15 * d < c.height) line(S.challengeCreditLine(fmt(credit)), 0xFFFFCD75.toInt())
    mgr.updateAppWidget(id, RemoteViews(ctx.packageName, R.layout.widget_image).apply {
        setImageViewBitmap(R.id.widget_image, bmp)
        setOnClickPendingIntent(R.id.widget_image, openApp(ctx))
    })
}

// ---------------------------------------------------------------- 5) rankings (a group's, or friends)

/** Which group each group-ranking widget shows (chosen when it's placed). */
internal object WidgetGroups {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun set(ctx: Context, widgetId: Int, convId: Int, name: String) =
        sp(ctx).edit().putInt("group_$widgetId", convId).putString("group_name_$widgetId", name).apply()
    fun group(ctx: Context, widgetId: Int) = sp(ctx).getInt("group_$widgetId", 0).takeIf { it > 0 }
    fun name(ctx: Context, widgetId: Int) = sp(ctx).getString("group_name_$widgetId", "") ?: ""
}

private fun rankingKey(convId: Int?, day: Boolean = false) =
    (if (convId == null) "ranking_friends" else "ranking_group_$convId") + if (day) "_24h" else ""

private fun cachedRanking(ctx: Context, convId: Int?, day: Boolean = false): List<RankEntry> =
    LocalStore(ctx, json).read(rankingKey(convId, day))?.let {
        runCatching { json.decodeFromString(RankingResponse.serializer(), it).entries }.getOrNull()
    }.orEmpty()

internal fun drawRanking(ctx: Context, mgr: AppWidgetManager, id: Int, title: String, entries: List<RankEntry>, empty: String,
                         click: PendingIntent = openApp(ctx), day: Boolean = false) {
    val (wDp, hDp) = size(mgr, id, 250, 180)
    val (bmp, d) = bitmapFor(ctx, wDp, hDp)
    val c = Canvas(bmp)
    val px = Px(ctx, d)
    c.card(CARD, 20 * d)
    val pad = 12 * d
    asset(ctx, "icons/trophy.png")?.let { c.pixels(it, Rect(0, 0, 16, 16), pad, pad - 2 * d, 1.25f * d) }
    val head = px.text(px.label, 10f, 0xFFFFCD75.toInt())
    var t = title.uppercase()
    while (head.measureText(t) > c.width - 2 * pad - 26 * d && t.length > 4) t = t.dropLast(2) + "…"
    c.drawText(t, pad + 26 * d, pad + 12 * d, head)
    val rowH = 22 * d
    val top = pad + 26 * d
    val rows = max(1, ((c.height - top - pad / 2) / rowH).toInt())
    if (entries.isEmpty()) c.drawText(empty, pad, top + 14 * d, px.text(px.body, 11f, Color.argb(150, 244, 244, 244)))
    // top places; if I'm further down, my row replaces the last one
    val mine = entries.firstOrNull { it.user.isMe }
    val shown = entries.take(rows).let { list ->
        if (mine != null && mine !in list && list.isNotEmpty()) list.dropLast(1) + mine else list
    }
    val clickTarget = click
    shown.forEachIndexed { i, e ->
        val y = top + i * rowH
        if (e.user.isMe) c.drawRoundRect(RectF(pad / 2, y - 2 * d, c.width - pad / 2, y + rowH - 4 * d), 8 * d, 8 * d,
            Paint().apply { color = 0x22FFFFFF })
        val base = y + 14 * d
        c.drawText("${e.rank}", pad, base, px.text(px.block, 12f, if (e.rank <= 3) 0xFFFFCD75.toInt() else Color.argb(140, 244, 244, 244)))
        // the 24 h widgets: "+120" / "−40" in green / orange
        val delta = e.delta ?: 0.0
        val value = if (day) (if (delta >= 0) "+" else "−") + com.momentum.app.fmtShort(kotlin.math.abs(delta)) else com.momentum.app.fmtShort(e.momentum)
        val vp = px.text(px.block, 12f, when {
            day -> if (delta > 0) 0xFFA7F070.toInt() else if (delta < 0) 0xFFEF7D57.toInt() else Color.argb(140, 244, 244, 244)
            e.competing -> Color.WHITE
            else -> Color.argb(140, 244, 244, 244)
        })
        val vx = c.width - pad - vp.measureText(value)
        c.drawText(value, vx, base, vp)
        val np = px.text(px.body, 12f, Color.WHITE)
        var name = e.user.username
        val nx = pad + 26 * d
        while (np.measureText(name) > vx - nx - 8 * d && name.length > 2) name = name.dropLast(2) + "…"
        c.drawText(name, nx, base, np)
    }
    mgr.updateAppWidget(id, RemoteViews(ctx.packageName, R.layout.widget_image).apply {
        setImageViewBitmap(R.id.widget_image, bmp)
        setOnClickPendingIntent(R.id.widget_image, clickTarget)
    })
}

/** Is this one of the "last 24 hours" group widgets? */
internal fun isDayGroupWidget(ctx: Context, id: Int) =
    id in AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, GroupDeltaWidget::class.java))

internal fun drawGroupRanking(ctx: Context, mgr: AppWidgetManager, id: Int, day: Boolean = isDayGroupWidget(ctx, id)) {
    val S = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)
    val conv = WidgetGroups.group(ctx, id)
    val suffix = if (day) " · " + S.period24h else ""
    drawRanking(ctx, mgr, id, (if (conv == null) S.leaderboardTitle else WidgetGroups.name(ctx, id)) + suffix,
        if (conv == null) emptyList() else cachedRanking(ctx, conv, day), if (conv == null) S.widgetPickGroup else S.widgetLoading,
        day = day,
        // no group chosen yet: a tap opens the group choice
        click = if (conv != null) openApp(ctx) else PendingIntent.getActivity(ctx, 30_000 + id,
            Intent(ctx, GroupWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
}

internal fun drawFriendsRanking(ctx: Context, mgr: AppWidgetManager, id: Int, day: Boolean = false) {
    val S = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)
    drawRanking(ctx, mgr, id, S.leaderboardTitle + " · " + S.scopeFriends + if (day) " · " + S.period24h else "",
        cachedRanking(ctx, null, day), S.widgetLoading, day = day)
}

/** Fetches the rankings shown by placed widgets (they can't be computed from the cached state). */
internal suspend fun refreshRankings(ctx: Context) {
    val prefs = Prefs(ctx)
    val token = prefs.token ?: return
    val api = Api(prefs.serverUrl, token, prefs.language)
    val mgr = AppWidgetManager.getInstance(ctx)
    val store = LocalStore(ctx, json)
    val groupIds = mgr.getAppWidgetIds(ComponentName(ctx, GroupRankingWidget::class.java))
    val groupDayIds = mgr.getAppWidgetIds(ComponentName(ctx, GroupDeltaWidget::class.java))
    for (convId in groupIds.toList().mapNotNull { wid -> WidgetGroups.group(ctx, wid) }.distinct()) {
        runCatching { store.write(rankingKey(convId), api.raw("GET", "/conversations/$convId/ranking")) }
    }
    for (convId in groupDayIds.toList().mapNotNull { wid -> WidgetGroups.group(ctx, wid) }.distinct()) {
        runCatching { store.write(rankingKey(convId, true), api.raw("GET", "/conversations/$convId/ranking?period=today")) }
    }
    val friendIds = mgr.getAppWidgetIds(ComponentName(ctx, FriendsRankingWidget::class.java))
    val friendDayIds = mgr.getAppWidgetIds(ComponentName(ctx, FriendsDeltaWidget::class.java))
    if (friendIds.isNotEmpty()) runCatching { store.write(rankingKey(null), api.raw("GET", "/leaderboard?scope=friends")) }
    if (friendDayIds.isNotEmpty()) runCatching { store.write(rankingKey(null, true), api.raw("GET", "/leaderboard?scope=friends&period=today")) }
    groupIds.forEach { drawGroupRanking(ctx, mgr, it, day = false) }
    friendIds.forEach { drawFriendsRanking(ctx, mgr, it) }
    groupDayIds.forEach { drawGroupRanking(ctx, mgr, it, day = true) }
    friendDayIds.forEach { drawFriendsRanking(ctx, mgr, it, day = true) }
}

class RankingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        refreshRankings(applicationContext)
        return Result.success()
    }

    companion object {
        fun now(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork("momentum-rankings",
            androidx.work.ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RankingWorker>().setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build())
    }
}

class OverviewWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { val d = load(ctx); ids.forEach { drawOverview(ctx, mgr, it, d) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawOverview(ctx, mgr, id, load(ctx))
}

class GroupRankingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { drawGroupRanking(ctx, mgr, it) }
        RankingWorker.now(ctx)
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawGroupRanking(ctx, mgr, id)
}

class FriendsRankingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { drawFriendsRanking(ctx, mgr, it) }
        RankingWorker.now(ctx)
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawFriendsRanking(ctx, mgr, id)
}

/** A group's ranking by the last 24 hours (+/-). */
class GroupDeltaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { drawGroupRanking(ctx, mgr, it, day = true) }
        RankingWorker.now(ctx)
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawGroupRanking(ctx, mgr, id, day = true)
}

/** My friends' ranking by the last 24 hours (+/-). */
class FriendsDeltaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { drawFriendsRanking(ctx, mgr, it, day = true) }
        RankingWorker.now(ctx)
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = drawFriendsRanking(ctx, mgr, id, day = true)
}
