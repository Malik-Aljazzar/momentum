package com.momentum.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Social API (server/social.py, posts.py, chat.py)

@Serializable
data class UserCard(
    val username: String,
    @SerialName("display_name") val displayName: String,
    val avatar: String? = null,
    val vehicle: Int = 0,
    @SerialName("is_me") val isMe: Boolean = false,
    val following: Boolean = false,
    @SerialName("follows_me") val followsMe: Boolean = false,
    val friend: Boolean = false,
    val private: Boolean = false,
    val id: Int? = null,
)

@Serializable
data class Profile(
    val username: String,
    @SerialName("display_name") val displayName: String,
    val avatar: String? = null,
    val vehicle: Int = 0,
    @SerialName("is_me") val isMe: Boolean = false,
    val following: Boolean = false,
    @SerialName("follows_me") val followsMe: Boolean = false,
    val friend: Boolean = false,
    val bio: String = "",
    val momentum: Double = 0.0,
    val posts: Int = 0,
    @SerialName("followers_count") val followersCount: Int = 0,
    @SerialName("following_count") val followingCount: Int = 0,
    @SerialName("next_at") val nextAt: Double? = null,
    @SerialName("milestone_step") val milestoneStep: Int = 1_000,
    @SerialName("reached_at") val reachedAt: Double = 0.0,
    val rules: List<PublicRule> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val private: Boolean = false,
    /** private account and I'm not an accepted follower: only counts are visible */
    val locked: Boolean = false,
    /** my follow request is waiting for approval */
    val requested: Boolean = false,
    /** I blocked this account */
    val blocked: Boolean = false,
    /** active stories: the profile picture's ring */
    val story: StoryState = StoryState(),
    val banner: String? = null,
    /** takes part in the community competition (daily limit on): `momentum` is ranked */
    val competing: Boolean = true,
    /** One of the Nine: the title keys this player holds (server/nine.py KEYS order) */
    val titles: List<String> = emptyList(),
    @SerialName("ranked_momentum") val rankedMomentum: Double = 0.0,
    @SerialName("free_score") val freeScore: Double = 0.0,
    /** public decay others may follow */
    val decay: DecayDetail? = null,
) {
    fun card() = UserCard(username, displayName, avatar, vehicle, isMe, following, followsMe, friend, private)
}

// ---------------------------------------------------------------- One of the Nine (server/nine.py)

@Serializable
data class NineMine(val value: Double = 0.0, val rank: Int = 0)

@Serializable
data class NineTitle(
    val key: String, val holder: UserCard? = null, val value: Double? = null,
    /** my own count and place; null while nothing is counted (the Larper: always null) */
    val mine: NineMine? = null, val total: Int? = null,
)

@Serializable
data class ElectionCandidate(val user: UserCard, val votes: Int = 0)

@Serializable
data class ElectionState(
    val open: Boolean = false, @SerialName("closes_at") val closesAt: String? = null,
    @SerialName("opened_at") val openedAt: String? = null, val voters: Int = 0, val needed: Int = 10,
    @SerialName("my_vote") val myVote: UserCard? = null, val top: List<ElectionCandidate> = emptyList(),
    val larper: UserCard? = null, @SerialName("next_at") val nextAt: String? = null,
)

@Serializable
data class NineResponse(val titles: List<NineTitle> = emptyList(), val election: ElectionState = ElectionState())

@Serializable
data class ElectionResponse(val election: ElectionState)

/** A rule the owner made visible on the profile. */
@Serializable
data class PublicRule(val id: Int, val kind: String, val name: String, val icon: String, val mode: String, val value: Double)

@Serializable
data class HistoryItem(val kind: String, val label: String, val icon: String, val delta: Double, val at: String)

