package com.simplemobiletools.dialer

import android.app.Application
import com.simplemobiletools.dialer.di.mainViewModelModule
import com.george.speech_commands_kotlin.di.recognizeCommandsModule
import com.simplemobiletools.commons.extensions.checkUseEnglish
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        checkUseEnglish()

        // Koin init
        startKoin {
            //androidContext(applicationContext)
            androidContext(this@App)
            modules(mainViewModelModule, recognizeCommandsModule)
        }
    }
}
