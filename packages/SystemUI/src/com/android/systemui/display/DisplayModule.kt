/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.display

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.DeviceStateRepositoryImpl
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayRepositoryImpl
import com.android.systemui.display.data.repository.DisplayScopeRepository
import com.android.systemui.display.data.repository.DisplayScopeRepositoryImpl
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepositoryImpl
import com.android.systemui.display.data.repository.FocusedDisplayRepository
import com.android.systemui.display.data.repository.FocusedDisplayRepositoryImpl
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractorImpl
import com.android.systemui.display.domain.interactor.DisplayWindowPropertiesInteractorModule
import com.android.systemui.display.domain.interactor.RearDisplayStateInteractor
import com.android.systemui.display.domain.interactor.RearDisplayStateInteractorImpl
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Module binding display related classes. */
@Module(includes = [DisplayWindowPropertiesInteractorModule::class])
interface DisplayModule {
    @Binds
    fun bindConnectedDisplayInteractor(
        provider: ConnectedDisplayInteractorImpl
    ): ConnectedDisplayInteractor

    @Binds
    fun bindRearDisplayStateInteractor(
        provider: RearDisplayStateInteractorImpl
    ): RearDisplayStateInteractor

    @Binds fun bindsDisplayRepository(displayRepository: DisplayRepositoryImpl): DisplayRepository

    @Binds
    fun bindsDeviceStateRepository(
        deviceStateRepository: DeviceStateRepositoryImpl
    ): DeviceStateRepository

    @Binds
    fun bindsFocusedDisplayRepository(
        focusedDisplayRepository: FocusedDisplayRepositoryImpl
    ): FocusedDisplayRepository

    @Binds fun displayScopeRepository(impl: DisplayScopeRepositoryImpl): DisplayScopeRepository

    @Binds
    fun displayWindowPropertiesRepository(
        impl: DisplayWindowPropertiesRepositoryImpl
    ): DisplayWindowPropertiesRepository

    companion object {
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(DisplayScopeRepositoryImpl::class)
        fun displayScopeRepoCoreStartable(
            repoImplLazy: Lazy<DisplayScopeRepositoryImpl>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                repoImplLazy.get()
            } else {
                CoreStartable.NOP
            }
        }

        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(DisplayWindowPropertiesRepository::class)
        fun displayWindowPropertiesRepoAsCoreStartable(
            repoLazy: Lazy<DisplayWindowPropertiesRepositoryImpl>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                return repoLazy.get()
            } else {
                CoreStartable.NOP
            }
        }
    }
}
