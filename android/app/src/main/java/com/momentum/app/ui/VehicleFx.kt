package com.momentum.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vehicle effects (web/src/gfx/vehicles.ts): what a 64x32 sprite sheet can't hold - dust and exhaust, speed
 * lines, headlights at night, contrails, afterburners, engine trails, star streaks and a glint sweeping over
 * the hull - drawn around the animated sprite. The table's coordinates are art pixels of the sprite
 * (0..63 / 0..31); particles are snapped to the art grid so they stay pixel art.
 */

/** Frames per sheet and how long one shows (pixelart/generate.py FRAMES; the sheet is 64 * FRAMES wide). */
const val FRAMES = 8
const val FRAME_MS = 80

/** Art pixels around the sprite the effects may use: behind, in front, above, below. */
object Pad { const val LEFT = 72; const val RIGHT = 48; const val TOP = 18; const val BOTTOM = 6 }

enum class VKind { Land, Air, Space }

class Engine(val x: Int, val y0: Int, val y1: Int)
class Strobe(val x: Int, val y: Int, val frames: List<Int>, val color: Color)

class VehicleFx(
    val kind: VKind,
    /** how fast the world passes by (1 = a car); the banner scrolls with it */
    val speed: Float,
    /** vertical span of the hull (speed lines) */
    val body: Pair<Int, Int>,
    /** rear wheel's contact point: dust kicks up here */
    val dust: Pair<Int, Int>? = null,
    /** tailpipes: smoke drifts left from here */
    val exhaust: List<Pair<Int, Int>> = emptyList(),
    val headlight: Pair<Int, Int>? = null,
    val taillight: Pair<Int, Int>? = null,
    /** x range of the shadow on the road */
    val shadow: Pair<Int, Int>? = null,
    /** wing tips / engines leaving contrails */
    val contrails: List<Pair<Int, Int>> = emptyList(),
    /** nozzles (x = where the trail starts, y0..y1 = its height) */
    val engines: List<Engine> = emptyList(),
    /** engine trail colours: head, mid, tail */
    val trail: List<Color> = listOf(WHITE, YELLOW, ORANGE),
    /** blinking lights that bloom (same frames as in the sheet) */
    val strobes: List<Strobe> = emptyList(),
    val sparks: Boolean = false,
)

private val WHITE = Color(0xFFFFFFFF)
private val YELLOW = Color(0xFFFFCD75)
private val ORANGE = Color(0xFFEF7D57)
private val RED = Color(0xFFFF5C6A)
private val LIME = Color(0xFFA7F070)
private val DUST = Color(0xFFC4BAA6)
private val SMOKE = Color(0xFFA5AAB4)
private val BEAM = Color(0xFFFFE896)
private val TAIL = Color(0xFFFF465A)

