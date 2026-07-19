package com.emuhub.app.domain

import android.content.Context
import android.content.pm.PackageManager
import com.emuhub.app.data.catalog.EmulatorDef
import com.emuhub.app.data.catalog.EmulatorRef
import com.emuhub.app.data.catalog.SystemCatalog

data class ResolvedEmulator(
    val def: EmulatorDef,
    val ref: EmulatorRef,
    /** Pacote instalado, ou null se o emulador não está no aparelho. */
    val installedPackage: String?,
)

class EmulatorRegistry(
    private val context: Context,
    private val catalog: SystemCatalog,
) {
    fun installedPackageOf(def: EmulatorDef): String? =
        def.packages.firstOrNull { isInstalled(it) }

    /** Candidatos de um sistema na ordem do catálogo, com estado de instalação. */
    fun candidatesFor(systemId: String): List<ResolvedEmulator> =
        catalog.emulatorsFor(systemId).map { (def, ref) ->
            ResolvedEmulator(def, ref, installedPackageOf(def))
        }

    /**
     * Emulador efetivo do sistema: o override do usuário se houver, senão o
     * primeiro candidato instalado, senão o candidato padrão (não instalado).
     */
    fun resolveFor(systemId: String, overrideEmulatorId: String?): ResolvedEmulator? {
        val candidates = candidatesFor(systemId)
        if (overrideEmulatorId != null) {
            candidates.firstOrNull { it.def.id == overrideEmulatorId }?.let { return it }
        }
        return candidates.firstOrNull { it.installedPackage != null } ?: candidates.firstOrNull()
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