@Serializable
data class MediaItem(
    val id: Int, val kind: String, val url: String, val thumb: String,
    val width: Int = 0, val height: Int = 0, val duration: Double = 0.0,
    /** text placed over the photo / video */
    val overlay: TextOverlay? = null,
    /** a chat photo: when only its small copy will be left / is left (report #48) */
    @SerialName("expires_at") val expiresAt: String? = null,
    val archived: Boolean = false,
    /** an encrypted chat's photo / voice message: the blobs are ciphertext; key + type come from the message */
    val encrypted: Boolean = false,
    val key: String? = null,
    val mime: String? = null,
    /** flagged by the content filter (server/moderation.py): shown behind [cover] until tapped ([thumb] is the cover) */
    val sensitive: Boolean = false,
    val cover: String? = null,
) {
    val isVideo get() = kind == "video"
}

@Serializable
data class CommentItem(
    val id: Int, val text: String, val at: String, val user: UserCard,
    @SerialName("can_delete") val canDelete: Boolean = false,
    val likes: Int = 0,
    val liked: Boolean = false,
    /** the top-level comment this one answers (threads are one level deep) */
    @SerialName("parent_id") val parentId: Int? = null,
)

/** Text over a photo / video: position (center) and size are fractions of the picture's width / height. */
@Serializable
data class TextOverlay(val text: String, val x: Float = .5f, val y: Float = .5f, val color: String = "white", val size: Float = .07f)

@Serializable
data class PostSound(val url: String, val title: String = "", val duration: Double = 0.0)

/** A rule tapped with a post's slide or a story; ruleId opens its detail (like on the profile). */
@Serializable
data class Trigger(
    val slide: Int = 0, @SerialName("rule_id") val ruleId: Int? = null, val name: String = "", val icon: String = "star",
    val kind: String = "acc", val times: Int = 1,
    /** what it actually gave / took (all taps) */
    val points: Double = 0.0,
)

@Serializable data class StoryState(val active: Int = 0, val unseen: Boolean = false)

@Serializable
data class Story(
    val id: Int, val at: String, @SerialName("expires_at") val expiresAt: String = "", val active: Boolean = true,
    val user: UserCard, val mine: Boolean = false, val seen: Boolean = false, val media: MediaItem,
    val triggers: List<Trigger> = emptyList(), val sound: PostSound? = null,
    /** my own story: how many have seen it */
    val views: Int? = null,
    /** others' stories: may I answer it (a message to the author)? */
    @SerialName("can_reply") val canReply: Boolean = false,
    /** seconds it runs (photos: 5, 10, 15 or 30; videos: their length) */
    val duration: Double = 5.0,
)

@Serializable data class StoryResponse(val story: Story)

/** Someone typing ("text") or recording a voice message ("audio") right now. */
@Serializable data class Typing(val username: String, val kind: String = "text")

@Serializable data class StoryViewer(val user: UserCard, val at: String = "")
@Serializable data class StoryViewersResponse(val viewers: List<StoryViewer> = emptyList())
@Serializable data class StoryRow(val user: UserCard, val stories: List<Story>, val unseen: Boolean = false)
@Serializable data class StoryRowsResponse(val rows: List<StoryRow> = emptyList())
@Serializable data class StoriesResponse(val stories: List<Story> = emptyList())
@Serializable data class Highlight(val id: Int, val title: String, val cover: String? = null, val stories: List<Story> = emptyList())
@Serializable data class HighlightsResponse(val highlights: List<Highlight> = emptyList())

@Serializable
data class Post(
    val sound: PostSound? = null,
    /** where it was (a place name, coordinates if picked from the search) */
    val location: PostLocation? = null,
    /** rules tapped with the slides (slide = position in media) */
    val triggers: List<Trigger> = emptyList(),
    val id: Int,
    val kind: String,
    val text: String = "",
    val vehicle: String? = null,
    val at: String,
    val user: UserCard,
    val media: List<MediaItem> = emptyList(),
    val tags: List<UserCard> = emptyList(),
    val likes: Int = 0,
    val liked: Boolean = false,
    val comments: Int = 0,
    val mine: Boolean = false,
    @SerialName("comment_list") val commentList: List<CommentItem> = emptyList(),
    /** kind "rule": a rule made public (or changed since: updated) */
    val rule: FeedRule? = null,
)

