package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.catalyst
import com.momentum.app.data.Decay
import com.momentum.app.data.Rule
import com.momentum.app.fmtVal
import com.momentum.app.nextValue
import com.momentum.app.parseNum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// "Lückentext" editors: a sentence explains the rule, the gaps are its settings.
// Picking another option in a dropdown rewrites the sentence.

private val LocalGapColor = staticCompositionLocalOf { Px.Yellow }
private val clozeText = T.body.copy(fontSize = 20.sp, lineHeight = 26.sp)

private class ClozeItem(val id: String, var padEnd: Boolean = true, var content: @Composable () -> Unit)

private class ClozeScope : ClozeBuilder {
    val items = mutableListOf<ClozeItem>()
    private var n = 0

    override fun text(s: String) {
        s.split(" ").filter { it.isNotEmpty() }.forEach { w ->
            val prev = items.lastOrNull()
            if (w.first() in ".,:;!?%" && prev?.id?.startsWith("gap:") == true) {
                // punctuation sticks to the gap before it, so a line never starts with ","
                val punct = w.takeWhile { it in ".,:;!?%" }
                val gapContent = prev.content
                prev.content = {
                    Row(verticalAlignment = Alignment.CenterVertically) { gapContent(); Text(punct, style = clozeText) }
                }
                val rest = w.drop(punct.length)
                if (rest.isNotEmpty()) items += ClozeItem("w${n++}") { Text(rest, style = clozeText) }
                return@forEach
            }
            items += ClozeItem("w${n++}") { Text(w, style = clozeText) }
        }
    }

    override fun gap(id: String, content: @Composable () -> Unit) {
        items += ClozeItem("gap:$id", content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Cloze(build: ClozeBuilder.() -> Unit) {
    val scope = ClozeScope().apply(build)
    FlowRow(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        scope.items.forEach { item ->
            key(item.id) {
                Box(
                    Modifier
                        .align(Alignment.CenterVertically)
                        .padding(end = if (item.padEnd) 6.dp else 0.dp),
                ) { item.content() }
            }
        }
    }
}

@Composable
private fun GapBox(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val color = LocalGapColor.current
    Box(
        Modifier
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(Color(0x14FFFFFF))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(color, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun gapStyle(color: Color) = clozeText.copy(color = color, fontWeight = FontWeight.SemiBold)

private fun numText(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString().replace('.', ',')

/** Width of a single-line text field that exactly fits its content (no inner scrolling). */
@Composable
private fun fitWidth(text: String, style: TextStyle, min: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val px = remember(text, style) { measurer.measure(text, style, maxLines = 1).size.width }
    return maxOf(min, with(density) { px.toDp() } + 4.dp)
}

@Composable
private fun TextGap(value: String, placeholder: String, onChange: (String) -> Unit) {
    val color = LocalGapColor.current
    GapBox {
        BasicTextField(
            value, { if (it.length <= 40) onChange(it) }, singleLine = true,
            textStyle = gapStyle(color), cursorBrush = SolidColor(color),
            modifier = Modifier.width(fitWidth(value.ifEmpty { placeholder }, gapStyle(color), 40.dp)),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = gapStyle(Color(0x4DFFFFFF)))
                inner()
            },
        )
    }
}

@Composable
private fun NumGap(value: Double, onChange: (Double) -> Unit) {
    val color = LocalGapColor.current
    var text by remember { mutableStateOf(numText(value)) }
    GapBox {
        BasicTextField(
            text, { t -> text = t; parseNum(t)?.let(onChange) }, singleLine = true,
            textStyle = gapStyle(color).copy(textAlign = TextAlign.Center), cursorBrush = SolidColor(color),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(fitWidth(text, gapStyle(color), 24.dp)),
        )
    }
}

@Composable
private fun <T> SelectGap(selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    val color = LocalGapColor.current
    var open by remember { mutableStateOf(false) }
    Box {
        GapBox(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == selected }?.second ?: selected.toString(), style = gapStyle(color))
                Text(" ▾", style = gapStyle(color.copy(alpha = .7f)).copy(fontSize = 13.sp))
            }
        }
        DropdownMenu(open, { open = false }, containerColor = Px.Card2) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = T.body.copy(color = if (value == selected) color else Px.Text)) },
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

/** A duration: the usual days as presets, plus "custom": any number 0..365 in hours or days (report #18). */
@Composable
private fun TimeGap(n: Int, unit: String, presets: List<Int>, label: (Int, String) -> String, onChange: (Int, String) -> Unit) {
    val S = LocalStrings.current
    val color = LocalGapColor.current
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    Box {
        GapBox(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(n, unit), style = gapStyle(color))
                Text(" ▾", style = gapStyle(color.copy(alpha = .7f)).copy(fontSize = 13.sp))
            }
        }
        DropdownMenu(open, { open = false }, containerColor = Px.Card2) {
            presets.forEach { v ->
                DropdownMenuItem(
                    text = { Text(label(v, "d"), style = T.body.copy(color = if (v == n && (unit == "d" || v == 0)) color else Px.Text)) },
                    onClick = { open = false; onChange(v, "d") },
                )
            }
            DropdownMenuItem(text = { Text(S.customTime, style = T.body.copy(color = Px.Cyan)) }, onClick = { open = false; custom = true })
        }
    }
    if (custom) {
        var text by remember { mutableStateOf(if (n > 0) n.toString() else "") }
        var u by remember { mutableStateOf(unit) }
        androidx.compose.ui.window.Dialog({ custom = false }) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Px.Card2).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(S.customTimeTitle, style = T.title.copy(fontSize = 18.sp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        PxTextField(text, { t -> text = t.filter(Char::isDigit).take(3) }, placeholder = "0–365", keyboardType = KeyboardType.Number)
                    }
                    listOf("h" to S.unitHours, "d" to S.unitDays).forEach { (k, l) ->
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (u == k) Px.Text else Px.Faint)
                            .clickable { u = k }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(l, style = T.button.copy(color = if (u == k) Color.Black else Px.Text))
                        }
                    }
                }
                PxButton(S.done, Modifier.fillMaxWidth()) {
                    custom = false
                    onChange((text.toIntOrNull() ?: 0).coerceIn(0, 365), u)
                }
            }
        }
    }
}

