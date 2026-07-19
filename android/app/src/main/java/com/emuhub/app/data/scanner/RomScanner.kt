package com.emuhub.app.data.scanner

import com.emuhub.app.data.catalog.SystemCatalog
import com.emuhub.app.data.catalog.SystemDef
import java.io.File

data class ScannedRom(
    val systemId: String,
    val file: File,
    val displayName: String,
    val coverPath: String?,
)

data class ScanProgress(
    val currentSystem: String,
    val systemsDone: Int,
    val systemsTotal: Int,
    val gamesFound: Int,
)

/**
 * * Varre a convenção de pastas do EmuHub: <raiz>/roms/<pasta-do-sistema>/.
 * Capas opcionais em <raiz>/media/<pasta-do-sistema>/<nome-do-arquivo>.png|jpg.
 */
class RomScanner(private val catalog: SystemCatalog) {

    fun scan(root: File, onProgress: (ScanProgress) -> Unit = {}): List<ScannedRom> {
        val romsDir = File(root, "roms")
        val mediaDir = File(root, "media")
        val results = mutableListOf<ScannedRom>()
        val total = catalog.systems.size

        catalog.systems.forEachIndexed { index, system ->
            onProgress(ScanProgress(system.name, index, total, results.size))
            val systemDir = File(romsDir, system.folder)
            if (systemDir.isDirectory) {
                results += scanSystemDir(system, systemDir, File(mediaDir, system.folder))
            }
        }
        onProgress(ScanProgress("", total, total, results.size))
        return results
    }

    private fun scanSystemDir(system: SystemDef, dir: File, mediaDir: File): List<ScannedRom> {
        val extensions = system.extensions.map { it.lowercase().removePrefix(".") }.toSet()
        return dir.walkTopDown()
            .maxDepth(3)
            .onEnter { !it.name.startsWith(".") && it.name != "media" }
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .map { file ->
                ScannedRom(
                    systemId = system.id,
                    file = file,
                    displayName = cleanDisplayName(file.nameWithoutExtension),
                    coverPath = findCover(mediaDir, file.nameWithoutExtension),
                )
            }
            .toList()
    }

    private fun findCover(mediaDir: File, baseName: String): String? =
        listOf("png", "jpg", "jpeg", "webp")
            .map { File(mediaDir, "$baseName.$it") }
            .firstOrNull { it.isFile }
            ?.absolutePath

    companion object {
        private val TAG_REGEX = Regex("""[(\[][^)\]]*[)\]]""")
        private val SPACES = Regex("""\s+""")

        /** "Super Game (USA) [!].sfc" → "Super Game" */
        fun cleanDisplayName(nameWithoutExtension: String): String {
            val cleaned = nameWithoutExtension
                .replace(TAG_REGEX, " ")
                .replace(SPACES, " ")
                .trim()
            return cleaned.ifEmpty { nameWithoutExtension.trim() }
        }
    }
}
