package com.example.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for UNEMI SGA Estudiante JWT & Auth REST API endpoints.
 * Base SPA: https://sgaestudiante.unemi.edu.ec
 * JWT API: https://sga.unemi.edu.ec/api/1.0/jwt
 */
object SgaApiClient {
    private const val TAG = "SgaApiClient"
    private const val LOGIN_URL = "https://sgaestudiante.unemi.edu.ec/api/auth/login.json"
    private const val BASE_JWT_URL = "https://sga.unemi.edu.ec/api/1.0/jwt"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    data class SgaAuthResult(
        val accessToken: String,
        val refreshToken: String,
        val decodedPayload: JSONObject?
    )

    /**
     * Authenticates with SGA Estudiante Django login endpoint.
     */
    suspend fun login(username: String, password: String): SgaAuthResult = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().apply {
            put("username", username.trim())
            put("password", password.trim())
            put("clientNavegador", "Chrome 126")
            put("clientOS", "Android")
            put("clientScreensize", "1080x2400")
            put("otp_verified_token", JSONObject.NULL)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(LOGIN_URL)
            .post(jsonBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: throw IOException("Empty SGA login response")
            if (!response.isSuccessful) {
                throw IOException("SGA Login failed (${response.code}): $bodyStr")
            }
            val json = JSONObject(bodyStr)
            val access = json.optString("access", "")
            val refresh = json.optString("refresh", "")

            if (access.isEmpty()) {
                throw IOException("No access token returned in SGA login response")
            }

            val decodedPayload = decodeJwtPayload(access)
            return@withContext SgaAuthResult(
                accessToken = access,
                refreshToken = refresh,
                decodedPayload = decodedPayload
            )
        }
    }

    /**
     * Refreshes JWT tokens using the refresh token.
     */
    suspend fun refreshToken(refreshToken: String): SgaAuthResult = withContext(Dispatchers.IO) {
        val url = "$BASE_JWT_URL/token/refresh"
        val jsonBody = JSONObject().apply {
            put("refresh", refreshToken)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: throw IOException("Empty refresh response")
            if (!response.isSuccessful) {
                throw IOException("SGA Token Refresh failed (${response.code}): $bodyStr")
            }
            val json = JSONObject(bodyStr)
            val access = json.optString("access", "")
            val newRefresh = json.optString("refresh", refreshToken)

            val decodedPayload = decodeJwtPayload(access)
            return@withContext SgaAuthResult(
                accessToken = access,
                refreshToken = newRefresh,
                decodedPayload = decodedPayload
            )
        }
    }

    /**
     * Executes a GET or POST request to any SGA endpoint.
     */
    suspend fun callSgaEndpoint(
        endpoint: String,
        method: String = "GET",
        accessToken: String,
        bodyJson: String = ""
    ): String = withContext(Dispatchers.IO) {
        val sanitizedEndpoint = endpoint.trim().removePrefix("/")
        val fullUrl = if (sanitizedEndpoint.startsWith("http://") || sanitizedEndpoint.startsWith("https://")) {
            sanitizedEndpoint
        } else {
            "$BASE_JWT_URL/$sanitizedEndpoint"
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val builder = Request.Builder()
            .url(fullUrl)
            .addHeader("Authorization", "Bearer $accessToken")

        val request = when (method.uppercase()) {
            "POST" -> {
                val reqBody = if (bodyJson.isNotEmpty()) bodyJson else "{}"
                builder.post(reqBody.toRequestBody(mediaType)).build()
            }
            else -> builder.get().build()
        }

        client.newCall(request).execute().use { response ->
            val code = response.code
            val bodyStr = response.body?.string() ?: ""
            if (code == 401 || code == 403) {
                throw IOException("SGA Authorization error ($code). Token may be expired.")
            }
            return@withContext bodyStr
        }
    }

    /**
     * Helper to decode JWT payload without external libraries.
     */
    fun decodeJwtPayload(jwtToken: String): JSONObject? {
        return try {
            val parts = jwtToken.split(".")
            if (parts.size >= 2) {
                val payloadBase64 = parts[1]
                val decodedBytes = try {
                    android.util.Base64.decode(payloadBase64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                } catch (e: Exception) {
                    android.util.Base64.decode(payloadBase64, android.util.Base64.DEFAULT)
                }
                val decodedString = String(decodedBytes, Charsets.UTF_8)
                JSONObject(decodedString)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JWT payload", e)
            null
        }
    }
}
