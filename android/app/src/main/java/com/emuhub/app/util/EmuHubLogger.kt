package com.emuhub.app.util

import android.os.Build
import android.util.Log
import com.emuhub.app.data.r2.R2Client
import com.emuhub.app.util.GrafanaLogger
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * * Logger interno do EmuHub que salva logs em arquivo no filesDir.
 * ESCREVE SÍNCRONO com FileWriter + flush explícito.
 * Fallback automático pra cacheDir se filesDir falhar.
 * Logs em: filesDir/emuhub-logs/
 */
object EmuHubLogger {

    private const val TAG = "EmuHubLogger"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB por arquivo
    private const val MAX_LOG_FILES = 3

    private var currentFile: File? = null
    private var initialized = false
    private var appVersionName = "dev"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun init(context: android.content.Context, versionName: String = "dev", versionCode: String = "0") {
        appVersionName = versionName
        try {
            // Tenta filesDir primeiro, fallback pra cacheDir
            val baseDir = try {
                val fd = context.filesDir
                if (fd != null && fd.exists()) File(fd, "emuhub-logs")
                else null
            } catch (e: Exception) {
                null
            } ?: try {
                val cd = context.cacheDir
                if (cd != null && cd.exists()) File(cd, "emuhub-logs")
                else null
            } catch (e: Exception) {
                null
            }

            if (baseDir == null) {
                Log.e(TAG, "Nao foi possivel criar dir de log")
                return
            }

            baseDir.mkdirs()
            rotateLogs(baseDir)

            val timestamp = fileDateFmt.format(Date())
            currentFile = File(baseDir, "emuhub-$timestamp.log")
            // Cria o arquivo fisicamente pra garantir
            currentFile?.createNewFile()
            initialized = true

            // UncaughtExceptionHandler
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e(TAG, "UNCAUGHT EXCEPTION em ${thread.name}", throwable)
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
                defaultHandler?.uncaughtException(thread, throwable)
            }

            // Cabeçalho: identifica versão e aparelho quando o log chega solto
            rawWrite("I", TAG, "EmuHub v$versionName (code $versionCode) | " +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} | Android: ${Build.VERSION.SDK_INT}")
            rawWrite("I", TAG, "Logger iniciado em: ${currentFile?.absolutePath}")
            // Também pro logcat
            Log.i(TAG, "Logger iniciado em: ${currentFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar logger", e)
        }
    }

    private fun rotateLogs(dir: File) {
        try {
            // Crash log nativo fica fora da rotação: precisa sobreviver até
            // o uploadLogs() enviá-lo pro R2
            val logs = dir.listFiles()
                ?.filter { it.name.startsWith("emuhub-") && it.name.endsWith(".log")
                        && it.name != "emuhub-native-crash.log" }
                ?.sortedByDescending { it.lastModified() } ?: return

            if (logs.size > MAX_LOG_FILES) {
                logs.drop(MAX_LOG_FILES).forEach { it.delete() }
            }
            val latest = logs.firstOrNull()
            if (latest != null && latest.length() > MAX_LOG_SIZE) {
                latest.delete()
            }
        } catch (_: Exception) {}
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        rawWrite("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        rawWrite("W", tag, msg)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        rawWrite("E", tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
        rawWrite("E", tag, "$msg: ${tr?.message}")
        if (tr != null) {
            for (ste in tr.stackTrace.take(5)) {
                rawWrite("E", tag, "  at ${ste.toString()}")
            }
        }
    }

    /** Escrita síncrona com FileWriter + flush, e forward pro GrafanaLogger */
    private fun rawWrite(level: String, tag: String, msg: String) {
        if (!initialized || currentFile == null) return
        val line = "${dateFmt.format(Date())} $level/$tag: $msg\n"
        try {
            FileWriter(currentFile, true).use { fw ->
                fw.append(line)
                fw.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao escrever log: ${e.message}")
        }
        // Forward to Grafana (Loki) — não-bloqueante via coroutine
        when (level) {
            "E" -> GrafanaLogger.e(tag, msg)
            "W" -> GrafanaLogger.w(tag, msg)
            else -> GrafanaLogger.i(tag, msg)
        }
    }

    fun getLog(): String {
        val file = currentFile
        return if (file != null && file.exists() && file.length() > 0) {
            try {
                file.readText()
            } catch (_: Exception) {
                "Erro ao ler log"
            }
        } else {
            "Nenhum log disponível (${file?.absolutePath}, size=${file?.length()})"
        }
    }

    fun getLogPath(): String? = currentFile?.absolutePath

    /**
     * Sobe pro R2 (logs/emuhub/{versao}/{device}-{arquivo}) todos os .log do
     * diretório de logs, EXCETO o arquivo ativo desta sessão — isso inclui
     * logs de sessões anteriores e o emuhub-native-crash.log gravado pelo
     * signal handler. Upload ok → deleta o local (não re-envia); falha
     * (sem rede etc.) → mantém e tenta de novo na próxima chamada.
     * Chamar em background; faz I/O de rede síncrono.
     */
    fun uploadLogs(client: R2Client) {
        val dir = currentFile?.parentFile ?: return
        val device = "${Build.MANUFACTURER}-${Build.MODEL}"
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") && it != currentFile && it.length() > 0 }
            ?: return
        for (file in files) {
            try {
                val key = "logs/emuhub/$appVersionName/$device-${file.name}"
                if (client.uploadObject(key, file)) {
                    i(TAG, "Log enviado pro R2: $key (${file.length()} bytes)")
                    file.delete()
                } else {
                    w(TAG, "Upload de log falhou, mantido p/ próxima tentativa: ${file.name}")
                }
            } catch (e: Exception) {
                w(TAG, "Upload de log falhou: ${file.name} (${e.message})")
            }
        }
    }

    fun getLogFiles(): List<File> {
        val dir = currentFile?.parentFile ?: return emptyList()
        return try {
            dir.listFiles()
                ?.filter { it.name.startsWith("emuhub-") && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
