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
        GenerateJob::class,
        BalanceSnapshot::class
    ],
    version = 13,
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
    abstract fun balanceSnapshotDao(): BalanceSnapshotDao

    companion object {
        /** v1 鈫?v2锛氶鍨嬪瓙绫诲瀷/鍏辩敤棰樺共/鍏辩敤閫夐」銆佷綔绛?runId/鐢ㄦ椂銆佹柀棰樿〃銆佸鍏ヤ换鍔¤〃銆佽繘搴︾瓫閫夋寚绾?*/
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

        /** v2 鈫?v3锛氶鐩〉鐮?*/
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN sourcePage INTEGER DEFAULT NULL")
            }
        }

        /** v3 鈫?v4锛欰I 鍘熺瓟妗堝啿绐佸瓧娈?+ 鐢熸垚浠诲姟琛?*/
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

        /** v4 鈫?v5锛欰I 绛旀鍒嗙锛坅iAnswer/answerVerified锛? 娣卞害瑙ｉ鏍囪 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN aiAnswer TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE questions ADD COLUMN answerVerified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE questions ADD COLUMN deepSolved INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 鈫?v6锛氬鍏ヨВ鏋愪篃瑕佺粺璁?token 鐢ㄩ噺锛堟垚鏈笉搴斿彧瑕嗙洊 AI 鐢熸垚閭ｄ竴鍗婏級 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN cacheHitTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN cacheMissTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN outputTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 鈫?v7锛氫换鍔″墠鍚庝綑棰濆揩鐓с€?
         * 瀹樻柟 `/user/balance` 鐨勫樊鍊兼墠鏄兘瀵硅处鐨勭湡瀹炶姳璐癸紝鏈湴 token 鍗曚环鍙槸銆屽弬鑰冩垚鏈€嶃€?
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
         * v7 鈫?v8锛?
         * 1. AI 瑙ｆ瀽鐨勫疄鏃惰緭鍑哄熬宸达紙瀵煎叆椤点€岀敓鎴愯繃绋嬮瑙堛€嶇敤锛夈€傚彧瀛樻渶杩戜竴灏忔鏂囨湰锛?
         *    涓嶈惤瀹屾暣杈撳嚭 鈥斺€?涓€娆″鍏ュ彲鑳藉嚑鍗佷竾瀛楃銆?
         * 2. 棰樺簱鐨勭疆椤朵笌褰掓。鏍囪锛堥椤甸暱鎸夎彍鍗曪級銆?
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN streamTail TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE banks ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE banks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 鈫?v9锛氶敊棰樻湰銆岄噸鐐规爣璁般€嶃€?
         *
         * `flagged = 1` 鐨勯敊棰樺彲浠ュ湪閿欓鏈噷鍗曠嫭鎴愮粍澶嶅埛锛屼笌銆屾槸鍚﹀凡鎺屾彙銆嶄簰涓嶅奖鍝?
         * 鈥斺€?宸叉帉鎻＄殑棰樹篃鍙兘鏄噸鐐癸紝鎵€浠ヤ笉鑳藉鐢?`mastered`銆?
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wrong_records ADD COLUMN flagged INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v9 鈫?v10锛氭妸鍘嗗彶棰樺簱閲岃鍐欏潖鐨勭珷鑺傚悕娲椾竴閬嶃€?
         *
         * 銆岀珷鑺傚悕甯︼紙涓€锛夊悕璇嶈В閲娿€嶈繖涓?bug 浠?v1.0.0 灏卞瓨鍦紝鍙慨瑙ｆ瀽鍣ㄧ殑璇濓紝
         * 鐢ㄦ埛鎵嬫満閲?*宸茬粡瀵煎叆**鐨勯搴撲粛鐒舵槸閿欑殑锛屽繀椤婚噸鏂板鍏ユ墠鑳藉ソ 鈥斺€?鑰岄噸瀵间竴鏈?
         * 鎵弿浠惰 OCR 鍗佸嚑鍒嗛挓銆傛墍浠ヨ繖閲屽湪鍗囩骇鏃剁洿鎺ユ妸搴撻噷鐨勮剰绔犺妭鍚嶅氨鍦版礂鎺夛細
         * 棰樺瀷鍒嗙粍/绔犲唴灏忚妭鐩綍 鈫?NULL锛堟湭鍒嗙珷锛夛紝銆岀珷鍚嶏紙涓€锛夊悕璇嶈В閲娿€嶁啋 鍙暀绔犲悕銆?
         *
         * 骞插噣鐨勫悕瀛楀師鏍蜂笉鍔紝鎵€浠ュ鏈夌珷鍙风殑姝ｈ棰樺簱闆跺奖鍝嶃€?
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val pending = mutableListOf<Pair<Long, String?>>()
                db.query(
                    "SELECT id, chapter FROM questions " +
                        "WHERE chapter IS NOT NULL AND TRIM(chapter) != ''"
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val raw = c.getString(1) ?: continue
                        val cleaned = com.examhelper.shua.data.parse.HeadingClassifier
                            .cleanChapter(raw)
                        if (cleaned != raw) pending += id to cleaned
                    }
                }
                pending.forEach { (id, cleaned) ->
                    if (cleaned == null) {
                        db.execSQL("UPDATE questions SET chapter = NULL WHERE id = ?", arrayOf(id))
                    } else {
                        db.execSQL(
                            "UPDATE questions SET chapter = ? WHERE id = ?",
                            arrayOf(cleaned, id)
                        )
                    }
                }
            }
        }

        /**
         * v10 鈫?v11锛氱珷鑺傚悕绗簩娆℃竻娲?鈥斺€?杩欎竴娆¤澶勭悊銆屽綊涓€銆嶃€?
         *
         * v10 鍙仛浜嗐€屽墺棰樺瀷鏍囩銆嶏紝鑰屼笖閭ｄ竴鐗堢殑 `cleanChapter` 鏈変釜鏇翠弗閲嶇殑缂洪櫡锛?
         * `绗叚绔?绾跨矑浣撻仐浼犮€怉1鍨嬮銆慲 浼氳鍓ユ垚銆岀鍏珷銆嶏紝**绔犲悕鏁翠釜涓㈡帀**銆?
         * 鍙﹀鍚屼竴绔犲湪搴撻噷鍙兘瀛樺湪澶氱鍐欐硶锛坄绗琗绔?AAAA` / `绗琗绔燻 / `AAAA`锛夛紝
         * 绔犺妭鍒楄〃閲屽氨浼氬苟鍒楀嚭鐜板ソ鍑犳潯鐪嬭捣鏉ユ槸鍚屼竴绔犵殑椤?鈥斺€?鐢ㄦ埛鎶ョ殑
         * 銆岀X绔?AAAA 鍙樻垚 绗琗绔?/ AAAA / 绗琗绔?AAAA + 绗琗绔?閲嶅銆嶃€?
         *
         * 杩欓噷鎸?*棰樺簱**鍒嗙粍锛氬厛閫愭潯 `cleanChapter`锛堢幇鍦ㄤ細淇濅綇绔犲悕鏈綋锛夛紝
         * 鍐嶇敤 `unifyChapterNames` 鎶婂悓涓€绔犵殑澶氱鍐欐硶鏀舵暃鍒颁竴涓鑼冨悕銆?
         * 鍒嗛搴撴槸蹇呴』鐨勶細绗?1 绔犲拰绗?1 绔犳槸涓嶅悓棰樺簱鐨勪袱绔狅紝涓嶈兘璺ㄥ簱鍚堝苟銆?
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val byBank = mutableMapOf<Long, MutableList<Pair<Long, String>>>()
                db.query(
                    "SELECT id, bankId, chapter FROM questions " +
                        "WHERE chapter IS NOT NULL AND TRIM(chapter) != ''"
                ).use { c ->
                    while (c.moveToNext()) {
                        byBank.getOrPut(c.getLong(1)) { mutableListOf() }
                            .add(c.getLong(0) to (c.getString(2) ?: continue))
                    }
                }
                byBank.forEach { (_, rows) ->
                    // 鈶?閫愭潯娓呮礂
                    val cleaned = rows.map { (id, raw) ->
                        id to com.examhelper.shua.data.parse.HeadingClassifier.cleanChapter(raw)
                    }
                    // 鈶?鍚屼竴棰樺簱鍐呭綊涓€
                    val mapping = com.examhelper.shua.data.parse.HeadingClassifier
                        .unifyChapterNames(cleaned.mapNotNull { it.second })
                    cleaned.forEach { (id, name) ->
                        val finalName = name?.let { mapping[it] ?: it }
                        db.execSQL(
                            "UPDATE questions SET chapter = ? WHERE id = ?",
                            arrayOf(finalName, id)
                        )
                    }
                }
            }
        }

        /**
         * v11 → v12：新增 `balance_snapshots`（账户余额快照）。
         *
         * DeepSeek 没有用量/账单查询接口，所以「近 N 天消费」只能靠余额变化反推：
         * 把窗口内相邻快照的下降量累加（上升视为充值，跳过）。
         * 这张表就是那些快照 —— 应用打开时每天采一次，任务前后也采一次。
         *
         * 纯新增表，不触碰已有数据，老题库零影响。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `balance_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`providerId` INTEGER NOT NULL, " +
                        "`amount` REAL NOT NULL, " +
                        "`currency` TEXT NOT NULL, " +
                        "`sampledAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_balance_snapshots_providerId_sampledAt` " +
                        "ON `balance_snapshots` (`providerId`, `sampledAt`)"
                )
            }
        }

        /**
         * v12 → v13：题库分组、合并、拖拽排序与卡片配色。
         *
         * 全部是 banks 的新增列，不动 questions：题库组是「聚合视图」，
         * 子题库用 groupId 反向归属，组本身不复制题目，避免 4229 题被复制一份。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE banks ADD COLUMN groupId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE banks ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE banks ADD COLUMN accentColor INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE banks ADD COLUMN note TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE banks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE banks ADD COLUMN mergedFromJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE banks ADD COLUMN mergeSnapshotJson TEXT DEFAULT NULL")
                // Room entity 里的 indices 是 SQL 生成的 `index_banks_groupId`；
                // 手写迁移必须用同名，否则 Room 校验 schema 会报“找不到索引”。
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_banks_groupId` ON `banks` (`groupId`)")
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
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
