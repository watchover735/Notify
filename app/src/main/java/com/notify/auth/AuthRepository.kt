package com.notify.auth

/**
 * Interface defining authentication credential retrieval for secure network operations.
 */
interface AuthRepository {
    /**
     * Returns the currently active JWT access token, or null if no valid user session exists.
     */
    fun getCurrentAccessToken(): String?
}
