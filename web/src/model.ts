// The JSON of the Quart API, as is (snake_case). Mirrors android/.../data/Models.kt + Social.kt.
import type { ReaderEnvelope } from "./e2ecrypto"

export interface Vehicle { key: string; label: string; category: string }

export interface Meta {
  vehicles: Vehicle[]
  icons: string[]
  timezones: string[]
  app?: { version_code?: number | null; version_name?: string; update_url?: string }
  /** sign in with Google (server/google.py); google_web: the PWA's redirect flow is set up */
  google_client_id?: string | null
  google_web?: boolean
  /** e-mails with codes work: "forgot password" is offered (server/mail.py) */
  mail?: boolean
}

export interface DailyLimit {
  cap: number; gained: number; remaining: number; average: number; bias: number; players: number
  /** today's average growth so far and the rate it would give tomorrow (report #46) */
  today_average?: number; today_players?: number; next_cap?: number
  day?: string | null; tz?: string
  provisional?: boolean
}

export interface OwnBanners { land?: string | null; air?: string | null; space?: string | null }

export interface UserInfo {
  id?: number; username: string; display_name: string; bio?: string; avatar?: string | null; email?: string | null
  /** signed in with Google: its address (shown only, never a login); has_password false = Google-only account */
  google_email?: string | null; has_password?: boolean
  needs_username?: boolean; private?: boolean; banner?: string | null; banners?: OwnBanners
  /** the address still to confirm: when it's due, and whether the app waits for it now (server/mail.py) */
  email_confirm?: { due: string; required: boolean } | null
  hyperspace?: boolean; ultra?: boolean; followers?: number; following?: number
}

export interface Unread {
  messages: number; notifications: number; latest_message_id?: number; latest_notification_id?: number
}

export interface VehicleInfo {
  index: number; next_at?: number | null; reached_at?: number; milestones?: number[]; hyperspace_at?: number
}

export interface UserSettings {
  timezone: string; milestone_step: number; haptics: boolean; show_history: boolean; daily_limit?: boolean
}

export interface Decay {
  base: number; threshold: number; catalyst_mode: string; catalyst_a: number; catalyst_b: number
  streak: number; today_gain: number; tonight: number
  /** others may follow my own decay */
  public?: boolean
  /** null: my own decay; else the followed one (it replaces mine) */
  follow?: DecayFollow | null
  /** a running challenge sets my decay (title) */
  locked_by?: string | null
}

export interface DecayFollow {
  kind: string; label: string; username?: string; decay_id?: number; group_id?: number; name?: string
}

/** A shared decay: a person's public one (kind "user") or a group's (kind "group"). */
export interface DecayDetail {
  kind: string; username?: string; owner?: UserCard | null; id?: number; name?: string
  group_id?: number | null; group_name?: string | null; public?: boolean; linked_from?: string | null; followers?: number
  following?: boolean; can_follow?: boolean; members_only?: boolean; editable?: boolean; removable?: boolean
  is_mine?: boolean; locked_by?: string | null
  base: number; threshold: number; catalyst_mode: string; catalyst_a: number; catalyst_b: number
}

export interface Rule {
  id: number; kind: string; name: string; icon: string; mode: string
  base: number; param: number; min_value: number; reset_days: number; value: number
  /** unit of reset_days / idle_days: "d" days or "h" hours */
  reset_unit?: string; idle_unit?: string
  public?: boolean; client_id?: string | null
  /** own | sub | group | challenge - only "own" is editable */
  origin?: string; origin_label?: string; root_id?: number | null; challenge_id?: number | null
  idle_days?: number; idle_rule_id?: number | null; locked_by?: string | null; idle_paused?: boolean
  /** false: a tap on the card doesn't count (only the automatic tap); copies keep it, the owner decides */
  tappable?: boolean
}

export interface Event {
  id: number; kind: string; label: string; icon: string; delta: number; at: string
  undoable?: boolean; auto_from?: string | null
}

export interface AppState {
  challenge_credit?: number
  unread: Unread
  user: UserInfo
  momentum: number
  ranked_momentum?: number
  free_score?: number
  vehicle: VehicleInfo
  settings: UserSettings
  decay: Decay
  rules: Rule[]
  events: Event[]
  limit: DailyLimit
}

export interface StateResponse {
  state: AppState; delta?: number | null; milestone?: boolean; capped?: boolean; hyperspace?: boolean
}

// ---------------------------------------------------------------- social

export interface UserCard {
  id?: number; username: string; display_name: string; avatar?: string | null; vehicle?: number
  is_me?: boolean; following?: boolean; follows_me?: boolean; friend?: boolean; private?: boolean
}

