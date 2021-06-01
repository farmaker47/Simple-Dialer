package com.george.speech_commands_kotlin.di

import com.simplemobiletools.dialer.helpers.RecognizeCommands
import org.koin.dsl.module

val recognizeCommandsModule = module {

    factory { RecognizeCommands(get()) }

}
