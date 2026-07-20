package com.emuhub.app.data.r2

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Cache LRU de arquivos baixados do R2.
 *
 * O cache fica no armazenamento externo (EmuHub/.cache) e não no cacheDir
 * interno do app: os emuladores rodam em outro processo e precisam conseguir
 * ler o arquivo baixado — /data/data/<pkg>/cache é invisível para eles.
 * Se o armazenamento externo não estiver disponível, cai no cacheDir interno.
 *
 * A "idade" LRU usa o lastModified do arquivo, renovado a cada acesso.
 */
class R2CacheManager(
    context: Context,
    private val config: R2Config,
) {
    val root: File = run {
        val external = Environment.getExternalStorageDirectory()
        if (external != null && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val candidate = File(external, "EmuHub/.${config.cacheDir}")
            // Verifica se consegue escrever no diretório (Android 14 scoped
            // storage pode negar mesmo com MEDIA_MOUNTED). Se falhar, usa
            // cacheDir interno como fallback.
            try {
                candidate.mkdirs()
                if (candidate.isDirectory && candidate.canWrite()) {
                    return@run candidate
                }
                Log.w(TAG, "Root externo $candidate não é writable — fallback p/ cache interno")
            } catch (_: Exception) {
                Log.w(TAG, "Root externo $candidate falhou — fallback p/ cache interno")
            }
        }
        File(context.cacheDir, config.cacheDir)
    }

    private val TAG = "R2CacheManager"

    /** Arquivo local correspondente a uma key do R2 (espelha a hierarquia da key). */
    fun fileFor(key: String): File = File(root, sanitize(key))

    /** Arquivo temporário usado durante o download (nunca entregue ao emulador). */
    fun tempFileFor(key: String): File = File(root, sanitize(key) + ".part")

    /** Retorna o arquivo em cache (renovando o LRU) ou null se não baixado. */
    fun get(key: String): File? {
        val file = fileFor(key)
        return if (file.isFile) {
            file.setLastModified(System.currentTimeMillis())
            file
        } else null
    }

    /** Registra um arquivo recém-baixado e aplica a política de tamanho. */
    fun put(key: String, file: File): File {
        val dest = fileFor(key)
        if (file.absolutePath != dest.absolutePath) {
            dest.parentFile?.mkdirs()
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            }
        }
        dest.setLastModified(System.currentTimeMillis())
        evict()
        return dest
    }

    /** Remove os arquivos menos usados até caber em maxCacheMB. */
    fun evict() {
        val maxBytes = config.maxCacheMB * 1024 * 1024
        var total = size()
        if (total <= maxBytes) return

        val files = allFiles().sortedBy { it.lastModified() }
        for (file in files) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
        pruneEmptyDirs(root)
    }

    /** Apaga todo o cache. */
    fun clear() {
        root.deleteRecursively()
    }

    /** Tamanho total do cache em bytes. */
    fun size(): Long = allFiles().sumOf { it.length() }

    private fun allFiles(): List<File> =
        root.walkTopDown().filter { it.isFile }.toList()

    private fun pruneEmptyDirs(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                pruneEmptyDirs(child)
                child.delete() // só apaga se estiver vazio
            }
        }
    }

    companion object {
        /** Remove segmentos perigosos da key antes de usá-la como caminho. */
        private fun sanitize(key: String): String =
            key.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
    }
}
