"""What the calling app understands.

The PWA is deployed together with the server, so it always knows everything. An Android app sends its
version (X-App-Version) and gets newer kinds of chat lines and activity only once it can show them (an
older app would show a member line as "encryption switched off" and new activity without text).
"""
from quart import has_request_context, request

# Android builds from 2026-10-29 on (versionCode = minutes since 1970 - 29,000,000): group member lines
# (report #56) and subscription activity (report #54)
MEMBER_LINES_APP = 888_050
MEMBER_KINDS = ("member_join", "member_add", "member_remove", "member_leave")
# Android builds from 2026-11-03 on show the confirm-your-e-mail screen; older ones aren't locked out (server/mail.py)
CONFIRM_APP = 895_206
SUBSCRIPTION_KINDS = ("sub_rule", "sub_decay")
# Android builds that can call (server/calls.py) show the calls' chat lines; older ones would show them as
# "encryption switched off"
CALLS_APP = 999_999_999
CALL_KINDS = ("call",)


def knows(version):
    if not has_request_context():
        return True
    sent = request.headers.get("X-App-Version", "")
    return not sent.isdigit() or int(sent) >= version


def hidden_message_kinds():
    return (() if knows(MEMBER_LINES_APP) else MEMBER_KINDS) + (() if knows(CALLS_APP) else CALL_KINDS)


def hidden_notification_kinds():
    return () if knows(MEMBER_LINES_APP) else SUBSCRIPTION_KINDS
