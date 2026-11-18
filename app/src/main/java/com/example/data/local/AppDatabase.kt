package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        NoteEntity::class,
        ParagraphFileEntity::class,
        VocabListEntity::class,
        VocabWordEntity::class,
        WeakWordEntity::class,
        TranslationCacheEntity::class,
        UserStatsEntity::class,
        PendingDeleteEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "revision_alarm.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
