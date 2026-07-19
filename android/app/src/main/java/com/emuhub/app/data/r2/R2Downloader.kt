package com.emuhub.app.data.r2

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Baixa um objeto do R2 para o cache local, reaproveitando o que já foi baixado.
 * Progresso reportado como fração 0f..1f (ou -1f quando o tamanho é desconhecido).
 */
class R2Downloader(
    private val client: R2Client,
    private val cache: R2CacheManager,
) {
    suspend fun download(remoteKey: String, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            // Já está no cache? Entrega direto.
            cache.get(remoteKey)?.let {
                onProgress(1f)
                return@withContext it
            }

            val temp = cache.tempFileFor(remoteKey)
            temp.parentFile?.mkdirs()
            try {
                client.downloadObject(remoteKey, temp) { read, total ->
                    onProgress(if (total > 0) read.toFloat() / total else -1f)
                }
            } catch (e: Exception) {
                temp.delete()
                throw if (e is IOException) e else IOException("Falha ao baixar $remoteKey", e)
            }

            onProgress(1f)
            cache.put(remoteKey, temp)
        }
}