export interface PublicRule { id: number; kind: string; name: string; icon: string; mode: string; value: number }
export interface HistoryItem { kind: string; label: string; icon: string; delta: number; at: string }

/** A badge in a profile and why it's there: `reporter_king` = the most implemented reports (numbers only). */
export interface ProfileBadge { key: string; count: number; bugs: number; features: number }

export interface Profile extends UserCard {
  bio: string; momentum: number; posts: number; followers_count: number; following_count: number
  badges?: ProfileBadge[]
  next_at?: number | null; milestone_step?: number; reached_at?: number
  rules: PublicRule[]; history: HistoryItem[]; locked?: boolean; requested?: boolean; banner?: string | null
  /** I blocked this account */
  blocked?: boolean
  competing?: boolean; ranked_momentum?: number; free_score?: number
  /** public decay others may follow */
  decay?: DecayDetail | null
  /** active stories (the profile picture's ring) */
  story?: { active: number; unseen: boolean }
}

export interface TextOverlay { text: string; x: number; y: number; color: string; size: number }

export interface MediaItem {
  id: number; kind: string; url: string; thumb: string; width?: number; height?: number; duration?: number
  overlay?: TextOverlay | null
  /** a chat photo: when only its small copy will be left / is left (report #48) */
  expires_at?: string; archived?: boolean
  /** an encrypted chat's photo / voice message: the blobs are ciphertext; key + type come from the message */
  encrypted?: boolean; key?: string; mime?: string
}

export interface CommentItem {
  id: number; text: string; at: string; user: UserCard; can_delete?: boolean; likes?: number; liked?: boolean
  /** the top-level comment this one answers (threads are one level deep) */
  parent_id?: number | null
}

export interface PostSound { url: string; title?: string; duration?: number }

export interface Post {
  sound?: PostSound | null; id: number; kind: string; text?: string; vehicle?: string | null; at: string
  /** where it was (a place name, coordinates if picked from the search) */
  location?: { name: string; lat?: number | null; lon?: number | null } | null
  user: UserCard; media: MediaItem[]; tags: UserCard[]; likes: number; liked: boolean; comments: number
  mine?: boolean; comment_list?: CommentItem[]
  /** rules tapped with the slides (slide = position in media) */
  triggers?: Trigger[]
  /** kind "rule": a rule made public (or changed since: updated) */
  rule?: FeedRule | null
}

export interface FeedRule { id: number; name: string; icon: string; kind: string; mode: string; base: number; updated?: boolean }

/** A rule tapped with a post's slide or a story; rule_id opens its detail (like on the profile). */
export interface Trigger { slide?: number; rule_id: number | null; name: string; icon: string; kind: string; times: number; points?: number }

export interface Story {
  id: number; at: string; expires_at: string; active: boolean; user: UserCard; mine: boolean; seen: boolean
  media: MediaItem; triggers: Trigger[]; sound?: PostSound | null
  /** my own story: how many have seen it */
  views?: number | null
  /** others' stories: may I answer it (a message to the author)? */
  can_reply?: boolean
  /** seconds it runs (photos: 5, 10, 15 or 30; videos: their length) */
  duration?: number
}
export interface StoryRow { user: UserCard; stories: Story[]; unseen: boolean }
export interface Highlight { id: number; title: string; cover?: string | null; stories: Story[] }

export interface ChallengeRef {
  id: number; title: string; conversation_id: number; rank?: number | null; reward?: number; mode?: string
}

export interface NotificationItem {
  id: number; kind: string; actor: UserCard; post_id?: number | null; post_thumb?: string | null
  challenge?: ChallengeRef | null; read?: boolean; at: string
  /** sub_rule / sub_decay (report #54): which rule, and the group that subscribed */
  rule?: { id: number; name: string; icon?: string } | null; group?: { id: number; name: string } | null
}

export interface LastMessage {
  text?: string; has_media?: boolean; mine?: boolean; sender?: string; at: string
  share_kind?: string | null; media_kind?: string | null
  /** a chat line (e2e_on / e2e_off); encrypted: the preview for me (opened in the app), sealed: can't be opened here */
  kind?: string | null; e2e?: ReaderEnvelope | null; sealed?: boolean
  /** member lines: who was added / removed (target_me: me) */
  target?: UserCard | null; target_me?: boolean
}

export interface Conversation {
  id: number; kind: string; name?: string; members: UserCard[]; unread?: number; last?: LastMessage | null
  is_admin?: boolean; banner?: string | null; auto_follow?: boolean; mentioned?: boolean
  public?: boolean; open?: boolean; member?: boolean; muted?: boolean
  avatar?: string | null; banner_image?: string | null; total_momentum?: number
  /** groups: anarchy mode (every member is an admin) and who the admins are */
  anarchy?: boolean; admins?: string[]
  /** who is typing / recording a voice message right now */
  typing?: Typing[]
  /** end-to-end encryption of new messages; may I switch it (direct: both, group: admins) */
  e2e?: boolean; can_switch_e2e?: boolean
}

