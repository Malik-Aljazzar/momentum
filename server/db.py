"""Database models (SQLAlchemy 2, async). SQLite by default.

All datetimes are stored as naive UTC.
"""
from datetime import date, datetime, timezone

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Integer, String, Text, event, inspect
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utcnow():
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(40), unique=True, index=True)
    email: Mapped[str | None] = mapped_column(String(254), nullable=True)       # login + account, never public
    # the address confirmed with a mailed code (server/mail.py); from email_due_at on the app is locked until then
    email_confirmed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    email_due_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # wrong passwords in a row; at mail.LOCK_AFTER the account locks until a new password is set by mailed code
    failed_logins: Mapped[int] = mapped_column(Integer, default=0)
    locked_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    username_set: Mapped[bool] = mapped_column(Boolean, default=True)          # False until chosen after signup
    password_hash: Mapped[str] = mapped_column(String(255))     # "" = Google-only account (no password login)
    # Sign in with Google (server/google.py): Google's account id; its e-mail is shown only, never a login
    google_sub: Mapped[str | None] = mapped_column(String(64), nullable=True)
    google_email: Mapped[str | None] = mapped_column(String(254), nullable=True)
    display_name: Mapped[str] = mapped_column(String(40), default="")   # always == username (kept for the API)
    bio: Mapped[str] = mapped_column(Text, default="")                    # profile text, max 150 chars
    private: Mapped[bool] = mapped_column(Boolean, default=False)         # only accepted followers see content
    # background behind the vehicle, one per vehicle type: road (banner), air, space
    banner: Mapped[str | None] = mapped_column(String(20), nullable=True)
    banner_air: Mapped[str | None] = mapped_column(String(20), nullable=True)
    banner_space: Mapped[str | None] = mapped_column(String(20), nullable=True)
    # Runs next to `momentum` (the competitive value with the daily limit): every tap counts in full.
    free_score: Mapped[float] = mapped_column(Float, default=0.0)
    # challenge rewards not credited yet: each new day fills the unused room of the day before
    challenge_credit: Mapped[float] = mapped_column(Float, default=0.0)
    credit_day: Mapped[date | None] = mapped_column(Date, nullable=True)      # last server day credited
    avatar: Mapped[str | None] = mapped_column(Text, nullable=True)  # blob key in MinIO
    momentum: Mapped[float] = mapped_column(Float, default=0.0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    # posts and stories ever made (deleted ones still count: the Clown, server/nine.py) and today's share
    # (at most nine.POSTS_PER_DAY a day)
    posts_total: Mapped[int] = mapped_column(Integer, default=0)
    posts_today: Mapped[int] = mapped_column(Integer, default=0)
    posts_day: Mapped[date | None] = mapped_column(Date, nullable=True)
    # may use the admin app (/api/admin/*); granted with: python -m server.admin grant <username>
    admin: Mapped[bool] = mapped_column(Boolean, default=False)
    # admin logins need a code from an authenticator app (TOTP, server/security.py); the last step used
    # (a code works once)
    totp_secret: Mapped[str | None] = mapped_column(String(64), nullable=True)
    totp_last_step: Mapped[int] = mapped_column(Integer, default=0)
    # end-to-end encrypted chats (server/e2e.py): the public key (P-256, raw point, base64) and the private key
    # encrypted with the recovery key that only the player has - the server can't open it
    e2e_public: Mapped[str | None] = mapped_column(Text, nullable=True)
    e2e_backup: Mapped[str | None] = mapped_column(Text, nullable=True)

    settings: Mapped["Settings"] = relationship(
        back_populates="user", lazy="selectin", cascade="all, delete-orphan")
    decay: Mapped["DailyDecay"] = relationship(
        back_populates="user", lazy="selectin", cascade="all, delete-orphan")


class Settings(Base):
    __tablename__ = "settings"
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    timezone: Mapped[str] = mapped_column(String(64), default="Europe/Berlin")
    milestone_step: Mapped[int] = mapped_column(Integer, default=1_000)   # first milestone; they double
    haptics: Mapped[bool] = mapped_column(Boolean, default=True)
    show_history: Mapped[bool] = mapped_column(Boolean, default=True)
    # off: no daily limit for the free score; the player leaves the community competition
    daily_limit: Mapped[bool] = mapped_column(Boolean, default=True)
    user: Mapped[User] = relationship(back_populates="settings")


class DailyDecay(Base):
    __tablename__ = "daily_decay"
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    base: Mapped[float] = mapped_column(Float, default=50.0)
    threshold: Mapped[float] = mapped_column(Float, default=200.0)
    catalyst_mode: Mapped[str] = mapped_column(String(16), default="linear")
    catalyst_a: Mapped[float] = mapped_column(Float, default=25.0)
    catalyst_b: Mapped[float] = mapped_column(Float, default=1.5)
    streak: Mapped[int] = mapped_column(Integer, default=0)
    last_processed_day: Mapped[date | None] = mapped_column(Date, nullable=True)
    # others may follow this decay (server/decays.py); or I follow someone else's / a group's instead of mine
    public: Mapped[bool] = mapped_column(Boolean, default=False)
    follow_user_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    follow_decay_id: Mapped[int | None] = mapped_column(Integer, nullable=True)        # group_decays.id
    # a running challenge forces its decay; what I followed before comes back afterwards
    decay_challenge_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    prev_follow_user_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    prev_follow_decay_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    user: Mapped[User] = relationship(back_populates="decay")


class Rule(Base):
    """An accelerator (kind='acc') or decelerator (kind='dec')."""
    __tablename__ = "rules"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(3))
    name: Mapped[str] = mapped_column(String(40))
    icon: Mapped[str] = mapped_column(String(32))
    mode: Mapped[str] = mapped_column(String(16), default="constant")
    base: Mapped[float] = mapped_column(Float)
    param: Mapped[float] = mapped_column(Float, default=0.0)
    min_value: Mapped[float] = mapped_column(Float, default=0.0)
    reset_days: Mapped[int] = mapped_column(Integer, default=0)
    # unit of reset_days / idle_days: "d" days (default) or "h" hours (0..365 either way)
    reset_unit: Mapped[str] = mapped_column(String(1), default="d")
    current: Mapped[float | None] = mapped_column(Float, nullable=True)
    last_triggered_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    position: Mapped[int] = mapped_column(Integer, default=0)
    public: Mapped[bool] = mapped_column(Boolean, default=False)   # shown (with history) on the profile
    client_id: Mapped[str | None] = mapped_column(String(40), nullable=True)   # set when created offline
    # Not tapped for idle_days -> idle_rule (a rule of the other kind) is tapped automatically, again
    # every idle_days while it stays untapped. idle_since: start of the current waiting period.
    idle_days: Mapped[int] = mapped_column(Integer, default=0)
    idle_unit: Mapped[str] = mapped_column(String(1), default="d")
    idle_rule_id: Mapped[int | None] = mapped_column(ForeignKey("rules.id"), nullable=True)
    idle_since: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # subscribed rules: shown in the subscriber's profile (rules and history)
    in_profile: Mapped[bool] = mapped_column(Boolean, default=False)
    idle_paused: Mapped[bool] = mapped_column(Boolean, default=False)   # automatic tap paused (also for subscriptions)
    # False: can't be tapped by hand (only the automatic tap counts it); copies take it over, the owner decides
    tappable: Mapped[bool] = mapped_column(Boolean, default=True)
    # Rule sharing: a subscribed copy points to its root rule and follows its changes.
    source_rule_id: Mapped[int | None] = mapped_column(ForeignKey("rules.id"), nullable=True, index=True)
    # Templates are not personal rules: group rules (group_id) and challenge rules (challenge_id).
    template: Mapped[bool] = mapped_column(Boolean, default=False)
    group_id: Mapped[int | None] = mapped_column(ForeignKey("conversations.id"), nullable=True, index=True)
    # challenge template (template=True) or a participant's temporary copy (template=False)
    challenge_id: Mapped[int | None] = mapped_column(ForeignKey("challenges.id"), nullable=True, index=True)
    # subscription made by a challenge start (removed when leaving it, unless the participant keeps it)
    via_challenge_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    # a group's real copy: the rule it was copied from (no link, only so it isn't copied twice)
    copied_from_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Event(Base):
    """Every change of the momentum value."""
    __tablename__ = "events"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    rule_id: Mapped[int | None] = mapped_column(
        ForeignKey("rules.id", ondelete="SET NULL"), nullable=True)
    kind: Mapped[str] = mapped_column(String(10))  # acc | dec | daily | reset | milestone
    label: Mapped[str] = mapped_column(String(60), default="")
    icon: Mapped[str] = mapped_column(String(32), default="")
    delta: Mapped[float] = mapped_column(Float)
    momentum_after: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
    undone: Mapped[bool] = mapped_column(Boolean, default=False)                 # reverted via the history
    op_id: Mapped[str | None] = mapped_column(String(40), nullable=True, index=True)  # client op (offline sync, dedupe)
    prev_current: Mapped[float | None] = mapped_column(Float, nullable=True)     # rule state before, for undo
    prev_triggered_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    free_delta: Mapped[float | None] = mapped_column(Float, nullable=True)       # change of the free score
    auto_from_rule_id: Mapped[int | None] = mapped_column(Integer, nullable=True)  # automatic tap: this rule was idle
    challenge_id: Mapped[int | None] = mapped_column(ForeignKey("challenges.id"), nullable=True, index=True)


