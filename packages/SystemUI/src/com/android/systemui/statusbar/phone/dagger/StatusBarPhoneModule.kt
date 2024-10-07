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

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.core.CommandQueueInitializer
import com.android.systemui.statusbar.core.StatusBarInitializer
import com.android.systemui.statusbar.core.StatusBarInitializerImpl
import com.android.systemui.statusbar.core.StatusBarOrchestrator
import com.android.systemui.statusbar.core.StatusBarSimpleFragment
import com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStateRepositoryStore
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStateRepositoryStoreImpl
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Similar in purpose to [StatusBarModule], but scoped only to phones */
@Module
interface StatusBarPhoneModule {

    @Binds
    abstract fun windowStateRepoStore(
        impl: StatusBarWindowStateRepositoryStoreImpl
    ): StatusBarWindowStateRepositoryStore

    @Binds
    abstract fun commandQCallbacks(
        impl: CentralSurfacesCommandQueueCallbacks
    ): CommandQueue.Callbacks

    /** Binds {@link StatusBarInitializer} as a {@link CoreStartable}. */
    @Binds
    @IntoMap
    @ClassKey(StatusBarInitializerImpl::class)
    fun bindStatusBarInitializer(impl: StatusBarInitializerImpl): CoreStartable

    @Binds fun statusBarInitializer(impl: StatusBarInitializerImpl): StatusBarInitializer

    companion object {
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(StatusBarOrchestrator::class)
        fun orchestratorCoreStartable(
            orchestratorLazy: Lazy<StatusBarOrchestrator>
        ): CoreStartable {
            return if (StatusBarSimpleFragment.isEnabled) {
                orchestratorLazy.get()
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
            return if (StatusBarSimpleFragment.isEnabled) {
                initializerLazy.get()
            } else {
                CoreStartable.NOP
            }
        }
    }
}
