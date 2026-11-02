"""What's new for background notifications on the phone (read-only: nothing is marked as read).

    GET /api/updates?after_message=<id>&after_notification=<id>

The app remembers the newest ids it has shown or notified about; a background job asks for
everything after them and turns it into system notifications.
"""
from quart import g, jsonify, request
from sqlalchemy import func, select

from .chat import mentions_of
from .db import ChallengeParticipant, Challenge, Conversation, ConversationMember, Media, Message, Notification, User
from .e2e import envelope_for, mentioned_ids
from .mentions import render_one
from .clients import hidden_notification_kinds
from .social import card_map, subject_of

LIMIT = 30


async def latest_ids(s, user_id):
    """Newest message (from others, in my chats) and newest notification: what "seen" means."""
    mine = select(ConversationMember.conversation_id).where(ConversationMember.user_id == user_id)
    msg = await s.scalar(select(func.max(Message.id)).where(Message.conversation_id.in_(mine), Message.user_id != user_id))
    note = await s.scalar(select(func.max(Notification.id)).where(Notification.user_id == user_id))
    return dict(latest_message_id=msg or 0, latest_notification_id=note or 0)


async def still_unread(s, user_id):
    """Chats with unread messages and unread activity ids: the phone takes away notifications of what
    was read in the meantime (also on another device)."""
    chats = await s.scalars(
        select(Message.conversation_id).distinct()
        .join(ConversationMember, (ConversationMember.conversation_id == Message.conversation_id)
              & (ConversationMember.user_id == user_id))
        .where(Message.user_id != user_id, Message.id > ConversationMember.last_read_id, Message.kind.is_(None)))
    notes = await s.scalars(select(Notification.id).where(Notification.user_id == user_id, Notification.read.is_(False))
                            .order_by(Notification.id.desc()).limit(200))
    return dict(unread_chats=sorted(chats), unread_notifications=sorted(notes))


def register(app, authed):
    @app.get("/api/updates")
    @authed
    async def updates():
        me = g.user.id
        after_msg = request.args.get("after_message", 0, type=int)
        after_note = request.args.get("after_notification", 0, type=int)
        # unread only: a message I already read in the app (read marker) isn't news
        rows = (await g.s.execute(
            select(Message, Conversation)
            .join(Conversation, Conversation.id == Message.conversation_id)
            .join(ConversationMember, (ConversationMember.conversation_id == Message.conversation_id)
                  & (ConversationMember.user_id == me))
            .where(Message.id > after_msg, Message.user_id != me, Message.id > ConversationMember.last_read_id,
                   Message.kind.is_(None),
                   # muted chats stay quiet, except when someone tags me
                   ConversationMember.muted.is_(False) | mentions_of(me))
            .order_by(Message.id).limit(LIMIT))).all()
        notes = list(await g.s.scalars(select(Notification).where(
            Notification.user_id == me, Notification.id > after_note, Notification.read.is_(False),
            Notification.kind.not_in(hidden_notification_kinds()))
            .order_by(Notification.id).limit(LIMIT)))
        people = await card_map(g.s, me, {m.user_id for m, _ in rows} | {n.actor_id for n in notes})
        messages = []
        for m, conv in rows:
            sender = people.get(m.user_id, {}).get("username", "")
            text = (await render_one(g.s, m.text)) if m.text else ""
            messages.append(dict(id=m.id, conversation_id=conv.id, group=conv.kind == "group",
                                 title=conv.name if conv.kind == "group" else sender, sender=sender, text=text,
                                 media=m.media_id is not None, share=m.share_kind, mention=me in mentioned_ids(m),
                                 media_kind=(await g.s.get(Media, m.media_id)).kind if m.media_id else None,
                                 # encrypted: the preview for me, the phone opens it
                                 e2e=envelope_for(m, me, full=False)))
        titles = {}
        for cid in {n.challenge_id for n in notes if n.challenge_id}:
            ch = await g.s.get(Challenge, cid)
            p = await g.s.get(ChallengeParticipant, (cid, me))
            titles[cid] = dict(title=ch.title if ch else "", rank=p.rank if p else None, reward=p.reward if p else 0)
        subjects = await subject_of(g.s, notes)
        notifications = [dict(id=n.id, kind=n.kind, actor=people.get(n.actor_id, {}).get("username", ""),
                              post_id=n.post_id, challenge=titles.get(n.challenge_id), **subjects[n.id]) for n in notes]
        return jsonify(messages=messages, notifications=notifications, **await latest_ids(g.s, me),
                       **await still_unread(g.s, me))
