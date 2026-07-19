package com.emuhub.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emuhub.app.R
import com.emuhub.app.data.db.GameEntity
import com.emuhub.app.ui.components.BoxArt
import com.emuhub.app.ui.components.FocusableCard
import com.emuhub.app.ui.components.parseSystemColor
import com.emuhub.app.ui.navigation.LocalAppContainer
import com.emuhub.app.ui.navigation.Routes
import com.emuhub.app.ui.theme.EmuHubGray
import com.emuhub.app.ui.theme.EmuHubRed
import com.emuhub.app.ui.theme.EmuHubSurfaceHigh

@Composable
fun HomeScreen(
    onOpenSystem: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val counts by container.gameRepository.observeSystemCounts().collectAsState(initial = emptyList())
    val recents by container.gameRepository.observeRecents().collectAsState(initial = emptyList())
    val favorites by container.gameRepository.observeFavorites().collectAsState(initial = emptyList())

    val countsById = counts.associate { it.systemId to it.count }
    val systemsWithGames = container.catalog.systems.filter { (countsById[it.id] ?: 0) > 0 }
    val totalGames = counts.sumOf { it.count }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.emuhub_logo),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "EmuHub",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = EmuHubRed,
                )
                Spacer(Modifier.weight(1f))
                FocusableCard(onClick = onOpenSettings, backgroundColor = EmuHubSurfaceHigh) {
                    Text(
                        stringResource(R.string.configuracoes),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            if (systemsWithGames.isEmpty()) {
                Text(
                    stringResource(R.string.nenhum_sistema),
                    color = EmuHubGray,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            } else {
                SectionTitle(stringResource(R.string.sistemas))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(systemsWithGames, key = { it.id }) { system ->
                        val color = parseSystemColor(system.color)
                        FocusableCard(onClick = { onOpenSystem(system.id) }) {
                            Column(
                                modifier = Modifier
                                    .width(170.dp)
                                    .height(110.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.4f))
                                        )
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    system.shortName,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                )
                                Column {
                                    Text(
                                        system.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        maxLines = 1,
                                    )
                                    Text(
                                        stringResource(R.string.jogos_count, countsById[system.id] ?: 0),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        FocusableCard(onClick = { onOpenSystem(Routes.FILTER_ALL) }) {
                            Column(
                                modifier = Modifier
                                    .width(170.dp)
                                    .height(110.dp)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("★", fontSize = 28.sp, color = EmuHubRed)
                                Column {
                                    Text(
                                        stringResource(R.string.todos_os_jogos),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        stringResource(R.string.jogos_count, totalGames),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmuHubGray,
                                    )
                                }
                            }
                        }
                    }
                }

                if (recents.isNotEmpty()) {
                    GameRow(stringResource(R.string.recentes), recents, onOpenSystem)
                }
                if (favorites.isNotEmpty()) {
                    GameRow(stringResource(R.string.favoritos), favorites, onOpenSystem)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
    )
}

@Composable
private fun GameRow(
    title: String,
    games: List<GameEntity>,
    onOpenSystem: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    SectionTitle(title)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(games, key = { it.id }) { game ->
            val system = container.catalog.systemById(game.systemId)
            FocusableCard(onClick = { onOpenSystem(game.systemId) }) {
                Column(modifier = Modifier.width(110.dp)) {
                    BoxArt(
                        title = game.displayName,
                        systemColor = parseSystemColor(system?.color ?: "#444444"),
                        systemShortName = system?.shortName ?: "?",
                        coverPath = game.coverPath,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                    )
                }
            }
        }
    }
}