val VEHICLE_FX: Map<String, VehicleFx> = mapOf(
    "fahrrad" to VehicleFx(VKind.Land, .4f, 8 to 24, dust = 17 to 30, shadow = 8 to 55),
    "motorroller" to VehicleFx(VKind.Land, .7f, 5 to 25, dust = 17 to 30, exhaust = listOf(7 to 24), headlight = 48 to 7, taillight = 7 to 17, shadow = 6 to 54),
    "kleinwagen" to VehicleFx(VKind.Land, .9f, 8 to 25, dust = 20 to 31, exhaust = listOf(10 to 24), headlight = 53 to 17, taillight = 11 to 17, shadow = 10 to 54),
    "pkw" to VehicleFx(VKind.Land, 1.1f, 9 to 25, dust = 15 to 31, exhaust = listOf(4 to 24), headlight = 59 to 18, taillight = 5 to 18, shadow = 4 to 60),
    "suv" to VehicleFx(VKind.Land, 1f, 5 to 25, dust = 15 to 31, exhaust = listOf(3 to 23), headlight = 59 to 16, taillight = 4 to 15, shadow = 3 to 61),
    "lkw" to VehicleFx(VKind.Land, .95f, 3 to 25, dust = 10 to 31, exhaust = listOf(1 to 23), headlight = 61 to 19, taillight = 2 to 20, shadow = 2 to 62, sparks = true,
        strobes = listOf(Strobe(6, 2, listOf(0, 1, 4, 5), ORANGE), Strobe(42, 2, listOf(0, 1, 4, 5), ORANGE))),
    "panzer" to VehicleFx(VKind.Land, .7f, 11 to 29, dust = 10 to 30, exhaust = listOf(4 to 20), shadow = 6 to 58, sparks = true,
        strobes = listOf(Strobe(17, 1, listOf(3, 4), RED))),
    "kleinflugzeug" to VehicleFx(VKind.Air, 1.3f, 4 to 24, contrails = listOf(24 to 10), strobes = listOf(Strobe(23, 9, listOf(0, 4), WHITE))),
    "passagierflugzeug" to VehicleFx(VKind.Air, 1.7f, 2 to 23, contrails = listOf(28 to 22, 22 to 19),
        strobes = listOf(Strobe(6, 2, listOf(0, 2), WHITE), Strobe(33, 20, listOf(4, 5), RED), Strobe(22, 18, listOf(6, 7), LIME))),
    "kampfjet" to VehicleFx(VKind.Air, 2.5f, 4 to 23, contrails = listOf(22 to 18), engines = listOf(Engine(5, 15, 18)), trail = listOf(WHITE, YELLOW, ORANGE),
        strobes = listOf(Strobe(12, 3, listOf(0, 1), RED), Strobe(37, 20, listOf(4, 5), LIME))),
    "shuttle" to VehicleFx(VKind.Space, 1.6f, 3 to 28, engines = listOf(Engine(3, 20, 23), Engine(9, 4, 7)), trail = listOf(WHITE, YELLOW, Color(0xFFC8CCD6))),
    "raumschiff" to VehicleFx(VKind.Space, 2f, 4 to 28, engines = listOf(Engine(6, 7, 10), Engine(6, 23, 26)), trail = listOf(WHITE, YELLOW, ORANGE),
        strobes = listOf(Strobe(53, 5, listOf(0, 1), RED), Strobe(53, 28, listOf(0, 1), RED))),
    "kreuzer" to VehicleFx(VKind.Space, 1.4f, 3 to 25, engines = listOf(Engine(6, 11, 14), Engine(6, 16, 19), Engine(6, 21, 24)),
        trail = listOf(Color(0xFFF4F4F4), Color(0xFF73EFF7), Color(0xFF41A6F6)),
        strobes = listOf(Strobe(14, 2, listOf(4, 5), WHITE), Strobe(9, 3, listOf(4, 5), WHITE))),
)

fun isVehicle(key: String) = key in VEHICLE_FX

/** How fast the world passes by for this vehicle (1 = a car). */
fun vehicleSpeed(key: String) = VEHICLE_FX[key]?.speed ?: 1f

/** Evenly spread offsets in 0..1 for particle i (golden ratio: no two alike, no pattern). */
internal fun spread(i: Int) = (i * .618034f) % 1f

/**
 * Draws the sprite's current frame at (ox, oy) pixels, [s] pixels per art pixel, with its effects around it.
 * [night]: headlights on, lights bloom. [t]: seconds.
 */
