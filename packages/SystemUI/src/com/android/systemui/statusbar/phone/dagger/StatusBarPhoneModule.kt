/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.statusbar.phone.dagger

import android.view.Display
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Default
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.core.CommandQueueInitializer
import com.android.systemui.statusbar.core.MultiDisplayStatusBarInitializerStore
import com.android.systemui.statusbar.core.MultiDisplayStatusBarStarter
import com.android.systemui.statusbar.core.SingleDisplayStatusBarInitializerStore
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.core.StatusBarInitializer
import com.android.systemui.statusbar.core.StatusBarInitializerImpl
import com.android.systemui.statusbar.core.StatusBarInitializerStore
import com.android.systemui.statusbar.core.StatusBarOrchestrator
import com.android.systemui.statusbar.core.StatusBarRootModernization
import com.android.systemui.statusbar.data.repository.PrivacyDotViewControllerStoreModule
import com.android.systemui.statusbar.data.repository.PrivacyDotWindowControllerStoreModule
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import com.android.systemui.statusbar.events.PrivacyDotViewControllerModule
import com.android.systemui.statusbar.phone.AutoHideControllerStore
import com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks
import com.android.systemui.statusbar.phone.MultiDisplayAutoHideControllerStore
import com.android.systemui.statusbar.phone.SingleDisplayAutoHideControllerStore
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStateRepositoryStore
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStateRepositoryStoreImpl
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope

/** Similar in purpose to [StatusBarModule], but scoped only to phones */
@Module(
    includes =
        [
            PrivacyDotViewControllerModule::class,
            PrivacyDotWindowControllerStoreModule::class,
            PrivacyDotViewControllerStoreModule::class,
        ]
)
interface StatusBarPhoneModule {

    @Binds
    abstract fun windowStateRepoStore(
        impl: StatusBarWindowStateRepositoryStoreImpl
    ): StatusBarWindowStateRepositoryStore

    @Binds
    abstract fun commandQCallbacks(
        impl: CentralSurfacesCommandQueueCallbacks
    ): CommandQueue.Callbacks

    @Binds
    fun initializerFactory(
        implFactory: StatusBarInitializerImpl.Factory
    ): StatusBarInitializer.Factory

    @Binds fun statusBarInitializer(@Default impl: StatusBarInitializerImpl): StatusBarInitializer

    companion object {
        /** Binds {@link StatusBarInitializer} as a {@link CoreStartable}. */
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(StatusBarInitializer::class)
        fun bindStatusBarInitializer(
            @Default defaultInitializerLazy: Lazy<StatusBarInitializerImpl>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                // Will be started through MultiDisplayStatusBarStarter
                CoreStartable.NOP
            } else if (StatusBarRootModernization.isEnabled) {
                defaultInitializerLazy.get()
            } else {
                // Will be started through CentralSurfaces
                CoreStartable.NOP
            }
        }

        // Dagger doesn't support providing AssistedInject types, without a qualifier. Using the
        // Default qualifier for this reason.
        @Default
        @Provides
        @SysUISingleton
        fun statusBarInitializerImpl(
            implFactory: StatusBarInitializerImpl.Factory,
            statusBarWindowControllerStore: StatusBarWindowControllerStore,
            statusBarModeRepositoryStore: StatusBarModeRepositoryStore,
        ): StatusBarInitializerImpl {
            return implFactory.create(
                statusBarWindowControllerStore.defaultDisplay,
                statusBarModeRepositoryStore.defaultDisplay,
            )
        }

        @Provides
        @SysUISingleton
        @Default // Dagger does not support providing @AssistedInject types without a qualifier
        fun orchestrator(
            @Background backgroundApplicationScope: CoroutineScope,
            statusBarWindowStateRepositoryStore: StatusBarWindowStateRepositoryStore,
            statusBarModeRepositoryStore: StatusBarModeRepositoryStore,
            initializerStore: StatusBarInitializerStore,
            statusBarWindowControllerStore: StatusBarWindowControllerStore,
            autoHideControllerStore: AutoHideControllerStore,
            statusBarOrchestratorFactory: StatusBarOrchestrator.Factory,
        ): StatusBarOrchestrator {
            return statusBarOrchestratorFactory.create(
                Display.DEFAULT_DISPLAY,
                backgroundApplicationScope,
                statusBarWindowStateRepositoryStore.defaultDisplay,
                statusBarModeRepositoryStore.defaultDisplay,
                initializerStore.defaultDisplay,
                statusBarWindowControllerStore.defaultDisplay,
                autoHideControllerStore.defaultDisplay,
            )
        }

        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(MultiDisplayStatusBarStarter::class)
        fun multiDisplayStarter(
            multiDisplayStatusBarStarterLazy: Lazy<MultiDisplayStatusBarStarter>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayStatusBarStarterLazy.get()
            } else {
                CoreStartable.NOP
            }
        }

        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(CommandQueueInitializer::class)
        fun commandQueueInitializerCoreStartable(
            initializerLazy: Lazy<CommandQueueInitializer>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                initializerLazy.get()
            } else {
                CoreStartable.NOP
            }
        }

        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(StatusBarInitializerStore::class)
        fun initializerStoreAsCoreStartable(
            multiDisplayStoreLazy: Lazy<MultiDisplayStatusBarInitializerStore>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayStoreLazy.get()
            } else {
                CoreStartable.NOP
            }
        }

        @Provides
        @SysUISingleton
        fun initializerStore(
            singleDisplayStoreLazy: Lazy<SingleDisplayStatusBarInitializerStore>,
            multiDisplayStoreLazy: Lazy<MultiDisplayStatusBarInitializerStore>,
        ): StatusBarInitializerStore {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayStoreLazy.get()
            } else {
                singleDisplayStoreLazy.get()
            }
        }

        @Provides
        @SysUISingleton
        fun autoHideStore(
            singleDisplayLazy: Lazy<SingleDisplayAutoHideControllerStore>,
            multiDisplayLazy: Lazy<MultiDisplayAutoHideControllerStore>,
        ): AutoHideControllerStore {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayLazy.get()
            } else {
                singleDisplayLazy.get()
            }
        }

        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(AutoHideControllerStore::class)
        fun storeAsCoreStartable(
            multiDisplayLazy: Lazy<MultiDisplayAutoHideControllerStore>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayLazy.get()
            } else {
                CoreStartable.NOP
            }
        }
    }
}