@Composable
private fun IconGap(icon: String, onClick: () -> Unit) = GapBox(onClick) { PixelIcon(icon, 24.dp) }

/** The editor's explanation sentence with fixed values: subscribed, group and challenge rules. */
@Composable
fun RuleSentence(rule: Rule) {
    val S = LocalStrings.current
    val color = if (rule.isAcc) Px.Lime else Px.Orange
    CompositionLocalProvider(LocalGapColor provides color) {
        val fixed: @Composable (String) -> Unit = { GapBox { Text(it, style = gapStyle(color)) } }
        val pct = if (rule.mode == "exponential") Math.round(rule.param * 100).toDouble() else 70.0
        val gaps = RuleGaps(
            icon = { GapBox { PixelIcon(rule.icon, 24.dp) } },
            name = { fixed(rule.name) },
            base = { fixed(numText(rule.base)) },
            mode = { fixed(S.modes.firstOrNull { it.first == rule.mode }?.second ?: rule.mode) },
            param = { fixed(numText(rule.param)) },
            pct = { fixed(numText(pct.toDouble())) },
            min = { fixed(numText(rule.minValue)) },
            reset = { fixed(S.resetSpan(rule.resetDays, rule.resetUnit)) },
        )
        Cloze { S.ruleSentence(this, rule.isAcc, rule.mode, rule.resetDays, gaps) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preview(caption: String, values: List<Pair<String?, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x4D000000))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(caption, style = T.small)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            values.forEachIndexed { i, (top, v) ->
                if (i > 0) Text("›", style = T.block.copy(color = Color(0x40FFFFFF), fontSize = 14.sp),
                    modifier = Modifier.align(Alignment.Bottom))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (top != null) Text(top, style = T.small.copy(fontSize = 10.sp))
                    Text(v, style = T.block.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal))
                }
            }
        }
    }
}

@Composable
private fun SheetBody(gapColor: Color, top: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGapColor provides gapColor) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to top, 0.4f to Px.Sheet))
                // long sentences / icon grid / keyboard: the sheet scrolls instead of cutting off
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

// ---------------------------------------------------------------- rules

private val RESETS = listOf(0, 1, 2, 3, 5, 7, 10, 14, 30)

private data class RuleDraft(
    val kind: String, val name: String, val icon: String, val mode: String, val base: Double,
    val param: Double, val minValue: Double, val resetDays: Int, val pct: Double, val public: Boolean = false,
    val idleDays: Int = 0, val idleRuleId: Int? = null, val tappable: Boolean = true,
    val resetUnit: String = "d", val idleUnit: String = "d",          // "d" days or "h" hours
)