/** Someone typing ("text") or recording a voice message ("audio") right now. */
export interface Typing { username: string; kind: "text" | "audio" }

export interface ReplyRef {
  id: number; user?: string; mine?: boolean; text?: string; media_kind?: string | null; share_kind?: string | null
  e2e?: ReaderEnvelope | null
}

export interface Share {
  kind: string; available?: boolean; id?: number | null; name?: string; icon?: string; rule_kind?: string
  owner?: string; user?: UserCard | null; post_owner?: UserCard | null; caption?: string; thumb?: string | null
  post_kind?: string; vehicle?: string | null; challenge?: Challenge | null
  media_count?: number; video?: boolean; ratio?: number
}

export interface ChatMessage {
  id: number; text?: string; at: string; user?: UserCard | null; mine?: boolean; media?: MediaItem | null
  share?: Share | null; mentions_me?: boolean; reply?: ReplyRef | null
  /** emojis on the message, most used first */
  reactions?: Reaction[]
  /** encrypted: the envelope for me; sealed: it can't be opened on this device; kind: a chat line (e2e_on / e2e_off) */
  e2e?: ReaderEnvelope | null; sealed?: boolean; kind?: string | null
  /** member lines (member_join / _add / _remove / _leave, report #56): who was added / removed (target_me: me) */
  target?: UserCard | null; target_me?: boolean
}

export interface Reaction { emoji: string; count: number; mine?: boolean }

export interface IdleRuleRef { id: number; name: string; icon?: string; kind?: string }

export interface RuleDetail {
  id: number; kind: string; name: string; icon: string; mode: string; base: number; param: number
  min_value: number; reset_days: number; reset_unit?: string; idle_unit?: string; owner?: UserCard | null; origin?: string; group_id?: number | null
  subscribers?: number; subscribed?: boolean; my_copy_id?: number | null; is_mine?: boolean
  can_subscribe?: boolean; linked_from?: string | null; editable?: boolean; removable?: boolean
  public?: boolean; group_name?: string | null; group_member?: boolean; locked_by?: string | null
  group_image?: string | null; group_banner?: string | null; members_only?: boolean
  /** the same rule another way (own original, via a person or a group): no second subscription */
  have_already?: boolean
  idle_days?: number; idle_rule?: IdleRuleRef | null; idle_paused?: boolean; tappable?: boolean
}

export interface RankEntry { rank: number; momentum?: number; competing?: boolean; user: UserCard; delta?: number }
export interface LeaderboardResponse { entries: RankEntry[]; me?: RankEntry | null; total?: number }

export interface BulkSkip { id: number; reason?: string; challenge?: string | null }
export interface BulkResult { state: AppState; done: number; skipped: BulkSkip[] }

export interface GroupRank {
  rank: number; id: number; name: string; banner?: string | null; members?: number; total?: number; member?: boolean; delta?: number
}
export interface GroupHit {
  id: number; name: string; banner?: string | null; open?: boolean; members?: number; member?: boolean
}

export interface LeaderboardEntry { user?: UserCard | null; score?: number; rank?: number | null; reward?: number }

export interface Challenge {
  id: number; conversation_id: number; title: string; mode: string; status: string; banner?: string | null
  start_day: string; end_day: string; days: number; days_left?: number | null; starts_in?: number | null
  target?: number; rewards?: number[]; creator?: UserCard | null; participants?: number; joined?: boolean
  left?: boolean; my_score?: number | null; my_rank?: number | null; my_reward?: number; can_join?: boolean
  can_cancel?: boolean; keep_rules?: boolean; leaderboard?: LeaderboardEntry[]; rules?: RuleDetail[]
  /** the decay participants follow while they take part */
  decay?: DecayDetail | null
}

export const isOwn = (r: Rule) => (r.origin ?? "own") === "own"
export const isAcc = (r: { kind: string }) => r.kind === "acc"
export const isGroup = (c: Conversation) => c.kind === "group"
export const challengeOver = (c: Challenge) => c.status === "finished" || c.status === "cancelled"

/** How far it is from the current vehicle to the next one (0..1). */
export function vehicleProgress(v: VehicleInfo, momentum: number): number {
  if (v.next_at == null) return 1
  const from = v.reached_at ?? 0
  return v.next_at > from ? (momentum - from) / (v.next_at - from) : 1
}
