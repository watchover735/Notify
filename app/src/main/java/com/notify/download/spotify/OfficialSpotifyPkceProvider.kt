package com.notify.download.spotify

/**
 * Contract and configuration for Official Spotify Authorization Code Flow with PKCE.
 * Does not require, embed, or store any client secret in the application binary.
 */
interface OfficialSpotifyPkceProvider : SpotifyMetadataProvider {
    /**
     * Checks if a valid user-authenticated OAuth token is currently present.
     */
    fun isAuthorized(): Boolean

    /**
     * Generates PKCE challenge and returns the Spotify OAuth authorization URL.
     */
    fun createAuthorizationUri(clientId: String, redirectUri: String): String

    /**
     * Exchanges auth code for access token via PKCE code_verifier.
     */
    suspend fun handleAuthorizationCode(code: String, redirectUri: String): Result<Unit>

    /**
     * Clears stored access token and credentials.
     */
    fun logout()
}
