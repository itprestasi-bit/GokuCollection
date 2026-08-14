package com.collectionfield.app

import android.app.Application
import com.collectionfield.app.data.repository.AppContainer

class CollectionFieldApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.seedDemoDataIfNeeded()
        container.enqueueSync()
    }
}
