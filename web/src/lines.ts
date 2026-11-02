// Texts made from a chat line or an activity (= android/.../ui/Lines.kt).
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

/** The text after the actor's name in the activity list. */
export function activityText(s: Strings, n: { kind: string; rule?: { name: string } | null; group?: { name: string } | null }): string {
  if (n.kind === "sub_rule") return n.group ? s.subscribedRuleGroup(n.rule?.name ?? "", n.group.name) : s.subscribedRule(n.rule?.name ?? "")
  if (n.kind === "sub_decay") return n.group ? s.decayInGroup(n.group.name) : s.followsDecay
  return s.notification(n.kind)
}
