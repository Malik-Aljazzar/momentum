package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** One of the Nine: nine titles with colours, creature sheets and texts in both languages (= web/tests/unit/nine.test.ts). */
class NineTest {
    private fun pngSize(f: File): Pair<Int, Int> = DataInputStream(f.inputStream()).use {
        it.skipBytes(16)
        it.readInt() to it.readInt()
    }

    @Test
    fun nineTitlesWithColoursThemesAndSheets() {
        assertEquals(listOf("goat", "larper", "hermes", "yapper", "charmeur", "gamemaster", "veteran", "clown", "supercharged"), NINE_KEYS)
        for (k in NINE_KEYS) {
            assertTrue(k, NINE_COLORS.containsKey(k))
            assertTrue(k, isBanner("nine_$k"))
            assertFalse(k, "nine_$k" in BANNERS)                  // never in the picker
            assertEquals(k, NINE_PX * NINE_FRAMES to NINE_PX, pngSize(File("../../pixelart/out/nine_$k/sheet.png")))
        }
    }

    @Test
    fun textsInBothLanguages() {
        for (L in listOf(De, En)) for (k in NINE_KEYS) {
            assertTrue(k, L.nineName(k).length > 2)
            assertTrue(k, L.nineLore(k).length > 40)
            assertTrue(k, L.nineHow(k).length > 15)
            assertTrue(k, "7" in L.nineUnit(k, "7"))
        }
        assertEquals("GOAT", De.nineName("goat"))
        assertEquals("3 comments", En.nineUnit("yapper", "3"))
        assertEquals("Du: 12 Posts · Platz 4 weltweit", De.nineMine("12 Posts", 4))
        assertEquals("3 of 10 votes needed", En.electionVotes(3, 10))
    }
}
