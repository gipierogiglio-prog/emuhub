package com.emuhub.app.ui.gamelist

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emuhub.app.R
import com.emuhub.app.data.catalog.EmulatorDef
import com.emuhub.app.data.db.GameEntity
import com.emuhub.app.domain.model.LaunchResult
import com.emuhub.app.util.EmuHubLogger
import com.emuhub.app.ui.components.BoxArt
import com.emuhub.app.ui.components.FocusableCard
import com.emuhub.app.ui.components.parseSystemColor
import com.emuhub.app.ui.navigation.LocalAppContainer
import com.emuhub.app.ui.navigation.Routes
import com.emuhub.app.ui.theme.EmuHubGray
import com.emuhub.app.ui.theme.EmuHubRed
import com.emuhub.app.ui.theme.EmuHubSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GameListScreen(filter: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val gamesFlow: Flow<List<GameEntity>> = remember(filter) {
        when (filter) {
            Routes.FILTER_ALL -> container.gameRepository.observeAll()
            Routes.FILTER_FAVORITES -> container.gameRepository.observeFavorites()
            Routes.FILTER_RECENTS -> container.gameRepository.observeRecents()
            else -> container.gameRepository.observeBySystem(filter)
        }
    }
    val games by gamesFlow.collectAsState(initial = emptyList())

    val title = when (filter) {
        Routes.FILTER_ALL -> stringResource(R.string.todos_os_jogos)
        Routes.FILTER_FAVORITES -> stringResource(R.string.favoritos)
        Routes.FILTER_RECENTS -> stringResource(R.string.recentes)
        else -> container.catalog.systemById(filter)?.name ?: filter
    }

    var missingEmulator by remember { mutableStateOf<EmulatorDef?>(null) }
    var installFailed by remember { mutableStateOf<EmulatorDef?>(null) }
    var launchError by remember { mutableStateOf<Pair<String?, String?>?>(null) } // (mensagem, core)
    var downloading by remember { mutableStateOf<Pair<String, Float>?>(null) } // (nome, progresso 0..1)
    var installing by remember { mutableStateOf<Pair<String, Float>?>(null) } // (emulador, progresso 0..1)
    var installReady by remember { mutableStateOf<String?>(null) } // emulador pronto pra instalar
    var searchQuery by remember { mutableStateOf("") }
    var r2SearchResults by remember { mutableStateOf<List<GameEntity>?>(null) }
    var r2Searching by remember { mutableStateOf(false) }

    // Filtra jogos pelo texto da pesquisa
    val filteredGames = remember(games, searchQuery) {
        if (searchQuery.isBlank()) games
        else games.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.systemId.contains(searchQuery, ignoreCase = true)
        }
    }

    // Mescla resultados locais com resultados da busca R2 (deduplicando por remoteKey)
    val mergedGames = remember(filteredGames, r2SearchResults) {
        val localKeys = filteredGames.filter { it.isRemote }.mapNotNull { it.remoteKey }.toSet()
        val r2New = (r2SearchResults ?: emptyList()).filter { it.remoteKey !in localKeys }
        filteredGames + r2New
    }

    // Busca no R2 quando o usuário digita (com debounce)
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            r2SearchResults = emptyList()
            r2Searching = false
            return@LaunchedEffect
        }
        r2Searching = true
        delay(300)
        val results = withContext(Dispatchers.IO) {
            try {
                container.gameRepository.searchR2(
                    searchQuery,
                    container.r2GameFetcher,
                    container.r2Config.gamesPrefix,
                )
            } catch (e: Exception) {
                EmuHubLogger.e("GameList", "R2 search failed", e)
                emptyList()
            }
        }
        r2SearchResults = results
        r2Searching = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(vertical = 24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            FocusableCard(onClick = onBack, backgroundColor = EmuHubSurfaceHigh) {
                Text(
                    "‹ " + stringResource(R.string.voltar),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.jogos_count, games.size),
                style = MaterialTheme.typography.bodyMedium,
                color = EmuHubGray,
            )
            if (searchQuery.isNotBlank() && (r2Searching || (r2SearchResults?.isNotEmpty() == true))) {
                Text(
                    if (r2Searching) " 🔍 R2..."
                    else " · +${r2SearchResults?.size ?: 0} R2",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r2Searching) EmuHubGray else EmuHubRed,
                )
            }
        }

        // Campo de pesquisa
        Spacer(Modifier.height(8.dp))
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Pesquisar jogos...") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                // Ao focar via D-pad, abre o teclado virtual
                .onFocusChanged { if (it.isFocused) keyboardController?.show() }
                // Botão B/Back do controle sai do campo de texto
                .onKeyEvent { event ->
                    if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyUp &&
                        (event.key == Key.Escape || event.key == Key.Back))
                    {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        true
                    } else false
                },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = EmuHubSurfaceHigh,
                unfocusedContainerColor = EmuHubSurfaceHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.height(16.dp))

        if (mergedGames.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (searchQuery.isNotBlank()) {
                    Text(
                        "Nenhum jogo para \"$searchQuery\"",
                        color = EmuHubGray,
                    )
                } else {
                    Text(
                        stringResource(R.string.nenhum_jogo_sistema, title),
                        color = EmuHubGray,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(mergedGames, key = { "${it.id}_${it.remoteKey ?: it.path}" }) { game ->
                    val system = container.catalog.systemById(game.systemId)
                    FocusableCard(
                        onClick = {
                            scope.launch {
                                // Download do game remoto na coroutine (não bloqueia UI)
                                val gameToLaunch = if (game.isRemote && game.remoteKey != null) {
                                    downloading = game.displayName to 0f
                                    try {
                                        val localFile = container.r2Downloader.download(game.remoteKey) { p ->
                                            downloading = game.displayName to p
                                        }
                                        downloading = null
                                        game.copy(path = localFile.absolutePath, isRemote = false, remoteKey = null)
                                    } catch (e: Exception) {
                                        EmuHubLogger.e("GameList", "Falha download ${game.displayName}", e)
                                        downloading = null
                                        game
                                    }
                                } else {
                                    game
                                }

                                // Tenta emulador nativo primeiro (se tiver core embutido)
                                val nativeLaunched = container.gameLauncher.tryLaunchNative(gameToLaunch)
                                if (nativeLaunched) {
                                    return@launch
                                }
                                var installedEmulator: String? = null
                                val result = container.gameLauncher.launch(
                                    gameToLaunch,
                                    onDownloadProgress = { progress ->
                                        installing = null
                                        downloading = gameToLaunch.displayName to progress
                                    },
                                    onInstallProgress = { emulatorName, progress ->
                                        // O download do jogo só recomeça após instalar.
                                        installedEmulator = emulatorName
                                        downloading = null
                                        installing = emulatorName to progress
                                    },
                                )
                                downloading = null
                                installing = null
                                when (result) {
                                    is LaunchResult.Launched -> {
                                        if (installedEmulator != null) {
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.instalacao_concluida,
                                                    installedEmulator,
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    is LaunchResult.EmulatorMissing -> missingEmulator = result.def
                                    is LaunchResult.NeedsInstallApk -> {
                                        installing = null
                                        val ok = container.emulatorInstaller.openInstaller(
                                            result.apkFile, context
                                        )
                                        if (ok) {
                                            installReady = result.def.name
                                        } else {
                                            installFailed = result.def
                                        }
                                    }
                                    is LaunchResult.InstallFailed -> installFailed = result.def
                                    is LaunchResult.Error -> {
                                        val core = container.catalog
                                            .emulatorsFor(game.systemId)
                                            .firstNotNullOfOrNull { it.second.core }
                                        launchError = result.message to core
                                    }
                                }
                            }
                        },
                        onLongClick = {
                            scope.launch {
                                container.gameRepository.setFavorite(game.id, !game.isFavorite)
                            }
                        },
                    ) { focused ->
                        Column {
                            BoxArt(
                                title = game.displayName,
                                systemColor = parseSystemColor(system?.color ?: "#444444"),
                                systemShortName = system?.shortName ?: "?",
                                coverPath = game.coverPath,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                if (game.isFavorite) {
                                    Text("★ ", color = EmuHubRed)
                                }
                                if (game.isRemote) {
                                    // Nuvem cinza = ainda não baixado; vermelha = já no cache.
                                    val cached = remember(game.id, downloading == null) {
                                        game.remoteKey?.let { container.r2CacheManager.get(it) } != null
                                    }
                                    Text("☁ ", color = if (cached) EmuHubRed else EmuHubGray)
                                }
                                Text(
                                    game.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    downloading?.let { (name, progress) ->
        AlertDialog(
            onDismissRequest = { /* download em andamento; não fecha */ },
            title = { Text(stringResource(R.string.baixando_jogo, name)) },
            text = {
                Column {
                    if (progress in 0f..1f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = EmuHubRed,
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmuHubGray,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = EmuHubRed)
                    }
                }
            },
            confirmButton = {},
        )
    }

    installing?.let { (emulatorName, progress) ->
        AlertDialog(
            onDismissRequest = { /* download em andamento; não fecha */ },
            title = { Text(stringResource(R.string.instala_emulador, emulatorName)) },
            text = {
                Column {
                    if (progress in 0f..1f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = EmuHubRed,
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmuHubGray,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = EmuHubRed)
                    }
                }
            },
            confirmButton = {},
        )
    }

    installReady?.let { emulatorName ->
        AlertDialog(
            onDismissRequest = { installReady = null },
            title = { Text(stringResource(R.string.instalacao_concluida, emulatorName)) },
            text = { Text(stringResource(R.string.instalacao_voltar, emulatorName)) },
            confirmButton = {
                TextButton(onClick = { installReady = null }) {
                    Text(stringResource(R.string.ok), color = EmuHubRed)
                }
            },
        )
    }

    // Se o launch falhar, mostra o erro pra ajudar no debug
    launchError?.let { (msg, core) ->
        AlertDialog(
            onDismissRequest = { launchError = null },
            title = { Text(stringResource(R.string.erro_ao_lancar)) },
            text = {
                Column {
                    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    core?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dica_core_retroarch, core),
                            style = MaterialTheme.typography.bodySmall,
                            color = EmuHubRed,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { launchError = null }) {
                    Text(stringResource(R.string.ok), color = EmuHubRed)
                }
            },
        )
    }

    installFailed?.let { def ->
        AlertDialog(
            onDismissRequest = { installFailed = null },
            title = { Text(stringResource(R.string.falha_instalacao, def.name)) },
            text = {
                Text(stringResource(R.string.emulador_nao_instalado_msg, title, def.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(container.gameLauncher.installIntent(def))
                    installFailed = null
                }) { Text(stringResource(R.string.instalar), color = EmuHubRed) }
            },
            dismissButton = {
                TextButton(onClick = { installFailed = null }) {
                    Text(stringResource(R.string.cancelar))
                }
            },
        )
    }

    missingEmulator?.let { def ->
        AlertDialog(
            onDismissRequest = { missingEmulator = null },
            title = { Text(stringResource(R.string.emulador_nao_instalado)) },
            text = {
                Text(stringResource(R.string.emulador_nao_instalado_msg, title, def.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(container.gameLauncher.installIntent(def))
                    missingEmulator = null
                }) { Text(stringResource(R.string.instalar), color = EmuHubRed) }
            },
            dismissButton = {
                TextButton(onClick = { missingEmulator = null }) {
                    Text(stringResource(R.string.cancelar))
                }
            },
        )
    }
}
