package com.emuhub.app.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.emuhub.app.util.EmuHubLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Informações sobre uma release do GitHub. */
data class GitHubRelease(
    val tagName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String?,
    val apkSize: Long,
    val body: String,
)

/**
 * Verifica updates no GitHub, baixa e instala o APK mais recente.
 *
 * O repositório é [REPO_OWNER]/[REPO_NAME]; a API do GitHub é consultada
 * sem autenticação (rate limit de 60 req/h para IPs sem token).
 */
class AutoUpdater(
    private val context: Context,
    private val currentVersionCode: Long,
) {

    companion object {
        private const val TAG = "AutoUpdater"
        private const val REPO_OWNER = "gipierogiglio-prog"
        private const val REPO_NAME = "emuhub"
        private const val API_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    }

    /**
     * Consulta o GitHub e retorna a última release, ou null se não houver
     * atualização ou se a consulta falhar.
     */
    suspend fun checkForUpdate(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "EmuHub-Android")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                EmuHubLogger.w(TAG, "GitHub API retornou HTTP ${conn.responseCode}")
                conn.disconnect()
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val release = parseRelease(body) ?: return@withContext null

            // Só considera se for mais novo que o atual
            if (release.versionCode <= currentVersionCode) {
                EmuHubLogger.i(TAG, "Versão atual ($currentVersionCode) é a mais recente")
                return@withContext null
            }

            EmuHubLogger.i(TAG, "Update disponível: ${release.tagName} (v${release.versionCode})")
            return@withContext release
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao verificar update", e)
            return@withContext null
        }
    }

    /**
     * Baixa o APK da release para o cache interno e retorna o arquivo.
     */
    suspend fun downloadApk(release: GitHubRelease): File? = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: return@withContext null
        try {
            val apkUrl = URL(url)
            val conn = apkUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000

            val tempDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(tempDir, "emuhub-${release.versionName}.apk")

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            EmuHubLogger.i(TAG, "APK baixado: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            return@withContext apkFile
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao baixar APK", e)
            return@withContext null
        }
    }

    /**
     * Instala o APK baixado usando Intent + FileProvider (Android 14+).
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            EmuHubLogger.i(TAG, "Intent de instalação enviada para $uri")
            true
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao iniciar instalação", e)
            false
        }
    }

    /**
     * Parseia o JSON da API do GitHub e extrai as informações da release,
     * incluindo a URL do primeiro APK (arm64-v8a) nos assets.
     */
    private fun parseRelease(json: String): GitHubRelease? {
        return try {
            // Parse manual sem dependências externas (sem kotlinx.serialization aqui)
            val tagName = extractJsonString(json, "tag_name") ?: return null
            val body = extractJsonString(json, "body") ?: ""
            val assetsStr = extractJsonArray(json, "assets") ?: "[]"

            // Encontra o asset do APK arm64-v8a
            val apkUrl = findAssetUrl(assetsStr, "arm64-v8a")
            val apkSize = findAssetSize(assetsStr, "arm64-v8a")

            // Extrai versionCode do tag (ex: "v1.2.52" → 52)
            val versionCode = tagName.substringAfterLast(".").toLongOrNull() ?: 0
            val versionName = tagName.trimStart('v')

            GitHubRelease(
                tagName = tagName,
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                apkSize = apkSize,
                body = body,
            )
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao parsear release JSON", e)
            null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonArray(json: String, key: String): String? {
        val regex = "\"$key\"\\s*:\\s*\\[([^]]+)\\]".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun findAssetUrl(assetsJson: String, arch: String): String? {
        val entries = assetsJson.split("},{")
        for (entry in entries) {
            if (entry.contains(arch, ignoreCase = true)) {
                return extractJsonString(entry, "browser_download_url")
            }
        }
        // Fallback: pega o primeiro asset que for .apk
        for (entry in entries) {
            val name = extractJsonString(entry, "name") ?: continue
            if (name.endsWith(".apk", ignoreCase = true)) {
                return extractJsonString(entry, "browser_download_url")
            }
        }
        return null
    }

    private fun findAssetSize(assetsJson: String, arch: String): Long {
        val entries = assetsJson.split("},{")
        for (entry in entries) {
            if (entry.contains(arch, ignoreCase = true)) {
                return extractJsonString(entry, "size")?.toLongOrNull() ?: 0L
            }
        }
        return 0L
    }
}
