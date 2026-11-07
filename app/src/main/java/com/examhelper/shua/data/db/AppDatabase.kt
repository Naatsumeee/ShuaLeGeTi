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
        ImportJob::class,
        GenerateJob::class
    ],
    version = 9,
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
    abstract fun generateJobDao(): GenerateJobDao

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

        /** v3 → v4：AI 原答案冲突字段 + 生成任务表 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN originalAnswer TEXT DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS generate_jobs (" +
                        "bankId INTEGER NOT NULL, " +
                        "stage TEXT NOT NULL DEFAULT 'RUNNING', " +
                        "forceAll INTEGER NOT NULL DEFAULT 0, " +
                        "total INTEGER NOT NULL DEFAULT 0, " +
                        "done INTEGER NOT NULL DEFAULT 0, " +
                        "ok INTEGER NOT NULL DEFAULT 0, " +
                        "fail INTEGER NOT NULL DEFAULT 0, " +
                        "message TEXT DEFAULT NULL, " +
                        "cacheHitTokens INTEGER NOT NULL DEFAULT 0, " +
                        "cacheMissTokens INTEGER NOT NULL DEFAULT 0, " +
                        "outputTokens INTEGER NOT NULL DEFAULT 0, " +
                        "errorMessage TEXT DEFAULT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(bankId))"
                )
            }
        }

        /** v4 → v5：AI 答案分离（aiAnswer/answerVerified）+ 深度解题标记 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN aiAnswer TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE questions ADD COLUMN answerVerified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE questions ADD COLUMN deepSolved INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 → v6：导入解析也要统计 token 用量（成本不应只覆盖 AI 生成那一半） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN cacheHitTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN cacheMissTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN outputTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 → v7：任务前后余额快照。
         * 官方 `/user/balance` 的差值才是能对账的真实花费，本地 token 单价只是「参考成本」。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN balanceBefore REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN balanceAfter REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN balanceCurrency TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE generate_jobs ADD COLUMN balanceBefore REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE generate_jobs ADD COLUMN balanceAfter REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE generate_jobs ADD COLUMN balanceCurrency TEXT DEFAULT NULL")
            }
        }

        /**
         * v7 → v8：
         * 1. AI 解析的实时输出尾巴（导入页「生成过程预览」用）。只存最近一小段文本，
         *    不落完整输出 —— 一次导入可能几十万字符。
         * 2. 题库的置顶与归档标记（首页长按菜单）。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN streamTail TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE banks ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE banks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 → v9：错题本「重点标记」。
         *
         * `flagged = 1` 的错题可以在错题本里单独成组复刷，与「是否已掌握」互不影响
         * —— 已掌握的题也可能是重点，所以不能复用 `mastered`。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wrong_records ADD COLUMN flagged INTEGER NOT NULL DEFAULT 0")
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
