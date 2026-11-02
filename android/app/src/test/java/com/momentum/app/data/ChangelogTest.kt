package com.momentum.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The changelog shown after an update; the same cases as web/tests/unit/changelog.test.ts. */
class ChangelogTest {
    private val sample = """
        # Changelog

        Some words about the format.

        ## 2026-11-05 b
        ### DE
        - Zweites Release am selben Tag.
        ### EN
        - Second release on the same day.

        ## 2026-11-05

        ### DE
        - Erster Punkt,
          der weitergeht.
        * Zweiter Punkt.

        ### EN
        - First point,
          continued.
        * Second point.

        ## 2026-11-02
        ### de
        - Changelog.
        ### en
        - Changelog.

        ## empty
        ### DE
    """.trimIndent()

    @Test
    fun parsesVersionsLanguagesAndContinuedLines() {
        val r = Changelog.parse(sample)
        assertEquals(listOf("2026-11-05 b", "2026-11-05", "2026-11-02"), r.map { it.version })
        assertEquals(listOf("Erster Punkt, der weitergeht.", "Zweiter Punkt."), r[1].de)
        assertEquals(listOf("First point, continued.", "Second point."), r[1].en)
        assertEquals(listOf("Changelog."), r[2].lines("de"))
    }

    @Test
    fun sinceGivesTheNewerVersions() {
        val r = Changelog.parse(sample)
        assertEquals(listOf("2026-11-05 b", "2026-11-05"), Changelog.since("2026-11-02", r).map { it.version })
        assertEquals(emptyList<Release>(), Changelog.since("2026-11-05 b", r))
        assertEquals(r, Changelog.since("", r))
        assertEquals(emptyList<Release>(), Changelog.since(null, r))
        assertEquals(emptyList<Release>(), Changelog.since("1999", r))
    }

    @Test
    fun theRealChangelogHasBothLanguagesEverywhere() {
        val md = File("../../CHANGELOG.md").readText()
        val r = Changelog.parse(md)
        assertTrue(r.isNotEmpty())
        for (rel in r) {
            assertTrue("${rel.version}: DE missing", rel.de.isNotEmpty())
            assertEquals("${rel.version}: DE and EN differ in length", rel.de.size, rel.en.size)
        }
        assertEquals(r.map { it.version }.distinct(), r.map { it.version })
    }
}
