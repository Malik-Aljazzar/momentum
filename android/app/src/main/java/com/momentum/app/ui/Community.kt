package com.momentum.app.ui

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.momentum.app.AppViewModel
import com.momentum.app.applied
import com.momentum.app.data.Challenge
import com.momentum.app.data.ChallengeResponse
import com.momentum.app.data.ChallengesResponse
import com.momentum.app.data.Conversation
import com.momentum.app.data.ConversationResponse
import com.momentum.app.data.ConversationsResponse
import com.momentum.app.data.MessageResponse
import com.momentum.app.data.RuleDetail
import com.momentum.app.data.RuleDetailResponse
import com.momentum.app.data.RuleDetailsResponse
import com.momentum.app.data.Share
import com.momentum.app.data.UserCard
import com.momentum.app.data.RankEntry
import com.momentum.app.data.RankingResponse
import com.momentum.app.data.UsersResponse
import com.momentum.app.fmt
import com.momentum.app.fmtVal
import com.momentum.app.nextValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ---------------------------------------------------------------- shared pieces

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PxSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Px.Sheet,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)))
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun RuleIconBox(icon: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(Px.Faint), contentAlignment = Alignment.Center) {
        PixelIcon(icon, size * .55f)
    }
}

private fun signedValue(kind: String, v: Double) = (if (kind == "acc") "+" else "−") + fmtVal(applied(kind, v))

@Composable
private fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(text.uppercase(), Modifier.weight(1f))
        action?.invoke()
    }
}

/** A row for a rule seen from outside (profile, group, challenge). */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun RuleDetailRow(r: RuleDetail, trailing: String? = null, selected: Boolean? = null, onLongClick: (() -> Unit)? = null,
                  onClick: () -> Unit) {
    val S = LocalStrings.current
    Row(Modifier.fillMaxWidth().background(if (selected == true) Px.Yellow.copy(alpha = .14f) else Color.Transparent)
        .combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (selected != null) {
            PixelCheckbox(selected)
            Spacer(Modifier.width(10.dp))
        }
        RuleIconBox(r.icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing ?: S.modeHint(r.asRule()), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(signedValue(r.kind, r.base), style = T.block.copy(fontSize = 16.sp, color = if (r.isAcc) Px.Lime else Px.Orange))
    }
}

// ---------------------------------------------------------------- rule detail + subscribe

/**
 * Settings of a rule someone else made (or a group/challenge rule): subscribe, unsubscribe, share.
 * [onEdit] is offered for own group rules.
 */
@Composable
fun RuleDetailSheet(vm: AppViewModel, ruleId: Int, onDismiss: () -> Unit, onEdit: ((RuleDetail) -> Unit)? = null,
                    /** opened from the group overview: only there a rule can be taken out of the group */
                    inGroup: Boolean = false) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<RuleDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var sharing by remember { mutableStateOf(false) }
    var addingToGroup by remember { mutableStateOf(false) }
    var copyingToGroup by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(ruleId, reload) {
        detail = vm.req<RuleDetailResponse>("GET", "/rules/$ruleId/detail")?.rule
        loaded = true
    }
    PxSheet(onDismiss) {
        val d = detail
        if (d == null) {
            if (loaded) { if (!vm.online) OfflineState() else EmptyHint(S.notAvailable, icon = "ui_rules") }
            else Box(Modifier.fillMaxWidth().height(160.dp))
            return@PxSheet
        }
        val acc = d.isAcc
        Row(verticalAlignment = Alignment.CenterVertically) {
            RuleIconBox(d.icon, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(if (acc) S.accTag else S.decTag)
                Text(d.name, style = T.title.copy(fontSize = 22.sp, lineHeight = 26.sp))
            }
            Text(signedValue(d.kind, d.base), style = T.block.copy(fontSize = 24.sp, color = if (acc) Px.Lime else Px.Orange))
        }
        val group = d.origin == "group"
        val origin = when {
            // a group rule belongs to the group: its name and picture, not who made it
            group -> (d.groupName?.let(S::groupRuleOf) ?: S.groupRule) + (d.linkedFrom?.let { " · ${S.followsRuleOf(it)}" } ?: "")
            d.isMine -> S.yourRule
            d.origin == "challenge" -> S.challengeRule
            else -> S.ruleBy(d.owner?.username ?: "?")
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Px.Faint)
                .clickable(enabled = (d.owner != null && d.origin == "profile") || (group && d.groupName != null && d.groupId != null)) {
                    onDismiss()
                    if (group) d.groupId?.let { nav.push(Route.GroupInfo(it)) }
                    else d.owner?.let { nav.push(Route.UserProfile(it.username)) }
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group) {
                val shape = RoundedCornerShape(9.dp)
                when {
                    d.groupImage != null -> RemoteImage(vm.abs(d.groupImage), Modifier.size(32.dp).clip(shape))
                    d.groupBanner != null -> BannerBackground(d.groupBanner, Modifier.size(32.dp).clip(shape), still = true)
                    else -> Box(Modifier.size(32.dp).clip(shape).background(Px.Faint), contentAlignment = Alignment.Center) { PixelIcon("ui_group", 18.dp) }
                }
                Spacer(Modifier.width(10.dp))
            } else d.owner?.let { Avatar(it.displayName, it.avatar, 32.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(origin, style = T.bodyStrong)
                if (d.origin != "challenge") Text(S.subscribers(d.subscribers), style = T.small)
            }
        }
        // same explanation as in the editor, only the values can't be changed
        RuleSentence(d.asRule())
        // the values it gives, one tap after another
        val steps = generateSequence(d.base) { nextValue(d.mode, it, d.param, d.minValue) }.take(6).toList()
        Text(S.previewRule, style = T.small)
        Text(steps.joinToString("  ›  ") { signedValue(d.kind, it) }, style = T.block.copy(fontSize = 14.sp))
        if (!d.tappable) Row(verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("ui_notap", 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(S.notTappable, style = T.small)
        }
        if (d.subscribed) Text(if (group) S.subscribedHintGroup else S.subscribedHint(d.owner?.username ?: "?"), style = T.small.copy(color = Px.Cyan))
        if (d.membersOnly) Text(S.onlyMembersCanSubscribe(d.groupName), style = T.small.copy(color = Px.Yellow))
        if (d.haveAlready) Text(S.haveRuleAlready, style = T.small.copy(color = Px.Yellow))
        d.lockedBy?.let { Text(S.lockedByChallenge(it), style = T.small.copy(color = Px.Yellow)) }
        d.idleRule?.let { target ->
            // linked: its automatic tap goes to another rule, which is subscribed along
            Text(S.idleLinked(S.idleSpan(d.idleDays, d.idleUnit), target.name), style = T.small.copy(color = Px.Cyan))
            val copy = vm.state?.rules?.firstOrNull { it.id == d.myCopyId }
            if (copy != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(S.idlePause, style = T.body)
                    Text(S.idlePauseHint, style = T.small.copy(fontSize = 12.sp))
                }
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Switch(copy.idlePaused, { on ->
                    scope.launch { vm.save("PUT", "/rules/${copy.id}/idle-pause", buildJsonObject { put("paused", on) }, quiet = true) }
                }, colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
                    ))
            }
        }
        // my subscription: shown in my profile (the rule and my taps) or not
        vm.state?.rules?.firstOrNull { it.id == d.myCopyId && it.origin != "challenge" }?.let { copy ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(S.inProfile, style = T.body)
                    Text(S.inProfileHint, style = T.small.copy(fontSize = 12.sp))
                }
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Switch(copy.public, { on ->
                    scope.launch {
                        vm.bulk("/rules/visibility-many", buildJsonObject {
                            put("public", on); put("ids", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(copy.id))))
                        })
                    }
                }, colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
                    ))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d.origin != "challenge") {
                PxButton(S.share, Modifier.weight(1f), style = BtnStyle.Ghost) { sharing = true }
            }
            when {
                d.canSubscribe -> PxButton(S.subscribe, Modifier.weight(1f)) {
                    scope.launch {
                        if (vm.save("POST", "/rules/${d.id}/subscribe", quiet = true)) { vm.showToast(S.subscribed); reload++ }
                    }
                }
                d.subscribed && d.myCopyId != null && d.lockedBy == null -> PxButton(S.unsubscribe, Modifier.weight(1f), style = BtnStyle.Danger) {
                    scope.launch { if (vm.save("DELETE", "/rules/${d.myCopyId}")) reload++ }
                }
            }
        }
        // own rules, or public rules of public accounts, can go into groups (they stay linked)
        if (d.origin == "profile" && d.public && (d.isMine || d.owner?.private == false)) {
            PxButton(S.subscribeForGroup, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { addingToGroup = true }
        }
        // my rules and the ones I subscribed to can be copied into a group (a real copy, no link)
        if (d.origin != "challenge" && (d.isMine || d.myCopyId != null)) {
            PxButton(S.copyToGroup, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { copyingToGroup = true }
        }
        if (onEdit != null && d.editable) {
            PxButton(S.edit, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { onEdit(d) }
        }
        // a rule the group follows (or one of its own): take it out of the group
        if (inGroup && d.origin == "group" && d.removable && d.groupId != null) {
            PxButton(if (confirmRemove) S.reallyRemoveFromGroup else S.removeFromGroup, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
                if (!confirmRemove) confirmRemove = true
                else scope.launch {
                    if (vm.call("DELETE", "/conversations/${d.groupId}/rules/${d.id}")) { vm.refresh(); onDismiss() }
                }
            }
        }
    }
    if (addingToGroup) detail?.let { d ->
        GroupPicker(vm, title = S.subscribeForGroup, onDismiss = { addingToGroup = false }) { c ->
            scope.launch {
                if (vm.req<RuleDetailResponse>("POST", "/conversations/${c.id}/rules",
                        buildJsonObject { put("from_rule", d.id) }) != null) {
                    vm.showToast(S.subscribed)
                    addingToGroup = false
                }
            }
        }
    }
    if (copyingToGroup) detail?.let { d ->
        GroupPicker(vm, title = S.copyToGroup, onDismiss = { copyingToGroup = false }) { c ->
            scope.launch {
                if (vm.req<RuleDetailResponse>("POST", "/conversations/${c.id}/rules",
                        buildJsonObject { put("copy_rule", if (d.isMine) d.id else d.myCopyId ?: d.id) }) != null) {
                    vm.showToast(S.copied)
                    copyingToGroup = false
                }
            }
        }
    }
    if (sharing) detail?.let { d ->
        SharePicker(vm, buildJsonObject { put("kind", "rule"); put("id", d.id) }) { sharing = false }
    }
}

// ---------------------------------------------------------------- share into chats

/** Pick a chat to share a rule / profile / post into ({"kind": ..., "id"|"username": ...}). */
@Composable
fun SharePicker(vm: AppViewModel, share: JsonObject, onDismiss: () -> Unit) = SharePicker(vm, listOf(share), onDismiss)

/** Share several items (e.g. selected rules) into chats: one message each. */
@Composable
fun SharePicker(vm: AppViewModel, shares: List<JsonObject>, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var convs by remember { mutableStateOf<List<Conversation>?>(null) }
    var sent by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(Unit) { convs = vm.req<ConversationsResponse>("GET", "/conversations")?.conversations }
    PxSheet(onDismiss) {
        Text(S.shareTo, style = T.title.copy(fontSize = 20.sp))
        if (!vm.online) { OfflineState(); return@PxSheet }
        val list = convs ?: return@PxSheet
        if (list.isEmpty()) EmptyHint(S.noChats)
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(list, key = { it.id }) { c ->
                val done = c.id in sent
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !done) {
                        scope.launch {
                            val sentAll = shares.all { share ->
                                vm.req<MessageResponse>("POST", "/conversations/${c.id}/messages",
                                    buildJsonObject { put("share", share) }) != null
                            }
                            if (sentAll) {
                                sent = sent + c.id
                                vm.showToast(S.shared)
                            }
                        }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConversationAvatar(c, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    ConversationName(c, Modifier.weight(1f))
                    Text(if (done) "✓" else "↗", style = T.block.copy(fontSize = 16.sp, color = if (done) Px.Lime else Px.Muted))
                }
            }
        }
    }
}

