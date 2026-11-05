package com.momentum.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.momentum.app.data.Rule
import com.momentum.app.fmtVal

enum class Lang(val code: String) { DE("de"), EN("en") }

/** Builder for cloze sentences: plain words and interactive gaps. */
interface ClozeBuilder {
    fun text(s: String)
    fun gap(id: String, content: @Composable () -> Unit)
}

/** The interactive gaps of the rule editor; each sentence places them differently. */
class RuleGaps(
    val icon: @Composable () -> Unit, val name: @Composable () -> Unit, val base: @Composable () -> Unit,
    val mode: @Composable () -> Unit, val param: @Composable () -> Unit, val pct: @Composable () -> Unit,
    val min: @Composable () -> Unit, val reset: @Composable () -> Unit,
)

class DecayGaps(
    val icon: @Composable () -> Unit, val base: @Composable () -> Unit, val threshold: @Composable () -> Unit,
    val mode: @Composable () -> Unit, val a: @Composable () -> Unit, val b: @Composable () -> Unit,
    val pct: @Composable () -> Unit,
)

/** All user-facing texts. */
interface Strings {
    val lang: Lang
    // general
    val saved: String
    val nameMissing: String
    val unreachable: String
    val sessionExpired: String
    // login / offline
    val tagline: String
    val username: String
    val password: String
    val login: String
    val register: String
    val toRegister: String
    val toLogin: String
    val server: String
    val offline: String
    val retry: String
    // home
    val hello: String
    fun days(n: Int): String
    fun stage(i: Int, n: Int): String
    val today: String
    val goalToday: String
    val tonight: String
    val nextMilestone: String
    fun toGo(amount: String): String
    val allDone: String
    val allDoneSub: String
    val garage: String
    val history: String
    val dailyDecay: String
    val resetDone: String
    fun timeAgo(sec: Long): String
    fun vehicle(key: String, fallback: String): String
    // rules
    val accTag: String
    val accTitle: String
    val accSub: String
    val decTag: String
    val decTitle: String
    val decSub: String
    val newAcc: String
    val newDec: String
    fun decayInfo(base: String, streak: Int): String
    fun collectedToday(a: String, b: String): String
    fun modeHint(r: Rule): String
    // editor
    val edit: String
    val save: String
    val delete: String
    val reallyDelete: String
    val previewRule: String
    val previewDecay: String
    fun day(n: Int): String
    val modes: List<Pair<String, String>>
    fun resetLabel(days: Int): String
    fun resetSpan(n: Int, unit: String): String
    fun idleSpan(n: Int, unit: String): String
    val customTime: String
    val customTimeTitle: String
    val unitHours: String
    val unitDays: String
    val catalysts: List<Pair<String, String>>
    fun namePlaceholder(acc: Boolean): String
    fun ruleSentence(c: ClozeBuilder, acc: Boolean, mode: String, resetDays: Int, g: RuleGaps)
    fun decaySentence(c: ClozeBuilder, mode: String, g: DecayGaps)
    // settings
    val settingsTag: String
    val settingsTitle: String
    val profile: String
    val displayName: String
    val change: String
    fun loggedInAs(u: String): String
    val game: String
    val language: String
    val timezone: String
    val milestoneStep: String
    val haptics: String
    val showHistory: String
    val changePassword: String
    val oldPassword: String
    val newPassword: String
    val savePassword: String
    val passwordChanged: String
    val dangerZone: String
    val logout: String
    val resetMomentum: String
    val deleteAccount: String
    val tapAgain: String
    val searchTimezone: String
    val cropTitle: String
    val cropHint: String
    val cancel: String
    val usePhoto: String
    val removePhoto: String
    // social
    val feed: String
    val findPeople: String
    val searchHint: String
    // emoji picker (ui/Emoji.kt, web/src/ui/emoji.tsx)
    val emojiTitle: String
    val emojiSearch: String
    val emojiRecent: String
    val emojiNone: String
    fun emojiGroup(name: String): String
    val emptyFeed: String
    val emptyFeedSub: String
    val newPost: String
    val publish: String
    val captionHint: String
    val addMedia: String
    val tagPeople: String
    val addLocation: String
    val newStory: String
    val whatToCreate: String
    val postHint: String
    val storyHint: String
    val yourStory: String
    val storyShared: String
    val slideRules: String
    val slideRulesHint: String
    val noProfileRules: String
    val camera: String
    val gallery: String
    val textOnImage: String
    val highlights: String
    val newHighlight: String
    val highlightName: String
    val addToHighlight: String
    val deleteStory: String
    val deleteHighlight: String
    val removeFromHighlight: String
    val pickStories: String
    val noStoriesYet: String
    val captionForPhoto: String
    val maxRulesPerSlide: String
    val shareStory: String
    val tapToEdit: String
    val searchPlace: String
    fun useTypedPlace(name: String): String
    val uploading: String
    fun likes(n: Int): String
    fun viewComments(n: Int): String
    val comments: String
    val addComment: String
    val blockUser: String
    val unblockUser: String
    fun blockConfirm(name: String): String
    val blockConfirmHint: String
    val blockedTitle: String
    val blockedSub: String
    val blockedAccounts: String
    val noBlocked: String
    val reply: String
    fun viewReplies(n: Int): String
    val hideReplies: String
    fun replyTo(name: String): String
    val with: String
    fun milestonePost(vehicle: String): String
    val deletePost: String
    val posts: String
    val followers: String
    val followingLabel: String
    val follow: String
    val followBack: String
    val followingButton: String
    val friend: String
    // One of the Nine (server/nine.py): the titles, their screens, the Larper's election
    val oneOfNine: String
    val theNine: String
    fun nineName(key: String): String
    fun nineLore(key: String): String
    fun nineHow(key: String): String
    fun nineUnit(key: String, n: String): String
    val nineHolder: String
    val nineNobody: String
    fun nineMine(value: String, rank: Int): String
    val nineNotCounted: String
    val nineHowTitle: String
    val electionTab: String
    val electionTitle: String
    val electionIntro: String
    fun electionVotes(n: Int, needed: Int): String
    fun electionEnds(date: String): String
    fun electionNext(date: String): String
    val electionClosed: String
    fun electionMyVote(name: String): String
    val electionVote: String
    val electionVoted: String
    val electionSearch: String
    val electionLeading: String
    val electionCurrentLarper: String
    val electionOpenHint: String
    val electionOpenAction: String
    val message: String
    val noPosts: String
    val noUsers: String
    val activity: String
    val noActivity: String
    fun notification(kind: String): String
    val chats: String
    val newMessage: String
    val newGroup: String
    val groupName: String
    val create: String
    val noChats: String
    val typeMessage: String
    val photo: String
    val you: String
    val leaveGroup: String
    fun members(n: Int): String
    val pickPeople: String
    val shareMilestone: String
    val shared: String
    val showOnProfile: String
    val showOnProfileHint: String
    val rulesTitle: String
    val usernameHint: String
    val devOn: String
    val devOff: String
    val devSection: String
    val noContacts: String
    val noContactsSub: String
    val nothingToSave: String
    val likedBy: String
    val seenBy: String
    val noViewsYet: String
    val email: String
    val bio: String
    val noPublicRules: String
    val noPublicRulesMine: String
    val noHistory: String
    // offline, undo, daily limit
    val offlineNow: String
    fun offlinePending(n: Int): String
    val offlineStale: String
    val needsInternet: String
    val offlineTitle: String
    val offlineSub: String
    val undoTitle: String
    val undo: String
    val pendingSync: String
    fun milestoneEvent(vehicle: String): String
    fun limitLeft(left: String, cap: String): String
    fun limitPartial(amount: String): String
    val limitZeroTap: String
    val limitReached: String
    val bioHint: String
    val emailOrUsername: String
    /** confirming the e-mail address (required) and a new password by code (server/mail.py) */
    val confirmEmailTitle: String
    fun confirmEmailSent(email: String): String
    fun confirmEmailAsk(email: String): String
    val confirmCode: String
    val confirmButton: String
    val sendCode: String
    val resendCode: String
    fun resendIn(seconds: Int): String
    val otherAddress: String
    val newEmail: String
    val checkSpam: String
    fun confirmDue(date: String): String
    val confirmNow: String
    val emailConfirmed: String
    val forgotPassword: String
    val forgotHint: String
    val forgotSent: String
    val backToLogin: String
    val googleSignIn: String
    val googleFailed: String
    val googleAccount: String
    val legalSection: String
    val privacyPolicy: String
    val termsOfUse: String
    val acceptTerms: String
    val invalidEmail: String
    val chooseUsername: String
    val chooseUsernameSub: String
    // milestone
    val milestoneReached: String
    val levelUp: String
    val nowDriving: String
    val next: String
    // private accounts
    val privateAccount: String
    val privateAccountHint: String
    val requested: String
    val lockedTitle: String
    val lockedSub: String
    val followRequests: String
    val accept: String
    val decline: String
    fun requestsCount(n: Int): String
    // rule sharing
    val subscribe: String
    val unsubscribe: String
    val subscribed: String
    val share: String
    val shareTo: String
    fun ruleBy(owner: String): String
    val groupRule: String
    val challengeRule: String
    val yourRule: String
    fun subscribers(n: Int): String
    fun subscribedHint(owner: String): String
    val subscribedHintGroup: String
    fun onlyMembersCanSubscribe(group: String?): String
    fun origin(origin: String, label: String): String
    val notAvailable: String
    val readOnlyRule: String
    val sharedRule: String
    val sharedPost: String
    val sharedProfile: String
    // groups + challenges
    val groupInfo: String
    val groupRules: String
    val noGroupRules: String
    val newGroupRule: String
    val challenges: String
    val noChallenges: String
    val newChallenge: String
    val membersTitle: String
    val modeRanking: String
    val modeTarget: String
    val modeRankingHint: String
    val modeTargetHint: String
    val challengeName: String
    val duration: String
    val startLabel: String
    fun startOption(n: Int): String
    fun rewardPlace(i: Int): String
    val optional: String
    val targetLabel: String
    val rewardLabel: String
    val challengeRules: String
    val challengeHint: String
    val join: String
    val leaveChallenge: String
    val reallyLeave: String
    val cancelChallenge: String
    val reallyCancel: String
    fun challengeStatus(status: String, daysLeft: Int?, startsIn: Int?): String
    fun joinedCount(n: Int): String
    val leaderboard: String
    val yourScore: String
    val leftChallenge: String
    val challengeMissing: String
    fun challengeResult(title: String, rank: Int?, reward: String): String
    fun challengeCancelled(title: String): String
    val challenge: String
    fun rewardsLine(rewards: List<String>): String
    fun targetLine(target: String, reward: String): String
    fun place(n: Int): String
    fun createdBy(name: String): String
    // banners
    val banner: String
    val chooseBanner: String
    val bannerAuto: String
    fun bannerName(key: String): String
    fun bannerGroup(key: String): String
    fun bannerKind(kind: String): String
    val addToGroup: String
    val fromMyRules: String
    fun followsRuleOf(name: String): String
    /** Empty lists: title + hint depending on where the list is. */
    fun empty(context: String, name: String = ""): Pair<String, String>
    val limitInfoTitle: String
    val limitProvisionalShort: String
    val limitProvisional: String
    fun limitInfo(cap: String, average: String, bias: String, gained: String, left: String): List<String>
    // competition / free score / hyperspace
    val dailyLimitSetting: String
    val dailyLimitHint: String
    val competition: String
    val unranked: String
    val freeMomentum: String
    fun rankedPaused(value: String): String
    fun rankedValue(value: String): String
    val noLimitBanner: String
    val hyperspaceTitle: String
    val hyperspaceSub: String
    val useAsBackground: String
    val hyperspaceLocked: String
    val hyperLore: String
    val ultraLore: String
    // automatic taps for rules left untapped
    fun idleTitle(acc: Boolean): String
    fun idleDays(n: Int): String
    fun idleSentence(b: ClozeBuilder, on: Boolean, days: @Composable () -> Unit, rule: @Composable () -> Unit)
    fun idleNoCounterpart(acc: Boolean): String
    fun autoTapped(from: String): String
    // update required
    val updateTag: String
    val updateTitle: String
    val updateText: String
    val updateButton: String
    // what's new after an update (CHANGELOG.md)
    val whatsNewTitle: String
    val whatsNewHint: String
    val whatsNewOpen: String
    val whatsNewOk: String
    // the daily limit changed since the day before (a day's first start, data/LimitNews.kt)
    val limitUpTitle: String
    val limitDownTitle: String
    fun limitChangeText(prev: String, cap: String): String
    fun limitChangeWhy(average: String): String
    val systemFontSetting: String
    val notificationsSetting: String
    val systemFontHint: String
    val autoFollow: String
    val autoFollowHint: String
    val noPublicRulesForGroup: String
    val lowerMomentum: String
    fun lowerMomentumHint(current: String): String
    val lowerMomentumInvalid: String
    fun lowerMomentumTo(value: String): String
    val takePhoto: String
    val takeVideo: String
    val recordVideo: String
    val fromGallery: String
    val reportTitle: String
    val reportBug: String
    val reportFeature: String
    val reportBugHint: String
    val reportFeatureHint: String
    val reportPlaceholder: String
    val reportImages: String
    val reportSend: String
    val reportThanks: String
    fun reportThanksBonus(n: String): String
    val reportBonus: String
    val reportSettingsHint: String
    val reportOpen: String
    val voiceMessage: String
    val releaseToSend: String
    val holdToRecord: String
    // end-to-end encrypted chats
    val e2eTitle: String
    val e2eHint: String
    val e2eOnlyAdmins: String
    val e2eChatNote: String
    fun e2eSwitchedOn(name: String): String
    fun e2eSwitchedOff(name: String): String
    val e2eYouSwitchedOn: String
    val e2eYouSwitchedOff: String
    // group member lines in the chat (report #56)
    fun memberJoined(name: String): String
    val youJoined: String
    fun memberAdded(actor: String, target: String): String
    fun youAdded(target: String): String
    fun addedYou(actor: String): String
    fun memberRemoved(actor: String, target: String): String
    fun youRemoved(target: String): String
    fun removedYou(actor: String): String
    fun memberLeft(name: String): String
    val youLeft: String
    val removeMember: String
    // voice / video calls (server/calls.py)
    val voiceCall: String
    val videoCall: String
    val groupVoiceCall: String
    val groupVideoCall: String
    val outgoingVoiceCall: String
    val outgoingVideoCall: String
    val missedVoiceCall: String
    val missedVideoCall: String
    val missedGroupCall: String
    val callNoAnswerShort: String
    val callDeclinedShort: String
    val callBusyShort: String
    val callIncomingVoice: String
    val callIncomingVideo: String
    val callRinging: String
    val callCalling: String
    val callConnecting: String
    val callReconnecting: String
    val callWaiting: String
    val callEnded: String
    val callBusy: String
    val callDeclined: String
    val callNoAnswer: String
    val callAccept: String
    val callDecline: String
    val callHangUp: String
    val callMute: String
    val callCamera: String
    val callFlip: String
    val callSpeaker: String
    val callMinimize: String
    val callBackTo: String
    val callJoin: String
    val callAlready: String
    val callNoMic: String
    val callNoCamera: String
    val callNeedsInternet: String
    val callUnsupported: String
    val callsChannel: String
    val callOngoing: String
    val callStateRinging: String
    fun callRunning(count: Int): String
    fun callFromIn(name: String, group: String): String
    fun removeMemberAsk(name: String): String
    // someone subscribed to my rule / follows my decay (report #54)
    fun subscribedRule(rule: String): String
    fun subscribedRuleGroup(rule: String, group: String): String
    val followsDecay: String
    fun decayInGroup(group: String): String
    val e2eSealed: String
    val e2eLocked: String
    val e2eNeedsUnlock: String
    val e2eUnsupported: String
    val e2eNewMessage: String
    val safetyCodes: String
    val safetyHint: String
    val yourCode: String
    fun keyChanged(name: String): String
    val keyChangedOk: String
    val recoveryTitle: String
    val recoveryIntro: String
    val recoveryCopy: String
    val recoveryNoted: String
    val recoveryCard: String
    val recoveryCardHint: String
    val unlockTitle: String
    val unlockIntro: String
    val unlockPlaceholder: String
    val unlockButton: String
    val unlockWrong: String
    val unlockCard: String
    val unlockCardHint: String
    val resetKey: String
    val resetKeyWarning: String
    val resetKeyConfirm: String
    val chatInfo: String
    val muteChat: String
    val muteChatHint: String
    val micReady: String
    val micBlocked: String
    val addMembers: String
    fun addMembersCount(n: Int): String
    val noOneToAdd: String
    fun mentionedYou(sender: String, chat: String?): String
    val mentionedPreview: String
    val accShort: String
    val decShort: String
    val dragToSort: String
    // shared decays
    val decayPublic: String
    val decayPublicHint: String
    fun decayOf(name: String): String
    fun groupDecayOf(group: String): String
    val followDecay: String
    val followDecayHint: String
    val decayFollowing: String
    fun decayLockedBy(title: String): String
    val groupDecays: String
    val newGroupDecay: String
    val noGroupDecays: String
    val decayName: String
    val challengeDecay: String
    val noDecay: String
    val challengeDecayHint: String
    val publicDecay: String
    val haveRuleAlready: String
    val ownDecayInGroup: String
    fun followsDecayOf(name: String): String
    val adminTag: String
    val makeAdmin: String
    val removeAdmin: String
    val viewProfile: String
    val anarchyMode: String
    val anarchyHint: String
    val onlyAdmins: String
    val tappableSetting: String
    val tappableHint: String
    val notTappable: String
    val subscribedLabel: String
    val leaderboardTitle: String
    val scopeGlobal: String
    val scopeFriends: String
    val yourPlace: String
    val paused: String
    fun placeOf(rank: Int, total: Int): String
    val groupRanking: String
    val groupPublic: String
    val groupPublicHint: String
    val groupOpen: String
    val groupOpenHint: String
    val joinGroup: String
    val closedGroup: String
    val tagPublic: String
    val tagPrivate: String
    val tagOpen: String
    val addGroupRule: String
    val followPublicRule: String
    val followPublicHint: String
    val createGroupRule: String
    val createGroupRuleHint: String
    val copyRule: String
    val copyRuleHint: String
    val noRulesToCopy: String
    val copyToGroup: String
    val copied: String
    val groupsTitle: String
    val challengeOnlyGroupRules: String
    val keepRules: String
    val keepRulesHint: String
    fun lockedByChallenge(title: String): String
    fun groupRuleOf(group: String): String
    val groupRulePublicHint: String
    val idlePause: String
    val idlePauseHint: String
    val idlePausedShort: String
    fun idleLinked(days: String, name: String): String
    val subscribeForGroup: String
    val followPublicFromRule: String
    val widgetPickGroup: String
    val widgetLoading: String
    fun challengeCreditLine(amount: String): String
    val challengeCreditHint: String
    fun rewardMax(max: String): String
    val chatMuted: String
    val chatUnmuted: String
    val uploadFailed: String
    fun serverDown(code: Int): String
    fun serverError(code: Int): String
    val groupTotal: String
    val groupLook: String
    val pickPixelBanner: String
    val ownBannerImage: String
    val groupPicture: String
    val removeImages: String
    val viewPost: String
    fun replyingTo(name: String): String
    // chat: typing / recording indicator (report #32), images pasted from the clipboard (report #31)
    val pasteImage: String
    /** a web link in the message being typed: its preview is on the way (sent along like WhatsApp) */
    val linkPreviewLoading: String
    fun typingOne(name: String): String
    fun typingTwo(a: String, b: String): String
    fun typingMany(n: Int): String
    fun recordingOne(name: String): String
    fun recordingMany(n: Int): String
    val typingShort: String
    val recordingShort: String
    // stories: answer, react, share (report #33)
    val replyToStory: String
    val sharedStory: String
    val viewStory: String
    val storyReplySent: String
    // the content filter (server/moderation.py): a flagged photo / video behind its cover
    val sensitiveTitle: String
    val sensitiveHint: String
    val sensitiveShow: String
    // story length (report #39), the part of a sound, rules in the feed, rankings of the last 24 h (report #40)
    val storyDuration: String
    val soundRangeHint: String
    val soundStoryHint: String
    val ruleSharedPost: String
    val ruleUpdatedPost: String
    val periodTotal: String
    val period24h: String
    fun limitForecast(average: String, players: Int, next: String): String
    val readBy: String
    fun notReadYet(n: Int): String
    /** who reacted to a message (report #63) */
    val reactions: String
    /** an upload's steps under its progress bar: shrinking a video on the phone, then the server re-encoding it */
    val compressing: String
    val processing: String
    /** chat: day separator, the countdown under a photo and its small copy after two weeks (reports #48, #49) */
    val yesterday: String
    fun deletedIn(days: Int, hm: String): String
    val photoArchived: String
    val originalSound: String
    val addSound: String
    val searchSong: String
    val songsByDeezer: String
    val ownSound: String
    val pickAudioFile: String
    val recordSound: String
    val recordSoundHint: String
    val soundTitle: String
    fun soundPart(from: String, to: String): String
    val unsubscribeAll: String
    val dropToDelete: String
    val dropToUnsubscribe: String
    fun deleteRuleQuestion(name: String): String
    val takePhotoOrVideo: String
    val videoLabel: String
    val removeFromGroup: String
    val reallyRemoveFromGroup: String
    val addText: String
    val overlayHint: String
    val done: String
    val noPublicGroups: String
    fun selectedCount(n: Int): String
    val selectAll: String
    fun deleteN(n: Int): String
    fun unsubscribeN(n: Int): String
    fun subscribeN(n: Int): String
    fun removeN(n: Int): String
    fun makePublicN(n: Int): String
    fun makePrivateN(n: Int): String
    val inProfile: String
    val inProfileHint: String
    fun bulkResult(done: Int, skipped: Int): String
    fun confirmBulkRemove(delete: Int, unsubscribe: Int): String
    fun bulkLockedHint(n: Int): String
    fun confirmRemoveFromGroup(n: Int): String
    val removeFromGroupHint: String
    val notificationsAllowed: String
    val notificationsBlocked: String
    // PWA (web/): notifications in the browser / on the iPhone home screen
    val notificationsBlockedWeb: String
    val notificationsInstallHint: String
    val allowNotifications: String
    val notifyAskTitle: String
    val notifyAskSub: String
    val notNow: String
    val openSystemSettings: String
    val testNotification: String
    val testNotificationText: String
}

