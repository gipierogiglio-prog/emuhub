package com.emuhub.app.di

import android.content.Context
import androidx.room.Room
import com.emuhub.app.data.catalog.SystemCatalog
import com.emuhub.app.data.db.EmuHubDatabase
import com.emuhub.app.data.r2.R2CacheManager
import com.emuhub.app.data.r2.R2Client
import com.emuhub.app.data.r2.R2Config
import com.emuhub.app.data.r2.R2Downloader
import com.emuhub.app.data.r2.R2GameFetcher
import com.emuhub.app.data.repo.GameRepository
import com.emuhub.app.data.repo.SettingsRepository
import com.emuhub.app.data.scanner.RomScanner
import com.emuhub.app.domain.EmulatorInstaller
import com.emuhub.app.domain.EmulatorRegistry
import com.emuhub.app.domain.GameLauncher

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val catalog: SystemCatalog by lazy { SystemCatalog.fromAssets(appContext) }

    private val database: EmuHubDatabase by lazy {
        Room.databaseBuilder(appContext, EmuHubDatabase::class.java, "emuhub.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val gameRepository: GameRepository by lazy {
        GameRepository(database.gameDao(), RomScanner(catalog))
    }

    val emulatorRegistry: EmulatorRegistry by lazy {
        EmulatorRegistry(appContext, catalog)
    }

    // --- Integração R2 (jogos na nuvem) ---

    val r2Config: R2Config by lazy { R2Config() }

    val r2Client: R2Client by lazy { R2Client(r2Config) }

    val r2GameFetcher: R2GameFetcher by lazy { R2GameFetcher(r2Client, catalog) }

    val r2CacheManager: R2CacheManager by lazy { R2CacheManager(appContext, r2Config) }

    val r2Downloader: R2Downloader by lazy { R2Downloader(r2Client, r2CacheManager) }

    val emulatorInstaller: EmulatorInstaller by lazy {
        EmulatorInstaller(appContext, r2Downloader)
    }

    val gameLauncher: GameLauncher by lazy {
        GameLauncher(
            appContext,
            catalog,
            emulatorRegistry,
            settingsRepository,
            gameRepository,
            r2Downloader,
            emulatorInstaller,
        )
    }
}
