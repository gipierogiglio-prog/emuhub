package com.emuhub.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

// v2: colunas isRemote/remoteKey para jogos hospedados no R2.
// Migração destrutiva (fallback no AppContainer) é aceitável: o banco é
// recriado por um novo scan e só perde favoritos/histórico.
@Database(entities = [GameEntity::class], version = 2, exportSchema = false)
abstract class EmuHubDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
}
