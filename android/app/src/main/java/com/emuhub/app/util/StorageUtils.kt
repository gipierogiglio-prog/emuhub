package com.emuhub.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat

object StorageUtils {

    /** O app tem acesso amplo aos arquivos para escanear ROMs e passar caminhos reais? */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Intent para a tela de "Acesso a todos os arquivos" do app (API 30+). */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Converte uma tree URI do seletor SAF em caminho real de arquivo.
     * "primary:EmuHub" → /storage/emulated/0/EmuHub; outros volumes → /storage/<id>/<subpasta>.
     */
    fun treeUriToPath(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', missingDelimiterValue = "")
        val base = if (volume == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        if (relative.isEmpty()) base else "$base/$relative"
    } catch (e: IllegalArgumentException) {
        null
    }
}
