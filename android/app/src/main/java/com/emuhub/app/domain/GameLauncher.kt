package com.emuhub.app.domain

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.emuhub.app.util.EmuHubLogger
import androidx.core.content.FileProvider
import com.emuhub.app.data.catalog.EmulatorDef
import com.emuhub.app.data.catalog.EmulatorRef
import com.emuhub.app.data.catalog.LaunchRecipe
import com.emuhub.app.data.catalog.SystemCatalog
import com.emuhub.app.data.db.GameEntity
import com.emuhub.app.data.r2.R2Downloader
import com.emuhub.app.data.repo.GameRepository
import com.emuhub.app.data.repo.SettingsRepository
import com.emuhub.app.domain.model.LaunchResult
import java.io.File
import kotlinx.coroutines.runBlocking

class GameLauncher(
    private val context: Context,
    private val catalog: SystemCatalog,
    private val registry: EmulatorRegistry,
    private val settings: SettingsRepository,
    private val games: GameRepository,
    private val r2Downloader: R2Downloader,
    private val installer: EmulatorInstaller,
) {
    /**
     * Lança o jogo no emulador. Para jogos remotos (R2), baixa antes para o
     * cache local (ou reaproveita o já baixado) reportando [onDownloadProgress].
     *
     * Se o emulador não está instalado mas há APK dele no R2, baixa e instala
     * automaticamente (reportando [onInstallProgress] com nome e fração) antes
     * de lançar. Sem APK disponível, devolve [LaunchResult.EmulatorMissing].
     */
    suspend fun launch(
        game: GameEntity,
        onDownloadProgress: (Float) -> Unit = {},
        onInstallProgress: (String, Float) -> Unit = { _, _ -> },
    ): LaunchResult {
        val resolved = registry.resolveFor(game.systemId, null)
            ?: return LaunchResult.Error("Sistema sem emulador no catálogo: ${game.systemId}")

        val pkg = resolved.installedPackage ?: run {
            if (!installer.hasApk(resolved.def.id)) {
                return LaunchResult.EmulatorMissing(resolved.def)
            }
            onInstallProgress(resolved.def.name, 0f)
            val download = installer.downloadApk(resolved.def.id) { progress ->
                onInstallProgress(resolved.def.name, progress)
            }
            if (download.isFailure) {
                EmuHubLogger.e(TAG, "Falha ao baixar APK: ${resolved.def.id}", download.exceptionOrNull())
                return LaunchResult.Error(download.exceptionOrNull()?.message)
            }
            // APK baixado, mas emulador ainda não instalado.
            // O caller (GameListScreen) abre o instalador com o Activity context.
            return LaunchResult.NeedsInstallApk(download.getOrThrow(), resolved.def)
        }

        // Jogo remoto: garante o arquivo local antes de montar o intent.
        val effectiveGame = if (game.isRemote) {
            val key = game.remoteKey
                ?: return LaunchResult.Error("Jogo remoto sem key do R2: ${game.displayName}")
            val localFile = try {
                r2Downloader.download(key, onDownloadProgress)
            } catch (e: Exception) {
                EmuHubLogger.e(TAG, "Falha ao baixar do R2: $key", e)
                return LaunchResult.Error("Falha no download: ${e.message}")
            }
            game.copy(path = localFile.absolutePath)
        } else {
            game
        }

        val intent = try {
            buildIntent(resolved.def, resolved.ref, pkg, effectiveGame)
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao montar intent", e)
            return LaunchResult.Error(e.message)
        }

        return try {
            context.startActivity(intent)
            games.recordPlay(game.id)
            LaunchResult.Launched(resolved.def.name, resolved.ref.core)
        } catch (e: ActivityNotFoundException) {
            EmuHubLogger.e(TAG, "Activity não encontrada para ${resolved.def.id}", e)
            LaunchResult.Error(e.message)
        } catch (e: SecurityException) {
            EmuHubLogger.e(TAG, "Sem permissão para lançar ${resolved.def.id}", e)
            LaunchResult.Error(e.message)
        }
    }

    /** Intent para a página de instalação do emulador (Play Store ou URL direta). */
    fun installIntent(def: EmulatorDef): Intent {
        val url = def.installUrl
            ?: "https://play.google.com/store/apps/details?id=${def.packages.first()}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Mapa: ref.core → nome do arquivo .so no nativeLibDir */
    private val nativeCoreMap = mapOf(
        "mgba" to "libmgba_libretro_android.so",
        "snes9x" to "libsnes9x_libretro_android.so",
        "fceumm" to "libfceumm_libretro_android.so",
        "gambatte" to "libgambatte_libretro_android.so",
        "genesis_plus_gx" to "libgenesis_plus_gx_libretro_android.so",
        "mednafen_pce_fast" to "libmednafen_pce_fast_libretro_android.so",
        "picodrive" to "libpicodrive_libretro_android.so",
        "stella" to "libstella_libretro_android.so",
        "mame2003_plus" to "libmame2003_plus_libretro_android.so",
        "fbneo" to "libfbneo_libretro_android.so",
        "mupen64plus_next" to "libmupen64plus_next_libretro_android.so",
        "ppsspp" to "libppsspp_libretro_android.so",
        "swanstation" to "libswanstation_libretro_android.so",
        "flycast" to "libflycast_libretro_android.so",
        "melonds" to "libmelonds_libretro_android.so",
        "citra" to "libcitra_libretro_android.so",
        "panda3ds" to "libpanda3ds_libretro_android.so",
        "play" to "libplay_libretro_android.so",
        "pcee2" to "libpcee2_libretro_android.so",
    )

    /** Tenta usar o emulador nativo interno. Retorna true se conseguiu iniciar. */
    fun tryLaunchNative(game: GameEntity, onProgress: (Float) -> Unit = {}): Boolean {
        // Se for jogo remoto, precisa baixar primeiro
        val effectivePath = if (game.isRemote) {
            val key = game.remoteKey ?: return false
            try {
                val localFile = runBlocking {
                    r2Downloader.download(key, onProgress)
                }
                localFile.absolutePath
            } catch (e: Exception) {
                EmuHubLogger.e(TAG, "Falha ao baixar ROM remota para native", e)
                return false
            }
        } else {
            game.path
        }

        // Se for ZIP, extrai o primeiro .gba/.gb/.gbc/.nes/.sfc ou similar
        val romPath = if (effectivePath.endsWith(".zip", ignoreCase = true) ||
            effectivePath.endsWith(".7z", ignoreCase = true)) {
            extractRomFromZip(effectivePath) ?: effectivePath
        } else {
            effectivePath
        }

        // Baixa BIOS necessários para o core (Dreamcast, PS1, etc.)
        val systemDir = File(context.filesDir, "system")
        systemDir.mkdirs()

        val biosMap = mapOf(
            "dc_boot.bin" to "PC/GPBOXPC/batocera/bios/dc_boot.bin",
            "dc_flash.bin" to "PC/GPBOXPC/batocera/bios/dc_flash.bin",
            // NDS melonDS BIOS
            "bios7.bin" to "PC/GPBOXPC/batocera/bios/bios7.bin",
            "bios9.bin" to "PC/GPBOXPC/batocera/bios/bios9.bin",
            "firmware.bin" to "PC/GPBOXPC/batocera/bios/firmware.bin",
        )
        for ((biosName, r2Key) in biosMap) {
            val biosFile = File(systemDir, biosName)
            if (!biosFile.exists()) {
                try {
                    val downloaded = runBlocking {
                        r2Downloader.download(r2Key) {}
                    }
                    downloaded.copyTo(biosFile, overwrite = true)
                    EmuHubLogger.i(TAG, "BIOS downloaded: $biosName")
                } catch (e: Exception) {
                    EmuHubLogger.w(TAG, "BIOS not available: $biosName (${e.message})")
                }
            }
        }

        // Citra system data (shared_font.bin, aes_keys.txt, seeddb.bin)
        // Citra procura em {systemDir}/sysdata/ para esses arquivos
        if (game.systemId == "3ds") {
            val citraSysdata = File(systemDir, "sysdata").apply { mkdirs() }
            // O core citra-libretro usa {systemDir}/citra como user dir,
            // então os arquivos precisam existir também em citra/sysdata/
            val citraUserSysdata = File(systemDir, "citra/sysdata").apply { mkdirs() }
            val citraNand = File(systemDir, "citra/nand/00000000000000000000000000000000/title").apply { mkdirs() }

            // Baixa cada arquivo uma vez e replica em todos os paths que o
            // citra pode consultar: raiz do systemDir, sysdata/ e citra/sysdata/
            val citraFiles = mapOf(
                "shared_font.bin" to "PC/GPBOXPC/batocera/bios/citra/sysdata/shared_font.bin",
                "aes_keys.txt" to "PC/GPBOXPC/batocera/bios/citra/sysdata/aes_keys.txt",
                "seeddb.bin" to "PC/GPBOXPC/batocera/bios/citra/sysdata/seeddb.bin",
            )
            for ((name, r2Key) in citraFiles) {
                val targets = listOf(
                    File(systemDir, name),
                    File(citraSysdata, name),
                    File(citraUserSysdata, name),
                )
                if (targets.all { it.exists() }) continue
                try {
                    val source = targets.firstOrNull { it.exists() }
                        ?: runBlocking { r2Downloader.download(r2Key) {} }
                    for (target in targets) {
                        if (!target.exists()) source.copyTo(target, overwrite = true)
                    }
                    EmuHubLogger.i(TAG, "Citra $name replicado nos paths do system dir")
                } catch (e: Exception) {
                    EmuHubLogger.w(TAG, "Citra $name not available: ${e.message}")
                }
            }

            // Tenta baixar NAND content files (home menu, etc)
            val nandFiles = listOf(
                "0004009b/00010202/content/00000000.app.romfs",
                "0004009b/00010402/content/00000000.app.romfs",
                "0004009b/00014002/content/00000000.app.romfs",
                "0004009b/00014102/content/00000000.app.romfs",
                "0004009b/00014202/content/00000000.app.romfs",
                "0004009b/00014302/content/00000000.app.romfs",
                "000400db/00010302/content/00000000.app.romfs",
            )
            for (nandPath in nandFiles) {
                val targetFile = File(citraNand, nandPath)
                if (!targetFile.exists()) {
                    try {
                        targetFile.parentFile?.mkdirs()
                        val downloaded = runBlocking {
                            r2Downloader.download("PC/GPBOXPC/batocera/bios/citra/nand/$nandPath") {}
                        }
                        downloaded.copyTo(targetFile, overwrite = true)
                    } catch (_: Exception) { /* optional */ }
                }
            }
        }

        // Procura core nativo: primeiro por tipo retroarch com core conhecido,
        // depois por qualquer emulador cujo ref/core corresponda ao nativeCoreMap
        val candidates = catalog.emulatorsFor(game.systemId)
        for ((def, ref) in candidates) {
            val coreName = when {
                def.launch.type == "retroarch" && ref.core != null -> ref.core
                nativeCoreMap.containsKey(def.id) -> def.id
                ref.core != null && nativeCoreMap.containsKey(ref.core) -> ref.core
                else -> null
            }
            val coreSo = coreName?.let { nativeCoreMap[it] }
            if (coreSo != null) {
                var corePath = NativeLibUtil.ensureCoreSo(context, coreSo)
                if (corePath == null) {
                    // Core não bundlado no APK — tenta baixar do R2
                    try {
                        EmuHubLogger.i(TAG, "Baixando core do R2: $coreSo")
                        val downloaded = runBlocking {
                            r2Downloader.download("cores/$coreSo") {}
                        }
                        val coresDir = java.io.File(context.filesDir, "cores")
                        coresDir.mkdirs()
                        val target = java.io.File(coresDir, coreSo)
                        downloaded.copyTo(target, overwrite = true)
                        target.setExecutable(true, false)
                        corePath = target.absolutePath
                        EmuHubLogger.i(TAG, "Core baixado: $corePath")
                    } catch (e: Exception) {
                        EmuHubLogger.w(TAG, "Falha ao baixar core do R2: ${e.message}")
                    }
                }
                if (corePath != null) {
                    val intent = com.emuhub.app.EmulatorActivity.createIntent(context, corePath, romPath)
                    context.startActivity(intent)
                    return true
                }
            }
        }
        return false
    }

    /** Extrai o primeiro arquivo de jogo de um ZIP para o cache */
    private fun extractRomFromZip(zipPath: String): String? {
        val validExts = setOf(".gba", ".gb", ".gbc", ".nes", ".sfc", ".smc",
            ".md", ".gen", ".sms", ".gg", ".n64", ".z64", ".v64",
            ".psx", ".pce", ".bin", ".iso", ".32x", ".3ds", ".cci", ".cxi")
        return try {
            val zipFile = java.util.zip.ZipFile(zipPath)
            val entry = zipFile.entries().asSequence().firstOrNull { entry ->
                val ext = entry.name.substringAfterLast('.').let { ".$it" }
                !entry.isDirectory && ext in validExts
            }
            if (entry != null) {
                val outDir = File(zipPath).parentFile ?: File(context.cacheDir, "roms")
                outDir.mkdirs()
                val outFile = File(outDir, entry.name)
                if (!outFile.exists()) {
                    zipFile.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                zipFile.close()
                outFile.absolutePath
            } else {
                zipFile.close()
                null
            }
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Falha ao extrair ROM do ZIP: $zipPath", e)
            null
        }
    }

    internal fun buildIntent(def: EmulatorDef, ref: EmulatorRef, pkg: String, game: GameEntity): Intent =
        when (def.launch.type) {
            "retroarch" -> retroArchIntent(pkg, ref.core, game)
            "component_extra" -> componentExtraIntent(def.launch, pkg, game)
            "view_uri" -> viewUriIntent(pkg, game)
            else -> throw IllegalArgumentException("Tipo de launch desconhecido: ${def.launch.type}")
        }

    private fun retroArchIntent(pkg: String, core: String?, game: GameEntity): Intent {
        // Tenta abrir com ROM + core primeiro (funciona se o core já foi baixado).
        // Se falhar, cai no catch do launch() e mostra o erro pro usuário.
        val corePath = core?.let { "/data/data/$pkg/cores/${it}_libretro_android.so" }
        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
            putExtra("ROM", game.path)
            if (corePath != null) putExtra("LIBRETRO", corePath)
            putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg")
            putExtra("QUITFOCUS", "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun componentExtraIntent(recipe: LaunchRecipe, pkg: String, game: GameEntity): Intent {
        val component = requireNotNull(recipe.component) { "Receita component_extra sem component" }
        val extraKey = requireNotNull(recipe.pathExtra) { "Receita component_extra sem pathExtra" }
        return Intent(Intent.ACTION_VIEW).apply {
            setComponent(ComponentName(pkg, component))
            if (recipe.pathKind == "content") {
                val uri = contentUriFor(game)
                putExtra(extraKey, uri.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Componente explícito não concede permissão sozinho.
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(extraKey, game.path)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun viewUriIntent(pkg: String, game: GameEntity): Intent {
        val uri = contentUriFor(game)
        context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun contentUriFor(game: GameEntity): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(game.path))

    companion object {
        private const val TAG = "GameLauncher"
    }
}