/** A shared item inside a chat bubble. */
@Composable
fun ShareCard(vm: AppViewModel, share: Share, onOpenRule: (Int) -> Unit) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val mod = Modifier.widthIn280().clip(RoundedCornerShape(14.dp)).background(Color(0x22000000))
    if (!share.available) {
        Row(mod.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("ui_lock", 16.dp, alpha = .6f)
            Spacer(Modifier.width(8.dp))
            Text(S.notAvailable, style = T.small)
        }
        return
    }
    when (share.kind) {
        "rule" -> Row(mod.clickable { share.id?.let(onOpenRule) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RuleIconBox(share.icon, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Label(S.sharedRule.uppercase())
                Text(share.name, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(S.ruleBy(share.owner), style = T.small)
            }
        }
        "profile" -> share.user?.let { u ->
            Row(mod.clickable { nav.push(Route.UserProfile(u.username)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(u.displayName, u.avatar, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Label(S.sharedProfile.uppercase())
                    Text(u.username, style = T.bodyStrong)
                }
            }
        }
        // a shared post as its own card, like on Instagram: author, the picture full width, caption, "view post"
        "post" -> Column(
            Modifier.width(250.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF14161F))
                .clickable { share.id?.let { nav.push(Route.PostDetail(it)) } },
        ) {
            share.postOwner?.let { u ->
                Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.displayName, u.avatar, 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(u.username, style = T.bodyStrong.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(if (share.postKind == "milestone") 1.5f else share.ratio.toFloat().coerceIn(.8f, 1.91f))
                .background(Color(0xFF0B0C12))) {
                when {
                    share.postKind == "milestone" -> MilestoneTile(vm, share.vehicle.orEmpty(), Modifier.matchParentSize(), dpPerPixel = 2f)
                    share.thumb != null -> RemoteImage(vm.abs(share.thumb), Modifier.matchParentSize())
                    else -> Box(Modifier.matchParentSize().padding(14.dp), contentAlignment = Alignment.Center) {
                        Text(share.caption, style = T.body.userText(), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (share.video) PixelIcon("ui_video", 16.dp, Modifier.align(Alignment.TopEnd).padding(8.dp))
                else if (share.mediaCount > 1) PixelIcon("ui_grid", 14.dp, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (share.caption.isNotBlank() && share.thumb != null || share.postKind == "milestone" && share.caption.isNotBlank()) {
                Text(
                    buildAnnotatedString {
                        share.postOwner?.let { withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(it.username + "  ") } }
                        append(share.caption)
                    },
                    style = T.small.copy(color = Px.Text).userText(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
                )
            }
            Text(S.viewPost + " ›", style = T.small.copy(fontSize = 12.sp, color = Px.Muted),
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp))
        }
        "challenge" -> share.challenge?.let { c -> ChallengeMini(c) { nav.push(Route.ChallengeDetail(c.id)) } }
        // a shared story: the author, its picture upright, "view story" (report #33)
        "story" -> {
            val scope = rememberCoroutineScope()
            Column(
                Modifier.width(140.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF14161F))
                    .clickable { share.id?.let { scope.launch { openSharedStory(vm, it) } } },
            ) {
                share.user?.let { u ->
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.displayName, u.avatar, 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(u.username, style = T.bodyStrong.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(share.ratio.toFloat().coerceIn(.5f, 1f)).background(Color(0xFF0B0C12))) {
                    share.thumb?.let { RemoteImage(vm.abs(it), Modifier.matchParentSize()) }
                    if (share.video) PixelIcon("ui_video", 16.dp, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
                Text(S.viewStory + " ›", style = T.small.copy(fontSize = 12.sp, color = Px.Muted),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp))
            }
        }
    }
}

private fun Modifier.widthIn280() = this.then(Modifier.width(260.dp))

// ---------------------------------------------------------------- challenges

private fun rewardText(S: Strings, c: Challenge) =
    if (c.isTarget) S.targetLine(fmt(c.target), fmt(c.rewards.firstOrNull() ?: 0.0))
    else S.rewardsLine(c.rewards.map(::fmt))

@Composable
fun ChallengeMini(c: Challenge, onClick: () -> Unit) {
    val S = LocalStrings.current
    Column(
        Modifier.width(260.dp).clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3B2A12), Color(0xFF1B1520))))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("trophy", 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label((S.challenge + " · " + if (c.isTarget) S.modeTarget else S.modeRanking).uppercase(), color = Px.Yellow)
                Text(c.title, style = T.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(S.days(c.days) + " · " + S.challengeStatus(c.status, c.daysLeft, c.startsIn), style = T.small)
        Text(rewardText(S, c), style = T.small.copy(color = Px.Yellow))
    }
}

@Composable
fun ChallengeDetailScreen(vm: AppViewModel, id: Int) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf<Challenge?>(null) }
    var openRule by remember { mutableStateOf<Int?>(null) }
    var openDecay by remember { mutableStateOf<com.momentum.app.data.DecayDetail?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, reload) { c = vm.req<ChallengeResponse>("GET", "/challenges/$id")?.challenge }

    suspend fun act(path: String) {
        vm.req<ChallengeResponse>("POST", "/challenges/$id/$path")?.let { c = it.challenge }
        confirm = null
        vm.refresh()        // challenge rules appear / disappear in the rule lists
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(S.challenge)
        OfflineStrip(vm.online)
        val ch = c ?: run { if (!vm.online) OfflineState(); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 190.dp).clip(CardShape)) {
                    if (ch.banner != null) BannerBackground(ch.banner, Modifier.matchParentSize())
                    else SceneBackground(Scene.Acc, Modifier.matchParentSize())
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelIcon("trophy", 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Label((if (ch.isTarget) S.modeTarget else S.modeRanking).uppercase(), color = Px.Yellow)
                        }
                        FitText(ch.title, T.title, 28.sp, Modifier.fillMaxWidth())
                        Text(S.days(ch.days) + " · " + S.challengeStatus(ch.status, ch.daysLeft, ch.startsIn) + " · " + S.joinedCount(ch.participants),
                            style = T.small.copy(color = Px.Text))
                        Text(rewardText(S, ch), style = T.body.copy(color = Px.Yellow))
                    }
                }
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (ch.isTarget) S.modeTargetHint else S.modeRankingHint, style = T.small)
                    ch.myScore?.let { score ->
                        PxCard(padding = 16.dp) {
                            Label(S.yourScore.uppercase())
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(fmt(score), style = T.block.copy(fontSize = 34.sp))
                                if (ch.isTarget) Text(" / " + fmt(ch.target), style = T.block.copy(fontSize = 16.sp, color = Px.Muted),
                                    modifier = Modifier.padding(bottom = 4.dp))
                                Spacer(Modifier.weight(1f))
                                ch.myRank?.let { if (!ch.isTarget) Text(S.place(it), style = T.block.copy(fontSize = 20.sp, color = Px.Yellow)) }
                            }
                            if (ch.isTarget) {
                                Spacer(Modifier.height(8.dp))
                                SegBar(if (ch.target > 0) score / ch.target else 0.0, 20, if (score >= ch.target) Px.Lime else Px.Yellow)
                            }
                            if (ch.myReward > 0) Text("+" + fmt(ch.myReward) + " Momentum", style = T.bodyStrong.copy(color = Px.Lime))
                        }
                    }
                    if (ch.left) Text(S.leftChallenge, style = T.small.copy(color = Px.Orange))
                    if (ch.joined && !ch.over) Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(S.keepRules, style = T.body)
                            Text(S.keepRulesHint, style = T.small.copy(fontSize = 12.sp))
                        }
                        Spacer(Modifier.width(10.dp))
                        androidx.compose.material3.Switch(ch.keepRules, { on ->
                            scope.launch {
                                vm.req<ChallengeResponse>("PUT", "/challenges/$id/keep-rules", buildJsonObject { put("keep", on) })
                                    ?.let { c = it.challenge }
                            }
                        }, colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                            uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
                        ))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ch.canJoin) PxButton(S.join, Modifier.weight(1f)) { scope.launch { act("join") } }
                        if (ch.joined && !ch.over) {
                            PxButton(if (confirm == "leave") S.reallyLeave else S.leaveChallenge, Modifier.weight(1f), style = BtnStyle.Ghost) {
                                if (confirm != "leave") confirm = "leave" else scope.launch { act("leave") }
                            }
                        }
                        if (ch.canCancel) {
                            PxButton(if (confirm == "cancel") S.reallyCancel else S.cancelChallenge, Modifier.weight(1f), style = BtnStyle.Danger) {
                                if (confirm != "cancel") confirm = "cancel" else scope.launch { act("cancel") }
                            }
                        }
                    }
                }
            }
            if (ch.leaderboard.isNotEmpty()) {
                item { SectionTitle(S.leaderboard) }
                items(ch.leaderboard) { e ->
                    val u = e.user ?: return@items
                    Row(
                        Modifier.fillMaxWidth().background(if (u.isMe) Px.Faint else Color.Transparent)
                            .clickable { nav.push(Route.UserProfile(u.username)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val rank = e.rank ?: 0
                        Text("$rank", style = T.block.copy(fontSize = 16.sp, color = if (rank in 1..3 && !ch.isTarget) Px.Yellow else Px.Muted),
                            modifier = Modifier.width(28.dp))
                        Avatar(u.displayName, u.avatar, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(u.username, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (e.reward > 0) {
                            PixelIcon("trophy", 16.dp)
                            Text(" +" + fmt(e.reward), style = T.small.copy(color = Px.Lime))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(fmt(e.score), style = T.block.copy(fontSize = 16.sp))
                    }
                }
            }
            item { SectionTitle(S.challengeRules) }
            items(ch.rules, key = { it.id }) { r -> RuleDetailRow(r) { openRule = r.id } }
            ch.decay?.let { d ->
                item { SectionTitle(S.dailyDecay) }
                item { DecayRow(d, trailing = S.challengeDecayHint) { openDecay = d } }
            }
            item {
                ch.creator?.let {
                    Text(S.createdBy(it.username), style = T.small, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
    openRule?.let { RuleDetailSheet(vm, it, onDismiss = { openRule = null }) }
    openDecay?.let { DecayDetailSheet(vm, it.ref(), onDismiss = { openDecay = null }) }
}

private const val MAX_CHALLENGE_RULES = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewChallengeScreen(vm: AppViewModel, convId: Int) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("ranking") }
    var days by remember { mutableIntStateOf(7) }
    var startIn by remember { mutableIntStateOf(0) }
    var r1 by remember { mutableStateOf("300") }
    var r2 by remember { mutableStateOf("") }
    var r3 by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("1000") }
    var groupRules by remember { mutableStateOf<List<RuleDetail>>(emptyList()) }
    var pickedGroupRules by remember { mutableStateOf(setOf<Int>()) }
    var groupDecays by remember { mutableStateOf<List<com.momentum.app.data.DecayDetail>>(emptyList()) }
    var pickedDecay by remember { mutableStateOf<Int?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    var pickBanner by remember { mutableStateOf(false) }
    LaunchedEffect(convId) {
        groupRules = vm.req<RuleDetailsResponse>("GET", "/conversations/$convId/rules")?.rules.orEmpty()
        groupDecays = vm.req<com.momentum.app.data.DecaysResponse>("GET", "/conversations/$convId/decays")?.decays.orEmpty()
    }
    val ruleCount = pickedGroupRules.size
    var busy by remember { mutableStateOf(false) }
    val ready = title.isNotBlank() && ruleCount > 0

    fun create() {
        if (!ready) { vm.showToast(S.challengeMissing, error = true); return }
        busy = true
        scope.launch {
            val body = buildJsonObject {
                put("title", title.trim()); put("mode", mode); put("days", days); put("start_in", startIn)
                put("reward1", r1.toIntOrNull() ?: 0)
                if (mode == "ranking") { put("reward2", r2.toIntOrNull() ?: 0); put("reward3", r3.toIntOrNull() ?: 0) }
                else put("target", target.toIntOrNull() ?: 0)
                put("rules", JsonArray(pickedGroupRules.map { buildJsonObject { put("rule_id", it) } }))
                banner?.let { put("banner", it) }
                pickedDecay?.let { put("decay_id", it) }
            }
            vm.req<ChallengeResponse>("POST", "/conversations/$convId/challenges", body)?.let {
                vm.refresh()
                nav.replace(Route.ChallengeDetail(it.challenge.id))
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(S.newChallenge) {
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (ready) Px.Text else Px.Faint)
                    .clickable(enabled = !busy) { create() }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(S.create.uppercase(), style = T.button.copy(color = if (ready) Color.Black else Px.Muted)) }
        }
        if (!vm.online) { OfflineState(); return@Column }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PxTextField(title, { if (it.length <= 60) title = it }, placeholder = S.challengeName)
            Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(14.dp)).clickable { pickBanner = true }) {
                if (banner != null) BannerBackground(banner, Modifier.matchParentSize(), still = true)
                else SceneBackground(Scene.Acc, Modifier.matchParentSize())
                Text(S.banner + ": " + (banner?.let(S::bannerName) ?: S.challenge) + "  ▾", style = T.small.copy(color = Px.Text),
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            ChipRow(listOf("ranking" to S.modeRanking, "target" to S.modeTarget), mode) { mode = it }
            Text(if (mode == "target") S.modeTargetHint else S.modeRankingHint, style = T.small)

            Label(S.duration.uppercase())
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBtn("−") { days = (days - 1).coerceAtLeast(7) }      // at least a week
                Text(S.days(days), style = T.block.copy(fontSize = 18.sp), modifier = Modifier.padding(horizontal = 16.dp))
                StepBtn("+") { days = (days + 1).coerceAtMost(90) }
                Spacer(Modifier.width(12.dp))
                listOf(7, 14, 30, 60).forEach { n ->
                    Text("$n", style = T.small.copy(color = if (n == days) Px.Yellow else Px.Muted),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { days = n }.padding(8.dp))
                }
            }
            Label(S.startLabel.uppercase())
            ChipRow(listOf(0, 1, 2, 7).map { it to S.startOption(it) }, startIn) { startIn = it }

            val limit = vm.state?.limit?.cap ?: 0.0
            val maxReward = (if (limit in 1.0..1e8) days * limit else Double.MAX_VALUE)
                .let { if (mode == "target") minOf(it, target.toDoubleOrNull() ?: 0.0) else it }
            if (maxReward < Double.MAX_VALUE) Text(S.rewardMax(fmt(maxReward)) + " · " + S.challengeCreditHint, style = T.small)
            if (mode == "ranking") {
                NumberField(S.rewardPlace(1), r1) { r1 = it }
                NumberField(S.rewardPlace(2) + " (${S.optional})", r2) { r2 = it }
                if ((r2.toIntOrNull() ?: 0) > 0) NumberField(S.rewardPlace(3) + " (${S.optional})", r3) { r3 = it }
            } else {
                NumberField(S.targetLabel, target) { target = it }
                NumberField(S.rewardLabel, r1) { r1 = it }
            }

            Label(S.challengeRules.uppercase())
            Text(S.challengeOnlyGroupRules, style = T.small)
            if (groupRules.isEmpty()) Text(S.noGroupRules, style = T.small.copy(color = Px.Yellow))
            if (groupRules.isNotEmpty()) {
                groupRules.forEach { r ->
                    val on = r.id in pickedGroupRules
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Px.Faint else Color.Transparent)
                            .clickable {
                                pickedGroupRules = if (on) pickedGroupRules - r.id
                                else if (ruleCount < MAX_CHALLENGE_RULES) pickedGroupRules + r.id else pickedGroupRules
                            }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RuleIconBox(r.icon, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(r.name, style = T.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(signedValue(r.kind, r.base), style = T.block.copy(fontSize = 14.sp, color = if (r.isAcc) Px.Lime else Px.Orange))
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Px.Lime else Px.Faint))
                    }
                }
            }
            if (groupDecays.isNotEmpty()) {
                Label(S.challengeDecay.uppercase())
                Text(S.challengeDecayHint, style = T.small)
                (listOf(null) + groupDecays).forEach { d ->
                    val on = pickedDecay == d?.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Px.Faint else Color.Transparent)
                            .clickable { pickedDecay = d?.id }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (d != null) DecayIconBox(36.dp) else Spacer(Modifier.width(36.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(d?.name ?: S.noDecay, style = T.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d != null) Text("−" + fmtVal(applied("dec", d.base)), style = T.block.copy(fontSize = 14.sp, color = Px.Orange))
                        Spacer(Modifier.width(10.dp))
                        PixelCheckbox(on)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (pickBanner) BannerPicker(banner, allowAuto = false, onDismiss = { pickBanner = false }) { banner = it; pickBanner = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Px.Text else Px.Faint).clickable { onPick(v) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(label.uppercase(), style = T.button.copy(color = if (on) Color.Black else Px.Text)) }
        }
    }
}

@Composable
internal fun StepBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Px.Faint).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, style = T.block.copy(fontSize = 18.sp)) }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = T.small)
        PxTextField(value, { v -> onChange(v.filter(Char::isDigit).take(7)) }, keyboardType = KeyboardType.Number)
    }
}

