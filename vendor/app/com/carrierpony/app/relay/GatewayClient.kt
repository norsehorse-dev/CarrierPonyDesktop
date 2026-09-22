// GatewayClient.kt
// CarrierPony Android
//
// Minimal client for the CarrierPony push gateway. Identity-agnostic: it holds
// only a push token and returns an opaque wake token, with no PGP auth.

package com.carrierpony.app.relay

import com.carrierpony.app.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GatewayClient {

    suspend fun registerPush(deviceID: String, token: String, platform: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_id", deviceID)
            .put("push_token", token)
            .put("platform", platform)
        val conn = URL(AppConfig.gatewayBaseURL.trimEnd('/') + "/v1/register-push").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw RuntimeException("gateway HTTP ${conn.responseCode}")
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val wake = JSONObject(resp).optString("wake_token", "")
            if (wake.isEmpty()) throw RuntimeException("no wake_token")
            wake
        } finally {
            conn.disconnect()
        }
    }

    suspend fun deregister(wakeToken: String) {
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(AppConfig.gatewayBaseURL.trimEnd('/') + "/v1/deregister").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.outputStream.use { it.write(JSONObject().put("wake_token", wakeToken).toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                // best-effort
            }
        }
    }
}
