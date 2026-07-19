package com.emuhub.app.domain

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import com.emuhub.app.util.EmuHubLogger
import androidx.core.content.FileProvider
import com.emuhub.app.data.r2.R2Downloader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Instala emuladores automaticamente: baixa o APK do R2, copia pra pasta
 * Downloads (acessível universalmente) e abre o instalador nativo do Android
 * via ACTION_INSTALL_PACKAGE (API oficial, funciona em todos Androids 8+).
 */
class EmulatorInstaller(
    private val context: Context,
    private val r2Downloader: R2Downloader,
) {
    private val apkKeys = mapOf(
        "retroarch" to "emulators/android/retroarch.apk",
        "ppsspp" to "emulators/android/ppsspp.apk",
        "flycast" to "emulators/android/flycast.apk",
        "citra" to "emulators/android/citra.apk",
        "aethersx2" to "emulators/android/nethersx2.apk",
    )

    fun hasApk(emulatorId: String): Boolean = emulatorId in apkKeys

    /**
     * Baixa o APK do R2 e copia pra Downloads (acessível a todos os apps).
     */
    suspend fun downloadApk(emulatorId: String, onProgress: (Float) -> Unit = {}): Result<File> {
        val key = apkKeys[emulatorId]
            ?: return Result.failure(IllegalArgumentException("Sem APK no R2 para: $emulatorId"))

        return try {
            val cached = r2Downloader.download(key) { p -> onProgress(p) }

            // Copia pra Downloads/EmuHub/ pra garantir acesso universal
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "EmuHub"
            )
            publicDir.mkdirs()
            val publicFile = File(publicDir, cached.name)
            if (!publicFile.exists()) {
                cached.copyTo(publicFile, overwrite = true)
            }
            onProgress(1f)
            Result.success(publicFile)
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao baixar/copiar APK: $key", e)
            Result.failure(e)
        }
    }

    /**
     * Abre o instalador nativo usando a API oficial ACTION_INSTALL_PACKAGE.
     */
    fun openInstaller(apk: File, activityContext: Context): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                activityContext,
                "${activityContext.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            activityContext.startActivity(intent)
            true
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao abrir instalador para ${apk.name}", e)
            false
        }
    }

    companion object {
        private const val TAG = "EmulatorInstaller"
    }
}