@Serializable
data class FeedRule(val id: Int, val name: String, val icon: String, val kind: String = "acc", val mode: String = "constant",
                    val base: Double = 0.0, val updated: Boolean = false)

@Serializable
data class NotificationItem(
    val id: Int, val kind: String, val actor: UserCard,
    @SerialName("post_id") val postId: Int? = null,
    @SerialName("post_thumb") val postThumb: String? = null,
    val challenge: ChallengeRef? = null,
    val read: Boolean = false, val at: String,
    /** sub_rule / sub_decay (report #54): which rule, and the group that subscribed */
    val rule: NamedRef? = null, val group: NamedRef? = null,
    /** activities about a comment (comment, reply, clike, mention in a comment): its text, shortened by the server */
    val comment: String? = null,
)

@Serializable
data class NamedRef(val id: Int, val name: String = "")

@Serializable
data class ChallengeRef(
    val id: Int, val title: String, @SerialName("conversation_id") val conversationId: Int,
    val rank: Int? = null, val reward: Double = 0.0, val mode: String = "ranking",
)

@Serializable
data class LastMessage(
    val text: String = "", @SerialName("has_media") val hasMedia: Boolean = false,
    val mine: Boolean = false, val sender: String = "", val at: String,
    @SerialName("share_kind") val shareKind: String? = null,
    @SerialName("media_kind") val mediaKind: String? = null,
    /** a chat line (e2e_on / e2e_off); encrypted: the preview for me (opened here), sealed: can't be opened here */
    val kind: String? = null,
    val e2e: ReaderEnvelope? = null,
    val sealed: Boolean = false,
    /** member lines: who was added / removed (targetMe: me) */
    val target: UserCard? = null,
    @SerialName("target_me") val targetMe: Boolean = false,
    /** kind "call": what the call line shows */
    val call: CallLine? = null,
)

@Serializable
data class Conversation(
    val id: Int, val kind: String, val name: String = "", val members: List<UserCard> = emptyList(),
    val unread: Int = 0, val last: LastMessage? = null, @SerialName("is_admin") val isAdmin: Boolean = false,
    val banner: String? = null,
    /** groups: I'm automatically subscribed to every rule the group follows */
    @SerialName("auto_follow") val autoFollow: Boolean = false,
    /** an unread message tags me */
    val mentioned: Boolean = false,
    /** groups: found in the search / anyone may join (only when public) */
    val public: Boolean = false,
    val open: Boolean = false,
    /** false: looking at a public group from outside */
    val member: Boolean = true,
    /** no notifications (mentions still come through) */
    val muted: Boolean = false,
    val typing: List<Typing> = emptyList(),
    /** groups: own picture / own banner image (server paths), total competitive momentum of the members */
    val avatar: String? = null,
    @SerialName("banner_image") val bannerImage: String? = null,
    @SerialName("total_momentum") val totalMomentum: Double = 0.0,
    /** groups: anarchy mode (every member is an admin) and who the admins are */
    val anarchy: Boolean = false,
    val admins: List<String> = emptyList(),
    /** end-to-end encryption of new messages; may I switch it (direct: both, group: admins) */
    val e2e: Boolean = false,
    @SerialName("can_switch_e2e") val canSwitchE2e: Boolean = false,
    /** a call running in this chat (server/calls.py) */
    val call: CallBrief? = null,
) {
    val isGroup get() = kind == "group"
}