private val IDLE_DAYS = listOf(0, 1, 2, 3, 5, 7, 14, 30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditor(
    vm: AppViewModel, rule: Rule?, kind: String, onClose: () -> Unit,
    /** custom target (group/challenge rule) instead of the personal rules */
    submit: (suspend (kotlinx.serialization.json.JsonObject) -> Boolean)? = null,
    onDelete: (suspend () -> Boolean)? = null,
    title: String? = null,
    /** group rules: may be public (others subscribe to it) */
    publicToggle: Pair<String, String>? = null,
) {
    val personal = submit == null
    val S = LocalStrings.current
    val acc = kind == "acc"
    var d by remember {
        mutableStateOf(
            rule?.let {
                RuleDraft(it.kind, it.name, it.icon, it.mode, it.base, it.param, it.minValue, it.resetDays,
                    if (it.mode == "exponential") Math.round(it.param * 100).toDouble() else 70.0, it.public,
                    it.idleDays, it.idleRuleId, it.tappable, it.resetUnit, it.idleUnit)
            } ?: RuleDraft(kind, "", if (acc) "star" else "storm", "constant", 50.0, 0.0, 0.0, 0, 70.0),
        )
    }
    var showIcons by remember { mutableStateOf(false) }
    var pickEmoji by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val icons = vm.meta?.icons.orEmpty()

    fun setMode(mode: String) {
        var n = d.copy(mode = mode)
        if (mode == "linear" && n.param == 0.0) n = n.copy(param = maxOf(1.0, Math.round(n.base / 4).toDouble()))
        if (mode != "constant" && n.minValue == 0.0) n = n.copy(minValue = maxOf(1.0, Math.round(n.base / 5).toDouble()))
        if (mode != "constant" && n.resetDays == 0 && rule == null) n = n.copy(resetDays = 3)
        d = n
    }

    val gaps = RuleGaps(
        icon = { IconGap(d.icon) { showIcons = !showIcons } },
        name = { TextGap(d.name, S.namePlaceholder(acc)) { d = d.copy(name = it) } },
        base = { NumGap(d.base) { d = d.copy(base = it) } },
        mode = { SelectGap(d.mode, S.modes, ::setMode) },
        param = { NumGap(d.param) { d = d.copy(param = it) } },
        pct = { NumGap(d.pct) { d = d.copy(pct = it) } },
        min = { NumGap(d.minValue) { d = d.copy(minValue = it) } },
        reset = { TimeGap(d.resetDays, d.resetUnit, RESETS, S::resetSpan) { n, u -> d = d.copy(resetDays = n, resetUnit = u) } },
    )

    SheetBody(if (acc) Px.Lime else Px.Orange, if (acc) Color(0xFF17302C) else Color(0xFF33172A)) {
        Text(title ?: if (rule != null) S.edit else if (acc) S.newAcc else S.newDec, style = T.title.copy(fontSize = 20.sp))

        Cloze { S.ruleSentence(this, acc, d.mode, d.resetDays, gaps) }

        if (personal) {
            // optional: left untapped for a while -> a rule of the other kind is tapped automatically
            val counterparts = vm.state?.rules.orEmpty().filter { it.kind != kind && it.challengeId == null && it.id > 0 }
            Label(S.idleTitle(acc).uppercase())
            if (counterparts.isEmpty()) {
                Text(S.idleNoCounterpart(acc), style = T.small)
            } else {
                val target = counterparts.firstOrNull { it.id == d.idleRuleId } ?: counterparts.first()
                Cloze {
                    S.idleSentence(this, d.idleDays > 0,
                        days = { TimeGap(d.idleDays, d.idleUnit, IDLE_DAYS, S::idleSpan) { n, u ->
                            d = d.copy(idleDays = n, idleUnit = u, idleRuleId = d.idleRuleId ?: target.id) } },
                        rule = {
                            CompositionLocalProvider(LocalGapColor provides if (acc) Px.Orange else Px.Lime) {
                                SelectGap(target.id, counterparts.map { it.id to it.name }) { id -> d = d.copy(idleRuleId = id) }
                            }
                        })
                }
                // pausing doesn't lose the setting (also possible for subscribed rules, in their details)
                val saved = rule?.let { r -> vm.state?.rules?.firstOrNull { it.id == r.id } }
                if (saved != null && saved.idleDays > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(S.idlePause, style = T.body)
                        Text(S.idlePauseHint, style = T.small.copy(fontSize = 12.sp))
                    }
                    androidx.compose.material3.Switch(saved.idlePaused, { on ->
                        scope.launch { vm.save("PUT", "/rules/${saved.id}/idle-pause", buildJsonObject { put("paused", on) }, quiet = true) }
                    }, colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
                    ))
                }
            }
        }

        if (showIcons) {
            // its own scroll area of a fixed height: the drag scrolls the icons first instead of fighting the
            // sheet's scroll / drag-to-close (report #26: scrolling through the icons got stuck)
            val gap = LocalGapColor.current
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(52.dp),
                Modifier.fillMaxWidth().height(292.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // any emoji as the icon (pixel art like the sprites)
                item(key = "emoji") {
                    val own = Emoji.isEmojiIcon(d.icon)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (own) gap.copy(alpha = .25f) else Px.Faint)
                            .clickable { pickEmoji = true },
                        contentAlignment = Alignment.Center,
                    ) { PixelIcon(if (own) d.icon else "😀", 32.dp) }
                }
                items(icons.size, key = { icons[it] }) { k ->
                    val name = icons[k]
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (name == d.icon) gap.copy(alpha = .25f) else Px.Faint)
                            .clickable { d = d.copy(icon = name); showIcons = false },
                        contentAlignment = Alignment.Center,
                    ) { PixelIcon(name, 32.dp) }
                }
            }
        }

        if (pickEmoji) EmojiPicker(onPick = { d = d.copy(icon = it); showIcons = false }, onDismiss = { pickEmoji = false })

        val sign = if (acc) "+" else "−"
        val steps = generateSequence(d.base) {
            nextValue(d.mode, it, if (d.mode == "exponential") d.pct / 100 else d.param, d.minValue)
        }.take(6).map { null to sign + fmtVal(com.momentum.app.applied(kind, it)) }.toList()
        Preview(S.previewRule, steps)

        // a rule that only counts through the automatic tap (subscribers keep that too)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(S.tappableSetting, style = T.body)
                Text(S.tappableHint, style = T.small)
            }
            androidx.compose.material3.Switch(
                d.tappable, { d = d.copy(tappable = it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
                ),
            )
        }

        val publicLabels = if (personal) S.showOnProfile to S.showOnProfileHint else publicToggle
        if (publicLabels != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(publicLabels.first, style = T.body)
                Text(publicLabels.second, style = T.small)
            }
            androidx.compose.material3.Switch(
                d.public, { d = d.copy(public = it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
                ),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rule != null && (personal || onDelete != null)) {
                PxButton(if (confirmDelete) S.reallyDelete else S.delete, style = BtnStyle.Danger) {
                    if (!confirmDelete) confirmDelete = true
                    else scope.launch {
                        if (onDelete?.invoke() ?: vm.save("DELETE", "/rules/${rule.id}")) onClose()
                    }
                }
            }
            PxButton(S.save, Modifier.weight(1f)) {
                if (d.name.isBlank()) {
                    vm.showToast(S.nameMissing, error = true)
                    return@PxButton
                }
                val body = buildJsonObject {
                    put("kind", d.kind); put("name", d.name.trim()); put("icon", d.icon); put("mode", d.mode)
                    put("base", d.base); put("param", if (d.mode == "exponential") d.pct / 100 else d.param)
                    put("min_value", d.minValue); put("reset_days", d.resetDays); put("reset_unit", d.resetUnit); put("public", d.public)
                    put("tappable", d.tappable)
                    if (personal) {
                        val counterparts = vm.state?.rules.orEmpty().filter { it.kind != kind && it.challengeId == null && it.id > 0 }
                        val target = counterparts.firstOrNull { it.id == d.idleRuleId } ?: counterparts.firstOrNull()
                        put("idle_days", if (target != null) d.idleDays else 0); put("idle_unit", d.idleUnit)
                        put("idle_rule_id", if (d.idleDays > 0) target?.id else null)
                    }
                }
                scope.launch {
                    val ok = submit?.invoke(body)
                        ?: vm.save(if (rule != null) "PUT" else "POST", if (rule != null) "/rules/${rule.id}" else "/rules", body)
                    if (ok) onClose()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- daily decay

@Composable
fun DecayEditor(
    vm: AppViewModel, decay: Decay, onClose: () -> Unit,
    /** a group decay instead of my own (then with a name) */
    submit: (suspend (kotlinx.serialization.json.JsonObject) -> Boolean)? = null,
    title: String? = null, name: String? = null, onDelete: (suspend () -> Boolean)? = null,
) {
    val S = LocalStrings.current
    var named by remember { mutableStateOf(name ?: "") }
    var public by remember { mutableStateOf(decay.public) }
    var confirmDelete by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf(decay.base) }
    var threshold by remember { mutableStateOf(decay.threshold) }
    var mode by remember { mutableStateOf(decay.catalystMode) }
    var a by remember { mutableStateOf(decay.catalystA) }
    var b by remember { mutableStateOf(if (decay.catalystMode == "exponential") decay.catalystB else 1.5) }
    var pct by remember { mutableStateOf(if (decay.catalystMode == "inverse") Math.round(decay.catalystB * 100).toDouble() else 70.0) }
    val scope = rememberCoroutineScope()

    val gaps = DecayGaps(
        icon = { PixelIcon("calendar", 24.dp) },
        base = { NumGap(base) { base = it } },
        threshold = { NumGap(threshold) { threshold = it } },
        mode = {
            SelectGap(mode, S.catalysts) {
                if (it != "none" && a == 0.0) a = 25.0
                mode = it
            }
        },
        a = { NumGap(a) { a = it } },
        b = { NumGap(b) { b = it } },
        pct = { NumGap(pct) { pct = it } },
    )

    SheetBody(Px.Orange, Color(0xFF33172A)) {
        Text(title ?: S.dailyDecay, style = T.title.copy(fontSize = 20.sp))
        if (name != null) PxTextField(named, { named = it.take(40) }, placeholder = S.decayName)
        Cloze { S.decaySentence(this, mode, gaps) }

        val bb = if (mode == "inverse") pct / 100 else b
        // first column: a good day (only the base), then 1..4 missed days in a row
        Preview(S.previewDecay, listOf("✓" to "−" + fmtVal(com.momentum.app.applied("dec", base))) +
            (1..4).map { n ->
                "$n×" to "−" + fmtVal(com.momentum.app.applied("dec", base + if (mode == "none") 0.0 else catalyst(mode, a, bb, n)))
            })

        // my own decay: public = in my profile, others may follow; a group's: others outside may
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (submit == null) S.decayPublic else S.groupPublic, style = T.body)
                Text(if (submit == null) S.decayPublicHint else S.groupRulePublicHint, style = T.small)
            }
            androidx.compose.material3.Switch(public, { public = it }, colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
            ))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onDelete != null) PxButton(if (confirmDelete) S.reallyDelete else S.delete, style = BtnStyle.Danger) {
                if (!confirmDelete) confirmDelete = true else scope.launch { if (onDelete()) onClose() }
            }
            PxButton(S.save, Modifier.weight(1f)) {
                if (name != null && named.isBlank()) { vm.showToast(S.nameMissing, error = true); return@PxButton }
                val body = buildJsonObject {
                    put("base", base); put("threshold", threshold); put("catalyst_mode", mode)
                    put("catalyst_a", a); put("catalyst_b", bb); put("public", public)
                    if (name != null) put("name", named.trim())
                }
                scope.launch { if (submit?.invoke(body) ?: vm.save("PUT", "/decay", body)) onClose() }
            }
        }
    }
}


