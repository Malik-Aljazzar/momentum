package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.applied
import com.momentum.app.data.Conversation
import com.momentum.app.data.DecayDetail
import com.momentum.app.data.DecayDetailResponse
import com.momentum.app.data.DecaysResponse
import com.momentum.app.fmtVal
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which shared decay: a person's public one or one of a group's. */
sealed interface DecayRef {
    data class User(val username: String) : DecayRef
    data class Group(val id: Int) : DecayRef
}

fun DecayDetail.ref(): DecayRef = if (kind == "group" && id != null) DecayRef.Group(id) else DecayRef.User(username ?: "")

@Composable
fun DecayIconBox(size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(Px.Orange.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
        PixelIcon("calendar", size * .55f)
    }
}

private fun lossText(base: Double) = "−" + fmtVal(applied("dec", base))

/** A shared decay in a list (profile, group, challenge). */
@Composable
fun DecayRow(d: DecayDetail, trailing: String? = null, onClick: () -> Unit) {
    val S = LocalStrings.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        DecayIconBox()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (d.kind == "group") d.name else S.decayOf(d.username ?: "?"), style = T.bodyStrong, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(trailing ?: decaySteps(d.asDecay()), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (d.following) { Label("✓", color = Px.Lime); Spacer(Modifier.width(8.dp)) }
        Text(lossText(d.base), style = T.block.copy(fontSize = 16.sp, color = Px.Orange))
    }
}

/**
 * A shared decay seen from outside: its values, who it belongs to, follow (it replaces my own) or stop.
 * [inGroup]: opened from the group overview, where admins edit and remove the group's decays.
 */
@Composable
fun DecayDetailSheet(vm: AppViewModel, ref: DecayRef, onDismiss: () -> Unit, inGroup: Boolean = false,
                     onEdit: ((DecayDetail) -> Unit)? = null, onChanged: () -> Unit = {}) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<DecayDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var toGroup by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(ref, reload) {
        detail = vm.req<DecayDetailResponse>("GET", when (ref) {
            is DecayRef.User -> "/decays/user/${ref.username}"
            is DecayRef.Group -> "/decays/${ref.id}"
        })?.decay
        loaded = true
    }
    PxSheet(onDismiss) {
        val d = detail
        if (d == null) {
            if (loaded) { if (!vm.online) OfflineState() else EmptyHint(S.notAvailable, icon = "calendar") }
            else Box(Modifier.fillMaxWidth().height(160.dp))
            return@PxSheet
        }
        val group = d.kind == "group"
        Row(verticalAlignment = Alignment.CenterVertically) {
            DecayIconBox(56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(S.dailyDecay)
                Text(if (group) d.name else S.decayOf(d.username ?: "?"), style = T.title.copy(fontSize = 22.sp, lineHeight = 26.sp))
            }
            Text(lossText(d.base), style = T.block.copy(fontSize = 24.sp, color = Px.Orange))
        }
        // whose it is: the group (its name stays hidden for private groups) or the person
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Px.Faint)
                .clickable(enabled = if (group) d.groupName != null && d.groupId != null else d.owner != null) {
                    onDismiss()
                    if (group) d.groupId?.let { nav.push(Route.GroupInfo(it)) }
                    else d.owner?.let { nav.push(Route.UserProfile(it.username)) }
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group) Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Px.Faint), contentAlignment = Alignment.Center) {
                PixelIcon("ui_group", 18.dp, tint = Px.Cyan)
            } else d.owner?.let { Avatar(it.displayName, it.avatar, 32.dp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (group) (d.groupName?.let(S::groupDecayOf) ?: S.groupDecays) + (d.linkedFrom?.let { " · ${S.followsDecayOf(it)}" } ?: "")
                    else if (d.isMine) S.publicDecay else S.decayOf(d.username ?: "?"), style = T.bodyStrong)
                Text(S.subscribers(d.followers), style = T.small)
            }
        }
        DecaySentence(d.asDecay())
        Text(S.previewDecay, style = T.small)
        Text(decaySteps(d.asDecay()), style = T.block.copy(fontSize = 14.sp))
        if (d.following) Text(S.decayFollowing, style = T.small.copy(color = Px.Cyan))
        if (group && d.isMine) Text(S.ownDecayInGroup, style = T.small.copy(color = Px.Cyan))
        if (d.membersOnly) Text(S.onlyMembersCanSubscribe(d.groupName), style = T.small.copy(color = Px.Yellow))
        val locked = d.lockedBy
        if (locked != null && (d.following || d.canFollow)) Text(S.decayLockedBy(locked), style = T.small.copy(color = Px.Yellow))
        if (locked == null) when {
            d.canFollow -> {
                Text(S.followDecayHint, style = T.small)
                PxButton(S.followDecay, Modifier.fillMaxWidth()) {
                    scope.launch {
                        val body = buildJsonObject { if (group) put("decay_id", d.id ?: 0) else put("username", d.username ?: "") }
                        if (vm.save("POST", "/decay/follow", body, quiet = true)) { vm.showToast(S.subscribed); reload++ }
                    }
                }
            }
            d.following -> PxButton(S.unsubscribe, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
                scope.launch { if (vm.save("DELETE", "/decay/follow", quiet = true)) reload++ }
            }
        }
        // public decays can go into groups (they stay linked to the original)
        if (d.public && (!group || d.groupName != null)) {
            PxButton(S.subscribeForGroup, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { toGroup = true }
        }
        if (onEdit != null && d.editable) PxButton(S.edit, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { onEdit(d) }
        if (inGroup && group && d.removable && d.groupId != null && d.id != null) {
            PxButton(if (confirmRemove) S.reallyRemoveFromGroup else S.removeFromGroup, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
                if (!confirmRemove) confirmRemove = true
                else scope.launch {
                    if (vm.call("DELETE", "/conversations/${d.groupId}/decays/${d.id}")) { onChanged(); vm.refresh(); onDismiss() }
                }
            }
        }
    }
    if (toGroup) detail?.let { d ->
        GroupPicker(vm, S.subscribeForGroup, onDismiss = { toGroup = false }) { c: Conversation ->
            toGroup = false
            scope.launch {
                val body = buildJsonObject { if (d.kind == "group") put("from_decay", d.id ?: 0) else put("from_user", d.username ?: "") }
                if (vm.req<DecayDetailResponse>("POST", "/conversations/${c.id}/decays", body) != null) vm.showToast(S.subscribed)
            }
        }
    }
}

