package com.emuhub.app.data.repo

import com.emuhub.app.data.db.GameDao
import com.emuhub.app.data.db.GameEntity
import com.emuhub.app.data.db.SystemCount
import com.emuhub.app.data.r2.R2GameFetcher
import com.emuhub.app.data.scanner.RomScanner
import com.emuhub.app.data.scanner.ScanProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class GameRepository(
    private val gameDao: GameDao,
    private val scanner: RomScanner,
) {
    fun observeAll(): Flow<List<GameEntity>> = gameDao.observeAll()
    fun observeBySystem(systemId: String): Flow<List<GameEntity>> = gameDao.observeBySystem(systemId)
    fun observeFavorites(): Flow<List<GameEntity>> = gameDao.observeFavorites()
    fun observeRecents(): Flow<List<GameEntity>> = gameDao.observeRecents()
    fun observeSystemCounts(): Flow<List<SystemCount>> = gameDao.observeSystemCounts()

    suspend fun byId(id: Long): GameEntity? = gameDao.byId(id)

    suspend fun setFavorite(id: Long, favorite: Boolean) = gameDao.setFavorite(id, favorite)

    suspend fun recordPlay(id: Long) = gameDao.recordPlay(id, System.currentTimeMillis())

    /**
     * Escaneia a pasta raiz e sincroniza com o banco: insere novos, remove os que
     * sumiram do disco e preserva favoritos/histórico dos que continuam (chave = path).
     */
    suspend fun scanAndSync(rootPath: String, onProgress: (ScanProgress) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val scanned = scanner.scan(File(rootPath), onProgress)
            // Só considera os jogos locais: os remotos (R2) são sincronizados
            // por scanFromR2 e não podem ser apagados por um rescan de disco.
            val existingByPath = gameDao.allOnce().filter { !it.isRemote }.associateBy { it.path }
            val scannedPaths = scanned.map { it.file.absolutePath }.toSet()

            val toInsert = scanned.map { rom ->
                val existing = existingByPath[rom.file.absolutePath]
                GameEntity(
                    id = existing?.id ?: 0,
                    systemId = rom.systemId,
                    path = rom.file.absolutePath,
                    fileName = rom.file.name,
                    displayName = rom.displayName,
                    sizeBytes = rom.file.length(),
                    lastModified = rom.file.lastModified(),
                    coverPath = rom.coverPath,
                    isFavorite = existing?.isFavorite ?: false,
                    lastPlayedAt = existing?.lastPlayedAt,
                    playCount = existing?.playCount ?: 0,
                )
            }
            val toDelete = existingByPath.keys.filter { it !in scannedPaths }

            if (toDelete.isNotEmpty()) gameDao.deleteByPaths(toDelete)
            if (toInsert.isNotEmpty()) gameDao.insertAll(toInsert)
            scanned.size
        }

    /**
     * Sincroniza os jogos hospedados no R2, espelhando a lógica do scanAndSync:
     * insere novos, remove os que sumiram do bucket e preserva favoritos/histórico
     * (chave = path, que para remotos é "r2://<key>").
     */
    suspend fun scanFromR2(
        fetcher: R2GameFetcher,
        prefix: String,
        onProgress: (ScanProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val remote = fetcher.fetchGames(prefix, onProgress)
        val existingByPath = gameDao.allOnce().filter { it.isRemote }.associateBy { it.path }
        val remotePaths = remote.map { remotePath(it.key) }.toSet()

        val toInsert = remote.map { rom ->
            val path = remotePath(rom.key)
            val existing = existingByPath[path]
            GameEntity(
                id = existing?.id ?: 0,
                systemId = rom.systemId,
                path = path,
                fileName = rom.fileName,
                displayName = rom.displayName,
                sizeBytes = rom.sizeBytes,
                lastModified = rom.lastModified,
                coverPath = existing?.coverPath,
                isFavorite = existing?.isFavorite ?: false,
                lastPlayedAt = existing?.lastPlayedAt,
                playCount = existing?.playCount ?: 0,
                isRemote = true,
                remoteKey = rom.key,
            )
        }
        val toDelete = existingByPath.keys.filter { it !in remotePaths }

        if (toDelete.isNotEmpty()) gameDao.deleteByPaths(toDelete)
        if (toInsert.isNotEmpty()) gameDao.insertAll(toInsert)
        remote.size
    }

    fun observeRemoteOnly(): Flow<List<GameEntity>> = gameDao.observeRemote()

    /** Remove todos os jogos remotos do banco (usado ao desativar o R2). */
    suspend fun removeAllRemote() = gameDao.deleteAllRemote()

    /**
     * Busca jogos no R2 cujo nome corresponda à [query], sem tocar no banco.
     * Útil para pesquisa ao vivo: o resultado é mapeado para GameEntity mas
     * NÃO é persistido — o download sob demanda cuida de trazer o jogo.
     */
    suspend fun searchR2(
        query: String,
        fetcher: R2GameFetcher,
        prefix: String,
    ): List<GameEntity> = withContext(Dispatchers.IO) {
        val remote = fetcher.searchGames(query, prefix)
        remote.map { rom ->
            GameEntity(
                systemId = rom.systemId,
                path = remotePath(rom.key),
                fileName = rom.fileName,
                displayName = rom.displayName,
                sizeBytes = rom.sizeBytes,
                lastModified = rom.lastModified,
                isRemote = true,
                remoteKey = rom.key,
            )
        }
    }

    companion object {
        /** Path sintético dos jogos remotos, para não colidir com paths locais. */
        fun remotePath(key: String): String = "r2://$key"
    }
}