/** Bottom sheet hosting a RuleEditor (same look as on the rule screens). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Px.Sheet,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)))
        },
    ) { content(close) }
}

// ---------------------------------------------------------------- group overview

enum class GroupSection(val icon: String) { Challenges("ui_cup"), Rules("ui_updown"), Ranking("ui_rules"), Members("ui_group") }

@Composable
internal fun groupSwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
    // disabled (someone else decides): still shows on / off - the row around it is dimmed
    disabledCheckedThumbColor = Px.Lime, disabledCheckedTrackColor = Px.Lime.copy(alpha = .3f), disabledCheckedBorderColor = Color.Transparent,
    disabledUncheckedThumbColor = Px.Muted, disabledUncheckedTrackColor = Px.Faint, disabledUncheckedBorderColor = Color.Transparent,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GroupInfoScreen(vm: AppViewModel, convId: Int) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var conv by remember { mutableStateOf<Conversation?>(null) }
    var rules by remember { mutableStateOf<List<RuleDetail>>(emptyList()) }
    var challenges by remember { mutableStateOf<List<Challenge>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var openRule by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Pair<String, RuleDetail?>?>(null) }     // kind, rule
    var confirmLeave by remember { mutableStateOf(false) }
    var pickBanner by remember { mutableStateOf(false) }
    var addRule by remember { mutableStateOf<String?>(null) }       // menu | follow | copy
    var addMembers by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<UserCard?>(null) }
    var ranking by remember { mutableStateOf<List<RankEntry>>(emptyList()) }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(GroupSection.Challenges) }
    var groupSel by remember { mutableStateOf(setOf<Int>()) }             // long-pressed group rules
    var confirmGroupRemove by remember { mutableStateOf(false) }
    var sharingGroupRules by remember { mutableStateOf<List<JsonObject>?>(null) }
    LaunchedEffect(section) { groupSel = emptySet() }
    androidx.activity.compose.BackHandler(enabled = groupSel.isNotEmpty()) { groupSel = emptySet() }
    var editLook by remember { mutableStateOf(false) }
    var imageFor by remember { mutableStateOf("banner") }
    var groupCrop by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val imagePicker = rememberMediaPicker { uris ->
        val uri = uris.firstOrNull() ?: return@rememberMediaPicker
        scope.launch {
            if (imageFor == "avatar") {
                groupCrop = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeForCrop(context, uri) }
            } else {
                vm.uploadMedia(uri)?.let { m ->
                    vm.req<ConversationResponse>("PUT", "/conversations/$convId", buildJsonObject { put("banner_media", m.id) })
                        ?.let { conv = it.conversation }
                }
            }
        }
    }
    LaunchedEffect(convId, reload, RankPeriod.day) {
        val c = vm.req<ConversationResponse>("GET", "/conversations/$convId")?.conversation
        conv = c
        rules = vm.req<RuleDetailsResponse>("GET", "/conversations/$convId/rules")?.rules.orEmpty()
        challenges = if (c?.member == true) vm.req<ChallengesResponse>("GET", "/conversations/$convId/challenges")?.challenges.orEmpty()
            else emptyList()
        ranking = vm.req<RankingResponse>("GET", "/conversations/$convId/ranking" + if (RankPeriod.day) "?period=today" else "")?.entries.orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(conv?.name ?: S.groupInfo)
        OfflineStrip(vm.online)
        val c = conv ?: run { if (!vm.online) OfflineState(); return@Column }
        val sections = GroupSection.entries.filter { c.member || it != GroupSection.Challenges }
        val current = section.takeIf { it in sections } ?: sections.first()
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                // the group's banner (a pixel banner or an own image), picture, name, total momentum
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(180.dp).clip(CardShape)) {
                    if (c.bannerImage != null) RemoteImage(vm.abs(c.bannerImage), Modifier.matchParentSize())
                    else BannerBackground(c.banner ?: "dusk", Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x99000000)))))
                    Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            val count = c.members.size + if (c.member) 1 else 0
                            Label((S.members(count) + " · " + (if (c.public) S.tagPublic else S.tagPrivate) +
                                if (c.open) " · " + S.tagOpen else "").uppercase(), color = Px.Text)
                            FitText(c.name, T.title, 26.sp, Modifier.fillMaxWidth())
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Label(S.groupTotal, color = Px.Yellow)
                            Text(com.momentum.app.fmtShort(c.totalMomentum), style = T.block.copy(fontSize = 22.sp))
                        }
                    }
                    if (c.isAdmin) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x66000000))) {
                        IconBtn("ui_edit", alpha = .9f) { editLook = true }
                    }
                }
            }
            if (!c.member) item {
                // a public group seen from outside: join if it's open
                Box(Modifier.padding(16.dp)) {
                    if (c.open) PxButton(S.joinGroup, Modifier.fillMaxWidth()) {
                        scope.launch {
                            vm.req<ConversationResponse>("POST", "/conversations/$convId/join")?.let {
                                conv = it.conversation; reload++
                            }
                        }
                    } else Text(S.closedGroup, style = T.small)
                }
            }
            // the areas as tabs, like on profiles
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(Px.Bg).padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        sections.forEach { sec ->
                            val on = sec == current
                            Column(Modifier.weight(1f).clickable { section = sec }.padding(top = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                PixelIcon(sec.icon, 20.dp, alpha = if (on) 1f else .4f)
                                Spacer(Modifier.height(10.dp))
                                Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Px.Text else Color.Transparent))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
                }
            }
            when (current) {
                GroupSection.Challenges -> {
                    item {
                        SectionTitle(S.challenges) {
                            if (c.isAdmin) IconBtn("ui_plus", alpha = .8f) { nav.push(Route.NewChallenge(convId)) }     // admins start challenges
                        }
                    }
                    if (challenges.isEmpty()) item { Text(S.noChallenges, style = T.small, modifier = Modifier.padding(horizontal = 16.dp)) }
                    items(challenges, key = { "c${it.id}" }) { ch ->
                        Row(Modifier.fillMaxWidth().clickable { nav.push(Route.ChallengeDetail(ch.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33FFCD75)), contentAlignment = Alignment.Center) {
                                PixelIcon("trophy", 26.dp, alpha = if (ch.over) .5f else 1f)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ch.title, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(S.challengeStatus(ch.status, ch.daysLeft, ch.startsIn) + " · " + S.joinedCount(ch.participants), style = T.small)
                            }
                            if (ch.joined && !ch.over) Label("✓", color = Px.Lime)
                        }
                    }
                }
                GroupSection.Rules -> {
                    item {
                        SectionTitle(S.groupRules) {
                            if (c.isAdmin) IconBtn("ui_plus", alpha = .8f) { addRule = "menu" }   // create or copy (admins)
                        }
                    }
                    if (c.member) item {
                        // always follow all: every rule of the group is subscribed automatically
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    if (vm.save("PUT", "/conversations/$convId/auto-follow",
                                            buildJsonObject { put("on", !c.autoFollow) }, quiet = true)) {
                                        conv = c.copy(autoFollow = !c.autoFollow)
                                        reload++
                                    }
                                }
                            }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(S.autoFollow, style = T.body)
                                Text(S.autoFollowHint, style = T.small.copy(fontSize = 12.sp))
                            }
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.material3.Switch(c.autoFollow, null, colors = groupSwitchColors())
                        }
                    }
                    if (c.member && rules.any { it.subscribed }) item {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            PxButton(S.unsubscribeAll, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) {
                                scope.launch {
                                    if (vm.save("DELETE", "/conversations/$convId/subscriptions", quiet = true)) {
                                        conv = c.copy(autoFollow = false)
                                        reload++
                                    }
                                }
                            }
                        }
                    }
                    if (rules.isEmpty()) item { Text(S.noGroupRules, style = T.small, modifier = Modifier.padding(horizontal = 16.dp)) }
                    items(rules, key = { "r${it.id}" }) { r ->
                        val picking = groupSel.isNotEmpty()
                        RuleDetailRow(r, trailing = S.subscribers(r.subscribers) + if (r.subscribed) " · ✓" else "",
                            selected = if (picking) r.id in groupSel else null,
                            onLongClick = { groupSel = groupSel + r.id }) {
                            if (picking) groupSel = if (r.id in groupSel) groupSel - r.id else groupSel + r.id else openRule = r.id
                        }
                    }
                    item(key = "decays") { GroupDecaysSection(vm, c, reload) }
                }
                GroupSection.Ranking -> {
                    item { SectionTitle(S.groupRanking) }
                    item { PeriodSwitch(Modifier.padding(bottom = 6.dp)) }
                    items(ranking, key = { "rank${it.user.username}" }) { e -> RankRow(e) { nav.push(Route.UserProfile(e.user.username)) } }
                }
                GroupSection.Members -> {
                    item {
                        SectionTitle(S.membersTitle) {
                            if (c.isAdmin) IconBtn("ui_plus", alpha = .8f) { addMembers = true }
                        }
                    }
                    items(c.members, key = { "m${it.username}" }) { u ->
                        // admins (not in anarchy, where everyone is one) manage the others' admin rights
                        UserRow(u, onClick = { if (c.isAdmin && !c.anarchy) memberMenu = u else nav.push(Route.UserProfile(u.username)) }) {
                            if (u.username in c.admins) Label(S.adminTag.uppercase(), color = Px.Yellow)
                        }
                    }
                    if (c.member && !c.isAdmin) item {
                        Text(S.onlyAdmins, style = T.small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                    if (c.member && c.isAdmin) item {
                        // visibility: public (found in the search) and open (anyone may join, only when public)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Triple(S.groupPublic, S.groupPublicHint, "public"), Triple(S.groupOpen, S.groupOpenHint, "open"),
                                Triple(S.anarchyMode, S.anarchyHint, "anarchy")).forEach { (t, hint, key) ->
                                val on = when (key) { "public" -> c.public; "open" -> c.open; else -> c.anarchy }
                                val enabled = key != "open" || c.public
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (enabled) 1f else .45f)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t, style = T.body)
                                        Text(hint, style = T.small.copy(fontSize = 12.sp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    androidx.compose.material3.Switch(on, { v ->
                                        scope.launch {
                                            vm.req<ConversationResponse>("PUT", "/conversations/$convId", buildJsonObject { put(key, v) })
                                                ?.let { conv = it.conversation }
                                        }
                                    }, enabled = enabled, colors = groupSwitchColors())
                                }
                            }
                        }
                    }
                    // end-to-end encryption (admins switch it) and the members' safety codes
                    if (c.member) item { EncryptionSection(vm, c) { conv = it } }
                    if (c.member) item {
                        Box(Modifier.padding(16.dp)) {
                            PxButton(if (confirmLeave) S.reallyLeave else S.leaveGroup, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
                                if (!confirmLeave) confirmLeave = true
                                else scope.launch {
                                    if (vm.call("DELETE", "/conversations/$convId/members/me")) {
                                        vm.refresh()
                                        nav.pop(); nav.pop()          // group info and the chat itself
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (groupSel.isNotEmpty()) {
        val sel = rules.filter { it.id in groupSel }
        val toSubscribe = sel.filter { it.canSubscribe }
        val toUnsubscribe = sel.filter { it.subscribed && it.myCopyId != null && it.lockedBy == null }
        val toRemove = sel.filter { it.removable }
        SelectionBar(S.selectedCount(sel.size), onClose = { groupSel = emptySet() },
            onAll = { groupSel = rules.map { it.id }.toSet() }) {
            if (toSubscribe.isNotEmpty()) SelectionAction(S.subscribeN(toSubscribe.size)) {
                scope.launch {
                    vm.bulk("/rules/subscribe-many", buildJsonObject { put("ids", JsonArray(toSubscribe.map { kotlinx.serialization.json.JsonPrimitive(it.id) })) })
                        ?.let { vm.showToast(S.bulkResult(it.done, it.skipped.size)); groupSel = emptySet(); reload++ }
                }
            }
            if (toUnsubscribe.isNotEmpty()) SelectionAction(S.unsubscribeN(toUnsubscribe.size)) {
                scope.launch {
                    vm.bulk("/rules/remove-many", buildJsonObject { put("ids", JsonArray(toUnsubscribe.map { kotlinx.serialization.json.JsonPrimitive(it.myCopyId!!) })) })
                        ?.let { vm.showToast(S.bulkResult(it.done, it.skipped.size)); groupSel = emptySet(); reload++ }
                }
            }
            if (toRemove.isNotEmpty()) SelectionAction(S.removeN(toRemove.size), danger = true) { confirmGroupRemove = true }
            // the group's own rules: who may edit them also decides who can see them
            fun visibility(rs: List<RuleDetail>, public: Boolean) = scope.launch {
                vm.bulk("/conversations/$convId/rules/bulk", buildJsonObject {
                    put("action", if (public) "public" else "private")
                    put("ids", JsonArray(rs.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
                })?.let { vm.showToast(S.bulkResult(it.done, it.skipped.size)); groupSel = emptySet(); reload++ }
            }
            val toPublic = sel.filter { it.editable && !it.public }
            val toPrivate = sel.filter { it.editable && it.public }
            if (toPublic.isNotEmpty()) SelectionAction(S.makePublicN(toPublic.size)) { visibility(toPublic, true) }
            if (toPrivate.isNotEmpty()) SelectionAction(S.makePrivateN(toPrivate.size)) { visibility(toPrivate, false) }
            SelectionAction(S.share) { sharingGroupRules = sel.map { buildJsonObject { put("kind", "rule"); put("id", it.id) } } }
        }
        if (confirmGroupRemove) PxSheet({ confirmGroupRemove = false }) {
            Text(S.confirmRemoveFromGroup(toRemove.size), style = T.title.copy(fontSize = 20.sp))
            Text(S.removeFromGroupHint, style = T.small)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PxButton(S.cancel, Modifier.weight(1f), style = BtnStyle.Ghost) { confirmGroupRemove = false }
                PxButton(S.removeN(toRemove.size), Modifier.weight(1f), style = BtnStyle.Danger) {
                    confirmGroupRemove = false
                    scope.launch {
                        vm.bulk("/conversations/$convId/rules/bulk", buildJsonObject {
                            put("action", "remove"); put("ids", JsonArray(toRemove.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
                        })?.let { vm.showToast(S.bulkResult(it.done, it.skipped.size)); groupSel = emptySet(); reload++ }
                    }
                }
            }
        }
    }
    sharingGroupRules?.let { shares -> SharePicker(vm, shares) { sharingGroupRules = null } }
    if (editLook) conv?.let { c ->
        // look of the group: pixel banner, own banner image, group picture
        PxSheet({ editLook = false }) {
            Text(S.groupLook, style = T.title.copy(fontSize = 20.sp))
            PxButton(S.pickPixelBanner, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { editLook = false; pickBanner = true }
            // one own image: the group's banner and its picture in the chat list
            PxButton(S.ownBannerImage, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { editLook = false; imageFor = "banner"; imagePicker.open() }
            if (c.bannerImage != null || c.avatar != null) PxButton(S.removeImages, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
                editLook = false
                scope.launch {
                    vm.req<ConversationResponse>("PUT", "/conversations/$convId", buildJsonObject {
                        put("banner_media", kotlinx.serialization.json.JsonNull); put("avatar_media", kotlinx.serialization.json.JsonNull)
                    })?.let { conv = it.conversation }
                }
            }
        }
    }
    groupCrop?.let { bmp ->
        // the group picture is square, like profile pictures
        CropDialog(bmp, onCancel = { groupCrop = null }) { jpeg ->
            groupCrop = null
            scope.launch {
                vm.uploadBytes(jpeg, "image/jpeg")?.let { m ->
                    vm.req<ConversationResponse>("PUT", "/conversations/$convId", buildJsonObject { put("avatar_media", m.id) })
                        ?.let { conv = it.conversation }
                }
            }
        }
    }
    openRule?.let { id ->
        RuleDetailSheet(vm, id, onDismiss = { openRule = null; reload++ }, onEdit = { d -> openRule = null; editing = d.kind to d },
            inGroup = true)
    }
    if (pickBanner) conv?.let { c ->
        BannerPicker(c.banner, allowAuto = false, onDismiss = { pickBanner = false }) { key ->
            pickBanner = false
            scope.launch {
                vm.req<ConversationResponse>("PUT", "/conversations/$convId", buildJsonObject { put("banner", key) })
                    ?.let { conv = it.conversation }
            }
        }
    }
    memberMenu?.let { u ->
        val c = conv
        PxSheet({ memberMenu = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(u.displayName, u.avatar, 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(u.username, style = T.title.copy(fontSize = 20.sp))
            }
            PxButton(S.viewProfile, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { memberMenu = null; nav.push(Route.UserProfile(u.username)) }
            val admin = c != null && u.username in c.admins
            PxButton(if (admin) S.removeAdmin else S.makeAdmin, Modifier.fillMaxWidth(), style = if (admin) BtnStyle.Danger else BtnStyle.Primary) {
                memberMenu = null
                scope.launch {
                    vm.req<ConversationResponse>("PUT", "/conversations/$convId/admins",
                        buildJsonObject { put("username", u.username); put("admin", !admin) })?.let { conv = it.conversation }
                }
            }
            // report #56: admins remove members
            var confirmKick by remember(u) { mutableStateOf(false) }
            PxButton(if (confirmKick) S.removeMemberAsk(u.username) else S.removeMember, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
                if (!confirmKick) { confirmKick = true; return@PxButton }
                memberMenu = null
                scope.launch {
                    vm.req<ConversationResponse>("DELETE", "/conversations/$convId/members/${android.net.Uri.encode(u.username)}")
                        ?.let { conv = it.conversation }
                }
            }
        }
    }
    if (addMembers) AddMembersSheet(vm, convId, onDismiss = { addMembers = false }) { updated ->
        addMembers = false
        conv = updated
    }
    when (addRule) {
        // three ways into the group: follow a public rule, create one, copy one of mine
        "menu" -> PxSheet({ addRule = null }) {
            Text(S.addGroupRule, style = T.title.copy(fontSize = 20.sp))
            Text(S.followPublicFromRule, style = T.small)
            listOf(Triple(S.createGroupRule, S.createGroupRuleHint, "create"),
                Triple(S.copyRule, S.copyRuleHint, "copy")).forEach { (t, hint, key) ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Px.Faint).clickable {
                        if (key == "create") { addRule = null; editing = "pick" to null } else addRule = key
                    }.padding(14.dp),
                ) {
                    Text(t, style = T.bodyStrong)
                    Text(hint, style = T.small)
                }
            }
        }
        "copy" -> {
            val follow = false
            // any of my rules, own or subscribed (rules it taps automatically are copied along)
            val mine = vm.state?.rules.orEmpty().filter { r -> r.id > 0 && r.origin != "challenge" }
            PxSheet({ addRule = null }) {
                Text(if (follow) S.followPublicRule else S.copyRule, style = T.title.copy(fontSize = 20.sp))
                Text(if (follow) S.followPublicHint else S.copyRuleHint, style = T.small)
                if (mine.isEmpty()) Text(if (follow) S.noPublicRulesForGroup else S.noRulesToCopy, style = T.small)
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(mine, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                                scope.launch {
                                    if (vm.req<RuleDetailResponse>("POST", "/conversations/$convId/rules",
                                            buildJsonObject { put(if (follow) "from_rule" else "copy_rule", r.id) }) != null) {
                                        vm.showToast(if (follow) S.saved else S.copied)
                                        addRule = null
                                        reload++
                                        vm.refresh()
                                    }
                                }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RuleIconBox(r.icon, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!r.isOwn) Text(S.origin(r.origin, r.originLabel), style = T.small.copy(fontSize = 12.sp), maxLines = 1)
                            }
                            Text(signedValue(r.kind, r.base), style = T.block.copy(fontSize = 14.sp, color = if (r.isAcc) Px.Lime else Px.Orange))
                        }
                    }
                }
            }
        }
    }
    if (editing?.first == "pick") PxSheet({ editing = null }) {
        // a new group rule: accelerator or decelerator
        Text(S.createGroupRule, style = T.title.copy(fontSize = 20.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PxButton(S.accTitle, Modifier.weight(1f), style = BtnStyle.Ghost) { editing = "acc" to null }
            PxButton(S.decTitle, Modifier.weight(1f), style = BtnStyle.Ghost) { editing = "dec" to null }
        }
    }
    editing?.takeIf { it.first != "pick" }?.let { (kind, rule) ->
        EditorSheet(onDismiss = { editing = null }) { close ->
            RuleEditor(
                vm, rule?.asRule(), kind, close, title = S.groupRule, publicToggle = S.groupPublic to S.groupRulePublicHint,
                submit = { body ->
                    val path = "/conversations/$convId/rules" + (rule?.let { "/${it.id}" } ?: "")
                    (vm.req<RuleDetailResponse>(if (rule != null) "PUT" else "POST", path, body) != null).also {
                        if (it) { reload++; vm.refresh() }
                    }
                },
                onDelete = rule?.let { r ->
                    {
                        vm.call("DELETE", "/conversations/$convId/rules/${r.id}").also { if (it) { reload++; vm.refresh() } }
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------- follow requests

@Composable
fun FollowRequestsScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserCard>?>(null) }
    LaunchedEffect(Unit) { users = vm.req<UsersResponse>("GET", "/follow-requests")?.users }
    Column(Modifier.fillMaxSize()) {
        TopBar(S.followRequests)
        OfflineStrip(vm.online)
        val list = users ?: run { if (!vm.online) OfflineState(); return@Column }
        if (list.isEmpty()) S.empty("requests").let { (t, sub) -> EmptyHint(t, sub, "ui_lock") }
        LazyColumn {
            items(list, key = { it.username }) { u ->
                UserRow(u, onClick = { nav.push(Route.UserProfile(u.username)) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallBtn(S.accept, primary = true) {
                            scope.launch {
                                if (vm.call("POST", "/follow-requests/${u.username}/accept")) users = users?.filter { it != u }
                            }
                        }
                        SmallBtn(S.decline, primary = false) {
                            scope.launch {
                                if (vm.call("DELETE", "/follow-requests/${u.username}")) users = users?.filter { it != u }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallBtn(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (primary) Px.Text else Px.Faint).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) { Text(text.uppercase(), style = T.button.copy(color = if (primary) Color.Black else Px.Text)) }
}


/** Pick one of my groups. */
@Composable
fun GroupPicker(vm: AppViewModel, title: String? = null, onDismiss: () -> Unit, onPick: (Conversation) -> Unit) {
    val S = LocalStrings.current
    var convs by remember { mutableStateOf<List<Conversation>?>(null) }
    LaunchedEffect(Unit) { convs = vm.req<ConversationsResponse>("GET", "/conversations")?.conversations?.filter { it.isGroup && it.isAdmin } }
    PxSheet(onDismiss) {
        Text(title ?: S.addToGroup, style = T.title.copy(fontSize = 20.sp))
        val list = convs ?: return@PxSheet
        if (list.isEmpty()) S.empty("group").let { (t, sub) -> EmptyHint(t, sub, "ui_group") }
        LazyColumn(Modifier.heightIn(max = 460.dp)) { items(list, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(c) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ConversationAvatar(c, 44.dp)
                Spacer(Modifier.width(12.dp))
                ConversationName(c, Modifier.weight(1f))
            }
        } }
    }
}