object De : Strings {
    override val lang = Lang.DE
    override val saved = "Gespeichert"
    override val nameMissing = "Gib einen Namen ein"
    override val unreachable = "Server nicht erreichbar"
    override val sessionExpired = "Sitzung abgelaufen"
    override val tagline = "Miss dein seelisches"
    override val username = "Benutzername"
    override val password = "Passwort"
    override val login = "Los geht's"
    override val register = "Konto erstellen"
    override val toRegister = "Neu hier? Konto erstellen"
    override val toLogin = "Schon dabei? Anmelden"
    override val server = "Server"
    override val offline = "Server nicht erreichbar.\nLäuft python -m server auf dem PC?"
    override val retry = "Nochmal versuchen"
    override val hello = "Hallo"
    override fun days(n: Int) = if (n == 1) "1 Tag" else "$n Tage"
    override fun stage(i: Int, n: Int) = "Stufe $i/$n"
    override val today = "Heute"
    override val goalToday = "Ziel heute"
    override val tonight = "Heute Nacht"
    override val nextMilestone = "Nächster Meilenstein"
    override fun toGo(amount: String) = "noch $amount Momentum"
    override val allDone = "Alle Meilensteine geschafft"
    override val allDoneSub = "Du bist ganz oben."
    override val garage = "Garage"
    override val history = "Verlauf"
    override val dailyDecay = "Täglicher Verfall"
    override val resetDone = "Momentum zurückgesetzt"
    override fun timeAgo(sec: Long) = when {
        sec < 60 -> "gerade eben"
        sec < 3600 -> "vor ${sec / 60} min"
        sec < 86400 -> "vor ${sec / 3600} h"
        sec < 2 * 86400 -> "gestern"
        else -> "vor ${sec / 86400} Tagen"
    }
    override fun vehicle(key: String, fallback: String) = fallback
    override val accTag = "Gib Gas"
    override val accTitle = "Beschleuniger"
    override val accSub = "Tippe auf etwas, das du geschafft hast."
    override val decTag = "Bremsklötze"
    override val decTitle = "Bremser"
    override val decSub = "Ehrlich sein: tippe, wenn es passiert ist."
    override val newAcc = "Neuer Beschleuniger"
    override val newDec = "Neuer Bremser"
    override fun decayInfo(base: String, streak: Int) =
        "heute Nacht · Grundwert $base" + if (streak > 0) " · Strähne $streak" else ""
    override fun collectedToday(a: String, b: String) = "Heute gesammelt $a / $b"
    override fun modeHint(r: Rule) = when (r.mode) {
        "linear" -> "linear · −${fmtVal(r.param)} pro Klick"
        "exponential" -> "exponentiell · ${Math.round(r.param * 100)}% bleiben"
        else -> "konstant"
    }
    override val edit = "Bearbeiten"
    override val save = "Speichern"
    override val delete = "Löschen"
    override val reallyDelete = "Wirklich?"
    override val previewRule = "So entwickelt es sich pro Klick"
    override val previewDecay = "Verlust pro Nacht: Ziel geschafft (✓) oder x-mal in Folge verpasst"
    override fun day(n: Int) = "Tag $n"
    override val modes = listOf("constant" to "konstant", "linear" to "linear sinkend", "exponential" to "exponentiell sinkend")
    override fun resetLabel(days: Int) = when (days) { 0 -> "nie"; 1 -> "1 Tag"; else -> "$days Tage" }
    override fun resetSpan(n: Int, unit: String) = if (n == 0) "nie" else if (unit == "h") (if (n == 1) "1 Stunde" else "$n Stunden") else (if (n == 1) "1 Tag" else "$n Tage")
    override fun idleSpan(n: Int, unit: String) = if (n == 0) "nie" else if (unit == "h") (if (n == 1) "1 Stunde" else "$n Stunden") else (if (n == 1) "1 Tag" else "$n Tagen")
    override val customTime = "Eigene Zeit…"
    override val customTimeTitle = "Zeit wählen (0–365)"
    override val unitHours = "Std."
    override val unitDays = "Tage"
    override val catalysts = listOf("none" to "keine", "linear" to "gleichmäßig", "exponential" to "immer schneller",
        "inverse" to "erst schnell, dann langsamer")
    override fun namePlaceholder(acc: Boolean) = if (acc) "Sport machen" else "Doomscrolling"

    override fun ruleSentence(c: ClozeBuilder, acc: Boolean, mode: String, resetDays: Int, g: RuleGaps) = with(c) {
        gap("icon", g.icon); text("Für"); gap("name", g.name)
        text(if (acc) "bekomme ich" else "verliere ich")
        if (mode == "constant") {
            text("jedes Mal"); gap("base", g.base); text("Momentum.")
            text(if (acc) "Das Wachstum ist" else "Die Strafe ist"); gap("mode", g.mode)
            text(if (acc) "— jeder Klick zählt gleich viel." else "— jedes Mal gleich hart.")
            return@with
        }
        text("beim ersten Mal"); gap("base", g.base); text("Momentum.")
        text(if (acc) "Das Wachstum ist" else "Die Strafe ist"); gap("mode", g.mode)
        if (mode == "linear") {
            text(if (acc) ": mit jedem weiteren Klick gibt es" else ": jedes weitere Mal kostet")
            gap("param", g.param)
            text(if (acc) "weniger, aber nie weniger als" else "weniger, aber immer mindestens")
        } else {
            text(if (acc) ": bei jedem Klick bleiben nur" else ": jedes Mal bleiben nur")
            gap("pct", g.pct)
            text("% davon übrig — erst geht es schnell runter, dann immer langsamer, aber nie unter")
        }
        gap("min", g.min); text(".")
        if (resetDays == 0) {
            text("Zurückgesetzt wird"); gap("reset", g.reset); text("— einmal verbraucht, bleibt es so.")
        } else {
            text("Nach"); gap("reset", g.reset)
            text(if (acc) "ohne Klick starte ich wieder bei voller Stärke." else "ohne Rückfall ist die Strafe wieder voll.")
        }
    }

    override fun decaySentence(c: ClozeBuilder, mode: String, g: DecayGaps) = with(c) {
        gap("icon", g.icon); text("Jede Nacht verliere ich"); gap("base", g.base); text("Momentum.")
        if (mode == "none") {
            text("Verpasse ich mein Tagesziel, kommt"); gap("mode", g.mode); text("eine Zusatzstrafe dazu.")
            return@with
        }
        text("Schaffe ich an einem Tag weniger als"); gap("threshold", g.threshold)
        text(", kommt eine Zusatzstrafe dazu, die mit jedem verpassten Tag in Folge"); gap("mode", g.mode)
        text("wächst:")
        when (mode) {
            "linear" -> { text("+"); gap("a", g.a); text("pro Tag.") }
            "exponential" -> {
                text("sie beginnt bei"); gap("a", g.a); text("und wird jeden Tag ×"); gap("b", g.b); text("größer.")
            }
            else -> {
                text("bis höchstens"); gap("a", g.a); text(". Jeder verpasste Tag schließt die Lücke zum Maximum, es bleiben nur");
                gap("pct", g.pct); text("% übrig.")
            }
        }
        text("Sobald ich das Ziel wieder schaffe, fällt sie weg.")
    }

