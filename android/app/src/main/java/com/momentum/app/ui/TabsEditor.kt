package com.momentum.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.AppState
import com.momentum.app.data.TabItem
import com.momentum.app.data.TabLayout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject

// Settings -> Tabs (server/tablayout.py, data/TabLayout.kt; = web/src/screens/TabsEditor.tsx): the bottom bar's places
// (up to 5: tabs and groups, a group's tabs indented below it), moved with pixel buttons; groups with a name, an icon
// from the tab library and what their tab menu shows; the hidden tabs to place again. Saved as a whole.

@Composable
fun TabsSettings(vm: AppViewModel, st: AppState) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    val lang = vm.lang.code
    val available = TabLayout.availableTabs(st.extensions)
    val shown = TabLayout.effectiveLayout(st.settings.tabs, available)
    var layout by remember(shown) { mutableStateOf(shown) }
    var editing by remember { mutableStateOf<String?>(null) }         // the group whose settings are open
    var placing by remember { mutableStateOf<String?>(null) }         // a hidden tab: where to?
    var saving by remember { mutableStateOf(false) }
    fun save() = scope.launch {
        saving = true
        val ok = vm.save("PUT", "/settings", buildJsonObject { put("tabs", TabLayout.toJson(layout)) }, quiet = true)
        saving = false
        if (ok) vm.showToast(S.saved)
    }
    fun apply(l: List<TabItem>?) { if (l != null) layout = l }
    val full = layout.size >= TabLayout.MAX_TOP
    val hidden = TabLayout.hiddenTabs(layout, available)
    val groups = layout.filter { it.isGroup }
    fun groupLabel(g: TabItem) = g.name.ifBlank { S.tabsGroupDefault(groups.indexOf(g) + 1) }
    fun add(k: String) {
        if (groups.isEmpty()) TabLayout.place(layout, k, null)?.let { layout = it } ?: vm.showToast(S.tabsBarFull)
        else placing = k
    }

    Box(Modifier.fillMaxSize()) {
        PageBody({ SaveBar(vm, layout != shown, saving) { save() } }) {
            item { Text(S.tabsIntro, style = T.small) }
            item {
                PxCard(padding = 0.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Label(S.tabsInBar, Modifier.weight(1f))
                            Text(S.tabsBarCount(layout.size, TabLayout.MAX_TOP),
                                style = T.small.copy(fontSize = 12.sp, color = if (full) Px.Yellow else Px.Muted))
                        }
                        layout.forEach { item ->
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
                            if (!item.isGroup) {
                                val k = item.tab!!
                                EditorTabRow(st, k, lang, indented = false) {
                                    MiniBtn("ui_up", S.tabsMoveUp, TabLayout.move(layout, k, -1) != null) { apply(TabLayout.move(layout, k, -1)) }
                                    MiniBtn("ui_down", S.tabsMoveDown, TabLayout.move(layout, k, 1) != null) { apply(TabLayout.move(layout, k, 1)) }
                                    MiniBtn("ui_back", S.tabsIndent, TabLayout.indent(layout, k) != null, mirrored = true) { apply(TabLayout.indent(layout, k)) }
                                    HideBtn(k) { apply(TabLayout.hide(layout, k)) }
                                }
                            } else {
                                val g = item
                                EditorRow(indented = false, glyph = { PixelIcon(g.icon, 20.dp) }, title = groupLabel(g), bold = true,
                                    sub = null, label = "group ${g.id}") {
                                    MiniBtn("ui_up", S.tabsMoveUp, TabLayout.move(layout, g.id, -1) != null) { apply(TabLayout.move(layout, g.id, -1)) }
                                    MiniBtn("ui_down", S.tabsMoveDown, TabLayout.move(layout, g.id, 1) != null) { apply(TabLayout.move(layout, g.id, 1)) }
                                    MiniBtn("ui_edit", S.tabsEdit, true) { editing = if (editing == g.id) null else g.id }
                                    MiniBtn("ui_close", S.tabsRemoveGroup, true) {
                                        layout = TabLayout.removeGroup(layout, g.id)
                                        if (editing == g.id) editing = null
                                    }
                                }
                                if (editing == g.id) GroupEditor(vm, g, groupLabel(g)) { name, icon, show ->
                                    layout = TabLayout.updateGroup(layout, g.id, name, icon, show)
                                }
                                g.tabs.forEach { k ->
                                    EditorTabRow(st, k, lang, indented = true) {
                                        MiniBtn("ui_up", S.tabsMoveUp, TabLayout.move(layout, k, -1) != null) { apply(TabLayout.move(layout, k, -1)) }
                                        MiniBtn("ui_down", S.tabsMoveDown, TabLayout.move(layout, k, 1) != null) { apply(TabLayout.move(layout, k, 1)) }
                                        MiniBtn("ui_back", S.tabsOutdent, TabLayout.outdent(layout, k) != null) { apply(TabLayout.outdent(layout, k)) }
                                        HideBtn(k) { apply(TabLayout.hide(layout, k)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                PxButton(S.tabsNewGroup, Modifier.fillMaxWidth().alpha(if (full) .4f else 1f), style = BtnStyle.Ghost) {
                    val next = TabLayout.addGroup(layout, S.tabsGroupDefault(TabLayout.nextGroupNumber(layout)), "tab_star")
                    if (next == null) vm.showToast(S.tabsBarFull)
                    else { layout = next; editing = next.last().id }
                }
            }
            item {
                Section(S.tabsHidden) {
                    if (hidden.isEmpty()) Text(S.tabsHiddenNone, style = T.small)
                    else Column {
                        hidden.forEach { k ->
                            EditorTabRow(st, k, lang, indented = false, padded = false) {
                                MiniBtn("ui_plus", S.tabsAdd, true) { add(k) }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S.tabsProfileStays, style = T.small.copy(fontSize = 12.sp))
                    PxButton(S.tabsReset, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) {
                        layout = TabLayout.DEFAULT_LAYOUT
                        editing = null
                    }
                }
            }
        }
        placing?.let { k ->
            // where the hidden tab goes: the bar or one of the groups (an overlay in the page, no Dialog: CLAUDE.md)
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { placing = null },
            ) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Px.Sheet)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {}
                        .padding(vertical = 18.dp),
                ) {
                    Text(S.tabsWhere, style = T.title.copy(fontSize = 20.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    ChooserRow("ui_plus", S.tabsIntoBar, enabled = !full) {
                        apply(TabLayout.place(layout, k, null))
                        placing = null
                    }
                    groups.forEach { g ->
                        ChooserRow(g.icon, groupLabel(g), enabled = true) {
                            apply(TabLayout.place(layout, k, g.id))
                            placing = null
                        }
                    }
                }
            }
        }
    }
}

/** A tab in the editor: its icon, its name (an extension's tab: the extension below), the buttons. */
@Composable
private fun EditorTabRow(st: AppState, key: String, lang: String, indented: Boolean, padded: Boolean = true,
                         buttons: @Composable RowScope.() -> Unit) {
    val ext = TabLayout.parseExtKey(key)?.let { (id, _) -> st.extensions.firstOrNull { it.id == id } }
    EditorRow(indented, glyph = { TabGlyph(st, key, 20.dp, 1f) }, title = tabName(st, key, lang), bold = false,
        sub = ext?.name?.of(lang), label = "tab $key", padded = padded, buttons = buttons)
}

@Composable
private fun EditorRow(indented: Boolean, glyph: @Composable () -> Unit, title: String, bold: Boolean, sub: String?,
                      label: String, padded: Boolean = true, buttons: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = label }
            .padding(start = if (padded) 12.dp else 0.dp, end = if (padded) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indented) {
            // a group's tab: indented, along a thin guide line
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(2.dp).height(52.dp).background(Px.Line))
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Px.Faint), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = if (bold) T.bodyStrong else T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = T.small.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        buttons()
    }
}

/** A small pixel button (dimmed when that move isn't possible). */
@Composable
private fun MiniBtn(icon: String, label: String, enabled: Boolean, mirrored: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(icon, 16.dp, Modifier.graphicsLayer { if (mirrored) scaleX = -1f }, alpha = if (enabled) 1f else .2f)
    }
}

/** Hide (not the profile: it always stays - the button's place stays empty). */
@Composable
private fun HideBtn(key: String, onClick: () -> Unit) {
    val S = LocalStrings.current
    if (key == "profile") Spacer(Modifier.size(34.dp)) else MiniBtn("ui_close", S.tabsHide, true, onClick = onClick)
}

/** A group's settings: its name, what its tab menu shows, its icon from the tab library. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditor(vm: AppViewModel, g: TabItem, placeholder: String, onChange: (name: String?, icon: String?, show: String?) -> Unit) {
    val S = LocalStrings.current
    Column(Modifier.fillMaxWidth().background(Color(0x0DFFFFFF)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(S.tabsGroupName, style = T.small)
        PxTextField(g.name, { onChange(it.take(24), null, null) }, placeholder = placeholder)
        Text(S.tabsGroupShow, style = T.small)
        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Px.Faint).padding(4.dp)) {
            listOf("icons" to S.tabsShowIcons, "names" to S.tabsShowNames, "both" to S.tabsShowBoth).forEach { (k, label) ->
                val on = g.show == k
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Px.Text else Color.Transparent)
                        .clickable { onChange(null, null, k) }.semantics { contentDescription = "show $k" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label.uppercase(), style = T.button.copy(color = if (on) Color.Black else Px.Text, fontWeight = FontWeight.Normal), maxLines = 1) }
            }
        }
        Text(S.tabsGroupIcon, style = T.small)
        val icons = vm.meta?.tabIcons.orEmpty().ifEmpty { listOf(g.icon) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            icons.forEach { ic ->
                val on = ic == g.icon
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Px.Faint else Color.Transparent)
                        .then(if (on) Modifier.border(2.dp, Px.Yellow, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable { onChange(null, ic, null) }.semantics { contentDescription = ic },
                    contentAlignment = Alignment.Center,
                ) { PixelIcon(ic, 20.dp, alpha = if (on) 1f else .7f) }
            }
        }
    }
}

@Composable
private fun ChooserRow(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else .35f).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Px.Faint), contentAlignment = Alignment.Center) {
            PixelIcon(icon, 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
