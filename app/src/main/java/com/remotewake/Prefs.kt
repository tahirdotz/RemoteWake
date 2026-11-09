package com.remotewake

import android.content.Context

enum class Role { NONE, TARGET, REMOTE }

/** Settings kept on this phone. TARGET = Phone A (gets woken), REMOTE = Phone B (does the waking). */
class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote_wake", Context.MODE_PRIVATE)

    var role: Role
        get() = runCatching { Role.valueOf(prefs.getString(KEY_ROLE, null)!!) }.getOrDefault(Role.NONE)
        set(value) = prefs.edit().putString(KEY_ROLE, value.name).apply()

    /** Phone A's own pairing code. */
    var targetCode: String?
        get() = prefs.getString(KEY_TARGET_CODE, null)
        set(value) = prefs.edit().putString(KEY_TARGET_CODE, value).apply()

    /** On Phone B: the code of the Phone A it controls. */
    var remoteCode: String?
        get() = prefs.getString(KEY_REMOTE_CODE, null)
        set(value) = prefs.edit().putString(KEY_REMOTE_CODE, value).apply()

    var server: String
        get() = prefs.getString(KEY_SERVER, null) ?: Relay.DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    /** On Phone A: whether the owner wants it listening (also after a reboot). */
    var listening: Boolean
        get() = prefs.getBoolean(KEY_LISTENING, false)
        set(value) = prefs.edit().putBoolean(KEY_LISTENING, value).apply()

    /** The last command Phone A handled, so it is not acted on twice after a reconnect or restart. */
    var lastEventId: String?
        get() = prefs.getString(KEY_LAST_EVENT_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_EVENT_ID, value).apply()

    var lastEventTime: Long
        get() = prefs.getLong(KEY_LAST_EVENT_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_EVENT_TIME, value).apply()

    /** When Phone A was last woken (milliseconds), for showing on its screen. */
    var lastWokenAt: Long
        get() = prefs.getLong(KEY_LAST_WOKEN, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_WOKEN, value).apply()

    /** The self-hosted WebSocket relay for screen sharing (e.g. "ws://192.168.1.5:8765"). Null until set. */
    var controlRelay: String?
        get() = prefs.getString(KEY_CONTROL_RELAY, null)
        set(value) = prefs.edit().putString(KEY_CONTROL_RELAY, value).apply()

    private companion object {
        const val KEY_ROLE = "role"
        const val KEY_TARGET_CODE = "target_code"
        const val KEY_REMOTE_CODE = "remote_code"
        const val KEY_SERVER = "server"
        const val KEY_LISTENING = "listening"
        const val KEY_LAST_EVENT_ID = "last_event_id"
        const val KEY_LAST_EVENT_TIME = "last_event_time"
        const val KEY_LAST_WOKEN = "last_woken"
        const val KEY_CONTROL_RELAY = "control_relay"
    }
}
