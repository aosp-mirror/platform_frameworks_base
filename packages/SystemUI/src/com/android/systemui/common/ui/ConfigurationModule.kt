/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.common.ui

import android.content.Context
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractorImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.Binds
import dagger.Module
import dagger.Provides

@Module
interface ConfigurationModule {

    /**
     * Deprecated: [ConfigurationState] should be injected only with the correct annotation. For
     * now, without annotation the global config associated state is provided.
     */
    @Binds
    @Deprecated("Use the @Main annotated one instead of this.")
    fun provideGlobalConfigurationState(
        @Main configurationState: ConfigurationState
    ): ConfigurationState

    @Binds
    @Deprecated("Use the @Main annotated one instead of this.")
    fun provideDefaultConfigurationState(
        @Main configurationState: ConfigurationInteractor
    ): ConfigurationInteractor

    companion object {
        @SysUISingleton
        @Provides
        @Main
        fun provideGlobalConfigurationState(
            configStateFactory: ConfigurationStateImpl.Factory,
            configurationController: ConfigurationController,
            @Main context: Context,
        ): ConfigurationState {
            return configStateFactory.create(context, configurationController)
        }

        @SysUISingleton
        @Provides
        @Main
        fun provideGlobalConfigurationInteractor(
            configurationRepository: ConfigurationRepository
        ): ConfigurationInteractor {
            return ConfigurationInteractorImpl(configurationRepository)
        }
    }
}
