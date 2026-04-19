package com.example.bundlecam

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.example.bundlecam.di.AppContainer

class ReconApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
    }
}