    override val settingsTag = "Boxenstopp"
    override val settingsTitle = "Einstellungen"
    override val profile = "Profil"
    override val displayName = "Anzeigename"
    override val change = "ändern"
    override fun loggedInAs(u: String) = "Angemeldet als @$u"
    override val game = "Spiel"
    override val language = "Sprache"
    override val timezone = "Zeitzone (Tageswechsel für den Verfall)"
    override val milestoneStep = "Momentum pro Meilenstein"
    override val haptics = "Vibration beim Tippen"
    override val showHistory = "Verlauf auf dem Startscreen"
    override val changePassword = "Passwort ändern"
    override val oldPassword = "Altes Passwort"
    override val newPassword = "Neues Passwort"
    override val savePassword = "Passwort speichern"
    override val passwordChanged = "Passwort geändert"
    override val dangerZone = "Gefahrenzone"
    override val logout = "Abmelden"
    override val resetMomentum = "Momentum auf 0 setzen"
    override val deleteAccount = "Konto löschen"
    override val tapAgain = "Sicher? Nochmal tippen"
    override val searchTimezone = "Zeitzone suchen"
    override val cropTitle = "Ausschnitt wählen"
    override val cropHint = "Verschieben und mit zwei Fingern zoomen"
    override val cancel = "Abbrechen"
    override val usePhoto = "Übernehmen"
    override val removePhoto = "Bild entfernen"
    override val feed = "Feed"
    override val findPeople = "Leute finden"
    override val searchHint = "Name oder @username"
    override val emojiTitle = "Emoji"
    override val emojiSearch = "Emoji suchen (englisch)…"
    override val emojiRecent = "Zuletzt verwendet"
    override val emojiNone = "Nichts gefunden"
    override fun emojiGroup(name: String) = when (name) {
        "Smileys & Emotion" -> "Smileys & Gefühle"; "People & Body" -> "Menschen & Körper"; "Animals & Nature" -> "Tiere & Natur"
        "Food & Drink" -> "Essen & Trinken"; "Travel & Places" -> "Reisen & Orte"; "Activities" -> "Aktivitäten"
        "Objects" -> "Objekte"; "Symbols" -> "Symbole"; "Flags" -> "Flaggen"; else -> name
    }
    override val emptyFeed = "Noch nichts los"
    override val emptyFeedSub = "Folge Leuten, um ihre Posts und Meilensteine hier zu sehen."
    override val newPost = "Neuer Post"
    override val publish = "Posten"
    override val captionHint = "Schreib etwas… @name erwähnt Leute"
    override val addMedia = "Fotos & Videos"
    override val tagPeople = "Personen markieren"
    override val addLocation = "Ort hinzufügen"
    override val newStory = "Neue Story"
    override val whatToCreate = "Was möchtest du teilen?"
    override val postHint = "Bleibt in deinem Profil und im Feed"
    override val storyHint = "24 Stunden oben im Feed, danach im Archiv"
    override val yourStory = "Deine Story"
    override val storyShared = "Story geteilt"
    override val slideRules = "Regeln zum Bild"
    override val slideRulesHint = "Werden beim Posten getippt und dezent im Bild gezeigt – Regeln aus deinem Profil, bis zu 5."
    override val noProfileRules = "Mach zuerst Regeln in deinem Profil sichtbar (Regel lange drücken → Öffentlich)."
    override val camera = "Kamera"
    override val gallery = "Galerie"
    override val textOnImage = "Text"
    override val highlights = "Highlights"
    override val newHighlight = "Neu"
    override val highlightName = "Name des Highlights"
    override val addToHighlight = "Zu Highlight"
    override val deleteStory = "Story löschen"
    override val deleteHighlight = "Highlight löschen"
    override val removeFromHighlight = "Aus Highlight entfernen"
    override val pickStories = "Stories auswählen"
    override val noStoriesYet = "Noch keine Stories"
    override val captionForPhoto = "Bildunterschrift…"
    override val maxRulesPerSlide = "Höchstens 5 Regeln pro Bild"
    override val shareStory = "Teilen"
    override val tapToEdit = "Antippen: Text & Regeln"
    override val searchPlace = "Ort suchen…"
    override fun useTypedPlace(name: String) = "„$name“ verwenden"
    override val uploading = "Lädt hoch…"
    override fun likes(n: Int) = if (n == 1) "1 Gefällt mir" else "$n Gefällt mir"
    override fun viewComments(n: Int) = if (n == 1) "1 Kommentar ansehen" else "Alle $n Kommentare ansehen"
    override val comments = "Kommentare"
    override val addComment = "Kommentieren…"
    override val blockUser = "Blockieren"
    override val unblockUser = "Blockierung aufheben"
    override fun blockConfirm(name: String) = "$name blockieren?"
    override val blockConfirmHint = "Ihr folgt euch dann nicht mehr, könnt euch keine Nachrichten schicken und seht eure Profile, Posts und Kommentare nicht. Du kannst das jederzeit aufheben."
    override val blockedTitle = "Blockiert"
    override val blockedSub = "Du hast dieses Konto blockiert."
    override val blockedAccounts = "Blockierte Konten"
    override val noBlocked = "Du hast niemanden blockiert."
    override val reply = "Antworten"
    override fun viewReplies(n: Int) = if (n == 1) "— 1 Antwort ansehen" else "— $n Antworten ansehen"
    override val hideReplies = "— Antworten ausblenden"
    override fun replyTo(name: String) = "Antwort an $name"
    override val with = "mit"
    override fun milestonePost(vehicle: String) = "hat einen Meilenstein erreicht: $vehicle"
    override val deletePost = "Post löschen"
    override val posts = "Posts"
    override val followers = "Follower"
    override val followingLabel = "Folge ich"
    override val follow = "Folgen"
    override val followBack = "Zurückfolgen"
    override val followingButton = "Gefolgt"
    override val friend = "Freund"
    override val oneOfNine = "One of the Nine"
    override val theNine = "The Nine"
    override fun nineName(key: String) = mapOf(
        "goat" to "GOAT", "larper" to "Larper", "hermes" to "Hermes", "yapper" to "Yapper", "charmeur" to "Charmeur",
        "gamemaster" to "Gamemaster", "veteran" to "Veteran", "clown" to "Clown", "supercharged" to "Supercharged",
    )[key] ?: key
    override fun nineLore(key: String) = mapOf(
        "goat" to "Greatest of all time. Als Ranglisten noch Höhlenmalereien waren, stand dieser Name schon ganz oben. Andere sammeln Momentum – der GOAT ist Momentum.",
        "larper" to "Tut so, als wäre er die größte Momentum-Legende aller Zeiten: Kartonrüstung, geliehener Heldenmut, große Geschichten. Dahinter steckt eine kleine Echse, die ihr Momentum nie so verdient hat, wie sie behauptet. Die Community hat abgestimmt.",
        "hermes" to "Bote der Götter, Flügel an den Sandalen. Kein Bug entkommt, kein Wunsch bleibt ungehört – Hermes hat ihn gemeldet, bevor du ihn überhaupt bemerkt hast.",
        "yapper" to "Spricht in Kommentaren, denkt in Kommentaren, träumt in Kommentaren. Man sagt, Yapper hat mal einen Sonnenuntergang kommentiert – und der hat zurückgeschrieben.",
        "charmeur" to "Ein Lächeln, ein Post, hundert Herzen. Charmeur sammelt Likes, wie andere Luft holen – und hat noch nie jemanden um eines gebeten.",
        "gamemaster" to "Schreibt die Regeln, nach denen alle anderen spielen. Wenn du eine Regel abonnierst, sitzt der Gamemaster irgendwo und nickt zufrieden.",
        "veteran" to "War schon da, als das Momentum noch kriechen lernte. Jeden Tag ein Login, jeden Tag ein Stück Geschichte – der Veteran hat sie alle gesehen.",
        "clown" to "Postet, als gäbe es kein Morgen. Stories um drei Uhr nachts, Fotos vom Frühstück, vom zweiten Frühstück. Die Manege gehört dem Clown.",
        "supercharged" to "Von null auf Lichtgeschwindigkeit in dreißig Tagen. Wo Supercharged vorbeizieht, riecht es nach verbranntem Gummi und frischem Momentum.",
    )[key] ?: ""
    override fun nineHow(key: String) = mapOf(
        "goat" to "Hab das höchste gewertete Momentum von allen – im Wettbewerb, mit Tageslimit.",
        "larper" to "Werde gewählt: Am 1. jedes Monats (und sobald der Titel frei ist) stimmen alle ab, wer nur so tut, als hätte er sein Momentum verdient. Die Wahl endet, wenn der Tag vorbei ist und mindestens 10 Spieler abgestimmt haben – sonst läuft sie weiter.",
        "hermes" to "Melde die meisten Bugs und Wünsche, die dann wirklich umgesetzt werden – der King of Reporters.",
        "yapper" to "Schreib die meisten Kommentare.",
        "charmeur" to "Sammle die meisten Likes auf deinen Posts.",
        "gamemaster" to "Hab die meisten Abonnenten auf deinen eigenen Regeln und deinem Decay – Regeln aus Gruppen zählen nicht.",
        "veteran" to "Nutze die App an den meisten Tagen – jeder Tag online zählt einmal.",
        "clown" to "Mach die meisten Posts und Stories. Gelöschte zählen weiter, mehr als 100 am Tag gehen nicht.",
        "supercharged" to "Hol in den letzten 30 Tagen das meiste gewertete Momentum von allen.",
    )[key] ?: ""
    override fun nineUnit(key: String, n: String) = when (key) {
        "goat" -> "$n Momentum"
        "larper" -> "$n Stimmen"
        "hermes" -> "$n Meldungen"
        "yapper" -> "$n Kommentare"
        "charmeur" -> "$n Likes"
        "gamemaster" -> "$n Abonnenten"
        "veteran" -> "$n Tage"
        "clown" -> "$n Posts"
        "supercharged" -> "+$n in 30 Tagen"
        else -> n
    }
    override val nineHolder = "Trägt den Titel"
    override val nineNobody = "Noch niemand"
    override fun nineMine(value: String, rank: Int) = "Du: $value · Platz $rank weltweit"
    override val nineNotCounted = "Du: noch nichts gezählt"
    override val nineHowTitle = "So bekommst du den Titel"
    override val electionTab = "Wahl"
    override val electionTitle = "Larper-Wahl"
    override val electionIntro = "Wer tut nur so, als hätte er sein Momentum verdient? Stimm ab – eine Stimme pro Spieler, du kannst sie bis zum Ende ändern."
    override fun electionVotes(n: Int, needed: Int) = "$n von $needed nötigen Stimmen"
    override fun electionEnds(date: String) = "Endet am $date – sobald mindestens 10 abgestimmt haben"
    override fun electionNext(date: String) = "Die nächste Wahl startet am $date"
    override val electionClosed = "Gerade läuft keine Wahl."
    override fun electionMyVote(name: String) = "Deine Stimme: @$name"
    override val electionVote = "Wählen"
    override val electionVoted = "Gewählt"
    override val electionSearch = "Spieler suchen …"
    override val electionLeading = "Vorne liegen"
    override val electionCurrentLarper = "Aktueller Larper"
    override val electionOpenHint = "Die Larper-Wahl läuft – wer tut nur so, als hätte er sein Momentum verdient?"
    override val electionOpenAction = "Jetzt abstimmen"
    override val message = "Nachricht"
    override val noPosts = "Noch keine Posts"
    override val noUsers = "Niemand gefunden"
    override val activity = "Aktivität"
    override val noActivity = "Noch keine Aktivität"
    override fun notification(kind: String) = when (kind) {
        "follow" -> "folgt dir jetzt."
        "like" -> "gefällt dein Post."
        "comment" -> "hat deinen Post kommentiert."
        "clike" -> "gefällt dein Kommentar."
        "reply" -> "hat auf deinen Kommentar geantwortet."
        "mention" -> "hat dich erwähnt."
        "tag" -> "hat dich in einem Post markiert."
        "request" -> "möchte dir folgen."
        "accepted" -> "hat deine Anfrage bestätigt."
        else -> ""
    }
    override val chats = "Nachrichten"
    override val newMessage = "Neue Nachricht"
    override val newGroup = "Neue Gruppe"
    override val groupName = "Gruppenname"
    override val create = "Erstellen"
    override val noChats = "Noch keine Unterhaltungen"
    override val typeMessage = "Nachricht…"
    override val photo = "Foto"
    override val you = "Du"
    override val leaveGroup = "Gruppe verlassen"
    override fun members(n: Int) = "$n Mitglieder"
    override val pickPeople = "Leute, mit denen du verbunden bist (einer folgt dem anderen)"
    override val shareMilestone = "Im Feed teilen"
    override val shared = "Geteilt!"
    override val showOnProfile = "Im Profil zeigen"
    override val showOnProfileHint = "Andere sehen die Regel und ihren Verlauf."
    override val rulesTitle = "Regeln"
    override val usernameHint = "3-30 Zeichen: a-z, 0-9, _ und ."
    override val devOn = "Entwicklermodus an"
    override val devOff = "Entwicklermodus aus"
    override val devSection = "Entwickler"
    override val noContacts = "Noch keine Kontakte"
    override val noContactsSub = "Folge Leuten oder lass dir folgen, dann tauchen sie hier auf."
    override val nothingToSave = "Nichts geändert"
    override val seenBy = "Gesehen von"
    override val noViewsYet = "Noch hat niemand sie gesehen"
    override val likedBy = "Gefällt"
    override val email = "E-Mail"
    override val bio = "Bio"
    override val bioHint = "Erzähl was über dich… (@name erwähnt Leute)"
    override val noPublicRules = "Keine sichtbaren Regeln"
    override val noPublicRulesMine = "Schalte bei einem Beschleuniger oder Bremser „Im Profil zeigen“ ein."
    override val noHistory = "Noch kein Verlauf"
    override val offlineNow = "Offline"
    override fun offlinePending(n: Int) = if (n == 1) "Offline · 1 Änderung wartet" else "Offline · $n Änderungen warten"
    override val offlineStale = "Offline – wird nicht aktualisiert"
    override val needsInternet = "Dafür brauchst du eine Internetverbindung"
    override val offlineTitle = "Keine Verbindung"
    override val offlineSub = "Dieser Bereich braucht Internet. Sobald du wieder online bist, geht's weiter."
    override val undoTitle = "Rückgängig machen?"
    override val undo = "Rückgängig"
    override val pendingSync = "wartet auf Sync"
    override fun milestoneEvent(vehicle: String) = "Meilenstein erreicht: $vehicle"
    override fun limitLeft(left: String, cap: String) = "Heute noch $left von $cap Momentum möglich"
    override fun limitPartial(amount: String) = "Tageslimit erreicht – nur +$amount gutgeschrieben"
    override val limitZeroTap = "Tageslimit erreicht – zählt mit +0, aber die automatische Regel startet neu"
    override val limitReached = "Tageslimit erreicht – morgen geht's weiter"
    override val emailOrUsername = "E-Mail oder Benutzername"
    override val confirmEmailTitle = "E-Mail bestätigen"
    override fun confirmEmailSent(email: String) = "Wir haben dir einen Code an $email geschickt. Gib ihn hier ein."
    override fun confirmEmailAsk(email: String) = "Bestätige deine E-Mail-Adresse: Wir schicken dir einen Code an $email."
    override val confirmCode = "6-stelliger Code"
    override val confirmButton = "Bestätigen"
    override val sendCode = "Code senden"
    override val resendCode = "Code erneut senden"
    override fun resendIn(seconds: Int) = "Neuer Code in $seconds s"
    override val otherAddress = "Andere Adresse verwenden"
    override val newEmail = "Neue E-Mail-Adresse"
    override val checkSpam = "Nichts angekommen? Schau auch im Spam-Ordner nach."
    override fun confirmDue(date: String) = "Bitte bestätige deine E-Mail-Adresse bis $date – danach geht es erst nach der Bestätigung weiter."
    override val confirmNow = "Jetzt bestätigen"
    override val emailConfirmed = "E-Mail-Adresse bestätigt"
    override val forgotPassword = "Passwort vergessen?"
    override val forgotHint = "Gib deine E-Mail-Adresse oder deinen Benutzernamen ein. Wir schicken dir einen Code an deine bestätigte Adresse."
    override val forgotSent = "Wenn es dazu ein Konto mit bestätigter E-Mail-Adresse gibt, ist jetzt ein Code unterwegs."
    override val backToLogin = "Zurück zum Login"
    override val googleSignIn = "Mit Google fortfahren"
    override val googleFailed = "Google-Anmeldung fehlgeschlagen"
    override val googleAccount = "Google-Konto"
    override val legalSection = "Rechtliches"
    override val privacyPolicy = "Datenschutz"
    override val termsOfUse = "Nutzungsbedingungen"
    override val acceptTerms = "Mit der Anmeldung akzeptierst du die Nutzungsbedingungen und die Datenschutzerklärung."
    override val invalidEmail = "Bitte eine gültige E-Mail-Adresse eingeben"
    override val chooseUsername = "Wähle deinen Benutzernamen"
    override val chooseUsernameSub = "So finden dich andere und so wirst du mit @ erwähnt."
    override val milestoneReached = "Meilenstein erreicht"
    override val levelUp = "Level up!"
    override val nowDriving = "Du fährst jetzt:"
    override val next = "Weiter"
    override val privateAccount = "Privates Konto"
    override val privateAccountHint = "Nur bestätigte Follower sehen Posts, Verlauf, Follower und Regeln."
    override val requested = "Angefragt"
    override val lockedTitle = "Dieses Konto ist privat"
    override val lockedSub = "Folge diesem Konto, um Posts, Regeln und Verlauf zu sehen."
    override val followRequests = "Follower-Anfragen"
    override val accept = "Bestätigen"
    override val decline = "Löschen"
    override fun requestsCount(n: Int) = if (n == 1) "1 Anfrage" else "$n Anfragen"
    override val subscribe = "Abonnieren"
    override val unsubscribe = "Abo beenden"
    override val subscribed = "Abonniert"
    override val share = "Teilen"
    override val shareTo = "Teilen mit…"
    override fun ruleBy(owner: String) = "Regel von $owner"
    override val groupRule = "Gruppenregel"
    override val challengeRule = "Challenge-Regel"
    override val yourRule = "Deine Regel"
    override fun subscribers(n: Int) = if (n == 1) "1 Abonnent" else "$n Abonnenten"
    override fun subscribedHint(owner: String) = "Änderungen von $owner bekommst du automatisch."
    override val subscribedHintGroup = "Updates zu dieser Regel bekommst du automatisch."
    override fun onlyMembersCanSubscribe(group: String?) =
        if (group != null) "Abonnieren können nur Mitglieder der Gruppe $group." else "Abonnieren können nur Mitglieder dieser privaten Gruppe."
    override fun origin(origin: String, label: String) = when (origin) {
        "sub" -> "abonniert · $label"
        "group" -> "Gruppe · $label"
        "challenge" -> "Challenge · $label"
        else -> ""
    }
    override val notAvailable = "Nicht verfügbar"
    override val readOnlyRule = "Abonnierte Regeln kann nur ihr Ersteller ändern."
    override val sharedRule = "Regel"
    override val sharedPost = "Post"
    override val sharedProfile = "Profil"
    override val groupInfo = "Gruppe"
    override val groupRules = "Gruppenregeln"
    override val noGroupRules = "Noch keine Gruppenregeln"
    override val newGroupRule = "Gruppenregel"
    override val challenges = "Challenges"
    override val noChallenges = "Noch keine Challenges"
    override val newChallenge = "Neue Challenge"
    override val membersTitle = "Mitglieder"
    override val modeRanking = "Rangliste"
    override val modeTarget = "Ziel"
    override val modeRankingHint = "Wer am Ende das meiste Momentum mit den Regeln hat, gewinnt."
    override val modeTargetHint = "Alle, die das Ziel in der Zeit erreichen, bekommen die Belohnung."
    override val challengeName = "Name der Challenge"
    override val duration = "Dauer"
    override val startLabel = "Start"
    override fun startOption(n: Int) = when (n) { 0 -> "heute"; 1 -> "morgen"; else -> "in $n Tagen" }
    override fun rewardPlace(i: Int) = "Belohnung $i. Platz"
    override val optional = "optional"
    override val targetLabel = "Ziel (Momentum)"
    override val rewardLabel = "Belohnung"
    override val challengeRules = "Regeln"
    override val challengeHint = "Wer mitmacht, bekommt diese Regeln – am Ende verschwinden sie wieder."
    override val join = "Mitmachen"
    override val leaveChallenge = "Verlassen"
    override val reallyLeave = "Endgültig verlassen?"
    override val cancelChallenge = "Abbrechen"
    override val reallyCancel = "Wirklich abbrechen?"
    override fun challengeStatus(status: String, daysLeft: Int?, startsIn: Int?) = when (status) {
        "upcoming" -> if (startsIn == 1) "startet morgen" else "startet in $startsIn Tagen"
        "running" -> if (daysLeft == 1) "letzter Tag" else "noch $daysLeft Tage"
        "finished" -> "beendet"
        else -> "abgebrochen"
    }
    override fun joinedCount(n: Int) = "$n dabei"
    override val leaderboard = "Rangliste"
    override val yourScore = "Dein Stand"
    override val leftChallenge = "Du hast diese Challenge verlassen."
    override val challengeMissing = "Name und mindestens eine Regel fehlen"
    override fun challengeResult(title: String, rank: Int?, reward: String) =
        "„$title“ ist vorbei" + (rank?.let { " – Platz $it" } ?: "") + (if (reward != "0") ", +$reward Momentum." else ".")
    override fun challengeCancelled(title: String) = "hat „$title“ abgebrochen."
    override val challenge = "Challenge"
    override fun rewardsLine(rewards: List<String>) = rewards.mapIndexed { i, r -> "${i + 1}. +$r" }.joinToString(" · ")
    override fun targetLine(target: String, reward: String) = "$target erreichen → +$reward"
    override fun place(n: Int) = "Platz $n"
    override fun createdBy(name: String) = "Erstellt von $name"
    override val banner = "Banner"
    override val chooseBanner = "Banner wählen"
    override val bannerAuto = "Automatisch"
    override fun bannerName(key: String) = mapOf(
        "dusk" to "Abendrot", "sky" to "Himmel", "cosmos" to "Weltall", "city" to "Großstadt", "desert" to "Wüste",
        "forest" to "Wald", "beach" to "Strand", "snow" to "Gipfel", "aurora" to "Polarlicht", "sakura" to "Kirschblüte",
        "volcano" to "Vulkan", "canyon" to "Canyon", "rain" to "Regenstadt", "moon" to "Mondbasis", "synthwave" to "Neon",
        "autumn" to "Herbst", "fields" to "Felder", "deepsea" to "Tiefsee", "storm" to "Gewitter", "sunrise" to "Morgengrauen",
        "cottoncandy" to "Zuckerwatte", "hearts" to "Herzchen", "strawberry" to "Erdbeerfeld", "meadow" to "Blumenwiese",
        "glitter" to "Glitzer", "lavender" to "Lavendel", "balloons" to "Ballonfahrt", "jungle" to "Dschungel",
        "harbor" to "Leuchtturm", "glacier" to "Gletscher", "hyperspace" to "Hyperraum", "ultrahyperspace" to "Ultra-Hyperraum",
        "ringplanet" to "Ringplanet", "redplanet" to "Roter Planet", "iceplanet" to "Eisplanet", "lavaplanet" to "Lavaplanet",
        "earthorbit" to "Erdorbit", "twinsuns" to "Doppelsonne", "nebula" to "Nebel", "asteroids" to "Asteroiden",
        "blackhole" to "Schwarzes Loch", "galaxy" to "Galaxie", "battlestation" to "Kampfstation",
        "wallcity" to "Mauerstadt", "ninjavillage" to "Ninja-Dorf", "redclouds" to "Rote Wolken", "grassroute" to "Monster-Route", "spiritforest" to "Geisterwald", "skyisland" to "Himmelsinsel", "tournament" to "Turnierinsel", "pirates" to "Piratenmeer", "wisteria" to "Glyzinien-Nacht", "moonprincess" to "Mondprinzessin", "neotokyo" to "Neo-Tokio", "comet" to "Komet", "torii" to "Torii-Schrein", "bathhouse" to "Badehaus", "fuji" to "Fuji-Express", "ramenalley" to "Ramen-Gasse", "schoolroof" to "Schuldach",
    )[key] ?: key
    override val limitInfoTitle = "So entsteht dein Tageslimit"
    override val limitProvisionalShort = "vorläufig"
    override val limitProvisional = "Du bist offline und es ist ein neuer Tag: Bis zur nächsten Verbindung gilt das Limit von gestern. Beim Synchronisieren rechnet der Server mit dem echten Limit von heute."
    override fun limitInfo(cap: String, average: String, bias: String, gained: String, left: String) = listOf(
        "Heute kannst du bis zu $cap Momentum sammeln.",
        "Das Limit ist eine Wachstumsrate, die sich täglich anpasst. Gestern sind die Spieler im Wettbewerb, die selbst etwas getippt haben, im Schnitt um $average gewachsen (ihre automatischen Taps zählen mit, der tägliche Verfall nicht): Liegt der Schnitt über 75 % der Rate, verdoppelt sie sich (bis 12.800), unter 25 % halbiert sie sich (bis 25). Sie gilt für alle gleich und steht für den ganzen Tag fest.",
        "Beschleuniger zählen dazu, Entschleuniger ziehen wieder ab. Du stehst heute bei $gained – noch $left möglich.",
        "Entschleuniger wirken immer, auch wenn das Limit erreicht ist.",
    )
    override fun bannerGroup(key: String) = when (key) {
        "pastel" -> "Pastell & verspielt"; "anime" -> "Anime"; "space" -> "Weltraum"; else -> "Klassisch"
    }
    override fun bannerKind(kind: String) = when (kind) { "air" -> "Luft"; "space" -> "Weltraum"; else -> "Straße" }
    override val addToGroup = "Zur Gruppe hinzufügen"
    override val fromMyRules = "Aus meinen Regeln"
    override fun followsRuleOf(name: String) = "folgt der Regel von $name"
    override fun empty(context: String, name: String) = when (context) {
        "chats" -> "Noch keine Unterhaltungen" to "Schreib jemandem, dem du folgst – oder starte eine Gruppe."
        "chat" -> "Niemand zum Schreiben" to "Schreiben kannst du Leuten, denen du folgst oder die dir folgen."
        "group" -> "Niemand für eine Gruppe" to "In Gruppen holen kannst du Leute, denen du folgst oder die dir folgen."
        "tag" -> "Niemand zum Markieren" to "Markieren kannst du Leute, denen du folgst oder die dir folgen."
        "followers" -> "Noch keine Follower" to "Teile dein Profil, damit dich andere finden."
        "following" -> "Du folgst noch niemandem" to "Finde Leute und sieh ihre Posts und Meilensteine im Feed."
        "followersOf" -> "Noch keine Follower" to "$name hat noch keine Follower."
        "followingOf" -> "Folgt noch niemandem" to "$name folgt noch niemandem."
        "requests" -> "Keine offenen Anfragen" to "Wenn dir jemand folgen möchte, siehst du es hier."
        else -> noContacts to noContactsSub
    }
    override val dailyLimitSetting = "Tageslimit · Community-Wettbewerb"
    override val dailyLimitHint = "Aus: kein Limit mehr, du sammelst freies Momentum. Dein Wettbewerbswert pausiert (auch kein Verfall) und läuft weiter, sobald du es wieder einschaltest."
    override val competition = "Wettbewerb"
    override val unranked = "Nicht gewertet"
    override val freeMomentum = "Freies Momentum"
    override fun rankedPaused(value: String) = "Wettbewerbswert pausiert: $value"
    override fun rankedValue(value: String) = "Freies Momentum: $value"
    override val noLimitBanner = "Ohne Tageslimit – du sammelst freies Momentum (nicht gewertet)."
    override val hyperspaceTitle = "Hyperraum!"
    override val hyperspaceSub = "1.000.000 Momentum – dein Raumschiff springt in den Hyperraum. Neuer Hintergrund freigeschaltet."
    override val useAsBackground = "Als Hintergrund nutzen"
    override val hyperspaceLocked = "ab 4 Mio. im Wettbewerb"
    override val hyperLore = "Alle sehnen sich danach, das ultimative Momentum zu erreichen."
    override val ultraLore = "Du kannst noch schneller werden?!"
    override fun idleTitle(acc: Boolean) = if (acc) "Wenn du es nicht tippst" else "Wenn du es lässt"
    override fun idleDays(n: Int) = when (n) { 0 -> "nie"; 1 -> "1 Tag"; else -> "$n Tagen" }
    override fun idleSentence(b: ClozeBuilder, on: Boolean, days: @Composable () -> Unit, rule: @Composable () -> Unit) {
        if (!on) {
            b.text("Automatisch tippen:"); b.gap("idle", days)
        } else {
            b.text("Nach"); b.gap("idle", days); b.text("ohne Tippen wird automatisch")
            b.gap("idlerule", rule); b.text("getippt – danach wieder nach jeweils dieser Zeit.")
        }
    }
    override fun idleNoCounterpart(acc: Boolean) =
        if (acc) "Lege einen Bremser an, um ihn hier automatisch tippen zu lassen." else "Lege einen Beschleuniger an, um ihn hier automatisch tippen zu lassen."
    override fun autoTapped(from: String) = "automatisch – „$from“ nicht getippt"
    override val updateTag = "Neue Version"
    override val updateTitle = "Zeit für ein Update"
    override val updateText = "Es gibt eine neue Version von Momentum. Lade sie auf der Webseite herunter und installiere sie – danach geht es genau dort weiter, wo du warst."
    override val updateButton = "Zur Webseite"
    override val whatsNewTitle = "Was ist neu"
    override val whatsNewHint = "Nach jedem Update zeigt dir Momentum, was sich geändert hat."
    override val whatsNewOpen = "Alle Änderungen"
    override val whatsNewOk = "Alles klar"
    override val limitUpTitle = "Tageslimit gestiegen"
    override val limitDownTitle = "Tageslimit gesunken"
    override fun limitChangeText(prev: String, cap: String) = "Heute kannst du bis zu $cap Momentum sammeln – gestern waren es $prev."
    override fun limitChangeWhy(average: String) = "Die Spieler im Wettbewerb sind gestern im Schnitt um $average gewachsen."
    override val systemFontSetting = "Systemschrift für Texte"
    override val notificationsSetting = "Benachrichtigungen"
    override val systemFontHint = "Nachrichten, Kommentare, Post-Texte und Bios in der normalen Schrift deines Handys statt in Pixel-Schrift."
    override val autoFollow = "Allen automatisch folgen"
    override val autoFollowHint = "Alle Regeln der Gruppe abonnieren – auch neue. Ausschalten lässt deine Abos bestehen."
    override val noPublicRulesForGroup = "Gruppen folgen öffentlichen Regeln. Schalte bei einer Regel „Im Profil zeigen“ an, um sie hier hinzuzufügen."
    override val lowerMomentum = "Momentum herabsetzen"
    override fun lowerMomentumHint(current: String) = "Du hast gerade $current. Setz es auf einen niedrigeren Wert – 0 heißt neu anfangen. Fahrzeuge darüber gehen dabei verloren."
    override val lowerMomentumInvalid = "Der Wert muss unter deinem aktuellen Momentum liegen."
    override fun lowerMomentumTo(value: String) = "Auf $value setzen"
    override val takePhoto = "Foto aufnehmen"
    override val takeVideo = "Video aufnehmen"
    override val recordVideo = "Video aufnehmen"
    override val fromGallery = "Aus der Galerie"
    override val reportTitle = "Report"
    override val reportBug = "Bug melden"
    override val reportFeature = "Feature-Wunsch"
    override val reportBugHint = "Was ist passiert, und was hättest du erwartet? Screenshots helfen sehr."
    override val reportFeatureHint = "Was wünschst du dir, und wofür würdest du es nutzen?"
    override val reportPlaceholder = "Beschreibung…"
    override val reportImages = "Bilder"
    override val reportSend = "Absenden"
    override val reportThanks = "Danke! Ist angekommen."
    override fun reportThanksBonus(n: String) = "Danke! Ist angekommen – +$n Momentum für dich."
    override val reportBonus = "Report-Bonus"
    override val reportSettingsHint = "Fehler gefunden oder eine Idee? Schick es uns – mit Bildern, wenn du magst."
    override val reportOpen = "Bug melden / Wunsch äußern"
    override val voiceMessage = "Sprachnachricht"
    override val releaseToSend = "Loslassen zum Senden"
    override val holdToRecord = "Zum Aufnehmen gedrückt halten"
    override val e2eTitle = "Ende-zu-Ende-Verschlüsselung"
    override val e2eHint = "Neue Nachrichten, Fotos und Sprachnachrichten können nur die Mitglieder des Chats lesen – nicht einmal der Server."
    override val e2eOnlyAdmins = "Nur Admins der Gruppe können das ändern."
    override val e2eChatNote = "Nachrichten in diesem Chat sind Ende-zu-Ende-verschlüsselt. Tippe für Details."
    override fun e2eSwitchedOn(name: String) = "$name hat die Ende-zu-Ende-Verschlüsselung eingeschaltet"
    override fun e2eSwitchedOff(name: String) = "$name hat die Ende-zu-Ende-Verschlüsselung ausgeschaltet"
    override val e2eYouSwitchedOn = "Du hast die Ende-zu-Ende-Verschlüsselung eingeschaltet"
    override val e2eYouSwitchedOff = "Du hast die Ende-zu-Ende-Verschlüsselung ausgeschaltet"
    override fun memberJoined(name: String) = "$name ist beigetreten"
    override val youJoined = "Du bist beigetreten"
    override val voiceCall = "Sprachanruf"
    override val videoCall = "Videoanruf"
    override val groupVoiceCall = "Gruppenanruf"
    override val groupVideoCall = "Gruppen-Videoanruf"
    override val outgoingVoiceCall = "Ausgehender Sprachanruf"
    override val outgoingVideoCall = "Ausgehender Videoanruf"
    override val missedVoiceCall = "Verpasster Sprachanruf"
    override val missedVideoCall = "Verpasster Videoanruf"
    override val missedGroupCall = "Verpasster Gruppenanruf"
    override val callNoAnswerShort = "keine Antwort"
    override val callDeclinedShort = "abgelehnt"
    override val callBusyShort = "besetzt"
    override val callIncomingVoice = "Eingehender Sprachanruf"
    override val callIncomingVideo = "Eingehender Videoanruf"
    override val callRinging = "Klingelt …"
    override val callCalling = "Ruft an …"
    override val callConnecting = "Verbinden …"
    override val callReconnecting = "Verbindung wird wiederhergestellt …"
    override val callWaiting = "Warten auf die anderen …"
    override val callEnded = "Anruf beendet"
    override val callBusy = "Besetzt"
    override val callDeclined = "Abgelehnt"
    override val callNoAnswer = "Keine Antwort"
    override val callAccept = "Annehmen"
    override val callDecline = "Ablehnen"
    override val callHangUp = "Auflegen"
    override val callMute = "Stumm"
    override val callCamera = "Kamera"
    override val callFlip = "Wechseln"
    override val callSpeaker = "Lautsprecher"
    override val callMinimize = "Verkleinern"
    override val callBackTo = "Tippen, um zum Anruf zurückzukehren"
    override val callJoin = "Beitreten"
    override val callAlready = "Du bist schon in einem Anruf"
    override val callNoMic = "Ohne Mikrofon kein Anruf – erlaube den Zugriff in den Einstellungen"
    override val callNoCamera = "Die Kamera ist nicht verfügbar"
    override val callNeedsInternet = "Für Anrufe brauchst du Internet"
    override val callUnsupported = "Dieser Browser kann keine Anrufe"
    override val callsChannel = "Anrufe"
    override val callOngoing = "Laufender Anruf"
    override val callStateRinging = "klingelt"
    override fun callRunning(count: Int) = "Anruf läuft · $count dabei"
    override fun callFromIn(name: String, group: String) = "$name · $group"
    override fun memberAdded(actor: String, target: String) = "$actor hat $target hinzugefügt"
    override fun youAdded(target: String) = "Du hast $target hinzugefügt"
    override fun addedYou(actor: String) = "$actor hat dich hinzugefügt"
    override fun memberRemoved(actor: String, target: String) = "$actor hat $target entfernt"
    override fun youRemoved(target: String) = "Du hast $target entfernt"
    override fun removedYou(actor: String) = "$actor hat dich entfernt"
    override fun memberLeft(name: String) = "$name hat die Gruppe verlassen"
    override val youLeft = "Du hast die Gruppe verlassen"
    override val removeMember = "Aus der Gruppe entfernen"
    override fun removeMemberAsk(name: String) = "$name aus der Gruppe entfernen?"
    override fun subscribedRule(rule: String) = "hat deine Regel „$rule“ abonniert."
    override fun subscribedRuleGroup(rule: String, group: String) = "hat deine Regel „$rule“ für die Gruppe $group abonniert."
    override val followsDecay = "folgt jetzt deinem Decay."
    override fun decayInGroup(group: String) = "hat deinen Decay in die Gruppe $group übernommen."
    override val e2eSealed = "Diese Nachricht kann auf diesem Gerät nicht gelesen werden."
    override val e2eLocked = "Verschlüsselte Nachricht – entsperre deine Chats, um sie zu lesen."
    override val e2eNeedsUnlock = "Entsperre zuerst deine verschlüsselten Chats."
    override val e2eUnsupported = "Dieser Browser kann keine verschlüsselten Chats."
    override val e2eNewMessage = "Neue Nachricht"
    override val safetyCodes = "Sicherheitscodes"
    override val safetyHint = "Vergleicht eure Codes persönlich: Stimmen sie auf beiden Handys überein, liest niemand mit."
    override val yourCode = "Dein Code"
    override fun keyChanged(name: String) = "Der Sicherheitscode von $name hat sich geändert (neues Gerät oder neuer Schlüssel)."
    override val keyChangedOk = "Verstanden"
    override val recoveryTitle = "Wiederherstellungsschlüssel"
    override val recoveryIntro = "Mit diesem Schlüssel liest du deine verschlüsselten Chats auf einem neuen Gerät. Notier ihn dir und bewahr ihn sicher auf – wir haben ihn nicht und können ihn nicht wiederherstellen."
    override val recoveryCopy = "Kopieren"
    override val recoveryNoted = "Ich habe ihn notiert"
    override val recoveryCard = "Sichere deinen Wiederherstellungsschlüssel"
    override val recoveryCardHint = "Ohne ihn kannst du verschlüsselte Chats auf einem neuen Gerät nicht lesen."
    override val unlockTitle = "Chats entsperren"
    override val unlockIntro = "Auf diesem Gerät fehlt dein Schlüssel. Gib deinen Wiederherstellungsschlüssel ein, um verschlüsselte Chats zu lesen und zu schreiben."
    override val unlockPlaceholder = "ABCD EFGH …"
    override val unlockButton = "Entsperren"
    override val unlockWrong = "Der Schlüssel passt nicht."
    override val unlockCard = "Verschlüsselte Chats entsperren"
    override val unlockCardHint = "Gib deinen Wiederherstellungsschlüssel ein."
    override val resetKey = "Schlüssel vergessen? Neuen anlegen"
    override val resetKeyWarning = "Mit einem neuen Schlüssel kannst du deine bisherigen verschlüsselten Nachrichten nicht mehr lesen. Deine Kontakte sehen, dass sich dein Sicherheitscode geändert hat."
    override val resetKeyConfirm = "Neuen Schlüssel anlegen"
    override val chatInfo = "Chat-Info"
    override val muteChat = "Stummschalten"
    override val muteChatHint = "Keine Benachrichtigungen – Erwähnungen kommen trotzdem an."
    override val micReady = "Mikrofon bereit – jetzt zum Aufnehmen gedrückt halten"
    override val micBlocked = "Kein Zugriff aufs Mikrofon – erlaube ihn in den Einstellungen"
    override val addMembers = "Mitglieder hinzufügen"
    override fun addMembersCount(n: Int) = if (n == 1) "1 Person hinzufügen" else "$n Personen hinzufügen"
    override val noOneToAdd = "Alle, denen du folgst oder die dir folgen, sind schon drin."
    override fun mentionedYou(sender: String, chat: String?) = if (chat != null) "$sender hat dich in $chat erwähnt" else "$sender hat dich erwähnt"
    override val mentionedPreview = "@ Du wurdest erwähnt"
    override val accShort = "BESCHL."
    override val decShort = "ENTSCHL."
    override val dragToSort = "Gedrückt halten zum Auswählen"
    override val decayPublic = "Andere dürfen folgen"
    override val decayPublicHint = "Dein Decay steht in deinem Profil. Wer ihm folgt, nutzt ihn statt seines eigenen."
    override fun decayOf(name: String) = "Tages-Decay von $name"
    override fun groupDecayOf(group: String) = "Decay der Gruppe $group"
    override val followDecay = "Folgen"
    override val followDecayHint = "Du hast immer genau einen Decay: dieser ersetzt deinen eigenen. Abbestellen bringt deinen zurück."
    override val decayFollowing = "Du folgst diesem Decay – er gilt statt deines eigenen."
    override fun decayLockedBy(title: String) = "Die Challenge „$title“ legt deinen Decay fest – ändern geht erst nach dem Austritt."
    override val groupDecays = "Decays"
    override val newGroupDecay = "Neuer Decay"
    override val noGroupDecays = "Noch keine Decays in der Gruppe."
    override val decayName = "Name des Decays"
    override val challengeDecay = "Decay (optional)"
    override val noDecay = "Keiner"
    override val challengeDecayHint = "Wer mitmacht, folgt diesem Decay, solange die Challenge läuft."
    override val publicDecay = "Öffentlicher Decay"
    override val haveRuleAlready = "Diese Regel hast du schon – auf anderem Weg abonniert oder deine eigene."
    override val ownDecayInGroup = "Das ist dein eigener Decay – die Gruppe folgt ihm."
    override fun followsDecayOf(name: String) = "folgt dem Decay von $name"
    override val adminTag = "Admin"
    override val makeAdmin = "Zum Admin machen"
    override val removeAdmin = "Admin-Rechte entziehen"
    override val viewProfile = "Profil ansehen"
    override val anarchyMode = "Anarchie-Modus"
    override val anarchyHint = "Alle Mitglieder sind Admins: Regeln, Challenges, Mitglieder und Einstellungen darf jeder ändern."
    override val onlyAdmins = "Ändern können das nur die Admins der Gruppe."
    override val tappableSetting = "Antippen erlaubt"
    override val tappableHint = "Aus: Ein Tipp auf die Karte zählt nicht – die Regel wirkt nur über das automatische Tippen. Gilt auch für alle, die sie abonnieren."
    override val notTappable = "Diese Regel kann man nicht antippen."
    override val subscribedLabel = "abonniert"
    override val leaderboardTitle = "Rangliste"
    override val scopeGlobal = "Weltweit"
    override val scopeFriends = "Freunde"
    override val yourPlace = "Dein Platz"
    override val paused = "pausiert"
    override fun placeOf(rank: Int, total: Int) = "Platz $rank von $total"
    override val groupRanking = "Rangliste"
    override val groupPublic = "Öffentlich"
    override val groupPublicHint = "In der Suche auffindbar – Übersicht, Mitglieder und öffentliche Regeln sieht jeder."
    override val groupOpen = "Offen für Beitritt"
    override val groupOpenHint = "Jeder kann beitreten. Geht nur bei öffentlichen Gruppen."
    override val joinGroup = "Beitreten"
    override val closedGroup = "Geschlossene Gruppe – Mitglieder können dich hinzufügen."
    override val tagPublic = "ÖFFENTLICH"
    override val tagPrivate = "PRIVAT"
    override val tagOpen = "OFFEN"
    override val addGroupRule = "Regel zur Gruppe"
    override val followPublicRule = "Öffentlicher Regel folgen"
    override val followPublicHint = "Bleibt verknüpft – Änderungen des Erstellers kommen automatisch an."
    override val createGroupRule = "Neue Regel erstellen"
    override val createGroupRuleHint = "Gehört der Gruppe. Öffentlich gestellt können sie auch andere abonnieren."
    override val copyRule = "Eigene Regel kopieren"
    override val copyRuleHint = "Echte Kopie ohne Verknüpfung – auch von Regeln, die du abonniert hast."
    override val noRulesToCopy = "Du hast noch keine Regeln."
    override val copyToGroup = "In eine Gruppe kopieren"
    override val copied = "Kopiert"
    override val groupsTitle = "Gruppen"
    override val challengeOnlyGroupRules = "Challenges spielen mit Regeln aus der Gruppe. Beim Start abonnieren alle Teilnehmer sie automatisch."
    override val keepRules = "Regeln danach behalten"
    override val keepRulesHint = "Sonst verschwinden die Abos der Challenge, wenn du austrittst oder sie endet."
    override fun lockedByChallenge(title: String) = "Teil der Challenge „$title“ – abbestellen geht erst nach dem Austritt."
    override fun groupRuleOf(group: String) = "Regel der Gruppe $group"
    override val groupRulePublicHint = "Andere können sie abonnieren, wenn die Gruppe öffentlich ist."
    override val idlePause = "Automatisches Antippen pausieren"
    override val idlePauseHint = "Solange pausiert, wird nichts automatisch angetippt."
    override val idlePausedShort = "Auto-Tippen pausiert"
    override fun idleLinked(days: String, name: String) = "Nach $days ohne Tippen wird automatisch „$name“ angetippt – beim Abonnieren kommt sie mit."
    override val subscribeForGroup = "Für eine Gruppe abonnieren"
    override val followPublicFromRule = "Öffentliche Regeln abonnierst du für die Gruppe direkt in der Regel selbst."
    override val widgetPickGroup = "Welche Gruppe soll das Widget zeigen?"
    override val widgetLoading = "Wird geladen …"
    override fun challengeCreditLine(amount: String) = "Challenge-Guthaben: $amount"
    override val challengeCreditHint = "Belohnungen kommen über die Tage: Jeder neue Tag füllt damit auf, was am Vortag unter dem Limit frei geblieben ist."
    override fun rewardMax(max: String) = "höchstens $max"
    override val chatMuted = "Stummgeschaltet – Erwähnungen kommen trotzdem an"
    override val chatUnmuted = "Benachrichtigungen an"
    override val uploadFailed = "Hochladen fehlgeschlagen – versuch es nochmal"
    override fun serverDown(code: Int) = "Der Server antwortet gerade nicht (Fehler $code) – wahrscheinlich ein Neustart, versuch es gleich nochmal"
    override fun serverError(code: Int) = "Fehler $code – der Server hat keine Erklärung mitgeschickt"
    override val groupTotal = "GESAMT"
    override val groupLook = "Aussehen der Gruppe"
    override val pickPixelBanner = "Pixel-Banner wählen"
    override val ownBannerImage = "Eigenes Bild"
    override val groupPicture = "Gruppenbild"
    override val removeImages = "Bild entfernen"
    override val viewPost = "Post ansehen"
    override fun replyingTo(name: String) = "↩ $name"
    override val pasteImage = "Bild aus der Zwischenablage einfügen"
    override val linkPreviewLoading = "Vorschau wird geladen…"
    override fun typingOne(name: String) = "$name schreibt…"
    override fun typingTwo(a: String, b: String) = "$a und $b schreiben…"
    override fun typingMany(n: Int) = "$n Leute schreiben…"
    override fun recordingOne(name: String) = "$name nimmt eine Sprachnachricht auf…"
    override fun recordingMany(n: Int) = "$n nehmen Sprachnachrichten auf…"
    override val typingShort = "schreibt…"
    override val recordingShort = "nimmt Audio auf…"
    override val replyToStory = "Antworten…"
    override val sharedStory = "Story"
    override val viewStory = "Story ansehen"
    override val storyReplySent = "Gesendet"
    override val sensitiveTitle = "Sensibler Inhalt"
    override val sensitiveHint = "Kann Nacktheit oder sexuelle Inhalte zeigen."
    override val sensitiveShow = "Anzeigen"
    override val storyDuration = "Dauer"
    override val soundRangeHint = "Zieh die Enden: dieser Teil läuft"
    override val soundStoryHint = "So lang wie die Story – verschieb den Ausschnitt"
    override val ruleSharedPost = "hat eine neue Regel geteilt"
    override val ruleUpdatedPost = "hat eine Regel aktualisiert"
    override val periodTotal = "Gesamt"
    override val period24h = "Heute"
    override fun limitForecast(average: String, players: Int, next: String) =
        "Heute bisher: im Schnitt $average bei $players aktiven Spielern → morgen voraussichtlich $next (steht erst um Mitternacht fest)."
    override val readBy = "Gelesen von"
    override fun notReadYet(n: Int) = "Noch nicht gelesen: $n"
    override val reactions = "Reaktionen"
    override val compressing = "Wird verkleinert…"
    override val processing = "Wird verarbeitet…"
    override val yesterday = "Gestern"
    override fun deletedIn(days: Int, hm: String) = "Gelöscht in $days T $hm"
    override val photoArchived = "Archiviert · kleine Kopie"
    override val originalSound = "Originalton"
    override val addSound = "Sound hinzufügen"
    override val searchSong = "Song suchen…"
    override val songsByDeezer = "Songausschnitte von Deezer"
    override val ownSound = "Oder ein eigener Sound"
    override val pickAudioFile = "Audiodatei wählen"
    override val recordSound = "Gedrückt halten zum Aufnehmen"
    override val recordSoundHint = "Oder nimm selbst etwas auf:"
    override val soundTitle = "Name des Sounds"
    override fun soundPart(from: String, to: String) = "Verwendet: $from – $to"
    override val unsubscribeAll = "Alle abbestellen"
    override val dropToDelete = "Hier ablegen zum Löschen"
    override val dropToUnsubscribe = "Hier ablegen zum Abbestellen"
    override fun deleteRuleQuestion(name: String) = "„$name“ löschen?"
    override val takePhotoOrVideo = "Kamera"
    override val videoLabel = "Video"
    override val removeFromGroup = "Aus der Gruppe entfernen"
    override val reallyRemoveFromGroup = "Wirklich entfernen?"
    override val addText = "Text"
    override val overlayHint = "Text schreiben – dann im Bild verschieben"
    override val done = "Fertig"
    override val noPublicGroups = "Noch keine öffentlichen Gruppen."
    override fun selectedCount(n: Int) = "$n ausgewählt"
    override val selectAll = "Alle"
    override fun deleteN(n: Int) = "Löschen ($n)"
    override fun unsubscribeN(n: Int) = "Abbestellen ($n)"
    override fun subscribeN(n: Int) = "Abonnieren ($n)"
    override fun removeN(n: Int) = "Entfernen ($n)"
    override fun makePublicN(n: Int) = "Öffentlich ($n)"
    override fun makePrivateN(n: Int) = "Privat ($n)"
    override val inProfile = "In meinem Profil zeigen"
    override val inProfileHint = "Die Regel und deine Taps erscheinen in deinem Profil und dessen Verlauf."
    override fun bulkResult(done: Int, skipped: Int) = if (skipped == 0) "Erledigt: $done" else "Erledigt: $done · übersprungen: $skipped"
    override fun confirmBulkRemove(delete: Int, unsubscribe: Int) = listOfNotNull(
        if (delete > 0) "$delete löschen" else null, if (unsubscribe > 0) "$unsubscribe abbestellen" else null).joinToString(" und ") + "?"
    override fun bulkLockedHint(n: Int) = "$n davon bleiben – eine laufende Challenge spielt mit ihnen."
    override fun confirmRemoveFromGroup(n: Int) = "$n aus der Gruppe entfernen?"
    override val removeFromGroupHint = "Alle Abos dieser Regeln über die Gruppe verschwinden mit."
    override val notificationsAllowed = "Android erlaubt Benachrichtigungen."
    override val notificationsBlocked = "Android blockiert Benachrichtigungen für Momentum – erlaube sie in den Systemeinstellungen."
    override val notificationsBlockedWeb = "Benachrichtigungen sind noch nicht erlaubt."
    override val notificationsInstallHint = "Auf dem iPhone kommen Benachrichtigungen nur, wenn Momentum auf dem Home-Bildschirm liegt: in Safari Teilen → „Zum Home-Bildschirm“, dann von dort öffnen."
    override val allowNotifications = "Erlauben"
    override val notifyAskTitle = "Benachrichtigungen erlauben?"
    override val notifyAskSub = "Damit du Nachrichten, Likes, Kommentare und Challenge-Ergebnisse sofort mitbekommst – auch wenn die App zu ist."
    override val notNow = "Später"
    override val openSystemSettings = "Systemeinstellungen"
    override val testNotification = "Testen"
    override val testNotificationText = "So sehen Benachrichtigungen von Momentum aus."
}

