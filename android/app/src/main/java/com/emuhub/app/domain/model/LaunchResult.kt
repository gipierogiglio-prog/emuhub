package com.emuhub.app.domain.model

import com.emuhub.app.data.catalog.EmulatorDef
import java.io.File

sealed class LaunchResult {
    /** Jogo enviado ao emulador; core = nome do core RetroArch usado, se aplicável. */
    data class Launched(val emulatorName: String, val retroArchCore: String? = null) : LaunchResult()

    /** Nenhum candidato do sistema está instalado; oferecer instalação do padrão. */
    data class EmulatorMissing(val def: EmulatorDef) : LaunchResult()

    /** APK baixado do R2, pronto pra instalar (caller abre o instalador com Activity context). */
    data class NeedsInstallApk(val apkFile: File, val def: EmulatorDef) : LaunchResult()

    /** A instalação automática do emulador falhou; oferecer a Play Store. */
    data class InstallFailed(val def: EmulatorDef, val message: String? = null) : LaunchResult()

    data class Error(val message: String? = null) : LaunchResult()
}
