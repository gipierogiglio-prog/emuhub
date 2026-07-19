package com.emuhub.app.domain

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile
import com.emuhub.app.util.EmuHubLogger
import com.emuhub.app.util.GrafanaLogger

/**
 * Extracts libretro core .so files from the APK to app's private directory,
 * since ApplicationInfo.nativeLibDir is no longer accessible on Android 16+.
 */
object NativeLibUtil {

    private const val TAG = "NativeLibUtil"

    /**
     * Ensures the named .so file is available in the app's cores directory.
     * Tries multiple sources in order:
     * 1. Already extracted (cached)
     * 2. nativeLibraryDir (standard Android path, works on most devices)
     * 3. Direct extraction from APK zip
     */
    fun ensureCoreSo(context: Context, soName: String): String? {
        val coresDir = File(context.filesDir, "cores")
        coresDir.mkdirs()
        val target = File(coresDir, soName)

        // 1. Already extracted
        if (target.exists() && target.length() > 0) {
            GrafanaLogger.i(TAG, "Core ja extraido (cache): $soName")
            return target.absolutePath
        }

        // 2. Native library dir
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            if (nativeLibDir != null) {
                val nativeFile = File(nativeLibDir, soName)
                if (nativeFile.exists() && nativeFile.length() > 0) {
                    GrafanaLogger.i(TAG, "Copiando do nativeLibraryDir: $nativeFile (${nativeFile.length()} bytes)")
                    nativeFile.copyTo(target, overwrite = true)
                    target.setExecutable(true, false)
                    if (target.exists() && target.length() > 0) {
                        GrafanaLogger.i(TAG, "Core obtido do nativeLibraryDir com sucesso")
                        return target.absolutePath
                    }
                } else {
                    GrafanaLogger.w(TAG, "nativeLibraryDir nao contem $soName: $nativeLibDir")
                }
            }
        } catch (e: Exception) {
            GrafanaLogger.w(TAG, "nativeLibraryDir falhou: ${e.message}")
        }

        // 3. Extract from APK zip
        try {
            val apkPath = context.applicationInfo.sourceDir
            GrafanaLogger.i(TAG, "Extraindo core do APK: $apkPath")
            val zip = ZipFile(apkPath)

            val abis = Build.SUPPORTED_ABIS
            val abisStr = abis.joinToString(", ")
            GrafanaLogger.i(TAG, "ABIs do dispositivo: $abisStr")

            var entry: java.util.zip.ZipEntry? = null
            var foundAbi = ""
            for (abi in abis) {
                val candidatePath = "lib/$abi/$soName"
                entry = zip.getEntry(candidatePath)
                val found = if (entry != null) "ACHOU" else "nao encontrado"
                GrafanaLogger.i(TAG, "ABI: $abi -> $candidatePath: $found")
                if (entry != null) {
                    foundAbi = abi
                    break
                }
            }

            if (entry == null) {
                zip.close()
                GrafanaLogger.w(TAG, "Core $soName nao encontrado em nenhuma ABI do APK")
                EmuHubLogger.w(TAG, "Core $soName nao encontrado em nenhuma ABI do APK")
                return null
            }

            GrafanaLogger.i(TAG, "Extraindo $soName de lib/$foundAbi/ (${entry.size} bytes)")

            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            zip.close()

            target.setExecutable(true, false)

            if (target.exists() && target.length() > 0) {
                val size = target.length()
                GrafanaLogger.i(TAG, "Core extraido com sucesso: $soName ($size bytes) em $foundAbi")
                EmuHubLogger.i(TAG, "Core extraido com sucesso: ${target.absolutePath} ($size bytes)")
                return target.absolutePath
            }
        } catch (e: Exception) {
            val errMsg = "Falha ao extrair core do APK: ${e.message}"
            GrafanaLogger.e(TAG, errMsg)
            EmuHubLogger.e(TAG, errMsg, e)
        }

        return null
    }
}
