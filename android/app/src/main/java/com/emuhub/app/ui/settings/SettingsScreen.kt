package com.emuhub.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.hardware.input.InputManager
import android.view.InputDevice
import com.emuhub.app.R
import com.emuhub.app.ui.components.FocusableCard
import com.emuhub.app.ui.navigation.LocalAppContainer
import com.emuhub.app.ui.theme.EmuHubGray
import com.emuhub.app.ui.theme.EmuHubRed
import com.emuhub.app.ui.theme.EmuHubSurfaceHigh
import com.emuhub.app.util.EmuHubLogger
import com.emuhub.app.util.StorageUtils
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val romRoot by container.settingsRepository.romRoot.collectAsState(initial = "")
    var scanning by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<Int?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { StorageUtils.treeUriToPath(it) }?.let { path ->
            scope.launch { container.settingsRepository.setRomRoot(path) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FocusableCard(onClick = onBack, backgroundColor = EmuHubSurfaceHigh) {
                Text(
                    "‹ " + stringResource(R.string.voltar),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.configuracoes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Pasta de jogos
        Text(stringResource(R.string.pasta_de_roms), style = MaterialTheme.typography.titleMedium, color = EmuHubRed)
        FocusableCard(onClick = { folderPicker.launch(null) }, backgroundColor = EmuHubSurfaceHigh) {
            Text(romRoot, modifier = Modifier.padding(12.dp))
        }

        Spacer(Modifier.height(20.dp))

        // Reescanear
        FocusableCard(
            onClick = {
                if (!scanning) scope.launch {
                    scanning = true
                    scanResult = container.gameRepository.scanAndSync(romRoot)
                    scanning = false
                }
            },
            backgroundColor = EmuHubSurfaceHigh,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.reescanear))
                if (scanning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = EmuHubRed,
                    )
                }
                scanResult?.let {
                    Text(
                        stringResource(R.string.scan_resultado, it, container.catalog.systems.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = EmuHubGray,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Armazenamento remoto (R2)
        R2Section()

        Spacer(Modifier.height(24.dp))

        // Controle físico
        ControllerSection()

        Spacer(Modifier.height(24.dp))

        // Sobre
        Text(stringResource(R.string.sobre), style = MaterialTheme.typography.titleMedium, color = EmuHubRed)
        Text(
            stringResource(R.string.sobre_texto),
            style = MaterialTheme.typography.bodySmall,
            color = EmuHubGray,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))

        // Atualizações
        Text("🔄 Atualizações", style = MaterialTheme.typography.titleMedium, color = EmuHubRed)
        Spacer(Modifier.height(8.dp))
        UpdateSection()

        Spacer(Modifier.height(12.dp))

        // Logs / Telemetria
        FocusableCard(
            onClick = {
                val logs = com.emuhub.app.util.EmuHubLogger.getLogFiles()
                if (logs.isNotEmpty()) {
                    val logText = logs.joinToString("\n${"=".repeat(60)}\n") { file ->
                        "📄 ${file.name} (${file.length() / 1024}KB)\n${"=".repeat(60)}\n${file.readText().takeLast(5000)}"
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "EmuHub Logs")
                        putExtra(android.content.Intent.EXTRA_TEXT, logText)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Compartilhar logs"))
                }
            },
            backgroundColor = EmuHubSurfaceHigh,
        ) {
            Text(
                "📋 Enviar logs de diagnóstico",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Os logs ficam em: filesDir/emuhub-logs/",
            style = MaterialTheme.typography.bodySmall,
            color = EmuHubGray,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun R2Section() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val r2Enabled by container.settingsRepository.r2Enabled.collectAsState(initial = false)
    val lastSync by container.settingsRepository.r2LastSync.collectAsState(initial = 0L)
    var r2Scanning by remember { mutableStateOf(false) }
    var r2ScanResult by remember { mutableStateOf<Int?>(null) }
    var r2Error by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var cacheCleared by remember { mutableStateOf(false) }

    // Calcula o tamanho do cache fora da main thread ao abrir a tela.
    LaunchedEffect(cacheCleared, r2ScanResult) {
        cacheBytes = withContext(Dispatchers.IO) { container.r2CacheManager.size() }
    }

    Text(
        stringResource(R.string.armazenamento_remoto),
        style = MaterialTheme.typography.titleMedium,
        color = EmuHubRed,
    )
    Spacer(Modifier.height(8.dp))

    // Status + liga/desliga
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (r2Enabled) stringResource(R.string.r2_status_conectado)
                else stringResource(R.string.r2_status_desconectado),
                style = MaterialTheme.typography.bodyMedium,
                color = if (r2Enabled) EmuHubRed else EmuHubGray,
            )
            Text(
                if (lastSync > 0L) {
                    stringResource(
                        R.string.r2_ultima_sync,
                        DateFormat.getDateTimeInstance().format(Date(lastSync)),
                    )
                } else {
                    stringResource(R.string.r2_nunca_sincronizado)
                },
                style = MaterialTheme.typography.bodySmall,
                color = EmuHubGray,
            )
        }
        Switch(
            checked = r2Enabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    container.settingsRepository.setR2Enabled(enabled)
                    // Ao desligar, os jogos remotos saem da lista.
                    if (!enabled) container.gameRepository.removeAllRemote()
                }
            },
        )
    }

    Spacer(Modifier.height(8.dp))

    // Reescanear R2
    FocusableCard(
        onClick = {
            if (!r2Scanning && r2Enabled) scope.launch {
                r2Scanning = true
                r2Error = null
                try {
                    r2ScanResult = container.gameRepository.scanFromR2(
                        container.r2GameFetcher,
                        container.r2Config.gamesPrefix,
                    )
                    container.settingsRepository.setR2LastSync(System.currentTimeMillis())
                } catch (e: Exception) {
                    r2Error = e.message ?: e.javaClass.simpleName
                }
                r2Scanning = false
            }
        },
        backgroundColor = EmuHubSurfaceHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.reescanear_r2))
            if (r2Scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = EmuHubRed,
                )
            }
            r2ScanResult?.let {
                Text(
                    stringResource(R.string.r2_scan_resultado, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuHubGray,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            r2Error?.let {
                Text(
                    stringResource(R.string.r2_erro, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuHubRed,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // Limpar cache
    FocusableCard(
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { container.r2CacheManager.clear() }
                cacheCleared = true
            }
        },
        backgroundColor = EmuHubSurfaceHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.limpar_cache))
            Text(
                if (cacheCleared) stringResource(R.string.cache_limpo)
                else stringResource(R.string.cache_usado, formatBytes(cacheBytes ?: 0L)),
                style = MaterialTheme.typography.bodySmall,
                color = EmuHubGray,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun ControllerSection() {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val autoHide by container.settingsRepository.autoHideTouchOverlay.collectAsState(initial = true)
    var gamepads by remember { mutableStateOf(connectedGamepadNames()) }

    // Atualiza a lista ao conectar/desconectar controle com a tela aberta
    DisposableEffect(Unit) {
        val im = context.getSystemService(android.content.Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { gamepads = connectedGamepadNames() }
            override fun onInputDeviceRemoved(deviceId: Int) { gamepads = connectedGamepadNames() }
            override fun onInputDeviceChanged(deviceId: Int) { gamepads = connectedGamepadNames() }
        }
        im.registerInputDeviceListener(listener, null)
        onDispose { im.unregisterInputDeviceListener(listener) }
    }

    Text(
        stringResource(R.string.controle),
        style = MaterialTheme.typography.titleMedium,
        color = EmuHubRed,
    )
    Spacer(Modifier.height(8.dp))

    Text(
        stringResource(R.string.controle_info),
        style = MaterialTheme.typography.bodySmall,
        color = EmuHubGray,
    )
    Spacer(Modifier.height(4.dp))
    if (gamepads.isEmpty()) {
        Text(
            stringResource(R.string.controle_nenhum),
            style = MaterialTheme.typography.bodyMedium,
            color = EmuHubGray,
        )
    } else {
        gamepads.forEach { name ->
            Text(
                "🎮 " + stringResource(R.string.controle_conectado, name),
                style = MaterialTheme.typography.bodyMedium,
                color = EmuHubRed,
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ocultar_touch_overlay),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.ocultar_touch_overlay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = EmuHubGray,
            )
        }
        Switch(
            checked = autoHide,
            onCheckedChange = { enabled ->
                scope.launch { container.settingsRepository.setAutoHideTouchOverlay(enabled) }
            },
        )
    }
}

/** Nomes dos gamepads/joysticks físicos conectados (Bluetooth ou USB). */
private fun connectedGamepadNames(): List<String> =
    InputDevice.getDeviceIds().toList()
        .mapNotNull { InputDevice.getDevice(it) }
        .filter { device ->
            !device.isVirtual && (
                device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            )
        }
        .map { it.name }

@Composable
private fun UpdateSection() {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<com.emuhub.app.domain.GitHubRelease?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    val updater = remember {
        com.emuhub.app.domain.AutoUpdater(context, 52L)  // versionCode
    }

    FocusableCard(
        onClick = {
            if (checking || downloading) return@FocusableCard
            scope.launch {
                checking = true
                error = null
                release = null
                downloadedFile = null
                installError = null
                val result = updater.checkForUpdate()
                if (result == null && error == null) {
                    error = "Você já está na versão mais recente"
                }
                release = result
                checking = false
            }
        },
        backgroundColor = EmuHubSurfaceHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (checking) {
                Text("Verificando...")
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = EmuHubRed,
                )
            } else if (downloading) {
                Text("Baixando atualização...")
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = EmuHubRed,
                )
            } else if (downloadedFile != null) {
                Text("✅ Atualização baixada!", color = EmuHubRed)
                Spacer(Modifier.height(4.dp))
                FocusableCard(
                    onClick = {
                        if (downloadedFile != null) {
                            val ok = updater.installApk(downloadedFile!!)
                            if (!ok) installError = "Falha ao abrir instalador"
                        }
                    },
                    backgroundColor = EmuHubRed,
                ) {
                    Text(
                        "Instalar agora",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            } else {
                Text(release?.let { "📦 ${it.tagName} disponível (${formatBytes2(it.apkSize)})" }
                    ?: error ?: "Toque para verificar atualizações",
                    color = if (release != null) EmuHubRed
                    else if (error != null) EmuHubGray
                    else EmuHubGray,
                )
                installError?.let {
                    Text(it, color = EmuHubRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // Quando encontra uma release, já começa a baixar
    LaunchedEffect(release) {
        val rel = release ?: return@LaunchedEffect
        downloading = true
        val file = updater.downloadApk(rel)
        downloadedFile = file
        downloading = false
    }
}

private fun formatBytes2(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
