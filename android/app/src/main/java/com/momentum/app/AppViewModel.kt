package com.momentum.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.momentum.app.data.Api
import com.momentum.app.data.ApiException
import com.momentum.app.data.AppState
import com.momentum.app.data.Event
import com.momentum.app.data.LocalStore
import com.momentum.app.data.MediaItem
import com.momentum.app.data.MediaResponse
import com.momentum.app.data.Meta
import com.momentum.app.data.PendingOp
import com.momentum.app.data.Prefs
import com.momentum.app.data.Rule
import com.momentum.app.data.StateResponse
import com.momentum.app.data.TokenResponse
import com.momentum.app.data.VehicleInfo
import com.momentum.app.ui.Lang
import com.momentum.app.ui.Strings
import com.momentum.app.ui.stringsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class Screen { Loading, Offline, Login, Main }

private val EMAIL = Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$")
private val RULE_PATH = Regex("^/rules/(-?\\d+)$")
private val TEMP_RULE = Regex("/rules/(-\\d+)")

/** Settings that make sense without the server (username, picture, password need it). */
private val OFFLINE_SETTINGS = setOf("timezone", "haptics", "show_history")

/** "+400" that floats up from a rule card. */
data class Pop(val ruleId: Int, val text: String, val nonce: Long)

data class Toast(val text: String, val error: Boolean, val nonce: Long)

