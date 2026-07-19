package com.emuhub.app.data.catalog

import android.content.Context
import kotlinx.serialization.json.Json

class SystemCatalog(private val catalogFile: CatalogFile) {

    val systems: List<SystemDef> = catalogFile.systems
    val emulators: List<EmulatorDef> = catalogFile.emulators

    private val systemsById = systems.associateBy { it.id }
    private val emulatorsById = emulators.associateBy { it.id }

    fun systemById(id: String): SystemDef? = systemsById[id]

    fun emulatorById(id: String): EmulatorDef? = emulatorsById[id]

    /** Emuladores candidatos de um sistema, na ordem de preferência do catálogo. */
    fun emulatorsFor(systemId: String): List<Pair<EmulatorDef, EmulatorRef>> =
        systemById(systemId)?.emulators.orEmpty().mapNotNull { ref ->
            emulatorById(ref.ref)?.let { it to ref }
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): SystemCatalog =
            SystemCatalog(json.decodeFromString<CatalogFile>(text))

        fun fromAssets(context: Context, fileName: String = "systems.json"): SystemCatalog =
            fromJson(context.assets.open(fileName).bufferedReader().use { it.readText() })
    }
}
