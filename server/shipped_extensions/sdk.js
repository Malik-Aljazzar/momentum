// The bridge an extension's page talks to Momentum through (API 1; server/extensions.py puts it in front of the
// page). Hosts: web/src/extensions.ts (a sandboxed iframe: postMessage) and android .../data/Extensions.kt (a
// WebView: window.MomentumAndroid). Messages are JSON strings {momentum: 1, id, method, params} -> {momentum: 1, id,
// result | error}; events from the app {momentum: 1, event, data}. The page has no network of its own.
(function () {
  "use strict"
  var pending = {}
  var seq = 0
  var listeners = {}
  var android = window.MomentumAndroid

  function send(msg) {
    var text = JSON.stringify(msg)
    if (android) android.post(text)
    else window.parent.postMessage(text, "*")
  }

  function receive(msg) {
    if (typeof msg === "string") {
      try { msg = JSON.parse(msg) } catch (e) { return }
    }
    if (!msg || msg.momentum !== 1) return
    if (msg.event) {
      (listeners[msg.event] || []).forEach(function (fn) {
        try { fn(msg.data) } catch (e) { console.error(e) }
      })
      return
    }
    var p = pending[msg.id]
    if (!p) return
    delete pending[msg.id]
    if (msg.error) {
      var err = new Error(msg.error.message || "error")
      err.status = msg.error.status || 0
      p.reject(err)
    } else p.resolve(msg.result)
  }

  // Android answers through this; the iframe gets messages from its parent only
  window.__momentumReceive = receive
  window.addEventListener("message", function (e) { if (e.source === window.parent) receive(e.data) })

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      var id = ++seq
      pending[id] = { resolve: resolve, reject: reject }
      send({ momentum: 1, id: id, method: method, params: params || {} })
    })
  }

  var api = {
    api: 1,
    /** {api, lang: "de" | "en", user: {username, display_name}, extension: {id, version}, tab: the manifest's key of
     *  the tab this page is shown as (null in apps from before tab layouts)} */
    ready: call("context"),
    data: Object.freeze({
      /** the stored value, or null */
      get: function (collection, key) { return call("data.get", { collection: collection, key: key }) },
      /** stores value (checked against the collection's schema); resolves {key, value, updated_at} */
      put: function (collection, key, value) { return call("data.put", { collection: collection, key: key, value: value }) },
      delete: function (collection, key) { return call("data.delete", { collection: collection, key: key }) },
      /** options: {offset, limit (<= 100), order: "key" | "-key" | "updated" | "-updated"}; resolves {docs, total} */
      list: function (collection, options) {
        var o = options || {}
        return call("data.list", { collection: collection, offset: o.offset || 0, limit: o.limit || 30, order: o.order || "key" })
      },
    }),
    /** the player's rules - only with the manifest's permission "rules" (else the call fails with status 403) */
    rules: Object.freeze({
      /** the tappable ones: [{id, name, icon (a sprite name or one emoji), kind: "acc" | "dec", value}] */
      list: function () { return call("rules.list") },
      /** taps rule `id` once, like the player would (daily limit, milestones and all); resolves {event, delta, capped}
       *  - keep `event` to take the tap back */
      tap: function (id) { return call("rules.tap", { rule: id }) },
      /** takes a tap back (an event from tap) */
      undo: function (event) { return call("rules.undo", { event: event }) },
    }),
    /** a short message at the bottom of the app */
    toast: function (text) { return call("toast", { text: String(text) }) },
    /** "show" (the tab came into view) / "hide" */
    on: function (event, fn) { (listeners[event] = listeners[event] || []).push(fn) },
  }
  Object.defineProperty(window, "momentum", { value: Object.freeze(api), writable: false, configurable: false })
})()
