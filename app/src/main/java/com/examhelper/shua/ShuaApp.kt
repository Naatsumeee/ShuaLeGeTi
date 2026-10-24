package com.examhelper.shua

import android.app.Application
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.data.settings.SettingsStore

class AppContainer(app: Application) {
    val database: AppDatabase = AppDatabase.get(app)
    val settings: SettingsStore = SettingsStore(app)
    val llmClient: LlmClient = LlmClient()
    val bankRepo: BankRepository = BankRepository(database)
    val studyRepo: StudyRepository = StudyRepository(database)
}

class ShuaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: ShuaApp
            private set
    }
}
