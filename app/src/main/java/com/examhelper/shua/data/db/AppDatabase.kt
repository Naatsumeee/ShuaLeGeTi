package com.examhelper.shua.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromQuestionType(value: QuestionType): String = value.name

    @TypeConverter
    fun toQuestionType(value: String): QuestionType = QuestionType.valueOf(value)

    @TypeConverter
    fun fromStudyMode(value: StudyMode): String = value.name

    @TypeConverter
    fun toStudyMode(value: String): StudyMode = StudyMode.valueOf(value)
}

@Database(
    entities = [
        QuestionBank::class,
        Question::class,
        StudyProgress::class,
        AttemptRecord::class,
        WrongRecord::class,
        FavoriteRecord::class,
        KillRecord::class,
        LlmProvider::class,
        ImportJob::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bankDao(): BankDao
    abstract fun questionDao(): QuestionDao
    abstract fun progressDao(): ProgressDao
    abstract fun recordDao(): RecordDao
    abstract fun providerDao(): ProviderDao
    abstract fun importJobDao(): ImportJobDao

    companion object {
        /** v1 → v2：题型子类型/共用题干/共用选项、作答 runId/用时、斩题表、导入任务表、进度筛选指纹 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN subtype TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE questions ADD COLUMN sharedStem TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE questions ADD COLUMN sharedOptionsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE attempt_records ADD COLUMN runId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE attempt_records ADD COLUMN durationMs INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attempt_records_runId ON attempt_records(runId)")
                db.execSQL("ALTER TABLE study_progress ADD COLUMN filterJson TEXT DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS kill_records (" +
                        "questionId INTEGER NOT NULL, " +
                        "bankId INTEGER NOT NULL, " +
                        "killedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(questionId))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS import_jobs (" +
                        "id TEXT NOT NULL, " +
                        "bankName TEXT NOT NULL, " +
                        "sourceName TEXT NOT NULL, " +
                        "stage TEXT NOT NULL DEFAULT 'INGEST', " +
                        "totalUnits INTEGER NOT NULL DEFAULT 0, " +
                        "doneUnits INTEGER NOT NULL DEFAULT 0, " +
                        "message TEXT DEFAULT NULL, " +
                        "rawTextPath TEXT DEFAULT NULL, " +
                        "parsedPath TEXT DEFAULT NULL, " +
                        "parsedCount INTEGER NOT NULL DEFAULT 0, " +
                        "errorMessage TEXT DEFAULT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
            }
        }

        /** v2 → v3：题目页码 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN sourcePage INTEGER DEFAULT NULL")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shua.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