@Serializable
data class ChatMessage(
    val id: Int, val text: String = "", val at: String, val user: UserCard? = null,
    val mine: Boolean = false, val media: MediaItem? = null,
    val share: Share? = null,
    @SerialName("mentions_me") val mentionsMe: Boolean = false,
    /** the message this one answers (swiped to reply) */
    val reply: ReplyRef? = null,
    /** emojis on the message, most used first */
    val reactions: List<Reaction> = emptyList(),
    /** encrypted: the envelope for me; sealed: it can't be opened on this phone; kind: a chat line (e2e_on / e2e_off) */
    val e2e: ReaderEnvelope? = null,
    val sealed: Boolean = false,
    val kind: String? = null,
    /** member lines (member_join / _add / _remove / _leave, report #56): who was added / removed (targetMe: me) */
    val target: UserCard? = null,
    @SerialName("target_me") val targetMe: Boolean = false,
    /** kind "call": a voice / video call */
    val call: CallLine? = null,
    /** the preview of a link in the text (server/linkpreview.py; encrypted: opened from the envelope) */
    val link: LinkPreview? = null,
)

/** A web page's preview as the sender's app fetched it (= web/src/model.ts LinkPreview): the small picture is
 *  base64 WebP. The same object goes inside an encrypted message's body ("l"). */
@Serializable
data class LinkPreview(
    val url: String, val title: String = "", val description: String = "", val site: String = "",
    val image: String? = null, val width: Int = 0, val height: Int = 0,
)

@Serializable data class LinkPreviewResponse(val preview: LinkPreview? = null)

@Serializable
data class Reaction(val emoji: String, val count: Int = 1, val mine: Boolean = false)

@Serializable data class ReactionsResponse(val reactions: List<Reaction> = emptyList())

@Serializable
data class ReplyRef(
    val id: Int, val user: String = "", val mine: Boolean = false, val text: String = "",
    @SerialName("media_kind") val mediaKind: String? = null, @SerialName("share_kind") val shareKind: String? = null,
    val e2e: ReaderEnvelope? = null,
)

/** Rule, profile, post or challenge shared in a chat, as the viewer may see it. */
@Serializable
data class Share(
    val kind: String,
    val available: Boolean = false,
    val id: Int? = null,
    val name: String = "",
    val icon: String = "",
    @SerialName("rule_kind") val ruleKind: String = "acc",
    val owner: String = "",
    val user: UserCard? = null,
    @SerialName("post_owner") val postOwner: UserCard? = null,
    val caption: String = "",
    val thumb: String? = null,
    @SerialName("post_kind") val postKind: String = "post",
    val vehicle: String? = null,
    val challenge: Challenge? = null,
    /** shared posts: number of photos/videos, first one is a video, its aspect ratio */
    @SerialName("media_count") val mediaCount: Int = 0,
    val video: Boolean = false,
    val ratio: Double = 1.0,
)

/** A rule as seen from outside: profile rule, group rule or challenge rule. */
@Serializable
data class RuleDetail(
    val id: Int, val kind: String, val name: String, val icon: String, val mode: String,
    val base: Double, val param: Double, @SerialName("min_value") val minValue: Double,
    @SerialName("reset_days") val resetDays: Int,
    val owner: UserCard? = null,
    val origin: String = "profile",
    @SerialName("group_id") val groupId: Int? = null,
    val subscribers: Int = 0,
    val subscribed: Boolean = false,
    @SerialName("my_copy_id") val myCopyId: Int? = null,
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("can_subscribe") val canSubscribe: Boolean = false,
    /** group rule that follows this user's rule */
    @SerialName("linked_from") val linkedFrom: String? = null,
    val editable: Boolean = false,
    /** a group rule I may take out of the group (I added it, or I'm the admin) */
    val removable: Boolean = false,
    val public: Boolean = false,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("group_member") val groupMember: Boolean = false,
    /** group rules show the group: its picture (own image) or its pixel banner */
    @SerialName("group_image") val groupImage: String? = null,
    @SerialName("group_banner") val groupBanner: String? = null,
    /** can't subscribe: only the group's members may (groupName is null for a private group) */
    @SerialName("members_only") val membersOnly: Boolean = false,
    /** the same rule another way (own original, via a person or a group): no second subscription */
    @SerialName("have_already") val haveAlready: Boolean = false,
    /** my subscription belongs to a running challenge (title): can't unsubscribe before leaving it */
    @SerialName("locked_by") val lockedBy: String? = null,
    /** not tapped for idleDays -> idleRule is tapped automatically (subscribing takes it along) */
    @SerialName("idle_days") val idleDays: Int = 0,
    @SerialName("idle_rule") val idleRule: IdleRuleRef? = null,
    @SerialName("idle_paused") val idlePaused: Boolean = false,
    val tappable: Boolean = true,
    @SerialName("reset_unit") val resetUnit: String = "d",
    @SerialName("idle_unit") val idleUnit: String = "d",
) {
    val isAcc get() = kind == "acc"
    fun asRule() = Rule(id, kind, name, icon, mode, base, param, minValue, resetDays, base, public = public, tappable = tappable,
        resetUnit = resetUnit)
}

