package com.emuhub.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "games", indices = [Index(value = ["path"], unique = true)])
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val systemId: String,
    val path: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val coverPath: String? = null,
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    /** Jogo hospedado no R2 (baixado sob demanda ao jogar). */
    val isRemote: Boolean = false,
    /** Key do objeto no bucket R2, quando isRemote = true. */
    val remoteKey: String? = null,
)
