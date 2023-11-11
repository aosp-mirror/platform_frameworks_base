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
import android.hardware.devicestate.DeviceStateManager
import android.os.SystemProperties
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.LifecycleScreenStatusProvider
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.data.repository.UnfoldTransitionRepository
import com.android.systemui.unfold.data.repository.UnfoldTransitionRepositoryImpl
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractorImpl
import com.android.systemui.unfold.system.SystemUnfoldSharedModule
import com.android.systemui.unfold.updates.FoldProvider
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.unfold.updates.RotationChangeProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.unfold.util.UnfoldOnlyProgressProvider
import com.android.systemui.unfold.util.UnfoldTransitionATracePrefix
import com.android.systemui.util.time.SystemClockImpl
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Module(
    includes =
        [
            UnfoldSharedModule::class,
            SystemUnfoldSharedModule::class,
            UnfoldTransitionModule.Bindings::class
        ]
)
class UnfoldTransitionModule {

    @Provides @UnfoldTransitionATracePrefix fun tracingTagPrefix() = "systemui"

    /** A globally available FoldStateListener that allows one to query the fold state. */
    @Provides
    @Singleton
    fun providesFoldStateListener(
        deviceStateManager: DeviceStateManager,
        @Application context: Context,
        @Main executor: Executor
    ): DeviceStateManager.FoldStateListener {
        val listener = DeviceStateManager.FoldStateListener(context)
        deviceStateManager.registerCallback(executor, listener)

        return listener
    }

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
    fun provideNaturalRotationProgressProvider(
        context: Context,
        rotationChangeProvider: RotationChangeProvider,
        unfoldTransitionProgressProvider: Optional<UnfoldTransitionProgressProvider>
    ): Optional<NaturalRotationUnfoldProgressProvider> =
        unfoldTransitionProgressProvider.map { provider ->
            NaturalRotationUnfoldProgressProvider(context, rotationChangeProvider, provider)
        }

    @Provides
    @Singleton
    @Named(UNFOLD_ONLY_PROVIDER)
    fun provideUnfoldOnlyProvider(
        foldProvider: FoldProvider,
        @Main executor: Executor,
        sourceProvider: Optional<UnfoldTransitionProgressProvider>
    ): Optional<UnfoldTransitionProgressProvider> =
        sourceProvider.map { provider ->
            UnfoldOnlyProgressProvider(foldProvider, executor, provider)
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
        foldProvider: FoldProvider,
        provider: Provider<Optional<UnfoldTransitionProgressProvider>>,
        @Named(UNFOLD_ONLY_PROVIDER)
        unfoldOnlyProvider: Provider<Optional<UnfoldTransitionProgressProvider>>
    ): ShellUnfoldProgressProvider {
        val resultingProvider =
            if (config.isEnabled) {
                // Return unfold only provider to the shell if we don't want to animate tasks during
                // folding. Shell provider listeners are responsible for animating task bounds.
                if (ENABLE_FOLD_TASK_ANIMATIONS) {
                    provider
                } else {
                    unfoldOnlyProvider
                }
            } else {
                null
            }

        return resultingProvider?.get()?.orElse(null)?.let { unfoldProgressProvider ->
            UnfoldProgressProvider(unfoldProgressProvider, foldProvider)
        } ?: ShellUnfoldProgressProvider.NO_PROVIDER
    }

    @Provides
    fun screenStatusProvider(impl: LifecycleScreenStatusProvider): ScreenStatusProvider = impl

    @Module
    interface Bindings {
        @Binds
        @IntoMap
        @ClassKey(UnfoldTraceLogger::class)
        fun bindUnfoldTraceLogger(impl: UnfoldTraceLogger): CoreStartable

        @Binds fun bindRepository(impl: UnfoldTransitionRepositoryImpl): UnfoldTransitionRepository

        @Binds fun bindInteractor(impl: UnfoldTransitionInteractorImpl): UnfoldTransitionInteractor
    }
}

const val UNFOLD_STATUS_BAR = "unfold_status_bar"
const val UNFOLD_ONLY_PROVIDER = "unfold_only_provider"

// TODO: b/265764985 - tracking bug to clean-up the flag
// FeatureFlags are not accessible here because it's a global submodule (see GlobalModule.java)
private val ENABLE_FOLD_TASK_ANIMATIONS =
    SystemProperties.getBoolean("persist.unfold.enable_fold_tasks_animation", false)