/**
 * App state + server access. Works offline: the last state and visited pages are cached,
 * rule/decay/settings changes and taps are applied locally and queued, and the queue is
 * sent in order when the network comes back (the server stays the source of truth).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val api = Api(prefs.serverUrl, prefs.token, prefs.language)
    private val store = LocalStore(app, api.json)
    private val ops = store.loadOps()
    private val tempRules = store.loadTempRules()
    private val syncLock = Mutex()

    var lang by mutableStateOf(if (prefs.language == "de") Lang.DE else Lang.EN); private set
    val strings: Strings get() = stringsFor(lang)

    var screen by mutableStateOf(Screen.Loading); private set
    var meta by mutableStateOf<Meta?>(null); private set
    var state by mutableStateOf<AppState?>(null); private set
    var pop by mutableStateOf<Pop?>(null); private set
    var toast by mutableStateOf<Toast?>(null); private set
    var milestone by mutableStateOf(false)
    /** this app is older than the one on the server: everything is blocked by the update screen */
    var updateRequired by mutableStateOf(false); private set
    /** easter egg: 1 million momentum reached -> hyperspace jump */
    var hyperspace by mutableStateOf(false)
    var devMode by mutableStateOf(prefs.devMode); private set
    /** user-written texts (chat, comments, captions, bios) in the system font */
    var systemFont by mutableStateOf(prefs.systemFont); private set
    var notifications by mutableStateOf(prefs.notifications); private set
    /** opened from a notification: chat id, or -1 for the activity list */
    var openRequest by mutableStateOf<Int?>(null)
    /** after an update: the changelog's versions this device hasn't seen yet (null = nothing to show) */
    var whatsNew by mutableStateOf<List<com.momentum.app.data.Release>?>(null)
    /** a day's first start: the daily limit is higher / lower than the day before (shown after whatsNew) */
    var limitNews by mutableStateOf<com.momentum.app.data.LimitNews?>(null)

    /** The whole changelog (CHANGELOG.md, bundled at build time). */
    fun changelog() = com.momentum.app.data.Changelog.load(getApplication())

    fun changeNotifications(on: Boolean) {
        notifications = on
        prefs.notifications = on
        if (on && api.token != null) com.momentum.app.data.Notifier.schedule(getApplication())
        else com.momentum.app.data.Notifier.cancel(getApplication())
    }

    fun changeSystemFont(on: Boolean) {
        systemFont = on
        prefs.systemFont = on
    }

    /** Connection to the server; public setter because inline [req] updates it. */
    var online by mutableStateOf(true)
    var pending by mutableIntStateOf(ops.size); private set
    /** Increments after each successful sync; images that failed to load retry then. */
    var imageRetry by mutableIntStateOf(0); private set

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                online = true
                when (screen) {
                    Screen.Main -> sync()
                    Screen.Offline -> boot()
                    else -> {}
                }
            }
        }

        override fun onLost(network: Network) {
            viewModelScope.launch { online = hasNetwork() }
        }
    }

    private fun hasNetwork(): Boolean = connectivity?.activeNetwork?.let { net ->
        connectivity.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } == true

    init {
        api.onUpdateRequired = {
            if (!updateRequired) viewModelScope.launch {         // show the version that's on offer now
                runCatching { meta = api.call<Meta>("GET", "/meta") }
            }
            updateRequired = true
        }
        if (prefs.token != null && prefs.notifications) com.momentum.app.data.Notifier.schedule(app)
        // calls (data/Calls.kt): its messages become toasts; the socket opens with the session
        com.momentum.app.data.Toasts.sink = { text, error -> showToast(text, error) }
        if (prefs.token != null) com.momentum.app.data.Calls.attach(app, api, cachedState()?.user?.id ?: -1)
        online = hasNetwork()
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        com.momentum.app.data.Changelog.pending(prefs, prefs.token != null, changelog())
            .takeIf { it.isNotEmpty() }?.let { whatsNew = it }
        boot()
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    fun toggleDevMode() {
        devMode = !devMode
        prefs.devMode = devMode
        showToast(if (devMode) strings.devOn else strings.devOff)
    }

    /** The "+400" pop is shown once; screens call this when its animation is done. */
    fun consumePop(p: Pop) {
        if (pop == p) pop = null
    }

    val serverUrl get() = api.baseUrl

    // ---------------------------------------------------------------- cache

    fun cacheWrite(key: String, text: String) = store.write(key, text)
    fun cacheRead(key: String): String? = store.read(key)

    /** The app is on screen (between onResume and onPause). */
    /** bumped after posting: the feed loads again */
    var feedVersion by mutableStateOf(0)

    var foreground by mutableStateOf(true)
        private set

    /**
     * Foreground: normal refreshes. Background: nothing counts as seen any more, and for a while new
     * messages / activity are checked about every minute (QuickCheckWorker), then every ~15 minutes.
     */
    fun onForegroundChanged(on: Boolean) {
        foreground = on
        com.momentum.app.data.Calls.setVisible(on)
        if (on) {
            com.momentum.app.data.QuickCheckWorker.stop(getApplication())
            refresh()
        } else if (prefs.token != null && prefs.notifications) {
            com.momentum.app.data.QuickCheckWorker.start(getApplication())
        }
    }

    private fun putState(s: AppState) {
        state = s
        com.momentum.app.data.LimitNews.check(prefs, s)?.let { limitNews = it }
        s.user.id?.let { com.momentum.app.data.Calls.attach(getApplication(), api, it) }
        // encrypted chats: my key on this phone (made on the first device, else unlocked with the recovery key)
        val uid = s.user.id
        if (uid != null && (e2eStartedFor != uid || com.momentum.app.data.E2EStore.status == com.momentum.app.data.E2EStore.Status.Loading)) {
            e2eStartedFor = uid
            viewModelScope.launch { runCatching { com.momentum.app.data.E2EStore.start(getApplication(), api, uid) } }
        }
        // app on screen: what's in the state is seen, the background check won't notify about it
        if (foreground) {
            prefs.lastMessageSeen = maxOf(prefs.lastMessageSeen, s.unread.latestMessageId)
            prefs.lastNotificationSeen = maxOf(prefs.lastNotificationSeen, s.unread.latestNotificationId)
        }
        store.write("state", api.json.encodeToString(AppState.serializer(), s))
        com.momentum.app.widget.Widgets.updateAll(getApplication())   // home screen widgets follow
    }

    private var e2eStartedFor: Int? = null

    private fun cachedState(): AppState? =
        store.read("state")?.let { runCatching { api.json.decodeFromString(AppState.serializer(), it) }.getOrNull() }

    private fun cachedMeta(): Meta? =
        store.read("meta")?.let { runCatching { api.json.decodeFromString(Meta.serializer(), it) }.getOrNull() }

    // ---------------------------------------------------------------- start, sync

    fun boot() = viewModelScope.launch {
        screen = Screen.Loading
        val m = try {
            api.call<Meta>("GET", "/meta").also {
                store.write("meta", api.json.encodeToString(Meta.serializer(), it))
                online = true
            }
        } catch (e: ApiException) {
            if (e.status == 0) online = false
            cachedMeta()
        }
        if (m == null) {
            screen = Screen.Offline
            return@launch
        }
        meta = m
        if (!BuildConfig.PLAY && (m.app.versionCode ?: 0) > BuildConfig.VERSION_CODE) updateRequired = true
        if (api.token == null) {
            screen = if (online) Screen.Login else Screen.Offline
            return@launch
        }
        cachedState()?.let {        // show the last known state right away, even offline
            state = currentDay(it)
            screen = Screen.Main
        }
        if (online && sync()) screen = Screen.Main
        else if (state == null) screen = Screen.Offline
    }

    /** Send queued offline changes in order, then load the fresh state. True if online. */
    suspend fun sync(): Boolean = syncLock.withLock {
        while (ops.isNotEmpty()) {
            val op = ops.first()
            val path = resolveTempIds(op.path)
            if (path == null) {          // e.g. the offline-created rule was rejected
                dropFirstOp()
                continue
            }
            try {
                val text = api.raw(op.method, path, op.body)
                runCatching { api.json.decodeFromString(StateResponse.serializer(), text) }.getOrNull()?.let {
                    putState(it.state)
                    if (it.capped) cappedToast(it.delta ?: 0.0)
                }
                dropFirstOp()
            } catch (e: ApiException) {
                when (e.status) {
                    0 -> { online = false; return false }
                    401 -> { logoutLocal(); return false }
                    else -> {                // rejected by the server (e.g. daily limit): drop it
                        dropFirstOp()
                        showToast(message(e), error = true)
                    }
                }
            }
        }
        try {
            putState(api.call<StateResponse>("GET", "/state").state)
            online = true
            imageRetry++
            true
        } catch (e: ApiException) {
            if (e.status == 0) online = false
            if (e.status == 401) logoutLocal()
            false
        }
    }

    private fun dropFirstOp() {
        ops.removeAt(0)
        saveOps()
    }

    private fun saveOps() {
        store.saveOps(ops)
        pending = ops.size
    }

    /** Rules created offline have negative ids until the server knows them (matched via client_id). */
    private fun resolveTempIds(path: String): String? {
        val m = TEMP_RULE.find(path) ?: return path
        val clientId = tempRules[m.groupValues[1].toInt()] ?: return null
        val real = state?.rules?.firstOrNull { it.clientId == clientId && it.id > 0 }?.id ?: return null
        return path.replaceRange(m.range, "/rules/$real")
    }

    /** Download page for the new version (the server's welcome page), or the Play Store's page of the app. */
    val updateUrl get() = if (BuildConfig.PLAY) "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
        else api.baseUrl.trimEnd('/') + (meta?.app?.updateUrl ?: "/")

    /** Re-fetch state (sends pending changes first; applies the daily decay after midnight). */
    fun refresh() = viewModelScope.launch {
        if (screen == Screen.Main && (online || hasNetwork())) sync()
        else state?.let { st -> currentDay(st).let { if (it !== st) putState(it) } }   // offline: new day?
    }

    /** The cached daily limit, moved to today if midnight passed while offline. */
    private fun currentDay(st: AppState): AppState {
        val l = st.limit.forToday()
        return if (l === st.limit) st else st.copy(limit = l)
    }

    fun setServer(url: String) {
        val clean = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        prefs.serverUrl = clean
        api.baseUrl = clean
    }

    fun setLanguage(l: Lang) {
        lang = l
        prefs.language = l.code
        api.language = l.code
    }

    private fun message(e: ApiException) = if (e.status == 0) strings.needsInternet else e.message ?: "Error"

    fun showToast(text: String, error: Boolean = false) {
        toast = Toast(text, error, System.nanoTime())
    }

    suspend fun auth(username: String, password: String, register: Boolean): Boolean {
        if (register && !EMAIL.matches(username.trim().lowercase())) {
            showToast(strings.invalidEmail, error = true)
            return false
        }
        // sign up with e-mail; log in with e-mail or username
        val body = buildJsonObject {
            put(if (register) "email" else "login", username)
            put("password", password)
            put("language", lang.code)
        }
        val ok = login(onLocked = { lockedLogin = username.trim() to it }) {
            api.call<TokenResponse>("POST", if (register) "/auth/register" else "/auth/login", body).token
        }
        if (ok && register) confirmSentTo = username.trim().lowercase()      // the code is on its way
        return ok
    }

    // ---------------------------------------------------------------- e-mail: confirm the address, new password (server/mail.py)

    /** Where the last confirm code went (this session): the confirm screen says so instead of offering to send one. */
    var confirmSentTo by mutableStateOf<String?>(null)

    /** A code to the account's address ([email]: first change it to that one); the seconds until another may be sent. */
    suspend fun sendConfirmCode(email: String? = null): Int? = try {
        val r = api.call<com.momentum.app.data.ConfirmSentResponse>("POST", "/auth/email/send", buildJsonObject { email?.let { put("email", it) } })
        putState(r.state)
        confirmSentTo = r.sentTo
        r.resendIn
    } catch (e: ApiException) {
        showToast(message(e), error = true)
        null
    }

    suspend fun confirmEmail(code: String): Boolean = try {
        putState(api.call<StateResponse>("POST", "/auth/email/confirm", buildJsonObject { put("code", code) }).state)
        confirmSentTo = null
        showToast(strings.emailConfirmed)
        true
    } catch (e: ApiException) {
        showToast(message(e), error = true)
        false
    }

    /** "Forgot password": a code to the account's confirmed address (the answer never tells whether it exists). */
    suspend fun forgotPassword(loginId: String): Boolean = try {
        api.raw("POST", "/auth/password/forgot", buildJsonObject { put("login", loginId) })
        true
    } catch (e: ApiException) {
        showToast(message(e), error = true)
        false
    }

    /** The new password with the mailed code; logged in right away (other devices are logged out). */
    suspend fun resetPassword(loginId: String, code: String, password: String): Boolean = login {
        api.call<TokenResponse>("POST", "/auth/password/reset", buildJsonObject {
            put("login", loginId); put("code", code); put("password", password)
        }).token.also { lockedLogin = null }
    }

    /** Sign in with Google: the ID token from the Credential Manager (data/GoogleSignIn.kt). */
    suspend fun googleAuth(idToken: String): Boolean = login {
        api.call<TokenResponse>("POST", "/auth/google", buildJsonObject {
            put("id_token", idToken)
            put("language", lang.code)
        }).token
    }

    /** A login answered "locked" (server/mail.py LOCK_AFTER): which one, and the server's explanation; the login
     *  screen opens "new password" (the code is mailed). */
    var lockedLogin by mutableStateOf<Pair<String, String>?>(null)

    private suspend fun login(onLocked: ((String) -> Unit)? = null, getToken: suspend () -> String): Boolean {
        return try {
            if (meta == null) meta = api.call<Meta>("GET", "/meta")
            val token = getToken()
            prefs.token = token
            api.token = token
            if (prefs.notifications) com.momentum.app.data.Notifier.schedule(getApplication())
            putState(api.call<StateResponse>("GET", "/state").state)
            online = true
            screen = Screen.Main
            true
        } catch (e: ApiException) {
            // locked: the last warning goes, the login screen explains it
            if (e.status == 423 && onLocked != null) { toast = null; onLocked(e.message.orEmpty()) } else showToast(message(e), error = true)
            false
        }
    }

    // ---------------------------------------------------------------- changes (offline-capable)

    private fun offlineCapable(method: String, path: String) = when {
        method == "POST" && path == "/rules" -> true
        (method == "PUT" || method == "DELETE") && RULE_PATH.matches(path) -> true
        method == "PUT" && (path == "/decay" || path == "/settings") -> true
        else -> false
    }

    /** Call returning a new state. Rule/decay/settings changes are queued when offline. */
    suspend fun save(method: String, path: String, body: JsonElement? = null, quiet: Boolean = false): Boolean {
        // new rules get a client id, so a retried offline create can't duplicate them
        val payload = if (method == "POST" && path == "/rules" && body is JsonObject && "client_id" !in body)
            JsonObject(body + ("client_id" to JsonPrimitive(UUID.randomUUID().toString()))) else body
        val offlineOk = offlineCapable(method, path)
        if (!online) {
            if (offlineOk) return queue(method, path, payload, quiet)
            showToast(strings.needsInternet, error = true)
            return false
        }
        return try {
            putState(api.call<StateResponse>(method, path, payload).state)
            if (!quiet && method != "DELETE") showToast(strings.saved)
            true
        } catch (e: ApiException) {
            if (e.status == 0) {
                online = false
                if (offlineOk) queue(method, path, payload, quiet) else { showToast(strings.needsInternet, true); false }
            } else {
                handle(e)
                false
            }
        }
    }

    private fun queue(method: String, path: String, body: JsonElement?, quiet: Boolean): Boolean {
        var payload = body
        if (path == "/settings" && body is JsonObject) {
            val allowed = JsonObject(body.filterKeys { it in OFFLINE_SETTINGS })
            if (allowed.size < body.size) showToast(strings.needsInternet, error = true)
            if (allowed.isEmpty()) return false
            payload = allowed
        }
        val st = state ?: return false
        putState(applyLocal(st, method, path, payload as? JsonObject))
        ops.add(PendingOp(UUID.randomUUID().toString(), method, path, payload))
        saveOps()
        if (!quiet && method != "DELETE" && path != "/settings") showToast(strings.saved)
        return true
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull

    private var tempCounter = 0
    private fun tempId(): Int = -((System.currentTimeMillis() / 1000 % 100_000_000).toInt() * 10 + (tempCounter++ % 10)) - 1

    /** Apply a queued change to the local state, the way the server will. */
    private fun applyLocal(st: AppState, method: String, path: String, b: JsonObject?): AppState {
        val ruleId = RULE_PATH.find(path)?.groupValues?.get(1)?.toInt()
        return when {
            method == "POST" && path == "/rules" && b != null -> {
                val id = tempId()
                val clientId = b.str("client_id") ?: UUID.randomUUID().toString()
                tempRules[id] = clientId
                store.saveTempRules(tempRules)
                val kind = b.str("kind") ?: "acc"
                val base = b.num("base") ?: 0.0
                st.copy(rules = st.rules + Rule(
                    id = id, kind = kind, name = b.str("name") ?: "", icon = b.str("icon") ?: "star",
                    mode = b.str("mode") ?: "constant", base = base, param = b.num("param") ?: 0.0,
                    minValue = b.num("min_value") ?: 0.0, resetDays = b.int("reset_days") ?: 0,
                    resetUnit = b.str("reset_unit") ?: "d",
                    value = applied(kind, base), public = b.bool("public") ?: false, clientId = clientId,
                ))
            }
            method == "PUT" && ruleId != null && b != null -> st.copy(rules = st.rules.map { r ->
                if (r.id != ruleId) r else {
                    val base = b.num("base") ?: r.base
                    r.copy(name = b.str("name") ?: r.name, icon = b.str("icon") ?: r.icon, mode = b.str("mode") ?: r.mode,
                        base = base, param = b.num("param") ?: r.param, minValue = b.num("min_value") ?: r.minValue,
                        resetDays = b.int("reset_days") ?: r.resetDays, public = b.bool("public") ?: r.public,
                        idleDays = b.int("idle_days") ?: r.idleDays,
                        resetUnit = b.str("reset_unit") ?: r.resetUnit, idleUnit = b.str("idle_unit") ?: r.idleUnit,
                        idleRuleId = if ("idle_rule_id" in b) b.int("idle_rule_id") else r.idleRuleId,
                        value = applied(r.kind, base))
                }
            })
            method == "DELETE" && ruleId != null -> st.copy(rules = st.rules.filter { it.id != ruleId })
            path == "/decay" && b != null -> {
                val d = st.decay
                val mode = b.str("catalyst_mode") ?: d.catalystMode
                val base = b.num("base") ?: d.base
                val threshold = b.num("threshold") ?: d.threshold
                val a = b.num("catalyst_a") ?: d.catalystA
                val bb = b.num("catalyst_b") ?: d.catalystB
                val streak = if (d.todayGain < threshold) d.streak + 1 else 0
                st.copy(decay = d.copy(base = base, threshold = threshold, catalystMode = mode, catalystA = a, catalystB = bb,
                    tonight = applied("dec", base + catalyst(mode, a, bb, streak))))
            }
            path == "/settings" && b != null -> st.copy(settings = st.settings.copy(
                timezone = b.str("timezone") ?: st.settings.timezone,
                haptics = b.bool("haptics") ?: st.settings.haptics,
                showHistory = b.bool("show_history") ?: st.settings.showHistory,
            ))
            else -> st
        }
    }

    /** The vehicle for a momentum, on the server's milestone ladder (sent with the state). */
    private fun vehicleFor(momentum: Double, current: VehicleInfo): VehicleInfo {
        val ladder = current.milestones.ifEmpty { return current }
        val idx = max(0, ladder.indexOfLast { momentum >= it })
        return current.copy(index = idx, reachedAt = ladder[idx], nextAt = ladder.getOrNull(idx + 1))
    }

    // ---------------------------------------------------------------- taps, undo

    fun trigger(rule: Rule) = viewModelScope.launch {
        if (!rule.tappable) return@launch          // only its automatic tap counts it (the card shows that)
        val opId = UUID.randomUUID().toString()
        val at = OffsetDateTime.now().toString()
        val body = buildJsonObject { put("at", at); put("op_id", opId) }
        // show the result right away (same rules as the server), the server confirms in the background
        val before = state ?: return@launch
        val tap = applyTap(rule, at) ?: return@launch
        val seq = ++tapSeq
        if (online && rule.id > 0) {
            try {
                val res = api.call<StateResponse>("POST", "/rules/${rule.id}/trigger", body)
                if (seq == tapSeq) putState(res.state)      // a newer tap's response brings the newer state
                if (res.milestone) milestone = true
                if (res.hyperspace) hyperspace = true
                if (res.capped && res.state.settings.dailyLimit) cappedToast(res.delta ?: 0.0)
                return@launch
            } catch (e: ApiException) {
                if (e.status != 0) {           // rejected (e.g. daily limit): take the optimistic tap back
                    putState(before)
                    milestone = false
                    hyperspace = false
                    handle(e)
                    return@launch
                }
                online = false
            }
        }
        // offline (or a rule only known locally): keep the local result and queue the tap
        if (tap.capped) cappedToast(tap.amount)
        ops.add(PendingOp(opId, "POST", "/rules/${rule.id}/trigger", body, tap.eventId))
        saveOps()
        if (online) sync()       // rule was only known locally: sync creates it first
    }

    private class Tap(val eventId: Int, val amount: Double, val capped: Boolean)
    private var tapSeq = 0

    /** Apply a tap locally with the server's rounding/limit rules (instant feedback, offline mode). */
    /** Tapping it keeps an automatic tap away (= service.guards_idle). */
    private fun guardsIdle(rule: Rule) = rule.idleDays > 0 && rule.idleRuleId != null && !rule.idlePaused

    /** Capped by the daily limit: what was credited (+0: only the automatic rule started over). */
    private fun cappedToast(amount: Double) =
        showToast(if (amount < 1) strings.limitZeroTap else strings.limitPartial(fmt(amount)), error = true)

    private fun applyTap(rule: Rule, at: String): Tap? {
        val st = currentDay(state ?: return null)
        val full = applied(rule.kind, rule.value)
        var amount = full
        var capped = false
        val ranked = st.settings.dailyLimit
        if (rule.isAcc && ranked) {
            val left = floor(st.limit.remaining)
            if (left <= 0) {
                // limit used up: still a tap with +0 when it keeps the rule's automatic decelerator away
                if (!guardsIdle(rule)) {
                    showToast(strings.limitReached, error = true)
                    return null
                }
                amount = 0.0
                capped = true
            } else if (amount > left) {
                amount = left
                capped = true
            }
        }
        // with the limit: ranked momentum (capped) is the playing value; without: the free score
        val sign = if (rule.isAcc) 1 else -1
        val newFree = max(0.0, st.freeScore + sign * full)
        val newMomentum = if (ranked) max(0.0, st.momentum + sign * amount) else newFree
        val actual = newMomentum - st.momentum
        // hyperspace is exclusive to the competition
        if (ranked && st.momentum < st.vehicle.hyperspaceAt && newMomentum >= st.vehicle.hyperspaceAt) hyperspace = true
        val eventId = tempId()
        val vehicle = vehicleFor(newMomentum, st.vehicle)
        var events = listOf(Event(eventId, rule.kind, rule.name, rule.icon, actual, at, undoable = true)) + st.events
        if (vehicle.index > st.vehicle.index) {
            val key = meta?.vehicles?.getOrNull(vehicle.index)?.key.orEmpty()
            events = listOf(Event(tempId(), "milestone", key, "", 0.0, at)) + events
            milestone = true
        }
        val next = nextValue(rule.mode, rule.value, rule.param, rule.minValue)
        putState(st.copy(
            momentum = newMomentum,
            rankedMomentum = if (ranked) newMomentum else st.rankedMomentum,
            freeScore = newFree,
            vehicle = vehicle,
            rules = st.rules.map { if (it.id == rule.id) it.copy(value = applied(rule.kind, if (rule.mode == "constant") rule.base else next)) else it },
            events = events,
            // the limit counts accelerators minus decelerators (never below 0), like the server
            limit = if (ranked) st.limit.counted(st.limit.gained + actual) else st.limit,
            decay = if (rule.isAcc) st.decay.copy(todayGain = st.decay.todayGain + actual) else st.decay,
        ))
        pop = Pop(rule.id, (if (actual >= 0) "+" else "−") + fmt(abs(actual)), System.nanoTime())
        return Tap(eventId, amount, capped)
    }

    /** Undo an accelerator/decelerator from the history. */
    fun undo(event: Event) = viewModelScope.launch {
        if (event.id < 0) {          // still in the queue: just drop it
            ops.removeAll { it.eventId == event.id }
            saveOps()
            revertLocal(event)
            return@launch
        }
        if (online) {
            try {
                putState(api.call<StateResponse>("POST", "/events/${event.id}/undo").state)
                return@launch
            } catch (e: ApiException) {
                if (e.status != 0) {
                    handle(e)
                    return@launch
                }
                online = false
            }
        }
        revertLocal(event)
        ops.add(PendingOp(UUID.randomUUID().toString(), "POST", "/events/${event.id}/undo"))
        saveOps()
    }

    private fun revertLocal(e: Event) {
        val st = state ?: return
        val momentum = max(0.0, st.momentum - e.delta)
        val vehicle = vehicleFor(momentum, st.vehicle)
        val reached = meta?.vehicles?.take(vehicle.index + 1)?.map { it.key }?.toSet().orEmpty()
        val acc = e.kind == "acc"
        putState(st.copy(
            momentum = momentum,
            vehicle = vehicle,
            events = st.events.filter { it.id != e.id && (it.kind != "milestone" || it.label in reached) },
            limit = st.limit.counted(st.limit.gained - e.delta),
            decay = if (acc) st.decay.copy(todayGain = st.decay.todayGain - e.delta) else st.decay,
        ))
    }

    // ---------------------------------------------------------------- online-only calls

    /** Call without state in the response. */
    suspend fun call(method: String, path: String, body: JsonElement? = null): Boolean {
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return false
        }
        return try {
            api.raw(method, path, body)
            true
        } catch (e: ApiException) {
            handle(e)
            false
        }
    }

    suspend fun uploadAvatar(jpeg: ByteArray): Boolean {
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return false
        }
        return try {
            putState(api.json.decodeFromString<StateResponse>(api.upload("/settings/avatar", jpeg, "image/jpeg")).state)
            true
        } catch (e: ApiException) {
            handle(e)
            false
        }
    }

    /** Server-relative media paths ("/media/...", "/avatars/...") -> absolute URL for Coil/ExoPlayer. */
    fun abs(url: String?): String? = url?.let {
        // decrypted chat media are local files (file://)
        if (it.startsWith("http") || it.startsWith("file:") || it.startsWith("content:")) it else api.baseUrl.trimEnd('/') + it
    }

    /**
     * Typed request for the social parts. GET responses are cached and shown when offline
     * (chats, messages, feed, profiles); other requests need the connection.
     */
    suspend inline fun <reified T> req(method: String, path: String, body: JsonElement? = null): T? {
        if (method == "GET") {
            return try {
                val text = api.raw(method, path, body)
                cacheWrite("get:$path", text)
                online = true
                api.json.decodeFromString<T>(text)
            } catch (e: ApiException) {
                if (e.status == 0) {
                    online = false
                    cacheRead("get:$path")?.let { runCatching { api.json.decodeFromString<T>(it) }.getOrNull() }
                } else {
                    onError(e)
                    null
                }
            }
        }
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return null
        }
        return try {
            api.call<T>(method, path, body)
        } catch (e: ApiException) {
            if (e.status == 0) online = false
            onError(e)
            null
        }
    }

    private var orderJob: kotlinx.coroutines.Job? = null

    /** Moves a rule one place up (-1) or down (+1) among the rules of its kind; shown at once, saved shortly after. */
    fun moveRule(id: Int, step: Int) {
        val s = state ?: return
        val r = s.rules.firstOrNull { it.id == id } ?: return
        val same = s.rules.filter { it.kind == r.kind }.toMutableList()
        val i = same.indexOf(r)
        val j = i + step
        if (j !in same.indices) return
        same[i] = same[j].also { same[j] = r }
        val rules = same + s.rules.filter { it.kind != r.kind }
        state = s.copy(rules = rules)
        // several taps in a row are saved together
        orderJob?.cancel()
        orderJob = viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            save("PUT", "/rules/order", buildJsonObject {
                put("ids", kotlinx.serialization.json.JsonArray(rules.filter { it.id > 0 }.map { JsonPrimitive(it.id) }))
            }, quiet = true)
        }
    }

    /** A bulk action (POST with {"ids": ...}); the new state is applied, the result tells what was skipped. */
    suspend fun bulk(path: String, body: JsonElement): com.momentum.app.data.BulkResult? {
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return null
        }
        return try {
            api.call<com.momentum.app.data.BulkResult>("POST", path, body).also { putState(it.state) }
        } catch (e: ApiException) {
            handle(e)
            null
        }
    }

    /** Upload bytes already prepared (e.g. a cropped picture). */
    suspend fun uploadBytes(bytes: ByteArray, type: String): MediaItem? {
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return null
        }
        return try {
            api.json.decodeFromString<MediaResponse>(api.upload("/media", bytes, type)).media
        } catch (e: ApiException) {
            if (e.status == 0) showToast(strings.uploadFailed, error = true) else onError(e)
            null
        }
    }

    /** A part of an audio file (a post's sound): from [startS], at most [seconds]. */
    suspend fun uploadAudioPart(uri: Uri, startS: Double, seconds: Double): MediaItem? {
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return null
        }
        val ctx = getApplication<Application>()
        val type = ctx.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
        val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return null
        return try {
            api.json.decodeFromString<MediaResponse>(api.upload("/media?start=$startS&seconds=$seconds", bytes, type)).media
        } catch (e: ApiException) {
            if (e.status == 0) showToast(strings.uploadFailed, error = true) else onError(e)
            null
        }
    }

    /** [progress]: where the upload is (for a progress bar): a big video shrunk here, the bytes sent, the server's turn. */
    suspend fun uploadMedia(uri: Uri, mime: String? = null, progress: (com.momentum.app.data.UploadProgress) -> Unit = {}): MediaItem? {
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return null
        }
        val ctx = getApplication<Application>()
        // file:// (the in-app camera) has no type in the content resolver: take it from the extension
        var type = mime ?: ctx.contentResolver.getType(uri)
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
                ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            // photos (camera pictures are several MB) are scaled down first: fast to send, the server
            // shrinks them anyway
            val shrunk = if (type.startsWith("image/") && type != "image/gif") {
                com.momentum.app.data.shrinkImage(ctx, uri)?.also { type = "image/jpeg" }
            } else null
            shrunk ?: ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }?.let { raw ->
            // big videos (straight from the camera, gallery) are re-encoded to 720p first: a fraction of the size
            if (type.startsWith("video/") && raw.size > 8_000_000) {
                progress(com.momentum.app.data.UploadProgress.Compressing(0f))
                com.momentum.app.data.compressVideo(ctx, uri) { progress(com.momentum.app.data.UploadProgress.Compressing(it)) }?.let { f ->
                    withContext(Dispatchers.IO) { f.readBytes().also { f.delete() } }.also { type = "video/mp4" }
                } ?: raw
            } else raw
        } ?: return null
        return try {
            api.json.decodeFromString<MediaResponse>(api.upload("/media", bytes, type) { done ->
                progress(if (done < 1f) com.momentum.app.data.UploadProgress.Sending(done) else com.momentum.app.data.UploadProgress.Processing)
            }).media
        } catch (e: ApiException) {
            // a failed upload isn't a lost connection: don't switch the app to offline
            if (e.status == 0) showToast(strings.uploadFailed, error = true) else onError(e)
            null
        }
    }

    /** An encrypted photo / voice message (or its small copy) as it is: [path] under /media. */
    suspend fun uploadEncrypted(path: String, bytes: ByteArray): MediaItem? {
        if (!online) {
            showToast(strings.needsInternet, error = true)
            return null
        }
        return try {
            api.json.decodeFromString<MediaResponse>(api.upload("/media$path", bytes, "application/octet-stream")).media
        } catch (e: ApiException) {
            if (e.status == 0) showToast(strings.uploadFailed, error = true) else onError(e)
            null
        }
    }

    fun logout(callApi: Boolean = true) = viewModelScope.launch {
        if (callApi && online) runCatching { api.raw("POST", "/auth/logout") }
        logoutLocal()
    }

    private fun logoutLocal() {
        com.momentum.app.data.Calls.detach()
        com.momentum.app.data.Notifier.cancel(getApplication())
        com.momentum.app.data.E2EStore.stop()
        e2eStartedFor = null
        prefs.token = null
        api.token = null
        state = null
        ops.clear()
        tempRules.clear()
        store.clear()          // cached chats/feed belong to this account
        pending = 0
        screen = Screen.Login
    }

    fun onError(e: ApiException) = handle(e)

    /** Opened from a call's notification: its screen (answer: at once, if the microphone may be used). */
    fun openCall(id: Int, answer: Boolean) {
        val ctx = getApplication<Application>()
        fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        com.momentum.app.data.Calls.openFromNotification(id, answer && granted(android.Manifest.permission.RECORD_AUDIO),
            hasCamera = { granted(android.Manifest.permission.CAMERA) }, onEnded = { conv -> openRequest = conv })
    }

    private fun handle(e: ApiException) {
        when (e.status) {
            401 -> {
                showToast(strings.sessionExpired, error = true)
                logoutLocal()
            }
            0 -> {
                online = false
                showToast(strings.needsInternet, error = true)
            }
            else -> showToast(message(e), error = true)
        }
    }
}
