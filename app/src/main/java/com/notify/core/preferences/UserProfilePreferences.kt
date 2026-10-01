package com.notify.core.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages user profile preferences such as display name.
 * Backed by SharedPreferences with a reactive StateFlow for instant UI updates.
 */
class UserProfilePreferences private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "notify_user_profile"
        private const val KEY_DISPLAY_NAME = "display_name"

        @Volatile
        private var instance: UserProfilePreferences? = null

        fun getInstance(context: Context): UserProfilePreferences {
            return instance ?: synchronized(this) {
                instance ?: UserProfilePreferences(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _displayNameFlow = MutableStateFlow(getDisplayName())
    val displayNameFlow: StateFlow<String> = _displayNameFlow.asStateFlow()

    fun getDisplayName(): String {
        return prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
    }

    fun updateDisplayName(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
        _displayNameFlow.value = trimmed
    }
}
