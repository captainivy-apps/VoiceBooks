package com.dafei.voicebook.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dafei.voicebook.data.model.BookFormat
import com.dafei.voicebook.data.model.ImportStatus

class Converters {
    @TypeConverter
    fun fromBookFormat(value: BookFormat): String = value.name

    @TypeConverter
    fun toBookFormat(value: String): BookFormat {
        return BookFormat.entries.firstOrNull { it.name == value } ?: BookFormat.UNKNOWN
    }

    @TypeConverter
    fun fromImportStatus(value: ImportStatus): String = value.name

    @TypeConverter
    fun toImportStatus(value: String): ImportStatus {
        return ImportStatus.entries.firstOrNull { it.name == value } ?: ImportStatus.FAILED
    }
}

@Database(
    entities = [
        BookEntity::class,
        SentenceIndexEntity::class,
        PlaybackProgressEntity::class,
        TtsModelEntity::class,
        TtsModelBenchmarkEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun sentenceIndexDao(): SentenceIndexDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun ttsModelDao(): TtsModelDao
    abstract fun ttsModelBenchmarkDao(): TtsModelBenchmarkDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tts_models ADD COLUMN downloadError TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tts_model_benchmarks (
                        modelId TEXT NOT NULL,
                        speakerId INTEGER NOT NULL,
                        synthesisMs INTEGER NOT NULL,
                        audioDurationMs INTEGER NOT NULL,
                        rtf REAL NOT NULL,
                        sampleRate INTEGER NOT NULL,
                        benchmarkedAt INTEGER NOT NULL,
                        PRIMARY KEY(modelId, speakerId)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN importProgress INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceUrl TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN publisher TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN publishedDate TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN language TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN isbn TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN subjects TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("books", "contentMd5")) {
                    db.execSQL("ALTER TABLE books ADD COLUMN contentMd5 TEXT")
                }
                db.execSQL("DROP INDEX IF EXISTS index_books_contentMd5")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_books_contentMd5 ON books(contentMd5)",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_books_contentMd5")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_books_contentMd5 ON books(contentMd5)",
                )
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
            query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
            }
            return false
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voicebook.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                    )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
