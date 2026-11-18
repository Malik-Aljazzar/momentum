package com.momentum.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive

// The player's tabs (server/tablayout.py): up to MAX_TOP places in the bottom bar, each a tab or a group (any number of
// tabs; its page has a tab menu at the top showing their names, icons or both). Tabs that aren't placed are hidden; the
// profile always stays. Pure functions for the app and the editor (settings -> tabs). = web/src/tablayout.ts

/** One place of the layout: a tab ([tab] = its key) or a group ([tab] null: [id], [name], [icon], [show], [tabs]). */
@Serializable
data class TabItem(
    val tab: String? = null,
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    /** what the group's tab menu shows: "icons", "names" or "both" (never mixed) */
    val show: String = "icons",
    val tabs: List<String> = emptyList(),
) {
    val isGroup get() = tab == null
    /** a tab key or a group id */
    val key get() = tab ?: id
}

object TabLayout {
    const val MAX_TOP = 5
    val STANDARD = listOf("rules", "chats", "feed", "profile")
    val DEFAULT_LAYOUT = STANDARD.map { TabItem(tab = it) }
    val SHOWS = listOf("icons", "names", "both")

    /** Where a tab is: its place in the bar and its group (null = right in the bar). */
    data class Place(val index: Int, val group: String?)

    fun extKey(ext: String, key: String) = "ext:$ext:$key"

