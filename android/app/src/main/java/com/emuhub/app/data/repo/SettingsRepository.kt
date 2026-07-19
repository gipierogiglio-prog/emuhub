package com.emuhub.app.data.repo

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.emuhub.app.data.r2.R2Config
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "emuhub_settings")

class SettingsRepository(private val context: Context) {

    private val keyOnboardingDone = booleanPreferencesKey("onboarding_done")
    private val keyRomRoot = stringPreferencesKey("rom_root")
    private val keyR2Enabled = booleanPreferencesKey("r2_enabled")
    private val keyR2CacheMb = longPreferencesKey("r2_cache_mb")
    private val keyR2LastSync = longPreferencesKey("r2_last_sync")
    private val keyAutoHideTouchOverlay = booleanPreferencesKey("auto_hide_touch_overlay")

    val onboardingDone: Flow<Boolean> =
        context.dataStore.data.map { it[keyOnboardingDone] ?: false }

    val romRoot: Flow<String> =
        context.dataStore.data.map { it[keyRomRoot] ?: defaultRomRoot() }

    /** Esconde o touch overlay automaticamente quando há um gamepad conectado. */
    val autoHideTouchOverlay: Flow<Boolean> =
        context.dataStore.data.map { it[keyAutoHideTouchOverlay] ?: true }

    val r2Enabled: Flow<Boolean> =
        context.dataStore.data.map { it[keyR2Enabled] ?: false }

    /** Timestamp (epoch ms) da última sincronização com o R2; 0 = nunca. */
    val r2LastSync: Flow<Long> =
        context.dataStore.data.map { it[keyR2LastSync] ?: 0L }

    /** Config do R2 com o estado (enabled/cache) vindo das preferências. */
    fun r2Config(): Flow<R2Config> = context.dataStore.data.map { prefs ->
        val base = R2Config()
        base.copy(
            enabled = prefs[keyR2Enabled] ?: false,
            maxCacheMB = prefs[keyR2CacheMb] ?: base.maxCacheMB,
        )
    }

    suspend fun currentR2Enabled(): Boolean = r2Enabled.first()

    suspend fun currentRomRoot(): String = romRoot.first()

    suspend fun currentAutoHideTouchOverlay(): Boolean = autoHideTouchOverlay.first()

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[keyOnboardingDone] = true }
    }

    suspend fun setRomRoot(path: String) {
        context.dataStore.edit { it[keyRomRoot] = path }
    }

    suspend fun setR2Enabled(enabled: Boolean) {
        context.dataStore.edit { it[keyR2Enabled] = enabled }
    }

    suspend fun setR2LastSync(timestamp: Long) {
        context.dataStore.edit { it[keyR2LastSync] = timestamp }
    }

    suspend fun setAutoHideTouchOverlay(enabled: Boolean) {
        context.dataStore.edit { it[keyAutoHideTouchOverlay] = enabled }
    }

    companion object {
        /** Pasta padrão: /storage/emulated/0/EmuHub */
        fun defaultRomRoot(): String =
            File(Environment.getExternalStorageDirectory(), "EmuHub").absolutePath
    }
}