fun DrawScope.drawVehicle(sheet: ImageBitmap, key: String, s: Int, ox: Float, oy: Float, night: Boolean, t: Float) {
    val def = VEHICLE_FX[key]
    val frame = ((t * 1000).toInt() / FRAME_MS) % FRAMES
    // aircraft and ships float on top of the sheet's own bob
    val lift = if (def != null && def.kind != VKind.Land) (sin(t * 1.1f) * 1.2f).roundToInt() else 0
    val py = oy + lift * s
    val sf = s.toFloat()
    /** a block of art pixels */
    fun p(color: Color, x: Float, y: Float, w: Float = 1f, h: Float = 1f) = drawRect(
        color, Offset(ox + x.roundToInt() * sf, py + y.roundToInt() * sf),
        Size(maxOf(1, w.roundToInt()) * sf, maxOf(1, h.roundToInt()) * sf),
    )
    fun cx(x: Float) = ox + x * sf
    fun cy(y: Float) = py + y * sf

    if (def != null) {
        val sp = def.speed
        when (def.kind) {
            VKind.Land -> {
                def.shadow?.let { (x0, x1) ->
                    p(Color.Black.copy(alpha = .22f), x0 + 2f, 31f, (x1 - x0 - 4).toFloat(), 1f)
                    p(Color.Black.copy(alpha = .16f), x0.toFloat(), 32f, (x1 - x0).toFloat(), 1f)
                }
                if (night) def.headlight?.let { (hx0, hy0) ->
                    val hx = cx(hx0 + 1f)
                    val hy = cy(hy0.toFloat()) + sf / 2
                    val flick = .9f + .1f * sin(t * 23)
                    val path = Path().apply {
                        moveTo(hx, hy - sf); lineTo(hx + 46 * sf, hy - 10 * sf); lineTo(hx + 46 * sf, hy + 13 * sf); lineTo(hx, hy + 2 * sf); close()
                    }
                    drawPath(path, Brush.horizontalGradient(listOf(BEAM.copy(alpha = .3f * flick), BEAM.copy(alpha = 0f)), hx, hx + 46 * sf))
                    glowAt(hx, hy, 6 * sf, BEAM.copy(alpha = .6f * flick))
                }
                if (night) def.taillight?.let { (tx, ty) -> glowAt(cx(tx + .5f), cy(ty + .5f), 4 * sf, TAIL.copy(alpha = .55f)) }
                def.dust?.let { (dx, dy) ->
                    val n = (4 + 6 * sp).roundToInt()
                    val life = maxOf(.5f, 1.3f - sp * .4f)
                    for (i in 0 until n) {
                        val k = (t / life + spread(i)) % 1f
                        val x = dx - 2 - k * (20 + 16 * sp) - (i % 3)
                        val y = dy + 1 - k * k * (8 + i % 4) - (i % 2)
                        val d = 1 + floor(k * 3).toInt()
                        val a = (1 - k) * .5f
                        p(DUST.copy(alpha = a), x, y, d.toFloat(), d.toFloat())
                        if (d > 1) p(DUST.copy(alpha = a * .7f), x - 1, y + 1, d.toFloat(), (d - 1).toFloat())
                    }
                }
                def.exhaust.forEach { (ex, ey) ->
                    for (i in 0 until 5) {
                        val k = (t / 1.5f + spread(i + 3)) % 1f
                        val x = ex - 1 - k * (14 + 8 * sp) - (i % 2)
                        val y = ey - k * 8 + sin(t * 2 + i) * 1
                        val d = 1 + floor(k * 2.5f).toInt()
                        p(SMOKE.copy(alpha = (1 - k) * .45f), x, y, d.toFloat(), d.toFloat())
                    }
                }
                if (sp >= .6f) for (i in 0 until 5) {
                    val k = (t / (.5f / sp) + spread(i)) % 1f
                    val y = def.body.first + ((i * 7 + 3) % (def.body.second - def.body.first))
                    val x = (def.shadow?.first ?: 4) - 2 - k * 44
                    val len = 5 + 9 * (1 - k)
                    p(Color.White.copy(alpha = .3f * (1 - k) * minOf(1f, sp)), x - len, y.toFloat(), len, 1f)
                }
                if (def.sparks) def.dust?.let { (dx, dy) ->
                    for (i in 0 until 3) if ((t * 1.7f + spread(i)) % 1f < .12f) {
                        p(YELLOW, (dx - 3 - i * 2).toFloat(), dy - 1 - ((t * 40 + i) % 3))
                    }
                }
            }
            VKind.Air, VKind.Space -> {
                val space = def.kind == VKind.Space
                // wisps of cloud / star streaks rushing past
                for (i in 0 until 8) {
                    val k = (t / ((if (space) .45f else .7f) / sp) + spread(i)) % 1f
                    val y = -Pad.TOP + 2 + ((i * 11) % (32 + Pad.TOP + 2))
                    val x = 64 + Pad.RIGHT - k * (64 + Pad.LEFT + Pad.RIGHT + 24)
                    val len = if (space) 6 + 10 * (1 - k) else 10 + 12 * k
                    p(Color.White.copy(alpha = (if (space) .3f else .16f) * (1 - k)), x, y.toFloat(), len, 1f)
                }
                def.contrails.forEach { (tx, ty) ->
                    for (i in 0 until 34) {
                        val x = tx - 1f - i * 2
                        val y = ty + sin(t * 2.6f - i * .32f) * (.6f + i * .04f) + i * .06f
                        val d = 1 + i / 12
                        val a = .5f * (1 - i / 34f) * (.75f + .25f * sin(t * 5 + i))
                        p(Color.White.copy(alpha = a), x, y - d / 2f, 2f, d.toFloat())
                    }
                }
                def.engines.forEachIndexed { ei, e ->
                    val mid = (e.y0 + e.y1 + 1) / 2f
                    val hgt = e.y1 - e.y0 + 1
                    val (head, body, tail) = def.trail
                    val pulse = sin(t * 9 + ei * 2) * .5f + .5f
                    glowAt(cx(e.x.toFloat()), cy(mid), (hgt + 5) * sf, body.copy(alpha = .3f + .25f * pulse))
                    if (space) {
                        for (i in 0 until 14) {
                            val k = (t / (1.1f / sp) + spread(i + ei * 5)) % 1f
                            val x = e.x - 2 - k * (56 + 22 * sp)
                            val y = mid - .5f + sin(i * 7.3f) * (hgt / 2f - .5f) + sin(t * 3 + i) * k * 2
                            val d = if (k < .3f) 2f else 1f
                            p((if (k < .2f) head else if (k < .5f) body else tail).copy(alpha = (1 - k) * .85f), x, y, d, d)
                        }
                        if (key == "shuttle") for (i in 0 until 8) {
                            val k = (t / 1.3f + spread(i + ei * 7)) % 1f
                            val x = e.x - 2 - k * 36 - (i % 2)
                            val y = mid - 1 + sin(i * 3.1f) * 2 + k * 3 * (if (i % 2 == 1) 1 else -1)
                            val d = 2 + floor(k * 4).toInt()
                            p((if (k < .3f) WHITE else tail).copy(alpha = (1 - k) * .5f), x, y - d / 2f, d.toFloat(), d.toFloat())
                        }
                    } else {
                        // afterburner: shock diamonds and heat shimmer
                        for (j in 0 until 4) {
                            if ((t * 25 + j) % 2f < 1f) p(if (j % 2 == 1) body else head, (e.x - 3 - j * 3 - (frame % 2)).toFloat(), mid - .5f)
                            p(Color.White.copy(alpha = .07f), (e.x - 5 - j * 4).toFloat(), mid - 2 + sin(t * 17 + j) * 2, 3f, 4f)
                        }
                    }
                }
            }
        }
        def.strobes.forEach { st -> if (frame in st.frames) glowAt(cx(st.x + .5f), cy(st.y + .5f), 4 * sf, st.color.copy(alpha = .6f)) }
    }

    // the sprite's frame
    drawPixels(sheet, IntOffset(frame * 64, 0), IntSize(64, 32), IntOffset(ox.toInt(), py.toInt()), s)

    // in front: a glint sweeping over the hull now and then (pixel by pixel over the frame's opaque ones -
    // a layer with SrcIn blurred the sprite on the emulator)
    if (def != null) {
        val cyc = 4.6f
        val k = ((t + (abs(key.hashCode()) % 100) / 25f) % cyc) / .55f
        if (k < 1f) {
            val gx = -12 + 88 * k
            val mask = opaque(sheet, key)
            for (y in 0 until 32) for (x in 0 until 64) {
                if (!mask[frame * 2048 + y * 64 + x]) continue
                val u = (16 * (x - gx + 12) + 32 * y) / 1280f          // position across the diagonal band
                val a = .5f * (1 - abs(u - .5f) * 2)
                if (a > .02f) p(Color.White.copy(alpha = a), x.toFloat(), y.toFloat())
            }
        }
    }
}

private val masks = HashMap<String, BooleanArray>()

/** Which pixels of the sheet are drawn ([frame * 2048 + y * 64 + x]), read once per vehicle. */
private fun opaque(sheet: ImageBitmap, key: String): BooleanArray = masks.getOrPut(key) {
    val px = IntArray(sheet.width * sheet.height)
    sheet.readPixels(px, 0, 0, sheet.width, sheet.height)
    BooleanArray(FRAMES * 2048) { i ->
        val f = i / 2048
        val y = (i % 2048) / 64
        val x = i % 64
        (px[y * sheet.width + f * 64 + x] ushr 24) != 0
    }
}
