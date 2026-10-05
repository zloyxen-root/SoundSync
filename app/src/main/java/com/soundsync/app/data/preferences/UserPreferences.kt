package com.soundsync.app.data.preferences

import android.content.Context
import android.content.SharedPreferences

class UserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("soundsync_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PROFILE_INPUT = "profile_input"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_AVATAR_URL = "avatar_url"
        private const val KEY_OAUTH_TOKEN = "oauth_token"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_AUTO_DELETE = "auto_delete_unliked"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
    }

    var profileInput: String
        get() = prefs.getString(KEY_PROFILE_INPUT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROFILE_INPUT, value).apply()

    var userId: Long
        get() = prefs.getLong(KEY_USER_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_USER_ID, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var avatarUrl: String?
        get() = prefs.getString(KEY_AVATAR_URL, null)
        set(value) = prefs.edit().putString(KEY_AVATAR_URL, value).apply()

    var oauthToken: String
        get() = prefs.getString(KEY_OAUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OAUTH_TOKEN, value).apply()

    var customClientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    var autoDeleteUnliked: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DELETE, value).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, value).apply()

    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    fun clearUser() {
        prefs.edit()
            .remove(KEY_PROFILE_INPUT)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_AVATAR_URL)
            .remove(KEY_OAUTH_TOKEN)
            .apply()
    }
}