class Follow(Base):
    """follower follows followee (one-way, like Instagram). Mutual = friends."""
    __tablename__ = "follows"
    follower_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    followee_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Post(Base):
    """Feed post: own text/photos/videos, or a milestone the user chose to share."""
    __tablename__ = "posts"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(10), default="post")   # post | milestone | rule
    # kind "rule": a rule of the poster that was made public (or changed while public) - shown in the feed
    rule_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    # the feed's order: created_at, or later when a rule post's rule changed (it comes to the top again)
    bumped_at: Mapped[datetime | None] = mapped_column(DateTime, default=utcnow, nullable=True, index=True)
    text: Mapped[str] = mapped_column(Text, default="")
    vehicle: Mapped[str | None] = mapped_column(String(32), nullable=True)   # milestone posts
    # a sound added to the post (an audio Media of the post) and its name
    sound_title: Mapped[str] = mapped_column(String(60), default="")
    # where it was (like Instagram): a place name, coordinates if picked from the search
    location: Mapped[str | None] = mapped_column(String(80), nullable=True)
    lat: Mapped[float | None] = mapped_column(Float, nullable=True)
    lon: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)


class Media(Base):
    """Uploaded photo or short video (blob in MinIO). Attached to a post or a message."""
    __tablename__ = "media"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(8))          # image | video | audio (voice message, chat only)
    key: Mapped[str] = mapped_column(String(128))
    thumb_key: Mapped[str | None] = mapped_column(String(128), nullable=True)
    width: Mapped[int] = mapped_column(Integer, default=0)
    height: Mapped[int] = mapped_column(Integer, default=0)
    duration: Mapped[float] = mapped_column(Float, default=0.0)
    post_id: Mapped[int | None] = mapped_column(ForeignKey("posts.id"), nullable=True, index=True)
    # a story's picture / video / sound (stories.py)
    story_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    position: Mapped[int] = mapped_column(Integer, default=0)
    attached: Mapped[bool] = mapped_column(Boolean, default=False)
    # text over a photo / video of a post: {"text", "x", "y", "color", "size"} (JSON)
    overlay: Mapped[str | None] = mapped_column(Text, nullable=True)
    # a chat photo after CHAT_IMAGE_DAYS: only the small copy is left (chat.archive_chat_images)
    archived: Mapped[bool] = mapped_column(Boolean, default=False)
    # a chat photo / voice message of an encrypted chat: the blobs are ciphertext, the key is in the message
    encrypted: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class MediaTrigger(Base):
    """A rule tapped with a post's slide or a story (shown in the picture's corner, tap = the rule).
    Name / icon / kind are kept as they were when posting."""
    __tablename__ = "media_triggers"
    id: Mapped[int] = mapped_column(primary_key=True)
    post_id: Mapped[int | None] = mapped_column(ForeignKey("posts.id"), nullable=True, index=True)
    story_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    slide: Mapped[int] = mapped_column(Integer, default=0)          # position of the post's photo / video
    rule_id: Mapped[int | None] = mapped_column(Integer, nullable=True)   # the poster's rule (its detail on tap)
    name: Mapped[str] = mapped_column(String(40))
    icon: Mapped[str] = mapped_column(String(32))
    kind: Mapped[str] = mapped_column(String(3))
    times: Mapped[int] = mapped_column(Integer, default=1)          # tapped this many times ("x3")
    points: Mapped[float] = mapped_column(Float, default=0.0)       # what it actually gave / took (all taps)
    position: Mapped[int] = mapped_column(Integer, default=0)