/** A shared decay: a person's public one (kind "user") or a group's (kind "group"). */
@Serializable
data class DecayDetail(
    val kind: String, val username: String? = null, val owner: UserCard? = null, val id: Int? = null, val name: String = "",
    @SerialName("group_id") val groupId: Int? = null, @SerialName("group_name") val groupName: String? = null,
    val public: Boolean = false, @SerialName("linked_from") val linkedFrom: String? = null, val followers: Int = 0,
    val following: Boolean = false, @SerialName("can_follow") val canFollow: Boolean = false,
    @SerialName("members_only") val membersOnly: Boolean = false, val editable: Boolean = false,
    val removable: Boolean = false, @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("locked_by") val lockedBy: String? = null,
    val base: Double = 0.0, val threshold: Double = 0.0, @SerialName("catalyst_mode") val catalystMode: String = "none",
    @SerialName("catalyst_a") val catalystA: Double = 0.0, @SerialName("catalyst_b") val catalystB: Double = 1.5,
) {
    fun asDecay() = Decay(base, threshold, catalystMode, catalystA, catalystB, 0, 0.0, 0.0, public = public)
}

@Serializable data class DecayDetailResponse(val decay: DecayDetail)
@Serializable data class DecaysResponse(val decays: List<DecayDetail> = emptyList())

@Serializable
data class IdleRuleRef(val id: Int, val name: String, val icon: String = "", val kind: String = "acc")

/** A player in a ranking (competitive momentum; competing = plays with the daily limit). */
@Serializable
data class RankEntry(val rank: Int, val momentum: Double = 0.0, val competing: Boolean = true, val user: UserCard,
                     /** ?period=24h: the change of the last 24 hours */
                     val delta: Double? = null)

@Serializable
data class LeaderboardResponse(val entries: List<RankEntry> = emptyList(), val me: RankEntry? = null, val total: Int = 0)

@Serializable
data class RankingResponse(val entries: List<RankEntry> = emptyList())

/** Answer of a bulk action: the new state, how many were done and what was skipped (and why). */
@Serializable
data class BulkSkip(val id: Int, val reason: String = "", val challenge: String? = null)

@Serializable
data class BulkResult(val state: AppState, val done: Int = 0, val skipped: List<BulkSkip> = emptyList())

@Serializable
data class GroupRank(val rank: Int, val id: Int, val name: String, val banner: String? = null, val members: Int = 0,
                     val total: Double = 0.0, val member: Boolean = false, val delta: Double? = null)

@Serializable
data class GroupRankingResponse(val groups: List<GroupRank> = emptyList(), val mine: List<GroupRank> = emptyList(), val total: Int = 0)

@Serializable
data class GroupHit(val id: Int, val name: String, val banner: String? = null, val open: Boolean = false,
                    val members: Int = 0, val member: Boolean = false)

@Serializable
data class GroupsResponse(val groups: List<GroupHit> = emptyList())

