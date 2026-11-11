package com.examhelper.shua

import android.app.Application
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.llm.BalanceClient
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.repo.LocalDataRepository
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.data.settings.SettingsStore

class AppContainer(app: Application) {
    val database: AppDatabase = AppDatabase.get(app)
    val settings: SettingsStore = SettingsStore(app)
    val llmClient: LlmClient = LlmClient()
    val balanceClient: BalanceClient = BalanceClient()
    val bankRepo: BankRepository = BankRepository(database)
    val studyRepo: StudyRepository = StudyRepository(database)
    val localDataRepo: LocalDataRepository = LocalDataRepository(database)
}

class ShuaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        // 清理过期的临时文件（PDF 落地、拍照输出等），只删过期的，不碰正在跑的任务
        runCatching {
            val app = this
            Thread { com.examhelper.shua.data.storage.AppStorage.cleanupStale(app) }.start()
        }
        // 进程被系统杀掉后，WorkManager 队列里那条导入任务也跟着没了，
        // 但库里的 stage 还停在 INGEST/PARSE → 界面永远显示「解析中」。
        // 启动时补排一次（已解析分块落盘，重排即断点续跑）。
        runCatching {
            val app = this
            Thread { com.examhelper.shua.data.importjob.ImportPipelineWorker.reviveStale(app) }.start()
        }
        // 余额快照（每天最多一次）：DeepSeek 没有用量查询接口，「近 N 天消费」只能靠
        // 余额下降量反推。不在这里定期采样的话，快照会只散落在任务前后，
        // 两三天没跑任务的窗口就完全算不出实际消费。
        // 全程 runCatching + 后台线程：采样失败绝不能影响启动。
        runCatching {
            val app = this
            Thread {
                runCatching {
                    val dao = app.container.database.balanceSnapshotDao()
                    val provider = kotlinx.coroutines.runBlocking {
                        app.container.database.providerDao().getPrimary()
                    } ?: return@runCatching
                    kotlinx.coroutines.runBlocking {
                        com.examhelper.shua.data.llm.UsageStats.sample(
                            app.container.balanceClient, dao, provider,
                            minIntervalMs = com.examhelper.shua.data.llm.UsageStats.DAILY_SAMPLE_INTERVAL_MS
                        )
                        com.examhelper.shua.data.llm.UsageStats.purge(dao)
                    }
                }
            }.start()
        }
    }

    companion object {
        lateinit var instance: ShuaApp
            private set
    }
}