class Story(Base):
    """A photo / video for 24 hours (like Instagram); kept afterwards for highlights and the archive."""
    __tablename__ = "stories"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    media_id: Mapped[int] = mapped_column(Integer)
    sound_title: Mapped[str] = mapped_column(String(60), default="")
    # how long a photo story runs (seconds: 5, 10, 15 or 29); videos run as long as they are
    duration: Mapped[float] = mapped_column(Float, default=5.0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime, index=True)


class StorySeen(Base):
    __tablename__ = "story_seen"
    story_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Highlight(Base):
    """Stories kept on the profile under a title (like Instagram's highlights)."""
    __tablename__ = "highlights"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    title: Mapped[str] = mapped_column(String(30))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class HighlightStory(Base):
    __tablename__ = "highlight_stories"
    highlight_id: Mapped[int] = mapped_column(ForeignKey("highlights.id"), primary_key=True)
    story_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class PostLike(Base):
    __tablename__ = "post_likes"
    post_id: Mapped[int] = mapped_column(ForeignKey("posts.id"), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class FollowRequest(Base):
    """Pending follow of a private account."""
    __tablename__ = "follow_requests"
    requester_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    target_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Challenge(Base):
    """Group challenge over whole (server) days with its own rules.

    ranking: most momentum from the challenge rules wins reward1/2/3.
    target:  everyone reaching `target` within the period gets reward1.
    """
    __tablename__ = "challenges"
    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    title: Mapped[str] = mapped_column(String(60))
    mode: Mapped[str] = mapped_column(String(10))
    start_day: Mapped[date] = mapped_column(Date)
    end_day: Mapped[date] = mapped_column(Date)            # inclusive
    target: Mapped[float] = mapped_column(Float, default=0.0)
    reward1: Mapped[float] = mapped_column(Float, default=0.0)
    reward2: Mapped[float] = mapped_column(Float, default=0.0)
    reward3: Mapped[float] = mapped_column(Float, default=0.0)
    status: Mapped[str] = mapped_column(String(10), default="upcoming")   # upcoming|running|finished|cancelled
    banner: Mapped[str | None] = mapped_column(String(20), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    # at most one of the group's decays, followed by every participant while they take part
    decay_id: Mapped[int | None] = mapped_column(Integer, nullable=True)


class ChallengeParticipant(Base):
    __tablename__ = "challenge_participants"
    challenge_id: Mapped[int] = mapped_column(ForeignKey("challenges.id"), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True, index=True)
    joined_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    left: Mapped[bool] = mapped_column(Boolean, default=False)       # leaving is final: no rewards
    score: Mapped[float] = mapped_column(Float, default=0.0)         # final, set when the challenge ends
    rank: Mapped[int | None] = mapped_column(Integer, nullable=True)
    reward: Mapped[float] = mapped_column(Float, default=0.0)
    # keep the challenge's rules (subscriptions) after leaving / the end
    keep_rules: Mapped[bool] = mapped_column(Boolean, default=False)


class ChallengeRule(Base):
    """A group rule a challenge is played with (participants subscribe to it on start)."""
    __tablename__ = "challenge_rules"
    challenge_id: Mapped[int] = mapped_column(ForeignKey("challenges.id"), primary_key=True)
    rule_id: Mapped[int] = mapped_column(ForeignKey("rules.id"), primary_key=True, index=True)


class PostTag(Base):
    """People tagged in a post."""
    __tablename__ = "post_tags"
    post_id: Mapped[int] = mapped_column(ForeignKey("posts.id"), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True, index=True)


class Comment(Base):
    __tablename__ = "comments"
    id: Mapped[int] = mapped_column(primary_key=True)
    post_id: Mapped[int] = mapped_column(ForeignKey("posts.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    text: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    # reply to a top-level comment (threads are one level deep, like Instagram)
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("comments.id"), nullable=True, index=True)


class CommentLike(Base):
    __tablename__ = "comment_likes"
    comment_id: Mapped[int] = mapped_column(ForeignKey("comments.id"), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Block(Base):
    """blocker blocked `blocked`: they don't see each other (profiles, posts, comments, search) and
    can't follow, message, add or tag each other. Blocking ends follows and requests both ways."""
    __tablename__ = "blocks"
    blocker_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    blocked_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Notification(Base):
    """Activity: follow | request | accepted | like | comment | reply | clike | mention | tag | ch_end | ch_cancel |
    sub_rule | sub_decay (someone - or a group, group_id - subscribed to my rule / follows my decay: report #54)."""
    __tablename__ = "notifications"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)     # recipient
    actor_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    kind: Mapped[str] = mapped_column(String(10))
    post_id: Mapped[int | None] = mapped_column(ForeignKey("posts.id"), nullable=True)
    comment_id: Mapped[int | None] = mapped_column(ForeignKey("comments.id"), nullable=True)
    challenge_id: Mapped[int | None] = mapped_column(ForeignKey("challenges.id"), nullable=True)
    rule_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    group_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    read: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Conversation(Base):
    """Direct message (2 members) or group chat."""
    __tablename__ = "conversations"
    id: Mapped[int] = mapped_column(primary_key=True)
    kind: Mapped[str] = mapped_column(String(8))           # direct | group
    name: Mapped[str] = mapped_column(String(60), default="")
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_message_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
    banner: Mapped[str | None] = mapped_column(String(20), nullable=True)   # groups: shown in the overview
    # groups: public = found in the search (overview visible to everyone); open = anyone may join
    # (only together with public)
    public: Mapped[bool] = mapped_column(Boolean, default=False)
    open: Mapped[bool] = mapped_column(Boolean, default=False)
    # groups: own picture and an own banner image (instead of a pixel banner), uploaded media
    avatar_media_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    banner_media_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # anarchy mode: every member is an admin
    anarchy: Mapped[bool] = mapped_column(Boolean, default=False)
    # end-to-end encryption of new messages (on by default; direct: both, groups: admins switch it)
    e2e: Mapped[bool] = mapped_column(Boolean, default=True)


class ConversationMember(Base):
    __tablename__ = "conversation_members"
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True, index=True)
    last_read_id: Mapped[int] = mapped_column(Integer, default=0)
    joined_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    # groups: automatically subscribe to every rule the group follows (now and later)
    auto_follow: Mapped[bool] = mapped_column(Boolean, default=False)
    # muted: no notifications for this chat (mentions still come through), not in the unread badge
    muted: Mapped[bool] = mapped_column(Boolean, default=False)
    # groups: admins change the group (rules, challenges, members, settings); there's always one
    admin: Mapped[bool] = mapped_column(Boolean, default=False)


class Message(Base):
    __tablename__ = "messages"
    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    text: Mapped[str] = mapped_column(Text, default="")
    media_id: Mapped[int | None] = mapped_column(ForeignKey("media.id"), nullable=True)
    share_kind: Mapped[str | None] = mapped_column(String(10), nullable=True)   # rule | profile | post
    share_ref: Mapped[str | None] = mapped_column(String(60), nullable=True)    # rule/post id or username
    reply_to_id: Mapped[int | None] = mapped_column(Integer, nullable=True)       # answer to this message (swipe)
    # encrypted chats: the envelope (server/e2e.py) instead of text; the tagged accounts as ",3,8," (the
    # server can't read the text, the sender names them)
    e2e: Mapped[str | None] = mapped_column(Text, nullable=True)
    mentions: Mapped[str | None] = mapped_column(Text, nullable=True)
    # a line from the chat itself: e2e_on | e2e_off (who switched the encryption); member_join | member_add |
    # member_remove | member_leave (groups, report #56: user_id = who did it, target_id = who was added / removed);
    # call (a voice / video call: user_id = who rang, call_id)
    kind: Mapped[str | None] = mapped_column(String(12), nullable=True)
    target_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # kind "call": the call it tells about (server/calls.py Call)
    call_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class MessageReaction(Base):
    """An emoji on a chat message (one per person, like Instagram; double tap = heart)."""
    __tablename__ = "message_reactions"
    message_id: Mapped[int] = mapped_column(ForeignKey("messages.id"), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    emoji: Mapped[str] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class UserActivity(Base):
    """Days on which a player used the app online (admin statistics)."""
    __tablename__ = "user_activity"
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    day: Mapped[date] = mapped_column(Date, primary_key=True, index=True)


class DailyCap(Base):
    """Frozen daily momentum limit: the day's growth rate (cap = bias = rate) and the average it came from."""
    __tablename__ = "daily_caps"
    day: Mapped[date] = mapped_column(Date, primary_key=True)
    average: Mapped[float] = mapped_column(Float)
    bias: Mapped[float] = mapped_column(Float)
    cap: Mapped[float] = mapped_column(Float)
    players: Mapped[int] = mapped_column(Integer)
    computed_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Report(Base):
    """Bug report or feature request from the app (admin app, or: python -m server.admin reports).

    Status: new (just filed - agents need the owner's explicit OK before touching it) -> todo (approved:
    implement it) -> done."""
    __tablename__ = "reports"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    username: Mapped[str] = mapped_column(String(40), default="")       # kept if the account is deleted
    kind: Mapped[str] = mapped_column(String(8))                       # bug | feature
    text: Mapped[str] = mapped_column(Text)
    media_keys: Mapped[str] = mapped_column(Text, default="")          # attached images, one blob key per line
    app_version: Mapped[str] = mapped_column(String(40), default="")
    device: Mapped[str] = mapped_column(String(120), default="")
    platform: Mapped[str] = mapped_column(String(10), default="")      # android | pwa
    status: Mapped[str] = mapped_column(String(8), default="new")      # new | todo | done
    note: Mapped[str] = mapped_column(Text, default="")                # admin's note / reworded task for agents
    status_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)   # last status change
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    # its issue in the public GitHub repo (server/github.py) and a hash of what was last sent there
    issue_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    issue_sync: Mapped[str] = mapped_column(String(40), default="")


class Config(Base):
    """Server-wide settings (e.g. daily_cap_bias)."""
    __tablename__ = "config"
    key: Mapped[str] = mapped_column(String(40), primary_key=True)
    value: Mapped[str] = mapped_column(String(200))


class AuthToken(Base):
    """A login session. scope "app": the game (ends after half a year unused); "admin": only the admin
    API (ends at expires_at). Neither works for the other."""
    __tablename__ = "auth_tokens"
    token: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    scope: Mapped[str] = mapped_column(String(8), default="app")
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


def _sqlite_wal(dbapi_conn, _record):
    """WAL: readers and the writer don't wait for each other. With the default rollback journal a commit
    waits for every reader and meanwhile locks out new ones - one stuck reader stopped all writes."""
    cur = dbapi_conn.cursor()
    cur.execute("PRAGMA journal_mode=WAL")
    cur.close()


class Database:
    def __init__(self, url):
        self.engine = create_async_engine(url)
        if url.startswith("sqlite") and ":memory:" not in url:
            event.listen(self.engine.sync_engine, "connect", _sqlite_wal)
        self.session = async_sessionmaker(self.engine, expire_on_commit=False)

    async def create_all(self):
        async with self.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            await conn.run_sync(_add_missing_columns)
            await conn.run_sync(_migrate_data)

    async def close(self):
        await self.engine.dispose()


def _migrate_data(conn):
    """One-off data fixes; safe to run on every start."""
    conn.exec_driver_sql("CREATE UNIQUE INDEX IF NOT EXISTS ix_users_email ON users (email)")
    conn.exec_driver_sql("CREATE UNIQUE INDEX IF NOT EXISTS ix_users_google ON users (google_sub)")
    # the name shown everywhere is the username
    conn.exec_driver_sql("UPDATE users SET display_name = username WHERE display_name IS NULL OR display_name != username")
    # the free score starts where the competitive momentum was when it was introduced
    if not conn.exec_driver_sql("SELECT 1 FROM config WHERE key = 'free_score_init'").first():
        conn.exec_driver_sql("UPDATE users SET free_score = momentum")
        conn.exec_driver_sql("INSERT INTO config (key, value) VALUES ('free_score_init', '1')")
    # the Clown's count starts with what exists when the titles arrive (server/nine.py)
    if not conn.exec_driver_sql("SELECT 1 FROM config WHERE key = 'posts_total_init'").first():
        conn.exec_driver_sql(
            "UPDATE users SET posts_total = (SELECT COUNT(*) FROM posts WHERE posts.user_id = users.id AND posts.kind = 'post')"
            " + (SELECT COUNT(*) FROM stories WHERE stories.user_id = users.id)")
        conn.exec_driver_sql("INSERT INTO config (key, value) VALUES ('posts_total_init', '1')")
    # one banner per vehicle type: space scenes chosen before move to the space slot
    conn.exec_driver_sql(
        "UPDATE users SET banner_space = banner, banner = NULL WHERE banner IN ('hyperspace', 'cosmos', 'moon')")
    _dedupe_group_rules(conn)
    # group names are unique (like usernames): older duplicates get a number
    seen = set()
    for conv_id, name in conn.exec_driver_sql(
            "SELECT id, name FROM conversations WHERE kind = 'group' ORDER BY id").fetchall():
        new, n = name, 1
        while new.lower() in seen:
            n += 1
            new = f"{name[:56]} {n}"
        seen.add(new.lower())
        if new != name:
            conn.exec_driver_sql("UPDATE conversations SET name = ? WHERE id = ?", (new, conv_id))
    # one milestone every 50,000 momentum (was 10,000)
    if not conn.exec_driver_sql("SELECT 1 FROM config WHERE key = 'milestone_50k'").first():
        conn.exec_driver_sql("UPDATE settings SET milestone_step = 50000 WHERE milestone_step = 10000")
        conn.exec_driver_sql("INSERT INTO config (key, value) VALUES ('milestone_50k', '1')")
    # milestones double from 1,000 on (were every 50,000)
    if not conn.exec_driver_sql("SELECT 1 FROM config WHERE key = 'milestone_ladder'").first():
        conn.exec_driver_sql("UPDATE settings SET milestone_step = 1000 WHERE milestone_step = 50000")
        conn.exec_driver_sql("INSERT INTO config (key, value) VALUES ('milestone_ladder', '1')")
    # group admins: whoever created a group is its admin (or the longest member, if they left)
    if not conn.exec_driver_sql("SELECT 1 FROM config WHERE key = 'group_admins'").first():
        groups = conn.exec_driver_sql("SELECT id, created_by FROM conversations WHERE kind = 'group'").all()
        for conv_id, creator in groups:
            hit = conn.exec_driver_sql("UPDATE conversation_members SET admin = 1 WHERE conversation_id = ? AND user_id = ?",
                                       (conv_id, creator)).rowcount
            if not hit:
                first = conn.exec_driver_sql("SELECT user_id FROM conversation_members WHERE conversation_id = ? "
                                             "ORDER BY joined_at, user_id LIMIT 1", (conv_id,)).first()
                if first:
                    conn.exec_driver_sql("UPDATE conversation_members SET admin = 1 WHERE conversation_id = ? AND user_id = ?",
                                         (conv_id, first[0]))
        conn.exec_driver_sql("INSERT INTO config (key, value) VALUES ('group_admins', '1')")
    # the feed is ordered by bumped_at (rule posts move up when their rule changes)
    conn.exec_driver_sql("UPDATE posts SET bumped_at = created_at WHERE bumped_at IS NULL")
    # reports: "open" became "new" (todo = approved for implementation, done)
    conn.exec_driver_sql("UPDATE reports SET status = 'new' WHERE status = 'open'")
    # momentum is whole numbers only (older versions could store fractions)
    conn.exec_driver_sql("UPDATE users SET momentum = CAST(momentum AS INTEGER) WHERE momentum != CAST(momentum AS INTEGER)")
    # early accounts used their e-mail as username: move it and ask for a real username
    # (the e-mail must never be visible to others, so the username becomes a neutral placeholder)
    conn.exec_driver_sql(
        "UPDATE users SET email = coalesce(email, lower(username)), username_set = 0, "
        "username = 'u' || lower(hex(randomblob(6))), "
        "display_name = CASE WHEN display_name LIKE '%@%' THEN '' ELSE display_name END "
        "WHERE username LIKE '%@%'")


_RULE_SHAPE = "kind, name, icon, mode, base, param, min_value, reset_days"


def _dedupe_group_rules(conn):
    """One-off: copying rules (with their idle chains) into groups could create the same rule twice.
    Keep one per shape (a followed rule wins over a copy), move everything that pointed to the others."""
    if conn.exec_driver_sql("SELECT 1 FROM config WHERE key = 'group_rule_dedupe'").first():
        return
    rows = conn.exec_driver_sql(
        f"SELECT id, group_id, source_rule_id, {_RULE_SHAPE} FROM rules WHERE template = 1 AND group_id IS NOT NULL "
        "ORDER BY (source_rule_id IS NULL), id").fetchall()
    keep = {}
    for r in rows:
        shape = (r[1],) + tuple(r[3:])
        if shape not in keep:
            keep[shape] = r[0]
            continue
        kept, dup = keep[shape], r[0]
        for sql in ("UPDATE rules SET source_rule_id = ? WHERE source_rule_id = ? AND template = 0",
                    "UPDATE rules SET idle_rule_id = ? WHERE idle_rule_id = ?",
                    "UPDATE OR IGNORE challenge_rules SET rule_id = ? WHERE rule_id = ?"):
            conn.exec_driver_sql(sql, (kept, dup))
        conn.exec_driver_sql("DELETE FROM challenge_rules WHERE rule_id = ?", (dup,))
        conn.exec_driver_sql("DELETE FROM rules WHERE id = ?", (dup,))
    # a member may now hold two subscriptions of the same kept rule: keep the older one
    for user_id, source_id, kept in conn.exec_driver_sql(
            "SELECT user_id, source_rule_id, MIN(id) FROM rules WHERE template = 0 AND source_rule_id IS NOT NULL "
            "AND challenge_id IS NULL GROUP BY user_id, source_rule_id HAVING COUNT(*) > 1").fetchall():
        for (dup,) in conn.exec_driver_sql(
                "SELECT id FROM rules WHERE template = 0 AND challenge_id IS NULL AND user_id = ? AND source_rule_id = ? "
                "AND id != ?", (user_id, source_id, kept)).fetchall():
            conn.exec_driver_sql("UPDATE rules SET idle_rule_id = ? WHERE idle_rule_id = ?", (kept, dup))
            conn.exec_driver_sql("UPDATE events SET rule_id = ? WHERE rule_id = ?", (kept, dup))
            conn.exec_driver_sql("DELETE FROM rules WHERE id = ?", (dup,))
    conn.exec_driver_sql("INSERT INTO config (key, value) VALUES ('group_rule_dedupe', '1')")


def _add_missing_columns(conn):
    """Tiny migration step: add columns that exist in the models but not yet in the DB."""
    insp = inspect(conn)
    for table in Base.metadata.sorted_tables:
        existing = {c["name"] for c in insp.get_columns(table.name)}
        for col in table.columns:
            if col.name in existing:
                continue
            sql = f"ALTER TABLE {table.name} ADD COLUMN {col.name} {col.type.compile(conn.dialect)}"
            default = col.default.arg if col.default is not None and not callable(col.default.arg) else None
            if default is not None:
                sql += f" DEFAULT {int(default) if isinstance(default, bool) else repr(default)}"
            conn.exec_driver_sql(sql)