object En : Strings {
    override val lang = Lang.EN
    override val saved = "Saved"
    override val nameMissing = "Enter a name"
    override val unreachable = "Can't reach the server"
    override val sessionExpired = "Session expired"
    override val tagline = "Measure your inner"
    override val username = "Username"
    override val password = "Password"
    override val login = "Let's go"
    override val register = "Create account"
    override val toRegister = "New here? Create an account"
    override val toLogin = "Already in? Log in"
    override val server = "Server"
    override val offline = "Can't reach the server.\nIs python -m server running on the PC?"
    override val retry = "Try again"
    override val hello = "Hi"
    override fun days(n: Int) = if (n == 1) "1 day" else "$n days"
    override fun stage(i: Int, n: Int) = "Level $i/$n"
    override val today = "Today"
    override val goalToday = "Daily goal"
    override val tonight = "Tonight"
    override val nextMilestone = "Next milestone"
    override fun toGo(amount: String) = "$amount momentum to go"
    override val allDone = "All milestones reached"
    override val allDoneSub = "You're at the top."
    override val garage = "Garage"
    override val history = "History"
    override val dailyDecay = "Daily decay"
    override val resetDone = "Momentum reset"
    override fun timeAgo(sec: Long) = when {
        sec < 60 -> "just now"
        sec < 3600 -> "${sec / 60} min ago"
        sec < 86400 -> "${sec / 3600} h ago"
        sec < 2 * 86400 -> "yesterday"
        else -> "${sec / 86400} days ago"
    }
    private val vehicles = mapOf(
        "fahrrad" to "Bicycle", "motorroller" to "Scooter", "kleinwagen" to "Small car", "pkw" to "Car",
        "suv" to "SUV", "lkw" to "Truck", "panzer" to "Tank", "kleinflugzeug" to "Light aircraft",
        "passagierflugzeug" to "Airliner", "kampfjet" to "Fighter jet", "shuttle" to "Space Shuttle",
        "raumschiff" to "Starfighter", "kreuzer" to "Space cruiser",
    )
    override fun vehicle(key: String, fallback: String) = vehicles[key] ?: fallback
    override val accTag = "Floor it"
    override val accTitle = "Accelerators"
    override val accSub = "Tap something you got done."
    override val decTag = "Brake pads"
    override val decTitle = "Decelerators"
    override val decSub = "Be honest: tap when it happened."
    override val newAcc = "New accelerator"
    override val newDec = "New decelerator"
    override fun decayInfo(base: String, streak: Int) =
        "tonight · base $base" + if (streak > 0) " · streak $streak" else ""
    override fun collectedToday(a: String, b: String) = "Collected today $a / $b"
    override fun modeHint(r: Rule) = when (r.mode) {
        "linear" -> "linear · −${fmtVal(r.param)} per tap"
        "exponential" -> "exponential · ${Math.round(r.param * 100)}% remain"
        else -> "constant"
    }
    override val edit = "Edit"
    override val save = "Save"
    override val delete = "Delete"
    override val reallyDelete = "Sure?"
    override val previewRule = "How it develops per tap"
    override val previewDecay = "Loss per night: goal reached (✓) or missed x times in a row"
    override fun day(n: Int) = "Day $n"
    override val modes = listOf("constant" to "constant", "linear" to "linearly decreasing", "exponential" to "exponentially decreasing")
    override fun resetLabel(days: Int) = when (days) { 0 -> "never"; 1 -> "1 day"; else -> "$days days" }
    override fun resetSpan(n: Int, unit: String) = if (n == 0) "never" else if (unit == "h") (if (n == 1) "1 hour" else "$n hours") else (if (n == 1) "1 day" else "$n days")
    override fun idleSpan(n: Int, unit: String) = if (n == 0) "never" else if (unit == "h") (if (n == 1) "1 hour" else "$n hours") else (if (n == 1) "1 day" else "$n days")
    override val customTime = "Custom time…"
    override val customTimeTitle = "Pick a time (0–365)"
    override val unitHours = "hrs"
    override val unitDays = "days"
    override val catalysts = listOf("none" to "no", "linear" to "steadily", "exponential" to "faster and faster",
        "inverse" to "fast, then slower")
    override fun namePlaceholder(acc: Boolean) = if (acc) "working out" else "doomscrolling"

