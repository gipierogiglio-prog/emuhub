package com.emuhub.app.data.catalog

import kotlinx.serialization.Serializable

@Serializable
data class CatalogFile(
    val systems: List<SystemDef>,
    val emulators: List<EmulatorDef>,
)

@Serializable
data class SystemDef(
    val id: String,
    val name: String,
    val shortName: String,
    val folder: String,
    val extensions: List<String>,
    val color: String,
    val emulators: List<EmulatorRef>,
)

@Serializable
data class EmulatorRef(
    val ref: String,
    /** Nome do core libretro quando o emulador é o RetroArch (ex.: "snes9x"). */
    val core: String? = null,
)

@Serializable
data class EmulatorDef(
    val id: String,
    val name: String,
    /** Pacotes candidatos em ordem de preferência (ex.: build 64 bits primeiro). */
    val packages: List<String>,
    val launch: LaunchRecipe,
    /** URL de instalação quando o app não está na Play Store. */
    val installUrl: String? = null,
)

/**
 * Receita de lançamento. type:
 *  - "retroarch": ACTION_MAIN em RetroActivityFuture com extras ROM/LIBRETRO/CONFIGFILE
 *  - "component_extra": componente explícito + extra de caminho (pathExtra/pathKind)
 *  - "view_uri": ACTION_VIEW com content:// URI direcionado ao pacote
 */
@Serializable
data class LaunchRecipe(
    val type: String,
    val component: String? = null,
    val pathExtra: String? = null,
    /** "file" = caminho real no extra; "content" = content:// URI no extra. */
    val pathKind: String = "file",
)
