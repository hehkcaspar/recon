package com.example.bundlecam

import android.app.Application
import com.example.bundlecam.di.AppContainer

class BundleCamApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