@Serializable
data class LeaderboardEntry(val user: UserCard? = null, val score: Double = 0.0, val rank: Int? = null, val reward: Double = 0.0)

@Serializable
data class Challenge(
    val id: Int,
    @SerialName("conversation_id") val conversationId: Int,
    val title: String,
    val mode: String,
    val status: String,
    val banner: String? = null,
    @SerialName("start_day") val startDay: String,
    @SerialName("end_day") val endDay: String,
    val days: Int,
    @SerialName("days_left") val daysLeft: Int? = null,
    @SerialName("starts_in") val startsIn: Int? = null,
    val target: Double = 0.0,
    val rewards: List<Double> = emptyList(),
    val creator: UserCard? = null,
    val participants: Int = 0,
    val joined: Boolean = false,
    val left: Boolean = false,
    @SerialName("my_score") val myScore: Double? = null,
    @SerialName("my_rank") val myRank: Int? = null,
    @SerialName("my_reward") val myReward: Double = 0.0,
    @SerialName("can_join") val canJoin: Boolean = false,
    @SerialName("can_cancel") val canCancel: Boolean = false,
    /** keep the challenge's rules after leaving / the end */
    @SerialName("keep_rules") val keepRules: Boolean = false,
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val rules: List<RuleDetail> = emptyList(),
    /** the decay participants follow while they take part */
    val decay: DecayDetail? = null,
) {
    val isTarget get() = mode == "target"
    val running get() = status == "running"
    val over get() = status == "finished" || status == "cancelled"
}

@Serializable data class PostsResponse(val posts: List<Post>, val following: Int = 0)
@Serializable data class PostResponse(val post: Post)
@Serializable data class ProfileResponse(val profile: Profile)
@Serializable data class UsersResponse(val users: List<UserCard>)

/** Who read a message (report #44); unread = the other members who haven't yet. */
@Serializable data class ReadersResponse(val readers: List<UserCard> = emptyList(), val unread: Int = 0)

/** Who reacted with what to a message (report #63), newest first. */
@Serializable data class Reactor(val user: UserCard, val emoji: String)
@Serializable data class ReactorsResponse(val reactions: List<Reactor> = emptyList())

/** A song for a post's sound (Deezer): its 30 s preview comes through the server. */
@Serializable data class Song(val id: Long, val title: String, val artist: String = "", val cover: String? = null)
@Serializable data class SongsResponse(val songs: List<Song> = emptyList())

@Serializable data class PostLocation(val name: String, val lat: Double? = null, val lon: Double? = null)

/** A hit of the place search (OpenStreetMap via the server). */
@Serializable data class Place(val name: String, val detail: String = "", val lat: Double? = null, val lon: Double? = null)
@Serializable data class PlacesResponse(val places: List<Place> = emptyList())
@Serializable data class NotificationsResponse(val items: List<NotificationItem>, val requests: Int = 0)
@Serializable data class RuleDetailResponse(val rule: RuleDetail)
@Serializable data class RuleDetailsResponse(val rules: List<RuleDetail>)
@Serializable data class ChallengeResponse(val challenge: Challenge)
@Serializable data class ChallengesResponse(val challenges: List<Challenge>)
@Serializable data class ConversationsResponse(val conversations: List<Conversation>)
@Serializable data class ConversationResponse(val conversation: Conversation)
@Serializable data class MessagesResponse(
    val messages: List<ChatMessage>, @SerialName("read_upto") val readUpto: Int = 0,
    val typing: List<Typing> = emptyList(),
    /** polling: the current reactions of the latest messages (message id -> reactions) */
    val reactions: Map<String, List<Reaction>> = emptyMap(),
    @SerialName("reacted_ids") val reactedIds: List<Int> = emptyList(),
)
@Serializable data class MessageResponse(val message: ChatMessage)
@Serializable data class MediaResponse(val media: MediaItem)
