package com.emuhub.app.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends logs to the DevGiglio LGTM stack (Loki via Grafana).
 * Non-blocking: logs are dispatched on a background coroutine.
 */
object GrafanaLogger {

    private const val TAG = "GrafanaLogger"
    private const val DEFAULT_ENDPOINT = "https://logs.devgiglio.uk/api/v1/logs"
    private const val DEFAULT_TOKEN = "gpbox2026"

    private var endpoint: String = DEFAULT_ENDPOINT
    private var authToken: String = DEFAULT_TOKEN
    private var deviceId: String = "unknown"
    private var enabled: Boolean = true
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context, customEndpoint: String? = null, customToken: String? = null) {
        endpoint = customEndpoint ?: DEFAULT_ENDPOINT
        authToken = customToken ?: DEFAULT_TOKEN
        deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.HARDWARE}"
            .replace(" ", "_")
            .take(64)
        i(TAG, "GrafanaLogger initialized: $deviceId -> $endpoint")
    }

    fun setEnabled(e: Boolean) { enabled = e }

    fun i(tag: String, message: String) = log("info", tag, message)
    fun w(tag: String, message: String) = log("warn", tag, message)
    fun e(tag: String, message: String) = log("error", tag, message)

    private fun log(level: String, tag: String, message: String) {
        // Also log to Android logcat
        android.util.Log.println(
            when (level) {
                "error" -> android.util.Log.ERROR
                "warn" -> android.util.Log.WARN
                else -> android.util.Log.INFO
            },
            tag, message
        )

        if (!enabled) return

        scope.launch {
            try {
                val payload = """
                [{
                    "level": "$level",
                    "tag": "$tag",
                    "message": ${jsonEscape(message)},
                    "device": "$deviceId"
                }]
                """.trimIndent()

                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $authToken")
                conn.setRequestProperty("User-Agent", "EmuHub-Android/${Build.VERSION.SDK_INT}")
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    android.util.Log.w(TAG, "Log push failed: HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Log push error: ${e.message}")
            }
        }
    }

    private fun jsonEscape(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