/**
 * Add people to a group: same ranking as @mentions (exact name, then people I follow, then followers).
 * Only connected people who aren't members yet are offered.
 */
@Composable
private fun AddMembersSheet(vm: AppViewModel, convId: Int, onDismiss: () -> Unit, onAdded: (Conversation) -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<UserCard>>(emptyList()) }
    var picked by remember { mutableStateOf(listOf<UserCard>()) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) kotlinx.coroutines.delay(150)          // debounce typing
        users = vm.req<UsersResponse>("GET", "/mentions?conv=$convId&q=" +
            java.net.URLEncoder.encode(query.trim().removePrefix("@"), "UTF-8"))?.users.orEmpty()
    }
    PxSheet(onDismiss) {
        Text(S.addMembers, style = T.title.copy(fontSize = 20.sp))
        PxTextField(query, { query = it }, placeholder = S.searchHint)
        // picked people stay visible even when the search no longer matches them
        val shown = picked + users.filter { u -> picked.none { it.username == u.username } }
        if (shown.isEmpty()) Text(if (query.isBlank()) S.noOneToAdd else S.noUsers, style = T.small,
            modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(shown, key = { it.username }) { u ->
                val on = picked.any { it.username == u.username }
                UserRow(u, onClick = { picked = if (on) picked.filter { it.username != u.username } else picked + u }) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Px.Lime else Px.Faint))
                }
            }
        }
        val ready = picked.isNotEmpty() && !sending
        PxButton(if (picked.isEmpty()) S.addMembers else S.addMembersCount(picked.size), Modifier.fillMaxWidth().alpha(if (ready) 1f else .4f)) {
            if (!ready) return@PxButton
            sending = true
            scope.launch {
                vm.req<ConversationResponse>("POST", "/conversations/$convId/members",
                    buildJsonObject { put("usernames", JsonArray(picked.map { kotlinx.serialization.json.JsonPrimitive(it.username) })) })
                    ?.let { onAdded(it.conversation) }
                sending = false
            }
        }
    }
}


/** Top of a selection: "3 ausgewählt", select all, close; the actions below it depend on the selection. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SelectionBar(
    title: String, onClose: () -> Unit, onAll: () -> Unit,
    onHeight: (Int) -> Unit = {},          // px: the list below can make room so its last items stay reachable
    actions: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    val S = LocalStrings.current
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .onGloballyPositioned { onHeight(it.size.height) }
                .padding(start = 12.dp, end = 12.dp, bottom = TAB_BAR_HEIGHT + 12.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xF21B1E2B)).border(1.dp, Px.Line, RoundedCornerShape(20.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn("ui_close", alpha = .8f) { onClose() }
                Text(title, style = T.bodyStrong, modifier = Modifier.weight(1f))
                Text(S.selectAll, style = T.small.copy(color = Px.Cyan), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAll() }.padding(8.dp))
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
fun SelectionAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (danger) Color(0xFFB13E53) else Px.Faint).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label.uppercase(), style = T.button.copy(color = Px.Text))
    }
}