/** The group's decays (group overview, rules tab): admins create them; members follow one. */
@Composable
fun GroupDecaysSection(vm: AppViewModel, c: Conversation, reloadKey: Int) {
    val S = LocalStrings.current
    var decays by remember { mutableStateOf<List<DecayDetail>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<DecayDetail?>(null) }
    var editing by remember { mutableStateOf<DecayDetail?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(c.id, reloadKey, reload) {
        decays = vm.req<DecaysResponse>("GET", "/conversations/${c.id}/decays")?.decays.orEmpty()
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(S.groupDecays.uppercase(), Modifier.weight(1f))
            if (c.isAdmin) IconBtn("ui_plus", alpha = .8f) { creating = true }
        }
        if (decays.isEmpty()) Text(S.noGroupDecays, style = T.small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        decays.forEach { d -> DecayRow(d) { open = d } }
    }
    open?.let { d ->
        DecayDetailSheet(vm, d.ref(), onDismiss = { open = null; reload++ }, inGroup = true,
            onEdit = { e -> open = null; editing = e }, onChanged = { reload++ })
    }
    if (creating || editing != null) {
        val e = editing
        EditorSheet(onDismiss = { creating = false; editing = null }) { close ->
            DecayEditor(
                vm, e?.asDecay() ?: com.momentum.app.data.Decay(50.0, 0.0, "none", 0.0, 1.5, 0, 0.0, 0.0), close,
                title = if (e == null) S.newGroupDecay else S.groupDecays, name = e?.name ?: "",
                submit = { body ->
                    val path = "/conversations/${c.id}/decays" + (e?.id?.let { "/$it" } ?: "")
                    (vm.req<DecayDetailResponse>(if (e != null) "PUT" else "POST", path, body) != null).also {
                        if (it) { reload++; vm.refresh() }
                    }
                },
                onDelete = e?.id?.let { id ->
                    { vm.call("DELETE", "/conversations/${c.id}/decays/$id").also { if (it) { reload++; vm.refresh() } } }
                },
            )
        }
    }
}
