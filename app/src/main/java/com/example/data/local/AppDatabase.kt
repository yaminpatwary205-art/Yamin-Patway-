package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import java.util.concurrent.ConcurrentHashMap

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
    version = 4,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        private const val PREFS_NAME = "revision_alarm_prefs"
        private const val KEY_ACTIVE_DB_USER_ID = "active_db_user_id"
        private const val KEY_SKIP_LOGIN = "skip_login"

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `imagePath` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val instances = ConcurrentHashMap<String, AppDatabase>()

        fun dbNameForUser(userId: String?): String {
            val cleanUid = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return "revision_alarm_local.db"
            val safeUid = cleanUid.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
            return "revision_alarm_u_$safeUid.db"
        }

        fun setActiveUserScope(context: Context, userId: String?) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val clean = userId?.trim()?.takeIf { it.isNotEmpty() }
            prefs.edit().apply {
                if (clean != null) {
                    putString(KEY_ACTIVE_DB_USER_ID, clean)
                } else {
                    remove(KEY_ACTIVE_DB_USER_ID)
                }
            }.apply()
        }

        fun resolveActiveUserId(context: Context): String? {
            val appContext = context.applicationContext
            val firebaseUid = if (runCatching { FirebaseApp.getApps(appContext).isNotEmpty() }.getOrDefault(false)) {
                runCatching { Firebase.auth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            if (firebaseUid != null) {
                setActiveUserScope(appContext, firebaseUid)
                return firebaseUid
            }
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_ACTIVE_DB_USER_ID, null)?.takeIf { it.isNotBlank() }
        }

        fun hasActiveSession(context: Context): Boolean {
            val appContext = context.applicationContext
            val signedIn = runCatching {
                FirebaseApp.getApps(appContext).isNotEmpty() && Firebase.auth.currentUser != null
            }.getOrDefault(false)
            if (signedIn) return true
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SKIP_LOGIN, false)
        }

        fun getInstanceForUser(context: Context, userId: String?): AppDatabase {
            val dbName = dbNameForUser(userId)
            return instances[dbName] ?: synchronized(this) {
                instances[dbName] ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbName
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instances[dbName] = it }
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return getInstanceForUser(context, resolveActiveUserId(context))
        }

        internal fun closeCachedInstance(userId: String?) {
            val dbName = dbNameForUser(userId)
            synchronized(this) {
                instances.remove(dbName)?.close()
            }
        }
    }
}
