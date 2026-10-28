package com.examhelper.shua

import android.app.Application
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.repo.LocalDataRepository
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.data.settings.SettingsStore
import com.examhelper.shua.ui.sound.SoundEffects

class AppContainer(app: Application) {
    val database: AppDatabase = AppDatabase.get(app)
    val settings: SettingsStore = SettingsStore(app)
    val llmClient: LlmClient = LlmClient()
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
        runCatching { SoundEffects.prepare(this) }
    }

    companion object {
        lateinit var instance: ShuaApp
            private set
    }
}
