package com.emuhub.app

import android.app.Application
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import com.emuhub.app.data.repo.SettingsRepository
import com.emuhub.app.di.AppContainer
import com.emuhub.app.data.r2.R2Client
import com.emuhub.app.util.EmuHubLogger
import com.emuhub.app.util.GrafanaLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EmuHubApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Migra dados do antigo diretório GPBOX para EmuHub. */
    private fun migrateFromGpbox() {
        val external = Environment.getExternalStorageDirectory()
        val oldRoot = File(external, "GPBOX")
        val newRoot = File(external, "EmuHub")

        if (!oldRoot.exists()) return // nada pra migrar

        EmuHubLogger.i("Migration", "Pasta GPBOX detectada em $oldRoot — migrando para $newRoot")

        // 1. Renomeia a raiz GPBOX → EmuHub
        try {
            if (newRoot.exists()) {
                // Novo diretório já existe — mescla conteúdo
                oldRoot.listFiles()?.forEach { file ->
                    val dest = File(newRoot, file.name)
                    if (dest.exists()) dest.deleteRecursively()
                    file.renameTo(dest)
                }
                oldRoot.delete()
            } else {
                oldRoot.renameTo(newRoot)
            }
            EmuHubLogger.i("Migration", "Diretório renomeado com sucesso")
        } catch (e: Exception) {
            EmuHubLogger.e("Migration", "Falha ao renomear diretório: ${e.message}")
            return
        }

        // 2. Atualiza o DataStore se a pasta salva era a antiga
        applicationScope.launch {
            try {
                val repo = SettingsRepository(this@EmuHubApplication)
                val currentRoot = repo.romRoot.first()
                if (currentRoot.contains("GPBOX") || currentRoot == oldRoot.absolutePath) {
                    repo.setRomRoot(newRoot.absolutePath)
                    EmuHubLogger.i("Migration", "Settings atualizado: $currentRoot → ${newRoot.absolutePath}")
                }
            } catch (_: Exception) { }
        }
    }

    override fun onCreate() {
        super.onCreate()
        migrateFromGpbox()

        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val versionName = info?.versionName ?: "dev"
        val versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "0"
        EmuHubLogger.init(this, versionName, versionCode)
        GrafanaLogger.init(this)
        container = AppContainer(this)

        // Sobe logs de sessões anteriores (inclui crash log nativo) pro R2.
        // No boot, e não no momento do crash: depois de um SIGSEGV o processo
        // morre e não dá pra confiar em rede.
        Thread {
            try {
                Thread.sleep(5000) // espera o app estabilizar
                EmuHubLogger.uploadLogs(container.r2Client)
            } catch (_: Exception) { }
        }.apply {
            name = "log-upload"
            isDaemon = true
            start()
        }
    }
}