    /** "ext:<extension>:<tab>" -> (extension, tab); null for a standard tab. */
    fun parseExtKey(k: String): Pair<String, String>? {
        val m = Regex("^ext:([^:]+):(.+)$").find(k) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** The tabs a player can place now: the standard ones, then those of the switched-on extensions. */
    fun availableTabs(exts: List<InstalledExtension>?): List<String> =
        STANDARD + exts.orEmpty().filter { it.enabled }.flatMap { e -> e.tabs.map { extKey(e.id, it.key) } }

    /** An extension tab's manifest entry (name, icon); null when its extension isn't there. */
    fun extTab(exts: List<InstalledExtension>?, k: String): ExtensionTabDef? {
        val (ext, key) = parseExtKey(k) ?: return null
        return exts.orEmpty().firstOrNull { it.id == ext }?.tabs?.firstOrNull { it.key == key }
    }

    /** The layout as shown: the stored one (or the default) without tabs that aren't available (an extension switched
     *  off or gone); the profile is always somewhere. */
    fun effectiveLayout(stored: List<TabItem>?, available: List<String>): List<TabItem> {
        val ok = available.toSet()
        val out = mutableListOf<TabItem>()
        for (i in stored ?: DEFAULT_LAYOUT) {
            if (i.isGroup) out += i.copy(tabs = i.tabs.filter { it in ok })
            else if (i.tab in ok) out += TabItem(tab = i.tab)
        }
        if (locate(out, "profile") == null) out += TabItem(tab = "profile")
        return out
    }

    /** Tabs placed nowhere (hidden), in the order of [available]. */
    fun hiddenTabs(layout: List<TabItem>, available: List<String>) = available.filter { locate(layout, it) == null }

    /** Where a tab is; null when it's hidden. */
    fun locate(layout: List<TabItem>, key: String): Place? {
        layout.forEachIndexed { i, it ->
            if (if (it.isGroup) key in it.tabs else it.tab == key) return Place(i, if (it.isGroup) it.id else null)
        }
        return null
    }

    private fun <T> swap(list: List<T>, i: Int, j: Int): List<T>? {
        if (j < 0 || j >= list.size) return null
        return list.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }

    // ---- editing (settings -> tabs): each returns the new layout, or null when that move isn't possible

    /** One step up (-1) / down (+1) within its own list (the bar, or its group); [key] = a tab or a group id. */
    fun move(layout: List<TabItem>, key: String, dir: Int): List<TabItem>? {
        val top = layout.indexOfFirst { it.key == key }
        if (top >= 0) return swap(layout, top, top + dir)
        val at = locate(layout, key) ?: return null
        if (at.group == null) return null
        val g = layout[at.index]
        val tabs = swap(g.tabs, g.tabs.indexOf(key), g.tabs.indexOf(key) + dir) ?: return null
        return layout.mapIndexed { n, i -> if (n == at.index) g.copy(tabs = tabs) else i }
    }

    /** A tab in the bar joins the group right above it (at its end). */
    fun indent(layout: List<TabItem>, key: String): List<TabItem>? {
        val i = layout.indexOfFirst { !it.isGroup && it.tab == key }
        if (i < 1) return null
        val above = layout[i - 1]
        if (!above.isGroup) return null
        return layout.subList(0, i - 1) + above.copy(tabs = above.tabs + key) + layout.subList(i + 1, layout.size)
    }

    /** A tab in a group goes into the bar, right after its group (the bar needs room). */
    fun outdent(layout: List<TabItem>, key: String): List<TabItem>? {
        val at = locate(layout, key) ?: return null
        if (at.group == null || layout.size >= MAX_TOP) return null
        val g = layout[at.index]
        return layout.subList(0, at.index) + g.copy(tabs = g.tabs - key) + TabItem(tab = key) +
            layout.subList(at.index + 1, layout.size)
    }

    /** A tab leaves the layout (hidden); never the profile. */
    fun hide(layout: List<TabItem>, key: String): List<TabItem>? {
        if (key == "profile" || locate(layout, key) == null) return null
        return layout.flatMap { i ->
            if (i.isGroup) listOf(i.copy(tabs = i.tabs - key)) else if (i.tab == key) emptyList() else listOf(i)
        }
    }

    /** A group dissolved: its tabs are hidden, except the profile, which takes the group's place. */
    fun removeGroup(layout: List<TabItem>, id: String): List<TabItem> = layout.flatMap { i ->
        if (!i.isGroup || i.id != id) listOf(i)
        else if ("profile" in i.tabs) listOf(TabItem(tab = "profile")) else emptyList()
    }

    /** A hidden tab placed: into the bar (at its end; needs room) or at the end of a group. */
    fun place(layout: List<TabItem>, key: String, group: String?): List<TabItem>? {
        if (locate(layout, key) != null) return null
        if (group == null) return if (layout.size < MAX_TOP) layout + TabItem(tab = key) else null
        val g = layout.firstOrNull { it.isGroup && it.id == group } ?: return null
        return layout.map { if (it === g) g.copy(tabs = g.tabs + key) else it }
    }

    /** A new, empty group at the end of the bar (needs room). */
    fun addGroup(layout: List<TabItem>, name: String, icon: String): List<TabItem>? {
        if (layout.size >= MAX_TOP) return null
        var n = 1
        while (layout.any { it.isGroup && it.id == "g$n" }) n++
        return layout + TabItem(id = "g$n", name = name, icon = icon, show = "icons", tabs = emptyList())
    }

    /** A group's name / icon / what its tab menu shows. */
    fun updateGroup(layout: List<TabItem>, id: String, name: String? = null, icon: String? = null, show: String? = null) =
        layout.map { if (it.isGroup && it.id == id) it.copy(name = name ?: it.name, icon = icon ?: it.icon, show = show ?: it.show) else it }

    /** A group's next free default number ("Gruppe 2"). */
    fun nextGroupNumber(layout: List<TabItem>) = layout.count { it.isGroup } + 1

    // ---- the app's pages: one per place in the bar (+ the election's while it runs)

    /** A page of the main pager: a tab key, or "group:<id>". */
    fun groupPage(id: String) = "group:$id"
    fun pageGroupId(page: String) = if (page.startsWith("group:")) page.substring(6) else null
    fun pagesOf(layout: List<TabItem>) = layout.map { if (it.isGroup) groupPage(it.id) else it.tab!! }

    /** The pager's pages; while the Larper election runs (server/nine.py) its page comes right before the profile's. */
    fun pagesWith(layout: List<TabItem>, election: Boolean): List<String> {
        val pages = pagesOf(layout)
        if (!election) return pages
        val at = locate(layout, "profile")?.index ?: pages.size
        return pages.take(at) + "election" + pages.drop(at)
    }

    /** The tab a page shows: itself, or the chosen tab of its group (its first one at the start). */
    fun leafOf(layout: List<TabItem>, page: String, chosen: Map<String, String>): String? {
        val id = pageGroupId(page) ?: return page
        val g = layout.firstOrNull { it.isGroup && it.id == id } ?: return null
        return chosen[id]?.takeIf { it in g.tabs } ?: g.tabs.firstOrNull()
    }

    /** The layout as the server takes it (PUT /api/settings {tabs}): {"tab"} or {id, name, icon, show, tabs}. */
    fun toJson(layout: List<TabItem>) = JsonArray(layout.map { i ->
        if (!i.isGroup) buildJsonObject { put("tab", i.tab) }
        else buildJsonObject {
            put("id", i.id); put("name", i.name); put("icon", i.icon); put("show", i.show)
            putJsonArray("tabs") { i.tabs.forEach { add(JsonPrimitive(it)) } }
        }
    })
}