    override fun ruleSentence(c: ClozeBuilder, acc: Boolean, mode: String, resetDays: Int, g: RuleGaps) = with(c) {
        gap("icon", g.icon); text("For"); gap("name", g.name)
        text(if (acc) "I gain" else "I lose")
        if (mode == "constant") {
            gap("base", g.base); text("momentum every time.")
            text(if (acc) "The growth is" else "The penalty is"); gap("mode", g.mode)
            text(if (acc) "— every tap counts the same." else "— equally harsh every time.")
            return@with
        }
        gap("base", g.base); text("momentum the first time.")
        text(if (acc) "The growth is" else "The penalty is"); gap("mode", g.mode)
        if (mode == "linear") {
            text(if (acc) ": every further tap gives" else ": every further time costs")
            gap("param", g.param)
            text(if (acc) "less, but never less than" else "less, but always at least")
        } else {
            text(if (acc) ": with every tap only" else ": every time only")
            gap("pct", g.pct)
            text("% of it remains — it drops fast at first, then slower and slower, but never below")
        }
        gap("min", g.min); text(".")
        if (resetDays == 0) {
            text("It resets"); gap("reset", g.reset); text("— once used up, it stays that way.")
        } else {
            text("After"); gap("reset", g.reset)
            text(if (acc) "without a tap it starts again at full strength." else "without a relapse the penalty is back to full.")
        }
    }

