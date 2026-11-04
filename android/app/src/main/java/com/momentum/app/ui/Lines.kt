package com.momentum.app.ui

// Texts made from a chat line or an activity (= web/src/lines.ts).

import com.momentum.app.data.CallLine

/** A chat line (null: me): who switched the encryption, joined, added, removed or left a group (report #56). */
fun chatLine(S: Strings, kind: String, actor: String?, target: String? = null): String = when (kind) {
    "member_join" -> if (actor == null) S.youJoined else S.memberJoined(actor)
    "member_add" -> when {
        actor == null -> S.youAdded(target.orEmpty())
        target == null -> S.addedYou(actor)
        else -> S.memberAdded(actor, target)
    }
    "member_remove" -> when {
        actor == null -> S.youRemoved(target.orEmpty())
        target == null -> S.removedYou(actor)
        else -> S.memberRemoved(actor, target)
    }
    "member_leave" -> if (actor == null) S.youLeft else S.memberLeft(actor)
    "e2e_on" -> "🔒 " + (if (actor == null) S.e2eYouSwitchedOn else S.e2eSwitchedOn(actor))
    else -> "🔒 " + (if (actor == null) S.e2eYouSwitchedOff else S.e2eSwitchedOff(actor))
}

/** A call's line (= lines.ts callLine): what it was, how long or why nobody talked (the chat list: with an emoji). */
fun callLine(S: Strings, c: CallLine, emoji: Boolean = true): String {
    val icon = if (!emoji) "" else if (c.video) "📹 " else "📞 "
    val what = if (c.group) (if (c.video) S.groupVideoCall else S.groupVoiceCall) else if (c.video) S.videoCall else S.voiceCall
    if (c.duration != null && (c.outgoing || c.joined)) {
        val out = if (c.outgoing && !c.group) (if (c.video) S.outgoingVideoCall else S.outgoingVoiceCall) else what
        return "$icon$out · ${callDuration(c.duration)}"
    }
    if (c.outgoing) {
        val why = when (c.reason) { "declined" -> S.callDeclinedShort; "busy" -> S.callBusyShort; else -> S.callNoAnswerShort }
        return "$icon$what · $why"
    }
    return icon + if (c.group) S.missedGroupCall else if (c.video) S.missedVideoCall else S.missedVoiceCall
}

/** 0:42, 12:03, 1:04:09 */
fun callDuration(seconds: Int): String {
    val sec = maxOf(0, seconds)
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val r = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/** Missed by me: shown in red. */
fun callMissed(c: CallLine) = !c.outgoing && !c.joined

/** The text after the actor's name in the activity list (report #54: which rule, which group). */
fun activityText(S: Strings, kind: String, rule: String?, group: String?): String = when (kind) {
    "sub_rule" -> if (group != null) S.subscribedRuleGroup(rule.orEmpty(), group) else S.subscribedRule(rule.orEmpty())
    "sub_decay" -> if (group != null) S.decayInGroup(group) else S.followsDecay
    else -> S.notification(kind)
}
