package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** Vehicle effects and banner extras; the same checks as web/tests/unit/vehicles.test.ts. */
class VehiclesTest {
    /** server/service.py VEHICLES */
    private val vehicles = listOf("fahrrad", "motorroller", "kleinwagen", "pkw", "suv", "lkw", "kleinflugzeug", "passagierflugzeug",
        "shuttle", "raumschiff", "panzer", "kampfjet", "kreuzer")

    @Test
    fun knowsEveryVehicle() {
        assertEquals(vehicles.sorted(), VEHICLE_FX.keys.sorted())
        for (v in vehicles) {
            val fx = VEHICLE_FX.getValue(v)
            assertTrue(v, fx.speed > 0f && fx.speed <= 3f)
            assertTrue(v, fx.body.first < fx.body.second)
            val points = listOfNotNull(fx.dust, fx.headlight, fx.taillight) + fx.exhaust + fx.contrails
            for ((x, y) in points) assertTrue("$v $x,$y", x in 0..63 && y in 0..31)
            for (e in fx.engines) assertTrue(v, e.y0 <= e.y1)
            for (st in fx.strobes) for (f in st.frames) assertTrue(v, f < FRAMES)
        }
        assertEquals(listOf("kleinflugzeug", "passagierflugzeug", "kampfjet"), vehicles.filter { VEHICLE_FX.getValue(it).kind == VKind.Air })
        assertEquals(listOf("shuttle", "raumschiff", "kreuzer"), vehicles.filter { VEHICLE_FX.getValue(it).kind == VKind.Space })
    }

    @Test
    fun speeds() {
        assertTrue(vehicleSpeed("fahrrad") < vehicleSpeed("pkw"))
        assertTrue(vehicleSpeed("kampfjet") > vehicleSpeed("passagierflugzeug"))
        assertEquals(1f, vehicleSpeed("gauge"))
    }

    @Test
    fun sheetsHaveFramesFrames() {
        assertEquals(640, FRAMES * FRAME_MS)
        for (v in vehicles + listOf("gauge", "friends")) {
            val (w, h) = pngSize(File("../../pixelart/out/$v/sheet.png"))
            assertEquals(v, 64 * FRAMES, w)
            assertEquals(v, 32, h)
        }
    }

    /** Width and height from the PNG header (IHDR) - no image libraries in unit tests. */
    private fun pngSize(f: File): Pair<Int, Int> = DataInputStream(f.inputStream()).use {
        it.skipBytes(16)
        it.readInt() to it.readInt()
    }

    @Test
    fun extrasNameExistingBanners() {
        for ((k, e) in EXTRAS) {
            assertTrue(k, isBanner(k))
            e.water?.let { (a, b) -> assertTrue(k, a in 0 until b && b <= 40) }
            if (e.disc || e.galaxy) assertTrue(k, "space" in bannerKinds(k))
        }
    }

    @Test
    fun darkBannersSwitchTheLightsOn() {
        assertTrue(bannerNight("city"))
        assertTrue(bannerNight("moon"))
        assertTrue(bannerNight(HYPERSPACE))
        assertTrue(!bannerNight("desert"))
        assertTrue(!bannerNight("sakura"))
        assertTrue(bannerNight(null))              // dusk
        assertTrue(BANNERS.count { bannerNight(it) } > 10)
    }
}
