package com.emuhub.app.ui.onboarding

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.emuhub.app.R
import com.emuhub.app.data.repo.SettingsRepository
import com.emuhub.app.ui.navigation.LocalAppContainer
import com.emuhub.app.ui.theme.EmuHubGray
import com.emuhub.app.ui.theme.EmuHubRed
import com.emuhub.app.util.StorageUtils
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(StorageUtils.hasAllFilesAccess(context)) }
    var romRoot by remember { mutableStateOf(SettingsRepository.defaultRomRoot()) }
    var createSkeleton by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var scanSystem by remember { mutableStateOf("") }
    var scanResult by remember { mutableStateOf<Int?>(null) }
    var r2Scanning by remember { mutableStateOf(false) }
    var r2ScanResult by remember { mutableStateOf<Int?>(null) }
    var r2Error by remember { mutableStateOf<String?>(null) }

    // Conecta ao R2, sincroniza o catálogo remoto e marca a flag nas preferências.
    fun connectR2(onDone: suspend (Boolean) -> Unit = {}) {
        if (r2Scanning) return
        scope.launch {
            r2Scanning = true
            r2Error = null
            val success = try {
                r2ScanResult = container.gameRepository.scanFromR2(
                    container.r2GameFetcher,
                    container.r2Config.gamesPrefix,
                )
                container.settingsRepository.setR2Enabled(true)
                container.settingsRepository.setR2LastSync(System.currentTimeMillis())
                true
            } catch (e: Exception) {
                r2Error = e.message ?: e.javaClass.simpleName
                false
            }
            r2Scanning = false
            onDone(success)
        }
    }

    // Reavalia a permissão ao voltar da tela de configurações do sistema.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = StorageUtils.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { StorageUtils.treeUriToPath(it) }?.let { romRoot = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(stringResource(R.string.bem_vindo), style = MaterialTheme.typography.headlineMedium, color = EmuHubRed)
        Text(
            stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = EmuHubGray,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        // Passo 1 — permissão
        Text(stringResource(R.string.passo_permissao_titulo), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.passo_permissao_texto),
            style = MaterialTheme.typography.bodySmall,
            color = EmuHubGray,
        )
        Spacer(Modifier.height(8.dp))
        if (hasPermission) {
            Text(stringResource(R.string.permissao_concedida), color = EmuHubRed)
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.startActivity(StorageUtils.allFilesAccessIntent(context))
                    } else {
                        legacyPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmuHubRed),
            ) { Text(stringResource(R.string.conceder_permissao)) }
        }

        Spacer(Modifier.height(24.dp))

        // Passo 2 — pasta
        Text(stringResource(R.string.passo_pasta_titulo), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.passo_pasta_texto),
            style = MaterialTheme.typography.bodySmall,
            color = EmuHubGray,
        )
        Spacer(Modifier.height(8.dp))
        Text(romRoot, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = hasPermission) {
                Text(stringResource(R.string.escolher_pasta))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = { romRoot = SettingsRepository.defaultRomRoot() },
                enabled = hasPermission,
            ) { Text(stringResource(R.string.usar_pasta_padrao)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = createSkeleton, onCheckedChange = { createSkeleton = it })
            Text(stringResource(R.string.criar_estrutura), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        // Passo 3 — scan
        Text(stringResource(R.string.passo_scan_titulo), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = EmuHubRed)
            Text(
                text = stringResource(R.string.escaneando) + " " + scanSystem,
                style = MaterialTheme.typography.bodySmall,
                color = EmuHubGray,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        scanResult?.let { count ->
            Text(
                text = stringResource(R.string.scan_resultado, count, container.catalog.systems.size),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        scanning = true
                        container.settingsRepository.setRomRoot(romRoot)
                        if (createSkeleton) {
                            container.catalog.systems.forEach { system ->
                                File(romRoot, "roms/${system.folder}").mkdirs()
                            }
                        }
                        scanResult = container.gameRepository.scanAndSync(romRoot) { progress ->
                            scanSystem = progress.currentSystem
                        }
                        scanning = false
                    }
                },
                enabled = hasPermission && !scanning,
                colors = ButtonDefaults.buttonColors(containerColor = EmuHubRed),
            ) { Text(stringResource(R.string.escanear)) }

            Button(
                onClick = {
                    scope.launch {
                        container.settingsRepository.setRomRoot(romRoot)
                        container.settingsRepository.setOnboardingDone()
                        onFinished()
                    }
                },
                enabled = hasPermission && !scanning && !r2Scanning &&
                    (scanResult != null || r2ScanResult != null),
                colors = ButtonDefaults.buttonColors(containerColor = EmuHubRed),
            ) { Text(stringResource(R.string.comecar)) }
        }

        Spacer(Modifier.height(24.dp))

        // Passo 4 — jogos na nuvem (R2)
        Text(stringResource(R.string.passo_r2_titulo), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.passo_r2_texto),
            style = MaterialTheme.typography.bodySmall,
            color = EmuHubGray,
        )
        Spacer(Modifier.height(8.dp))
        if (r2Scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = EmuHubRed)
            Text(
                stringResource(R.string.r2_conectando),
                style = MaterialTheme.typography.bodySmall,
                color = EmuHubGray,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        r2ScanResult?.let { count ->
            Text(
                stringResource(R.string.r2_scan_resultado, count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        r2Error?.let { error ->
            Text(
                stringResource(R.string.r2_erro, error),
                style = MaterialTheme.typography.bodySmall,
                color = EmuHubRed,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { connectR2() },
                enabled = hasPermission && !r2Scanning && !scanning,
            ) { Text(stringResource(R.string.conectar_r2)) }

            // Pula o scan local: conecta no R2 e já entra no app.
            Button(
                onClick = {
                    connectR2 { success ->
                        if (success) {
                            container.settingsRepository.setRomRoot(romRoot)
                            container.settingsRepository.setOnboardingDone()
                            onFinished()
                        }
                    }
                },
                enabled = hasPermission && !r2Scanning && !scanning,
                colors = ButtonDefaults.buttonColors(containerColor = EmuHubRed),
            ) { Text(stringResource(R.string.usar_apenas_r2)) }
        }
    }
}
