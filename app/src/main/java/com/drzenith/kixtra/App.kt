package com.drzenith.kixtra

import android.app.Application
import com.drzenith.kixtra.data.Store
import com.drzenith.kixtra.player.PlayerController

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        playerController = PlayerController(this, store)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
