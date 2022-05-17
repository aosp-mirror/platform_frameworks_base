/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.unfold

import android.content.Context
import android.view.IWindowManager
import com.android.systemui.keyguard.LifecycleScreenStatusProvider
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.unfold.util.UnfoldTransitionATracePrefix
import com.android.systemui.util.time.SystemClockImpl
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider
import dagger.Lazy
import dagger.Module
import dagger.Provides
import java.util.Optional
import javax.inject.Named
import javax.inject.Singleton

@Module(includes = [UnfoldSharedModule::class])
class UnfoldTransitionModule {

    @Provides @UnfoldTransitionATracePrefix fun tracingTagPrefix() = "systemui"

    @Provides
    @Singleton
    fun providesFoldStateLoggingProvider(
        config: UnfoldTransitionConfig,
        foldStateProvider: Lazy<FoldStateProvider>
    ): Optional<FoldStateLoggingProvider> =
        if (config.isHingeAngleEnabled) {
            Optional.of(FoldStateLoggingProviderImpl(foldStateProvider.get(), SystemClockImpl()))
        } else {
            Optional.empty()
        }

    @Provides
    @Singleton
    fun providesFoldStateLogger(
        optionalFoldStateLoggingProvider: Optional<FoldStateLoggingProvider>
    ): Optional<FoldStateLogger> =
        optionalFoldStateLoggingProvider.map { FoldStateLoggingProvider ->
            FoldStateLogger(FoldStateLoggingProvider)
        }

    @Provides
    @Singleton
    fun provideUnfoldTransitionConfig(context: Context): UnfoldTransitionConfig =
        createConfig(context)

    @Provides
    @Singleton
    fun provideNaturalRotationProgressProvider(
        context: Context,
        windowManager: IWindowManager,
        unfoldTransitionProgressProvider: Optional<UnfoldTransitionProgressProvider>
    ): Optional<NaturalRotationUnfoldProgressProvider> =
        unfoldTransitionProgressProvider.map { provider ->
            NaturalRotationUnfoldProgressProvider(context, windowManager, provider)
        }

    @Provides
    @Named(UNFOLD_STATUS_BAR)
    @Singleton
    fun provideStatusBarScopedTransitionProvider(
        source: Optional<NaturalRotationUnfoldProgressProvider>
    ): Optional<ScopedUnfoldTransitionProgressProvider> =
        source.map { provider -> ScopedUnfoldTransitionProgressProvider(provider) }

    @Provides
    @Singleton
    fun provideShellProgressProvider(
        config: UnfoldTransitionConfig,
        provider: Optional<UnfoldTransitionProgressProvider>
    ): ShellUnfoldProgressProvider =
        if (config.isEnabled && provider.isPresent) {
            UnfoldProgressProvider(provider.get())
        } else {
            ShellUnfoldProgressProvider.NO_PROVIDER
        }

    @Provides
    fun screenStatusProvider(impl: LifecycleScreenStatusProvider): ScreenStatusProvider = impl
}

const val UNFOLD_STATUS_BAR = "unfold_status_bar"
