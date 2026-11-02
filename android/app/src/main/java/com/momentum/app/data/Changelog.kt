package com.momentum.app.data

import android.content.Context

/**
 * What's new (web/src/changelog.ts): CHANGELOG.md at the project root is bundled into every build
 * (build.gradle.kts copies it into the assets); after an update the app shows the versions added since
 * the one it ran before.
 *
 *     ## <version>      any unique text, newest first - only the order counts
 *     ### DE / ### EN   the language of the lines below
 *     - <entry>         a point (a following line indented by two spaces continues it)
 */
data class Release(val version: String, val de: List<String>, val en: List<String>) {
    fun lines(lang: String) = if (lang == "de") de else en
}

object Changelog {
    private class Draft(val version: String) {
        val de = mutableListOf<String>()
        val en = mutableListOf<String>()
    }

    fun parse(md: String): List<Release> {
        val out = mutableListOf<Draft>()
        var cur: Draft? = null
        var lang: MutableList<String>? = null
        for (raw in md.replace("\r\n", "\n").split("\n")) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    cur = Draft(line.removePrefix("## ").trim()).also { out.add(it) }
                    lang = null
                }
                line.startsWith("### ") -> lang = when (line.removePrefix("### ").trim().lowercase()) {
                    "de" -> cur?.de
                    "en" -> cur?.en
                    else -> null
                }
                (line.startsWith("- ") || line.startsWith("* ")) && lang != null -> lang.add(line.substring(2).trim())
                line.startsWith("  ") && line.isNotBlank() && lang?.isNotEmpty() == true ->
                    lang[lang.size - 1] = lang.last() + " " + line.trim()
            }
        }
        return out.filter { it.de.isNotEmpty() || it.en.isNotEmpty() }.map { Release(it.version, it.de, it.en) }
    }

    /** The releases newer than [seen] (the newest version shown before); all of them for "" and none for
     *  an unknown version (the file was rewritten: don't flood the screen). null = never shown before. */
    fun since(seen: String?, releases: List<Release>): List<Release> {
        if (seen == "") return releases
        if (seen == null) return emptyList()
        val i = releases.indexOfFirst { it.version == seen }
        return if (i < 0) emptyList() else releases.subList(0, i)
    }

    fun load(context: Context): List<Release> = runCatching {
        context.assets.open("CHANGELOG.md").bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(emptyList())

    /** At start: what to show. A device that already had the app (a token) but never saw a changelog gets the
     *  whole list once; a fresh install starts at the newest version and sees nothing. */
    fun pending(prefs: Prefs, hadApp: Boolean, releases: List<Release>): List<Release> {
        val show = since(prefs.changelogSeen ?: if (hadApp) "" else null, releases)
        releases.firstOrNull()?.let { prefs.changelogSeen = it.version }
        return show
    }
}
