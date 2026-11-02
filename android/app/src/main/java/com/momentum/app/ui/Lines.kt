package com.momentum.app.ui

// Texts made from a chat line or an activity (= web/src/lines.ts).

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

/** The text after the actor's name in the activity list (report #54: which rule, which group). */
fun activityText(S: Strings, kind: String, rule: String?, group: String?): String = when (kind) {
    "sub_rule" -> if (group != null) S.subscribedRuleGroup(rule.orEmpty(), group) else S.subscribedRule(rule.orEmpty())
    "sub_decay" -> if (group != null) S.decayInGroup(group) else S.followsDecay
    else -> S.notification(kind)
}
