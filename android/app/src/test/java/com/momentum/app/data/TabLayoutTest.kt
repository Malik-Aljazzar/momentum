package com.momentum.app.data

import com.momentum.app.data.TabLayout.Place
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tab layout's rules (data/TabLayout.kt); the same cases as web/tests/unit/tablayout.test.ts. */
class TabLayoutTest {
    private val journal = InstalledExtension("journal", "1.1.0", Texts("Tagebuch", "Journal"), "openbook", enabled = true,
        tabs = listOf(ExtensionTabDef("today", Texts("Heute", "Today"), "tab_pen"), ExtensionTabDef("entries", Texts("Einträge", "Entries"), "tab_book")))
    private val off = journal.copy(id = "notes", enabled = false)
    private val available = listOf("rules", "chats", "feed", "profile", "ext:journal:today", "ext:journal:entries")
    private fun social(vararg tabs: String) = TabItem(id = "g1", name = "Social", icon = "tab_people", show = "both", tabs = tabs.toList())
    private fun t(k: String) = TabItem(tab = k)
    private val l = listOf(t("rules"), social("chats", "feed"), t("ext:journal:today"), t("profile"))

    @Test
    fun theTabsToChooseFrom() {
        assertEquals(available, TabLayout.availableTabs(listOf(journal, off)))
        assertEquals(listOf("rules", "chats", "feed", "profile"), TabLayout.availableTabs(null))
    }

    @Test
    fun theLayoutAsShown() {
        assertEquals(TabLayout.DEFAULT_LAYOUT, TabLayout.effectiveLayout(null, available))
        val gone = TabLayout.effectiveLayout(listOf(t("rules"), social("chats", "ext:notes:x"), t("ext:notes:y")), available)
        assertEquals(listOf(t("rules"), social("chats"), t("profile")), gone)
        assertEquals(listOf(social(), t("profile")), TabLayout.effectiveLayout(listOf(social(), t("profile")), available))  // empty groups stay
        assertEquals(listOf("ext:journal:entries"), TabLayout.hiddenTabs(l, available))
        assertEquals(Place(1, "g1"), TabLayout.locate(l, "feed"))
        assertEquals(Place(3, null), TabLayout.locate(l, "profile"))
        assertNull(TabLayout.locate(l, "ext:journal:entries"))
        assertEquals(listOf("rules", "group:g1", "ext:journal:today", "profile"), TabLayout.pagesOf(l))
    }

    @Test
    fun movesWithinItsOwnList() {
        assertEquals(listOf(social("chats", "feed"), t("rules"), l[2], l[3]), TabLayout.move(l, "g1", -1))
        assertEquals(listOf(l[0], social("feed", "chats"), l[2], l[3]), TabLayout.move(l, "feed", -1))
        assertNull(TabLayout.move(l, "rules", -1))
        assertNull(TabLayout.move(l, "profile", 1))
        assertNull(TabLayout.move(l, "feed", 1))                         // the group's last
    }

    @Test
    fun intoTheGroupAboveAndOutAgain() {
        val into = TabLayout.indent(l, "ext:journal:today")!!
        assertEquals(listOf(l[0], social("chats", "feed", "ext:journal:today"), l[3]), into)
        assertNull(TabLayout.indent(l, "rules"))                         // nothing above
        assertNull(TabLayout.indent(l, "profile"))                       // above it is a tab, not a group
        assertEquals(listOf(l[0], social("feed", "ext:journal:today"), t("chats"), l[3]), TabLayout.outdent(into, "chats"))
        val full = l + t("ext:journal:entries")
        assertNull(TabLayout.outdent(full, "chats"))                     // the bar has 5 already
    }

    @Test
    fun hidesDissolvesPlaces() {
        assertEquals(listOf(l[0], social("chats"), l[2], l[3]), TabLayout.hide(l, "feed"))
        assertEquals(listOf(l[1], l[2], l[3]), TabLayout.hide(l, "rules"))
        assertNull(TabLayout.hide(l, "profile"))
        assertEquals(listOf(l[0], l[2], l[3]), TabLayout.removeGroup(l, "g1"))
        assertEquals(listOf(t("profile"), t("rules")), TabLayout.removeGroup(listOf(social("chats", "profile"), t("rules")), "g1"))
        assertEquals(l + t("ext:journal:entries"), TabLayout.place(l, "ext:journal:entries", null))
        assertEquals(listOf(l[0], social("chats", "feed", "ext:journal:entries"), l[2], l[3]), TabLayout.place(l, "ext:journal:entries", "g1"))
        assertNull(TabLayout.place(l, "feed", null))                     // already placed
        assertNull(TabLayout.place(l + t("chats"), "ext:journal:entries", null))     // full
    }

    @Test
    fun newGroupsGetFreeIdsAndAGroupsSettingsChange() {
        val g = TabLayout.addGroup(l, "Gruppe 2", "tab_star")!!
        assertEquals(TabItem(id = "g2", name = "Gruppe 2", icon = "tab_star", show = "icons", tabs = emptyList()), g[4])
        assertNull(TabLayout.addGroup(g, "x", "tab_star"))               // full
        assertEquals(social("chats", "feed").copy(show = "names", icon = "tab_heart"),
            TabLayout.updateGroup(l, "g1", show = "names", icon = "tab_heart")[1])
    }

    @Test
    fun aGroupsPageShowsTheChosenTabElseItsFirst() {
        assertEquals("chats", TabLayout.leafOf(l, "group:g1", emptyMap()))
        assertEquals("feed", TabLayout.leafOf(l, "group:g1", mapOf("g1" to "feed")))
        assertEquals("chats", TabLayout.leafOf(l, "group:g1", mapOf("g1" to "rules")))   // not in this group (any more)
        assertNull(TabLayout.leafOf(listOf(social()), "group:g1", emptyMap()))
        assertEquals("rules", TabLayout.leafOf(l, "rules", emptyMap()))
    }

    @Test
    fun theElectionsPageComesBeforeTheProfiles() {
        assertEquals(listOf("rules", "group:g1", "ext:journal:today", "election", "profile"), TabLayout.pagesWith(l, true))
        assertEquals(listOf("election", "group:g1", "rules"),
            TabLayout.pagesWith(listOf(social("chats", "profile"), TabItem(tab = "rules")), true))
        assertEquals(TabLayout.pagesOf(l), TabLayout.pagesWith(l, false))
    }

    @Test
    fun sentAndReadLikeTheServerKeepsThem() {
        assertEquals("""[{"tab":"rules"},{"id":"g1","name":"Social","icon":"tab_people","show":"both","tabs":["chats","feed"]},""" +
            """{"tab":"ext:journal:today"},{"tab":"profile"}]""", TabLayout.toJson(l).toString())
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        assertEquals(l, json.decodeFromString<List<TabItem>>(TabLayout.toJson(l).toString()))
    }
}
