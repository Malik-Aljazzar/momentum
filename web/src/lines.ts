// Texts made from a chat line or an activity (= android/.../ui/Lines.kt).
import type { CallLine } from "./model"
import type { Strings } from "./strings"

/** A chat line (null: me): who switched the encryption, joined, added, removed or left a group (report #56). */
export function chatLine(s: Strings, kind: string, actor: string | null, target: string | null = null): string {
  switch (kind) {
    case "member_join": return actor == null ? s.youJoined : s.memberJoined(actor)
    case "member_add": return actor == null ? s.youAdded(target ?? "") : target == null ? s.addedYou(actor) : s.memberAdded(actor, target)
    case "member_remove": return actor == null ? s.youRemoved(target ?? "") : target == null ? s.removedYou(actor) : s.memberRemoved(actor, target)
    case "member_leave": return actor == null ? s.youLeft : s.memberLeft(actor)
    case "e2e_on": return "🔒 " + (actor == null ? s.e2eYouSwitchedOn : s.e2eSwitchedOn(actor))
    default: return "🔒 " + (actor == null ? s.e2eYouSwitchedOff : s.e2eSwitchedOff(actor))
  }
}

/** A call's line (= Lines.kt callLine): what it was, how long or why nobody talked (the chat list: with an emoji). */
export function callLine(s: Strings, c: CallLine, emoji = true): string {
  const icon = !emoji ? "" : c.video ? "📹 " : "📞 "
  const what = c.group ? (c.video ? s.groupVideoCall : s.groupVoiceCall) : c.video ? s.videoCall : s.voiceCall
  if (c.duration != null && (c.outgoing || c.joined)) {
    const out = c.outgoing && !c.group ? (c.video ? s.outgoingVideoCall : s.outgoingVoiceCall) : what
    return `${icon}${out} · ${callDuration(c.duration)}`
  }
  if (c.outgoing) {
    const why = c.reason === "declined" ? s.callDeclinedShort : c.reason === "busy" ? s.callBusyShort : s.callNoAnswerShort
    return `${icon}${what} · ${why}`
  }
  return icon + (c.group ? s.missedGroupCall : c.video ? s.missedVideoCall : s.missedVoiceCall)
}

/** 0:42, 12:03, 1:04:09 */
export function callDuration(seconds: number): string {
  const sec = Math.max(0, Math.floor(seconds))
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), r = sec % 60
  const two = (n: number) => String(n).padStart(2, "0")
  return h > 0 ? `${h}:${two(m)}:${two(r)}` : `${m}:${two(r)}`
}

/** Missed by me: shown in red. */
export const callMissed = (c: CallLine) => !c.outgoing && !c.joined

/** The text after the actor's name in the activity list. */
export function activityText(s: Strings, n: { kind: string; rule?: { name: string } | null; group?: { name: string } | null }): string {
  if (n.kind === "sub_rule") return n.group ? s.subscribedRuleGroup(n.rule?.name ?? "", n.group.name) : s.subscribedRule(n.rule?.name ?? "")
  if (n.kind === "sub_decay") return n.group ? s.decayInGroup(n.group.name) : s.followsDecay
  return s.notification(n.kind)
}
