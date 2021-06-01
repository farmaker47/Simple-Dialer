package com.simplemobiletools.dialer.di

import com.simplemobiletools.dialer.viewmodels.CallActivityViewModel
import org.koin.android.viewmodel.dsl.viewModel
import org.koin.dsl.module

val mainViewModelModule = module {
    viewModel { CallActivityViewModel(get()) }
}
