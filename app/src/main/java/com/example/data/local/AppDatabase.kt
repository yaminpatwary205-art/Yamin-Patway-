package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        PendingDeleteEntity::class,
        RevisionAlarmEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `revision_alarms` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `scheduleType` TEXT NOT NULL,
                        `intervalMinutes` INTEGER NOT NULL,
                        `hourOfDay` INTEGER NOT NULL,
                        `minute` INTEGER NOT NULL,
                        `repeatDaysCsv` TEXT NOT NULL,
                        `useActiveHours` INTEGER NOT NULL,
                        `activeStartMinutes` INTEGER NOT NULL,
                        `activeEndMinutes` INTEGER NOT NULL,
                        `selectedBookIds` TEXT NOT NULL,
                        `selectedChapterIds` TEXT NOT NULL,
                        `selectedNoteIds` TEXT NOT NULL,
                        `selectedParagraphIds` TEXT NOT NULL,
                        `selectedVocabListIds` TEXT NOT NULL,
                        `rotationCursor` INTEGER NOT NULL,
                        `lastTriggeredEpochMs` INTEGER NOT NULL,
                        `snoozedUntilEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "revision_alarm.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