/** The decay's explanation with fixed values (a decay seen from outside). */
@Composable
fun DecaySentence(d: Decay) {
    val S = LocalStrings.current
    CompositionLocalProvider(LocalGapColor provides Px.Orange) {
        val fixed: @Composable (String) -> Unit = { GapBox { Text(it, style = gapStyle(Px.Orange)) } }
        val gaps = DecayGaps(
            icon = { PixelIcon("calendar", 24.dp) }, base = { fixed(numText(d.base)) }, threshold = { fixed(numText(d.threshold)) },
            mode = { fixed(S.catalysts.firstOrNull { it.first == d.catalystMode }?.second ?: d.catalystMode) },
            a = { fixed(numText(d.catalystA)) }, b = { fixed(numText(d.catalystB)) },
            pct = { fixed(numText(Math.round(d.catalystB * 100).toDouble())) },
        )
        Cloze { S.decaySentence(this, d.catalystMode, gaps) }
    }
}

/** "−50 › −75 › ..." for a good day and 1..4 missed days in a row. */
fun decaySteps(d: Decay): String = (listOf(d.base) + (1..4).map { n ->
    d.base + if (d.catalystMode == "none") 0.0 else catalyst(d.catalystMode, d.catalystA, d.catalystB, n)
}).joinToString("  ›  ") { "−" + fmtVal(com.momentum.app.applied("dec", it)) }