    override fun decaySentence(c: ClozeBuilder, mode: String, g: DecayGaps) = with(c) {
        gap("icon", g.icon); text("Every night I lose"); gap("base", g.base); text("momentum.")
        if (mode == "none") {
            text("Missing my daily goal adds"); gap("mode", g.mode); text("extra penalty.")
            return@with
        }
        text("If I gather less than"); gap("threshold", g.threshold)
        text("in a day, an extra penalty is added that grows"); gap("mode", g.mode)
        text("with every missed day in a row:")
        when (mode) {
            "linear" -> { text("+"); gap("a", g.a); text("per day.") }
            "exponential" -> {
                text("it starts at"); gap("a", g.a); text("and grows ×"); gap("b", g.b); text("every day.")
            }
            else -> {
                text("up to at most"); gap("a", g.a); text(". Each missed day closes the gap to that maximum, leaving only");
                gap("pct", g.pct); text("% of it.")
            }
        }
        text("As soon as I hit the goal again, it's gone.")
    }

    override val settingsTag = "Pit stop"
    override val settingsTitle = "Settings"
    override val profile = "Profile"
    override val displayName = "Display name"
    override val change = "change"
    override fun loggedInAs(u: String) = "Logged in as @$u"
    override val game = "Game"
    override val language = "Language"
    override val timezone = "Timezone (day change for the decay)"
    override val milestoneStep = "Momentum per milestone"
    override val haptics = "Vibrate on tap"
    override val showHistory = "History on the home screen"
    override val changePassword = "Change password"
    override val oldPassword = "Old password"
    override val newPassword = "New password"
    override val savePassword = "Save password"
    override val passwordChanged = "Password changed"
    override val dangerZone = "Danger zone"
    override val logout = "Log out"
    override val resetMomentum = "Reset momentum to 0"
    override val deleteAccount = "Delete account"
    override val tapAgain = "Sure? Tap again"
    override val searchTimezone = "Search timezone"
    override val cropTitle = "Choose crop"
    override val cropHint = "Drag and pinch with two fingers to zoom"
    override val cancel = "Cancel"
    override val usePhoto = "Use photo"
    override val removePhoto = "Remove picture"
    override val feed = "Feed"
    override val findPeople = "Find people"
    override val searchHint = "Name or @username"
    override val emojiTitle = "Emoji"
    override val emojiSearch = "Search emojis…"
    override val emojiRecent = "Recently used"
    override val emojiNone = "Nothing found"
    override fun emojiGroup(name: String) = name
    override val emptyFeed = "Nothing here yet"
    override val emptyFeedSub = "Follow people to see their posts and milestones here."
    override val newPost = "New post"
    override val publish = "Share"
    override val captionHint = "Write something… @name mentions people"
    override val addMedia = "Photos & videos"
    override val tagPeople = "Tag people"
    override val addLocation = "Add location"
    override val newStory = "New story"
    override val whatToCreate = "What do you want to share?"
    override val postHint = "Stays on your profile and in the feed"
    override val storyHint = "24 hours at the top of the feed, then in your archive"
    override val yourStory = "Your story"
    override val storyShared = "Story shared"
    override val slideRules = "Rules with the picture"
    override val slideRulesHint = "Tapped when posting and shown subtly in the picture – rules from your profile, up to 5."
    override val noProfileRules = "First make rules visible on your profile (long-press a rule → Public)."
    override val camera = "Camera"
    override val gallery = "Gallery"
    override val textOnImage = "Text"
    override val highlights = "Highlights"
    override val newHighlight = "New"
    override val highlightName = "Highlight name"
    override val addToHighlight = "To highlight"
    override val deleteStory = "Delete story"
    override val deleteHighlight = "Delete highlight"
    override val removeFromHighlight = "Remove from highlight"
    override val pickStories = "Pick stories"
    override val noStoriesYet = "No stories yet"
    override val captionForPhoto = "Caption…"
    override val maxRulesPerSlide = "At most 5 rules per picture"
    override val shareStory = "Share"
    override val tapToEdit = "Tap: text & rules"
    override val searchPlace = "Search a place…"
    override fun useTypedPlace(name: String) = "Use “$name”"
    override val uploading = "Uploading…"
    override fun likes(n: Int) = if (n == 1) "1 like" else "$n likes"
    override fun viewComments(n: Int) = if (n == 1) "View 1 comment" else "View all $n comments"
    override val comments = "Comments"
    override val addComment = "Add a comment…"
    override val blockUser = "Block"
    override val unblockUser = "Unblock"
    override fun blockConfirm(name: String) = "Block $name?"
    override val blockConfirmHint = "You stop following each other, can't message each other and won't see each other's profile, posts or comments. You can undo it anytime."
    override val blockedTitle = "Blocked"
    override val blockedSub = "You blocked this account."
    override val blockedAccounts = "Blocked accounts"
    override val noBlocked = "You haven't blocked anyone."
    override val reply = "Reply"
    override fun viewReplies(n: Int) = if (n == 1) "— View 1 reply" else "— View $n replies"
    override val hideReplies = "— Hide replies"
    override fun replyTo(name: String) = "Replying to $name"
    override val with = "with"
    override fun milestonePost(vehicle: String) = "reached a milestone: $vehicle"
    override val deletePost = "Delete post"
    override val posts = "Posts"
    override val followers = "Followers"
    override val followingLabel = "Following"
    override val follow = "Follow"
    override val followBack = "Follow back"
    override val followingButton = "Following"
    override val friend = "Friend"
    override val oneOfNine = "One of the Nine"
    override val theNine = "The Nine"
    override fun nineName(key: String) = mapOf(
        "goat" to "GOAT", "larper" to "Larper", "hermes" to "Hermes", "yapper" to "Yapper", "charmeur" to "Charmeur",
        "gamemaster" to "Gamemaster", "veteran" to "Veteran", "clown" to "Clown", "supercharged" to "Supercharged",
    )[key] ?: key
    override fun nineLore(key: String) = mapOf(
        "goat" to "Greatest of all time. When leaderboards were still cave paintings, this name was already on top. Others collect momentum – the GOAT is momentum.",
        "larper" to "Pretends to be the greatest momentum legend of all time: cardboard armour, borrowed bravery, tall tales. Behind it, a little lizard that never earned its momentum the way it claims. The community has voted.",
        "hermes" to "Messenger of the gods, wings on the sandals. No bug escapes, no wish goes unheard – Hermes reported it before you even noticed.",
        "yapper" to "Speaks in comments, thinks in comments, dreams in comments. They say Yapper once commented on a sunset – and it wrote back.",
        "charmeur" to "A smile, a post, a hundred hearts. Charmeur collects likes the way others breathe – and has never asked anyone for one.",
        "gamemaster" to "Writes the rules everyone else plays by. Whenever you subscribe to a rule, the Gamemaster sits somewhere, nodding contentedly.",
        "veteran" to "Was already here when momentum was learning to crawl. A login every day, a piece of history every day – the Veteran has seen them all.",
        "clown" to "Posts like there's no tomorrow. Stories at three in the morning, photos of breakfast, of second breakfast. The ring belongs to the Clown.",
        "supercharged" to "From zero to light speed in thirty days. Where Supercharged passes, it smells of burnt rubber and fresh momentum.",
    )[key] ?: ""
    override fun nineHow(key: String) = mapOf(
        "goat" to "Have the highest ranked momentum of all – in the competition, with the daily limit.",
        "larper" to "Get elected: on the 1st of every month (and whenever the title is vacant) everyone votes on who merely pretends to have earned their momentum. The election ends once the day is over and at least 10 players have voted – otherwise it goes on.",
        "hermes" to "Report the most bugs and requests that actually get implemented – the King of Reporters.",
        "yapper" to "Write the most comments.",
        "charmeur" to "Collect the most likes on your posts.",
        "gamemaster" to "Have the most subscribers on your own rules and your decay – rules made in groups don't count.",
        "veteran" to "Use the app on the most days – every day online counts once.",
        "clown" to "Make the most posts and stories. Deleted ones still count, more than 100 a day isn't possible.",
        "supercharged" to "Gain the most ranked momentum of all in the last 30 days.",
    )[key] ?: ""
    override fun nineUnit(key: String, n: String) = when (key) {
        "goat" -> "$n momentum"
        "larper" -> "$n votes"
        "hermes" -> "$n reports"
        "yapper" -> "$n comments"
        "charmeur" -> "$n likes"
        "gamemaster" -> "$n subscribers"
        "veteran" -> "$n days"
        "clown" -> "$n posts"
        "supercharged" -> "+$n in 30 days"
        else -> n
    }
    override val nineHolder = "Holds the title"
    override val nineNobody = "Nobody yet"
    override fun nineMine(value: String, rank: Int) = "You: $value · rank $rank worldwide"
    override val nineNotCounted = "You: nothing counted yet"
    override val nineHowTitle = "How to get the title"
    override val electionTab = "Election"
    override val electionTitle = "Larper election"
    override val electionIntro = "Who merely pretends to have earned their momentum? Cast your vote – one per player, changeable until the end."
    override fun electionVotes(n: Int, needed: Int) = "$n of $needed votes needed"
    override fun electionEnds(date: String) = "Ends on $date – once at least 10 have voted"
    override fun electionNext(date: String) = "The next election starts on $date"
    override val electionClosed = "There's no election right now."
    override fun electionMyVote(name: String) = "Your vote: @$name"
    override val electionVote = "Vote"
    override val electionVoted = "Voted"
    override val electionSearch = "Find a player …"
    override val electionLeading = "In the lead"
    override val electionCurrentLarper = "Current Larper"
    override val electionOpenHint = "The Larper election is on – who merely pretends to have earned their momentum?"
    override val electionOpenAction = "Vote now"
    override val message = "Message"
    override val noPosts = "No posts yet"
    override val noUsers = "No one found"
    override val activity = "Activity"
    override val noActivity = "No activity yet"
    override fun notification(kind: String) = when (kind) {
        "follow" -> "started following you."
        "like" -> "liked your post."
        "comment" -> "commented on your post."
        "clike" -> "liked your comment."
        "reply" -> "replied to your comment."
        "mention" -> "mentioned you."
        "tag" -> "tagged you in a post."
        "request" -> "wants to follow you."
        "accepted" -> "accepted your follow request."
        else -> ""
    }
    override val chats = "Messages"
    override val newMessage = "New message"
    override val newGroup = "New group"
    override val groupName = "Group name"
    override val create = "Create"
    override val noChats = "No conversations yet"
    override val typeMessage = "Message…"
    override val photo = "Photo"
    override val you = "You"
    override val leaveGroup = "Leave group"
    override fun members(n: Int) = "$n members"
    override val pickPeople = "People you're connected with (one follows the other)"
    override val shareMilestone = "Share to feed"
    override val shared = "Shared!"
    override val showOnProfile = "Show on profile"
    override val showOnProfileHint = "Others see the rule and its history."
    override val rulesTitle = "Rules"
    override val usernameHint = "3-30 characters: a-z, 0-9, _ and ."
    override val devOn = "Developer mode on"
    override val devOff = "Developer mode off"
    override val devSection = "Developer"
    override val noContacts = "No contacts yet"
    override val noContactsSub = "Follow people or get followed, then they show up here."
    override val nothingToSave = "Nothing changed"
    override val seenBy = "Seen by"
    override val noViewsYet = "No one has seen it yet"
    override val likedBy = "Likes"
    override val email = "E-mail"
    override val bio = "Bio"
    override val bioHint = "Tell people about yourself… (@name mentions people)"
    override val noPublicRules = "No visible rules"
    override val noPublicRulesMine = "Turn on “Show on profile” for an accelerator or decelerator."
    override val noHistory = "No history yet"
    override val offlineNow = "Offline"
    override fun offlinePending(n: Int) = if (n == 1) "Offline · 1 change waiting" else "Offline · $n changes waiting"
    override val offlineStale = "Offline – not updating"
    override val needsInternet = "You need an internet connection for this"
    override val offlineTitle = "No connection"
    override val offlineSub = "This part needs internet. It continues as soon as you're back online."
    override val undoTitle = "Undo this?"
    override val undo = "Undo"
    override val pendingSync = "waiting to sync"
    override fun milestoneEvent(vehicle: String) = "Milestone reached: $vehicle"
    override fun limitLeft(left: String, cap: String) = "$left of $cap momentum left today"
    override fun limitPartial(amount: String) = "Daily limit reached – only +$amount credited"
    override val limitZeroTap = "Daily limit reached – counts +0, but the automatic rule starts over"
    override val limitReached = "Daily limit reached – more tomorrow"
    override val emailOrUsername = "E-mail or username"
    override val confirmEmailTitle = "Confirm your e-mail"
    override fun confirmEmailSent(email: String) = "We sent a code to $email. Enter it here."
    override fun confirmEmailAsk(email: String) = "Confirm your e-mail address: we'll send a code to $email."
    override val confirmCode = "6-digit code"
    override val confirmButton = "Confirm"
    override val sendCode = "Send code"
    override val resendCode = "Send the code again"
    override fun resendIn(seconds: Int) = "New code in $seconds s"
    override val otherAddress = "Use another address"
    override val newEmail = "New e-mail address"
    override val checkSpam = "Nothing there? Check your spam folder too."
    override fun confirmDue(date: String) = "Please confirm your e-mail address by $date – after that the app waits for it."
    override val confirmNow = "Confirm now"
    override val emailConfirmed = "E-mail address confirmed"
    override val forgotPassword = "Forgot your password?"
    override val forgotHint = "Enter your e-mail address or username. We'll send a code to your confirmed address."
    override val forgotSent = "If there's an account with a confirmed e-mail address, a code is on its way now."
    override val backToLogin = "Back to login"
    override val googleSignIn = "Continue with Google"
    override val googleFailed = "Google sign-in failed"
    override val googleAccount = "Google account"
    override val legalSection = "Legal"
    override val privacyPolicy = "Privacy policy"
    override val termsOfUse = "Terms of use"
    override val acceptTerms = "By signing in you accept the terms of use and the privacy policy."
    override val invalidEmail = "Please enter a valid e-mail address"
    override val chooseUsername = "Choose your username"
    override val chooseUsernameSub = "This is how people find you and @mention you."
    override val milestoneReached = "Milestone reached"
    override val levelUp = "Level up!"
    override val nowDriving = "Now riding:"
    override val next = "Continue"
    override val privateAccount = "Private account"
    override val privateAccountHint = "Only approved followers see your posts, history, followers and rules."
    override val requested = "Requested"
    override val lockedTitle = "This account is private"
    override val lockedSub = "Follow this account to see posts, rules and history."
    override val followRequests = "Follow requests"
    override val accept = "Confirm"
    override val decline = "Delete"
    override fun requestsCount(n: Int) = if (n == 1) "1 request" else "$n requests"
    override val subscribe = "Subscribe"
    override val unsubscribe = "Unsubscribe"
    override val subscribed = "Subscribed"
    override val share = "Share"
    override val shareTo = "Share to…"
    override fun ruleBy(owner: String) = "Rule by $owner"
    override val groupRule = "Group rule"
    override val challengeRule = "Challenge rule"
    override val yourRule = "Your rule"
    override fun subscribers(n: Int) = if (n == 1) "1 subscriber" else "$n subscribers"
    override fun subscribedHint(owner: String) = "You get changes by $owner automatically."
    override val subscribedHintGroup = "You get updates for this rule automatically."
    override fun onlyMembersCanSubscribe(group: String?) =
        if (group != null) "Only members of the group $group can subscribe." else "Only members of this private group can subscribe."
    override fun origin(origin: String, label: String) = when (origin) {
        "sub" -> "subscribed · $label"
        "group" -> "group · $label"
        "challenge" -> "challenge · $label"
        else -> ""
    }
    override val notAvailable = "Not available"
    override val readOnlyRule = "Only its creator can change a subscribed rule."
    override val sharedRule = "Rule"
    override val sharedPost = "Post"
    override val sharedProfile = "Profile"
    override val groupInfo = "Group"
    override val groupRules = "Group rules"
    override val noGroupRules = "No group rules yet"
    override val newGroupRule = "Group rule"
    override val challenges = "Challenges"
    override val noChallenges = "No challenges yet"
    override val newChallenge = "New challenge"
    override val membersTitle = "Members"
    override val modeRanking = "Ranking"
    override val modeTarget = "Target"
    override val modeRankingHint = "Whoever has the most momentum from the rules at the end wins."
    override val modeTargetHint = "Everyone who reaches the target in time gets the reward."
    override val challengeName = "Challenge name"
    override val duration = "Duration"
    override val startLabel = "Start"
    override fun startOption(n: Int) = when (n) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $n days" }
    override fun rewardPlace(i: Int) = "Reward ${listOf("1st", "2nd", "3rd")[i - 1]} place"
    override val optional = "optional"
    override val targetLabel = "Target (momentum)"
    override val rewardLabel = "Reward"
    override val challengeRules = "Rules"
    override val challengeHint = "Everyone who joins gets these rules – they disappear when it ends."
    override val join = "Join"
    override val leaveChallenge = "Leave"
    override val reallyLeave = "Leave for good?"
    override val cancelChallenge = "Cancel"
    override val reallyCancel = "Really cancel?"
    override fun challengeStatus(status: String, daysLeft: Int?, startsIn: Int?) = when (status) {
        "upcoming" -> if (startsIn == 1) "starts tomorrow" else "starts in $startsIn days"
        "running" -> if (daysLeft == 1) "last day" else "$daysLeft days left"
        "finished" -> "finished"
        else -> "cancelled"
    }
    override fun joinedCount(n: Int) = "$n joined"
    override val leaderboard = "Leaderboard"
    override val yourScore = "Your score"
    override val leftChallenge = "You left this challenge."
    override val challengeMissing = "Name and at least one rule needed"
    override fun challengeResult(title: String, rank: Int?, reward: String) =
        "“$title” is over" + (rank?.let { " – place $it" } ?: "") + (if (reward != "0") ", +$reward momentum." else ".")
    override fun challengeCancelled(title: String) = "cancelled “$title”."
    override val challenge = "Challenge"
    override fun rewardsLine(rewards: List<String>) = rewards.mapIndexed { i, r -> "${i + 1}. +$r" }.joinToString(" · ")
    override fun targetLine(target: String, reward: String) = "reach $target → +$reward"
    override fun place(n: Int) = "Place $n"
    override fun createdBy(name: String) = "Created by $name"
    override val banner = "Banner"
    override val chooseBanner = "Choose banner"
    override val bannerAuto = "Automatic"
    override fun bannerName(key: String) = mapOf(
        "dusk" to "Dusk", "sky" to "Sky", "cosmos" to "Cosmos", "city" to "City", "desert" to "Desert",
        "forest" to "Forest", "beach" to "Beach", "snow" to "Peaks", "aurora" to "Aurora", "sakura" to "Sakura",
        "volcano" to "Volcano", "canyon" to "Canyon", "rain" to "Rain city", "moon" to "Moon base", "synthwave" to "Neon",
        "autumn" to "Autumn", "fields" to "Fields", "deepsea" to "Deep sea", "storm" to "Storm", "sunrise" to "Sunrise",
        "cottoncandy" to "Cotton candy", "hearts" to "Hearts", "strawberry" to "Strawberries", "meadow" to "Meadow",
        "glitter" to "Glitter", "lavender" to "Lavender", "balloons" to "Balloons", "jungle" to "Jungle",
        "harbor" to "Lighthouse", "glacier" to "Glacier", "hyperspace" to "Hyperspace", "ultrahyperspace" to "Ultra-Hyperspace",
        "ringplanet" to "Ringed planet", "redplanet" to "Red planet", "iceplanet" to "Ice planet", "lavaplanet" to "Lava planet",
        "earthorbit" to "Earth orbit", "twinsuns" to "Twin suns", "nebula" to "Nebula", "asteroids" to "Asteroids",
        "blackhole" to "Black hole", "galaxy" to "Galaxy", "battlestation" to "Battle station",
        "wallcity" to "Walled city", "ninjavillage" to "Ninja village", "redclouds" to "Red clouds", "grassroute" to "Monster route", "spiritforest" to "Spirit forest", "skyisland" to "Sky island", "tournament" to "Tournament isle", "pirates" to "Pirate sea", "wisteria" to "Wisteria night", "moonprincess" to "Moon princess", "neotokyo" to "Neo Tokyo", "comet" to "Comet", "torii" to "Torii shrine", "bathhouse" to "Bathhouse", "fuji" to "Fuji express", "ramenalley" to "Ramen alley", "schoolroof" to "School rooftop",
    )[key] ?: key
    override val limitInfoTitle = "How your daily limit works"
    override val limitProvisionalShort = "provisional"
    override val limitProvisional = "You're offline and it's a new day: until you're back online, yesterday's limit applies. When syncing, the server uses today's real limit."
    override fun limitInfo(cap: String, average: String, bias: String, gained: String, left: String) = listOf(
        "Today you can collect up to $cap momentum.",
        "The limit is a growth rate that adapts every day. Yesterday the players in the competition who tapped something themselves grew by $average on average (their automatic taps count too, the daily decay doesn't): above 75 % of the rate it doubles (up to 12,800), below 25 % it halves (down to 25). It's the same for everyone and fixed for the whole day.",
        "Accelerators count towards it, decelerators take it back down. You're at $gained today – $left left.",
        "Decelerators always apply, even when the limit is reached.",
    )
    override fun bannerGroup(key: String) = when (key) {
        "pastel" -> "Pastel & playful"; "anime" -> "Anime"; "space" -> "Space"; else -> "Classic"
    }
    override fun bannerKind(kind: String) = when (kind) { "air" -> "Air"; "space" -> "Space"; else -> "Road" }
    override val addToGroup = "Add to group"
    override val fromMyRules = "From my rules"
    override fun followsRuleOf(name: String) = "follows $name's rule"
    override fun empty(context: String, name: String) = when (context) {
        "chats" -> "No conversations yet" to "Message someone you follow – or start a group."
        "chat" -> "No one to message" to "You can message people you follow or who follow you."
        "group" -> "No one for a group" to "You can add people you follow or who follow you."
        "tag" -> "No one to tag" to "You can tag people you follow or who follow you."
        "followers" -> "No followers yet" to "Share your profile so others can find you."
        "following" -> "You're not following anyone" to "Find people and see their posts and milestones in your feed."
        "followersOf" -> "No followers yet" to "$name has no followers yet."
        "followingOf" -> "Not following anyone" to "$name isn't following anyone yet."
        "requests" -> "No pending requests" to "When someone wants to follow you, it shows up here."
        else -> noContacts to noContactsSub
    }
    override val dailyLimitSetting = "Daily limit · community competition"
    override val dailyLimitHint = "Off: no limit, you collect free momentum. Your ranked value pauses (no decay either) and continues when you switch it back on."
    override val competition = "Competition"
    override val unranked = "Unranked"
    override val freeMomentum = "Free momentum"
    override fun rankedPaused(value: String) = "Ranked value paused: $value"
    override fun rankedValue(value: String) = "Free momentum: $value"
    override val noLimitBanner = "No daily limit – you collect free momentum (unranked)."
    override val hyperspaceTitle = "Hyperspace!"
    override val hyperspaceSub = "1,000,000 momentum – your spaceship jumps into hyperspace. New background unlocked."
    override val useAsBackground = "Use as background"
    override val hyperspaceLocked = "from 4M in competition"
    override val hyperLore = "Everybody craves to reach the ultimate momentum."
    override val ultraLore = "You can become even faster?!"
    override fun idleTitle(acc: Boolean) = if (acc) "If you don't tap it" else "If you stay away from it"
    override fun idleDays(n: Int) = when (n) { 0 -> "never"; 1 -> "1 day"; else -> "$n days" }
    override fun idleSentence(b: ClozeBuilder, on: Boolean, days: @Composable () -> Unit, rule: @Composable () -> Unit) {
        if (!on) {
            b.text("Tap automatically:"); b.gap("idle", days)
        } else {
            b.text("After"); b.gap("idle", days); b.text("without a tap,")
            b.gap("idlerule", rule); b.text("is tapped automatically – then again after the same time.")
        }
    }
    override fun idleNoCounterpart(acc: Boolean) =
        if (acc) "Create a decelerator to have it tapped here automatically." else "Create an accelerator to have it tapped here automatically."
    override fun autoTapped(from: String) = "automatic – “$from” not tapped"
    override val updateTag = "New version"
    override val updateTitle = "Time to update"
    override val updateText = "There's a new version of Momentum. Download it on the website and install it – you'll continue right where you left off."
    override val updateButton = "Go to website"
    override val whatsNewTitle = "What's new"
    override val whatsNewHint = "After every update Momentum shows you what changed."
    override val whatsNewOpen = "All changes"
    override val whatsNewOk = "Got it"
    override val limitUpTitle = "Daily limit up"
    override val limitDownTitle = "Daily limit down"
    override fun limitChangeText(prev: String, cap: String) = "Today you can collect up to $cap momentum – yesterday it was $prev."
    override fun limitChangeWhy(average: String) = "Yesterday the players in the competition grew by $average on average."
    override val systemFontSetting = "System font for texts"
    override val notificationsSetting = "Notifications"
    override val systemFontHint = "Messages, comments, captions and bios in your phone's regular font instead of the pixel font."
    override val autoFollow = "Always follow all"
    override val autoFollowHint = "Subscribe to every rule of the group – new ones too. Switching it off keeps your subscriptions."
    override val noPublicRulesForGroup = "Groups follow public rules. Turn on “Show on profile” for a rule to add it here."
    override val lowerMomentum = "Lower momentum"
    override fun lowerMomentumHint(current: String) = "You're at $current. Set it to a lower value – 0 means starting over. Vehicles above it are lost."
    override val lowerMomentumInvalid = "The value must be below your current momentum."
    override fun lowerMomentumTo(value: String) = "Set to $value"
    override val takePhoto = "Take a photo"
    override val takeVideo = "Record a video"
    override val recordVideo = "Record a video"
    override val fromGallery = "From the gallery"
    override val reportTitle = "Report"
    override val reportBug = "Bug report"
    override val reportFeature = "Feature request"
    override val reportBugHint = "What happened, and what did you expect? Screenshots help a lot."
    override val reportFeatureHint = "What would you like, and what would you use it for?"
    override val reportPlaceholder = "Description…"
    override val reportImages = "Images"
    override val reportSend = "Send"
    override val reportThanks = "Thanks! We got it."
    override fun reportThanksBonus(n: String) = "Thanks! We got it – +$n momentum for you."
    override val reportBonus = "Report bonus"
    override val reportSettingsHint = "Found a bug or have an idea? Send it to us – with images if you like."
    override val reportOpen = "Report a bug / request a feature"
    override val voiceMessage = "Voice message"
    override val releaseToSend = "Release to send"
    override val holdToRecord = "Hold to record"
    override val e2eTitle = "End-to-end encryption"
    override val e2eHint = "New messages, photos and voice messages can only be read by the chat's members – not even by the server."
    override val e2eOnlyAdmins = "Only the group's admins can change this."
    override val e2eChatNote = "Messages in this chat are end-to-end encrypted. Tap for details."
    override fun e2eSwitchedOn(name: String) = "$name turned end-to-end encryption on"
    override fun e2eSwitchedOff(name: String) = "$name turned end-to-end encryption off"
    override val e2eYouSwitchedOn = "You turned end-to-end encryption on"
    override val e2eYouSwitchedOff = "You turned end-to-end encryption off"
    override fun memberJoined(name: String) = "$name joined"
    override val youJoined = "You joined"
    override val voiceCall = "Voice call"
    override val videoCall = "Video call"
    override val groupVoiceCall = "Group call"
    override val groupVideoCall = "Group video call"
    override val outgoingVoiceCall = "Outgoing voice call"
    override val outgoingVideoCall = "Outgoing video call"
    override val missedVoiceCall = "Missed voice call"
    override val missedVideoCall = "Missed video call"
    override val missedGroupCall = "Missed group call"
    override val callNoAnswerShort = "no answer"
    override val callDeclinedShort = "declined"
    override val callBusyShort = "busy"
    override val callIncomingVoice = "Incoming voice call"
    override val callIncomingVideo = "Incoming video call"
    override val callRinging = "Ringing …"
    override val callCalling = "Calling …"
    override val callConnecting = "Connecting …"
    override val callReconnecting = "Reconnecting …"
    override val callWaiting = "Waiting for the others …"
    override val callEnded = "Call ended"
    override val callBusy = "Busy"
    override val callDeclined = "Declined"
    override val callNoAnswer = "No answer"
    override val callAccept = "Accept"
    override val callDecline = "Decline"
    override val callHangUp = "Hang up"
    override val callMute = "Mute"
    override val callCamera = "Camera"
    override val callFlip = "Flip"
    override val callSpeaker = "Speaker"
    override val callMinimize = "Minimize"
    override val callBackTo = "Tap to return to the call"
    override val callJoin = "Join"
    override val callAlready = "You're already in a call"
    override val callNoMic = "No call without the microphone – allow access in the settings"
    override val callNoCamera = "The camera isn't available"
    override val callNeedsInternet = "Calls need the internet"
    override val callUnsupported = "This browser can't make calls"
    override val callsChannel = "Calls"
    override val callOngoing = "Ongoing call"
    override val callStateRinging = "ringing"
    override fun callRunning(count: Int) = "Call on · $count in it"
    override fun callFromIn(name: String, group: String) = "$name · $group"
    override fun memberAdded(actor: String, target: String) = "$actor added $target"
    override fun youAdded(target: String) = "You added $target"
    override fun addedYou(actor: String) = "$actor added you"
    override fun memberRemoved(actor: String, target: String) = "$actor removed $target"
    override fun youRemoved(target: String) = "You removed $target"
    override fun removedYou(actor: String) = "$actor removed you"
    override fun memberLeft(name: String) = "$name left the group"
    override val youLeft = "You left the group"
    override val removeMember = "Remove from group"
    override fun removeMemberAsk(name: String) = "Remove $name from the group?"
    override fun subscribedRule(rule: String) = "subscribed to your rule “$rule”."
    override fun subscribedRuleGroup(rule: String, group: String) = "subscribed to your rule “$rule” for the group $group."
    override val followsDecay = "now follows your decay."
    override fun decayInGroup(group: String) = "added your decay to the group $group."
    override val e2eSealed = "This message can't be read on this device."
    override val e2eLocked = "Encrypted message – unlock your chats to read it."
    override val e2eNeedsUnlock = "Unlock your encrypted chats first."
    override val e2eUnsupported = "This browser can't do encrypted chats."
    override val e2eNewMessage = "New message"
    override val safetyCodes = "Safety codes"
    override val safetyHint = "Compare your codes in person: if they match on both phones, nobody is listening in."
    override val yourCode = "Your code"
    override fun keyChanged(name: String) = "$name's safety code changed (new device or new key)."
    override val keyChangedOk = "Got it"
    override val recoveryTitle = "Recovery key"
    override val recoveryIntro = "With this key you read your encrypted chats on a new device. Write it down and keep it safe – we don't have it and can't restore it."
    override val recoveryCopy = "Copy"
    override val recoveryNoted = "I wrote it down"
    override val recoveryCard = "Save your recovery key"
    override val recoveryCardHint = "Without it you can't read encrypted chats on a new device."
    override val unlockTitle = "Unlock chats"
    override val unlockIntro = "Your key is missing on this device. Enter your recovery key to read and write encrypted chats."
    override val unlockPlaceholder = "ABCD EFGH …"
    override val unlockButton = "Unlock"
    override val unlockWrong = "That key doesn't fit."
    override val unlockCard = "Unlock encrypted chats"
    override val unlockCardHint = "Enter your recovery key."
    override val resetKey = "Forgot the key? Make a new one"
    override val resetKeyWarning = "With a new key you can no longer read your earlier encrypted messages. Your contacts will see that your safety code changed."
    override val resetKeyConfirm = "Make a new key"
    override val chatInfo = "Chat info"
    override val muteChat = "Mute"
    override val muteChatHint = "No notifications – mentions still come through."
    override val micReady = "Microphone ready – now hold to record"
    override val micBlocked = "No access to the microphone – allow it in the settings"
    override val addMembers = "Add members"
    override fun addMembersCount(n: Int) = if (n == 1) "Add 1 person" else "Add $n people"
    override val noOneToAdd = "Everyone you follow or who follows you is already in."
    override fun mentionedYou(sender: String, chat: String?) = if (chat != null) "$sender mentioned you in $chat" else "$sender mentioned you"
    override val mentionedPreview = "@ You were mentioned"
    override val accShort = "ACC."
    override val decShort = "DEC."
    override val dragToSort = "Long-press to select"
    override val decayPublic = "Others may follow"
    override val decayPublicHint = "Your decay shows in your profile. Whoever follows it uses it instead of their own."
    override fun decayOf(name: String) = "Daily decay of $name"
    override fun groupDecayOf(group: String) = "Decay of the group $group"
    override val followDecay = "Follow"
    override val followDecayHint = "You always have exactly one decay: this one replaces yours. Unsubscribing brings yours back."
    override val decayFollowing = "You follow this decay – it counts instead of your own."
    override fun decayLockedBy(title: String) = "The challenge “$title” sets your decay – you can change it after leaving."
    override val groupDecays = "Decays"
    override val newGroupDecay = "New decay"
    override val noGroupDecays = "No decays in the group yet."
    override val decayName = "Name of the decay"
    override val challengeDecay = "Decay (optional)"
    override val noDecay = "None"
    override val challengeDecayHint = "Whoever takes part follows this decay while the challenge runs."
    override val publicDecay = "Public decay"
    override val haveRuleAlready = "You have this rule already – subscribed another way or your own."
    override val ownDecayInGroup = "This is your own decay – the group follows it."
    override fun followsDecayOf(name: String) = "follows the decay of $name"
    override val adminTag = "Admin"
    override val makeAdmin = "Make admin"
    override val removeAdmin = "Remove as admin"
    override val viewProfile = "View profile"
    override val anarchyMode = "Anarchy mode"
    override val anarchyHint = "Every member is an admin: anyone may change rules, challenges, members and settings."
    override val onlyAdmins = "Only the group's admins can change this."
    override val tappableSetting = "Can be tapped"
    override val tappableHint = "Off: tapping the card doesn't count – the rule only works through the automatic tap. Applies to everyone who subscribes too."
    override val notTappable = "This rule can't be tapped."
    override val subscribedLabel = "subscribed"
    override val leaderboardTitle = "Leaderboard"
    override val scopeGlobal = "Global"
    override val scopeFriends = "Friends"
    override val yourPlace = "Your place"
    override val paused = "paused"
    override fun placeOf(rank: Int, total: Int) = "Place $rank of $total"
    override val groupRanking = "Ranking"
    override val groupPublic = "Public"
    override val groupPublicHint = "Found in the search – everyone sees the overview, members and public rules."
    override val groupOpen = "Open to join"
    override val groupOpenHint = "Anyone can join. Only for public groups."
    override val joinGroup = "Join"
    override val closedGroup = "Closed group – members can add you."
    override val tagPublic = "PUBLIC"
    override val tagPrivate = "PRIVATE"
    override val tagOpen = "OPEN"
    override val addGroupRule = "Add a rule to the group"
    override val followPublicRule = "Follow a public rule"
    override val followPublicHint = "Stays linked – changes by its creator arrive automatically."
    override val createGroupRule = "Create a new rule"
    override val createGroupRuleHint = "Belongs to the group. Made public, others can subscribe to it too."
    override val copyRule = "Copy one of my rules"
    override val copyRuleHint = "A real copy without a link – also of rules you subscribed to."
    override val noRulesToCopy = "You don't have any rules yet."
    override val copyToGroup = "Copy into a group"
    override val copied = "Copied"
    override val groupsTitle = "Groups"
    override val challengeOnlyGroupRules = "Challenges use rules from the group. When it starts, every participant subscribes to them automatically."
    override val keepRules = "Keep the rules afterwards"
    override val keepRulesHint = "Otherwise the challenge's subscriptions go away when you leave or it ends."
    override fun lockedByChallenge(title: String) = "Part of the challenge “$title” – you can unsubscribe after leaving it."
    override fun groupRuleOf(group: String) = "Rule of the group $group"
    override val groupRulePublicHint = "Others can subscribe to it when the group is public."
    override val idlePause = "Pause the automatic tap"
    override val idlePauseHint = "While paused, nothing is tapped automatically."
    override val idlePausedShort = "Auto-tap paused"
    override fun idleLinked(days: String, name: String) = "After $days without a tap, “$name” is tapped automatically – subscribing takes it along."
    override val subscribeForGroup = "Subscribe for a group"
    override val followPublicFromRule = "Public rules are subscribed for the group right in the rule itself."
    override val widgetPickGroup = "Which group should the widget show?"
    override val widgetLoading = "Loading …"
    override fun challengeCreditLine(amount: String) = "Challenge credit: $amount"
    override val challengeCreditHint = "Rewards arrive over the days: each new day tops up whatever stayed free under the previous day's limit."
    override fun rewardMax(max: String) = "at most $max"
    override val chatMuted = "Muted – mentions still come through"
    override val chatUnmuted = "Notifications on"
    override val uploadFailed = "Upload failed – please try again"
    override fun serverDown(code: Int) = "The server isn't answering right now (error $code) – probably a restart, try again in a moment"
    override fun serverError(code: Int) = "Error $code – the server sent no explanation"
    override val groupTotal = "TOTAL"
    override val groupLook = "Look of the group"
    override val pickPixelBanner = "Pick a pixel banner"
    override val ownBannerImage = "Own image"
    override val groupPicture = "Group picture"
    override val removeImages = "Remove image"
    override val viewPost = "View post"
    override fun replyingTo(name: String) = "↩ $name"
    override val pasteImage = "Paste image from clipboard"
    override val linkPreviewLoading = "Loading preview…"
    override fun typingOne(name: String) = "$name is typing…"
    override fun typingTwo(a: String, b: String) = "$a and $b are typing…"
    override fun typingMany(n: Int) = "$n people are typing…"
    override fun recordingOne(name: String) = "$name is recording a voice message…"
    override fun recordingMany(n: Int) = "$n are recording voice messages…"
    override val typingShort = "typing…"
    override val recordingShort = "recording audio…"
    override val replyToStory = "Reply…"
    override val sharedStory = "Story"
    override val viewStory = "View story"
    override val storyReplySent = "Sent"
    override val sensitiveTitle = "Sensitive content"
    override val sensitiveHint = "May show nudity or sexual content."
    override val sensitiveShow = "Show"
    override val storyDuration = "Duration"
    override val soundRangeHint = "Drag the ends: this part plays"
    override val soundStoryHint = "As long as the story – slide the part"
    override val ruleSharedPost = "shared a new rule"
    override val ruleUpdatedPost = "updated a rule"
    override val periodTotal = "Total"
    override val period24h = "Today"
    override fun limitForecast(average: String, players: Int, next: String) =
        "So far today: $average on average over $players active players → tomorrow probably $next (fixed at midnight)."
    override val readBy = "Read by"
    override fun notReadYet(n: Int) = "Not read yet: $n"
    override val reactions = "Reactions"
    override val compressing = "Compressing…"
    override val processing = "Processing…"
    override val yesterday = "Yesterday"
    override fun deletedIn(days: Int, hm: String) = "Deleted in $days d $hm"
    override val photoArchived = "Archived · small copy"
    override val originalSound = "Original sound"
    override val addSound = "Add a sound"
    override val searchSong = "Search a song…"
    override val songsByDeezer = "Song previews by Deezer"
    override val ownSound = "Or a sound of your own"
    override val pickAudioFile = "Pick an audio file"
    override val recordSound = "Hold to record"
    override val recordSoundHint = "Or record something yourself:"
    override val soundTitle = "Name of the sound"
    override fun soundPart(from: String, to: String) = "Used: $from – $to"
    override val unsubscribeAll = "Unsubscribe all"
    override val dropToDelete = "Drop here to delete"
    override val dropToUnsubscribe = "Drop here to unsubscribe"
    override fun deleteRuleQuestion(name: String) = "Delete “$name”?"
    override val takePhotoOrVideo = "Camera"
    override val videoLabel = "Video"
    override val removeFromGroup = "Remove from the group"
    override val reallyRemoveFromGroup = "Really remove?"
    override val addText = "Text"
    override val overlayHint = "Write a text – then drag it into place"
    override val done = "Done"
    override val noPublicGroups = "No public groups yet."
    override fun selectedCount(n: Int) = "$n selected"
    override val selectAll = "All"
    override fun deleteN(n: Int) = "Delete ($n)"
    override fun unsubscribeN(n: Int) = "Unsubscribe ($n)"
    override fun subscribeN(n: Int) = "Subscribe ($n)"
    override fun removeN(n: Int) = "Remove ($n)"
    override fun makePublicN(n: Int) = "Public ($n)"
    override fun makePrivateN(n: Int) = "Private ($n)"
    override val inProfile = "Show in my profile"
    override val inProfileHint = "The rule and your taps appear in your profile and its history."
    override fun bulkResult(done: Int, skipped: Int) = if (skipped == 0) "Done: $done" else "Done: $done · skipped: $skipped"
    override fun confirmBulkRemove(delete: Int, unsubscribe: Int) = listOfNotNull(
        if (delete > 0) "Delete $delete" else null, if (unsubscribe > 0) "unsubscribe $unsubscribe" else null).joinToString(" and ") + "?"
    override fun bulkLockedHint(n: Int) = "$n of them stay – a running challenge uses them."
    override fun confirmRemoveFromGroup(n: Int) = "Remove $n from the group?"
    override val removeFromGroupHint = "All subscriptions of these rules through the group go away too."
    override val notificationsAllowed = "Android allows notifications."
    override val notificationsBlocked = "Android blocks notifications for Momentum – allow them in the system settings."
    override val notificationsBlockedWeb = "Notifications aren't allowed yet."
    override val notificationsInstallHint = "On the iPhone, notifications only arrive when Momentum is on the home screen: in Safari tap Share → “Add to Home Screen”, then open it from there."
    override val allowNotifications = "Allow"
    override val notifyAskTitle = "Allow notifications?"
    override val notifyAskSub = "So you see messages, likes, comments and challenge results right away – even when the app is closed."
    override val notNow = "Not now"
    override val openSystemSettings = "System settings"
    override val testNotification = "Test"
    override val testNotificationText = "This is what notifications from Momentum look like."
}

fun stringsFor(lang: Lang): Strings = if (lang == Lang.DE) De else En

val LocalStrings = staticCompositionLocalOf<Strings> { De }
