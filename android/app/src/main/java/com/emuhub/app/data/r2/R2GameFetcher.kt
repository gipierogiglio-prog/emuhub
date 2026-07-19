package com.emuhub.app.data.r2

import com.emuhub.app.data.catalog.SystemCatalog
import com.emuhub.app.data.scanner.RomScanner
import com.emuhub.app.data.scanner.ScanProgress

/** ROM encontrada no R2 — equivalente remoto do ScannedRom, com key no lugar de File. */
data class RemoteRom(
    val systemId: String,
    val key: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * "Scanner" remoto: lista <prefix>/<pasta-do-sistema>/ no bucket para cada
 * sistema do catálogo e mapeia extensões/nomes igual ao RomScanner local.
 */
class R2GameFetcher(
    private val client: R2Client,
    private val catalog: SystemCatalog,
) {
    /**
     * Busca jogos no R2 cujo nome corresponda à [query] (case-insensitive).
     * Útil para pesquisa ao vivo sem depender de sync completo.
     */
    fun searchGames(query: String, prefix: String): List<RemoteRom> {
        val results = mutableListOf<RemoteRom>()
        val lowerQuery = query.lowercase()
        catalog.systems.forEach { system ->
            val extensions = system.extensions.map { it.lowercase().removePrefix(".") }.toSet()
            val systemPrefix = "$prefix${system.folder}/"
            val objects = try {
                client.listObjects(systemPrefix)
            } catch (_: Exception) {
                emptyList()
            }
            objects.forEach { obj ->
                val fileName = obj.key.substringAfterLast('/')
                if (fileName.isEmpty() || fileName.startsWith(".")) return@forEach
                val nameOnly = fileName.substringBeforeLast('.')
                if (!nameOnly.lowercase().contains(lowerQuery)) return@forEach
                val extension = fileName.substringAfterLast('.', "").lowercase()
                if (extension !in extensions) return@forEach
                results += RemoteRom(
                    systemId = system.id,
                    key = obj.key,
                    fileName = fileName,
                    displayName = RomScanner.cleanDisplayName(nameOnly),
                    sizeBytes = obj.sizeBytes,
                    lastModified = obj.lastModified,
                )
            }
        }
        return results
    }

    fun fetchGames(prefix: String, onProgress: (ScanProgress) -> Unit = {}): List<RemoteRom> {
        val results = mutableListOf<RemoteRom>()
        val total = catalog.systems.size

        catalog.systems.forEachIndexed { index, system ->
            onProgress(ScanProgress(system.name, index, total, results.size))
            val extensions = system.extensions.map { it.lowercase().removePrefix(".") }.toSet()
            val systemPrefix = "$prefix${system.folder}/"

            val objects = try {
                client.listObjects(systemPrefix)
            } catch (e: Exception) {
                // Sistema sem pasta no bucket ou erro pontual: segue para o próximo.
                emptyList()
            }

            objects.forEach { obj ->
                val fileName = obj.key.substringAfterLast('/')
                if (fileName.isEmpty() || fileName.startsWith(".")) return@forEach
                val extension = fileName.substringAfterLast('.', "").lowercase()
                if (extension !in extensions) return@forEach

                results += RemoteRom(
                    systemId = system.id,
                    key = obj.key,
                    fileName = fileName,
                    displayName = RomScanner.cleanDisplayName(fileName.substringBeforeLast('.')),
                    sizeBytes = obj.sizeBytes,
                    lastModified = obj.lastModified,
                )
            }
        }
        onProgress(ScanProgress("", total, total, results.size))
        return results
    }

}
