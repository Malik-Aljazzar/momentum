// Which component draws which tab / opened screen (App.kt: MainScreen pager + RouteScreen).
import type { Route, Tab } from "../nav"
import { state } from "../store"
import { ChatRoomScreen, ChatsScreen, NewChatScreen, NewGroupScreen } from "./Chat"
import { ChallengeDetailScreen, FollowRequestsScreen, GroupInfoScreen, NewChallengeScreen } from "./Community"
import { FeedScreen, PostDetailScreen } from "./Feed"
import { NewPostScreen, NewStoryScreen } from "./Compose"
import { LeaderboardScreen } from "./Leaderboard"
import { BlockedListScreen, FollowListScreen, NotificationsScreen, ProfileScreen, SearchScreen } from "./Profile"
import { ReportScreen } from "./Report"
import { ChatInfoScreen, RecoveryScreen, UnlockScreen } from "./Secure"
import { ConfirmEmailScreen } from "./Login"
import { SettingsPage } from "./Settings"

export function TabScreen({ tab }: { tab: Tab }) {
  const st = state.value
  if (!st) return null
  switch (tab) {
    case "chats": return <ChatsScreen isTab />
    case "feed": return <FeedScreen />
    case "profile": return <ProfileScreen username={st.user.username} isTab />
    default: return null
  }
}

export function RouteScreen({ route }: { route: Route }) {
  const st = state.value
  switch (route.r) {
    case "user": return <ProfileScreen username={route.username} isTab={false} />
    case "post": return <PostDetailScreen id={route.id} />
    case "follows": return <FollowListScreen username={route.username} followers={route.followers} />
    case "blocked": return <BlockedListScreen />
    case "search": return <SearchScreen />
    case "notifications": return <NotificationsScreen />
    case "chats": return <ChatsScreen />
    case "chat": return <ChatRoomScreen id={route.id} />
    case "newPost": return <NewPostScreen />
    case "newStory": return <NewStoryScreen />
    case "newChat": return <NewChatScreen />
    case "newGroup": return <NewGroupScreen />
    case "settings": return st ? <SettingsPage st={st} /> : null
    case "group": return <GroupInfoScreen convId={route.id} />
    case "challenge": return <ChallengeDetailScreen id={route.id} />
    case "newChallenge": return <NewChallengeScreen convId={route.conversationId} />
    case "requests": return <FollowRequestsScreen />
    case "report": return <ReportScreen />
    case "leaderboard": return <LeaderboardScreen />
    case "chatInfo": return <ChatInfoScreen id={route.id} />
    case "e2eRecovery": return <RecoveryScreen />
    case "e2eUnlock": return <UnlockScreen />
    case "confirmEmail": return <ConfirmEmailScreen opened />
  }
}
