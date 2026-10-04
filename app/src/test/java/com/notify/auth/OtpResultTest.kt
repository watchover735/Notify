package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class OtpResultTest {

    @Test
    fun parseVerifyResponse_success200_returnsSuccessSession() {
        val json = """
            {
                "access_token": "mock_access_token_123",
                "refresh_token": "mock_refresh_token_456",
                "expires_in": 3600,
                "user": {
                    "id": "user-uuid-789",
                    "email": "listener@example.com"
                }
            }
        """.trimIndent()

        val result = parseVerifyResponse(200, json)
        assertTrue(result is VerifyOtpResult.Success)
        val success = result as VerifyOtpResult.Success
        assertEquals("mock_access_token_123", success.session.accessToken)
        assertEquals("mock_refresh_token_456", success.session.refreshToken)
        assertEquals("user-uuid-789", success.session.userId)
        assertEquals("listener@example.com", success.session.email)
    }

    @Test
    fun parseVerifyResponse_rateLimited429_returnsRateLimited() {
        val result = parseVerifyResponse(429, """{"message": "Too many requests"}""")
        assertEquals(VerifyOtpResult.RateLimited, result)
    }

    @Test
    fun parseVerifyResponse_server5xx_returnsServer5xx() {
        val res500 = parseVerifyResponse(500, """{"message": "Internal server error"}""")
        val res503 = parseVerifyResponse(503, """{"message": "Service unavailable"}""")

        assertEquals(VerifyOtpResult.Server5xx(500), res500)
        assertEquals(VerifyOtpResult.Server5xx(503), res503)
    }

    @Test
    fun parseVerifyResponse_otpExpired400Or403_returnsOtpInvalidOrExpired() {
        val res400 = parseVerifyResponse(
            400,
            """{"error_code": "otp_expired", "msg": "Token has expired or is invalid"}"""
        )
        val res403 = parseVerifyResponse(
            403,
            """{"error_code": "otp_expired", "msg": "Token has expired or is invalid"}"""
        )

        assertEquals(VerifyOtpResult.OtpInvalidOrExpired, res400)
        assertEquals(VerifyOtpResult.OtpInvalidOrExpired, res403)
    }

    @Test
    fun parseVerifyResponse_unknown4xx_returnsOtpInvalidOrExpired() {
        val resBadCode = parseVerifyResponse(
            400,
            """{"error_code": "bad_code", "msg": "Invalid token"}"""
        )
        val resGeneric400 = parseVerifyResponse(
            400,
            """{"error": "invalid_grant"}"""
        )

        assertEquals(VerifyOtpResult.OtpInvalidOrExpired, resBadCode)
        assertEquals(VerifyOtpResult.OtpInvalidOrExpired, resGeneric400)
    }

    @Test
    fun parseVerifyResponse_serverTypeError_returnsUnknownWithTypeError() {
        val result = parseVerifyResponse(
            400,
            """{"error_code": "validation_failed", "msg": "Invalid type for verification"}"""
        )
        assertTrue(result is VerifyOtpResult.Unknown)
        val unknown = result as VerifyOtpResult.Unknown
        assertTrue(unknown.message.contains("type", ignoreCase = true))
    }

    @Test
    fun parseResendResponse_success200_returnsSuccess() {
        val result = parseResendResponse(200, "{}")
        assertEquals(ResendOtpResult.Success, result)
    }

    @Test
    fun parseResendResponse_rateLimited429OrErrorCode_returnsRateLimited() {
        val res429 = parseResendResponse(429, """{"message": "Too many requests"}""")
        val resCode = parseResendResponse(
            400,
            """{"error_code": "over_email_send_rate_limit", "msg": "For security purposes, you can only request this once every 60 seconds"}"""
        )

        assertEquals(ResendOtpResult.RateLimited, res429)
        assertEquals(ResendOtpResult.RateLimited, resCode)
    }

    @Test
    fun parseResendResponse_server5xx_returnsServer5xx() {
        val result = parseResendResponse(500, """{"message": "DB error"}""")
        assertEquals(ResendOtpResult.Server5xx(500), result)
    }

    @Test
    fun parseResendResponse_unknown4xx_returnsUnknownWithMessage() {
        val result = parseResendResponse(
            400,
            """{"msg": "User already confirmed"}"""
        )
        assertTrue(result is ResendOtpResult.Unknown)
        assertEquals("User already confirmed", (result as ResendOtpResult.Unknown).message)
    }

    @Test
    fun mapOtpException_mapsCorrectlyForVerifyAndResend() {
        val timeoutEx = SocketTimeoutException("Read timed out")
        val ioEx = IOException("Failed to connect")
        val stateEx = IllegalStateException("Something broke")

        assertEquals(VerifyOtpResult.Timeout, mapOtpExceptionToVerifyResult(timeoutEx))
        assertEquals(VerifyOtpResult.Offline, mapOtpExceptionToVerifyResult(ioEx))
        assertTrue(mapOtpExceptionToVerifyResult(stateEx) is VerifyOtpResult.Unknown)

        assertEquals(ResendOtpResult.Timeout, mapOtpExceptionToResendResult(timeoutEx))
        assertEquals(ResendOtpResult.Offline, mapOtpExceptionToResendResult(ioEx))
        assertTrue(mapOtpExceptionToResendResult(stateEx) is ResendOtpResult.Unknown)
    }

    @Test
    fun maskEmail_masksSensitivePartsCorrectly() {
        assertEquals("r***l@gmail.com", maskEmail("rahul@gmail.com"))
        assertEquals("j******e@company.org", maskEmail("john.doe@company.org"))
        assertEquals("a*@domain.com", maskEmail("ab@domain.com"))
        assertEquals("*@domain.com", maskEmail("a@domain.com"))
        assertEquals("***@***", maskEmail("invalid"))
        assertEquals("***@***", maskEmail(""))
    }
}
