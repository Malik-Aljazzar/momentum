import { describe, expect, it } from "vitest"
import { activityText, chatLine } from "../../src/lines"
import { DE as de } from "../../src/strings"

describe("group member lines in the chat (report #56)", () => {
  it("names who did it and whom, 'you' for me", () => {
    expect(chatLine(de, "member_add", "Ann", "Ben")).toBe("Ann hat Ben hinzugefügt")
    expect(chatLine(de, "member_add", null, "Ben")).toBe("Du hast Ben hinzugefügt")
    expect(chatLine(de, "member_add", "Ann", null)).toBe("Ann hat dich hinzugefügt")
    expect(chatLine(de, "member_remove", "Ann", "Ben")).toBe("Ann hat Ben entfernt")
    expect(chatLine(de, "member_join", "Dan")).toBe("Dan ist beigetreten")
    expect(chatLine(de, "member_leave", "Dan")).toBe("Dan hat die Gruppe verlassen")
    expect(chatLine(de, "e2e_on", null)).toBe("🔒 Du hast die Ende-zu-Ende-Verschlüsselung eingeschaltet")
  })
})

describe("subscription activity (report #54)", () => {
  it("names the rule and the group", () => {
    expect(activityText(de, { kind: "sub_rule", rule: { name: "Push-ups" } })).toBe("hat deine Regel „Push-ups“ abonniert.")
    expect(activityText(de, { kind: "sub_rule", rule: { name: "Push-ups" }, group: { name: "Gym" } }))
      .toBe("hat deine Regel „Push-ups“ für die Gruppe Gym abonniert.")
    expect(activityText(de, { kind: "sub_decay" })).toBe("folgt jetzt deinem Decay.")
    expect(activityText(de, { kind: "sub_decay", group: { name: "Gym" } })).toBe("hat deinen Decay in die Gruppe Gym übernommen.")
    expect(activityText(de, { kind: "follow" })).toBe("folgt dir jetzt.")
  })
})
