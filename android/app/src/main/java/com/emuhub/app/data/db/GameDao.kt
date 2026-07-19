package com.emuhub.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class SystemCount(val systemId: String, val count: Int)

@Dao
interface GameDao {

    @Query("SELECT * FROM games ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE systemId = :systemId ORDER BY displayName COLLATE NOCASE")
    fun observeBySystem(systemId: String): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY displayName COLLATE NOCASE")
    fun observeFavorites(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecents(limit: Int = 12): Flow<List<GameEntity>>

    @Query("SELECT systemId, COUNT(*) AS count FROM games GROUP BY systemId")
    fun observeSystemCounts(): Flow<List<SystemCount>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun byId(id: Long): GameEntity?

    @Query("SELECT * FROM games")
    suspend fun allOnce(): List<GameEntity>

    @Query("SELECT * FROM games WHERE isRemote = 1 ORDER BY displayName COLLATE NOCASE")
    fun observeRemote(): Flow<List<GameEntity>>

    @Query("DELETE FROM games WHERE isRemote = 1")
    suspend fun deleteAllRemote()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("UPDATE games SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE games SET lastPlayedAt = :playedAt, playCount = playCount + 1 WHERE id = :id")
    suspend fun recordPlay(id: Long, playedAt: Long)
}
