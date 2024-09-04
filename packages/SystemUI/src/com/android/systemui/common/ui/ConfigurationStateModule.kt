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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.Binds
import dagger.Module
import dagger.Provides
import javax.inject.Qualifier

/**
 * Annotates elements that provide information from the global configuration.
 *
 * The global configuration is the one associted with the main display. Secondary displays will
 * apply override to the global configuration. Elements annotated with this shouldn't be used for
 * secondary displays.
 */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class GlobalConfig

@Module
interface ConfigurationStateModule {

    /**
     * Deprecated: [ConfigurationState] should be injected only with the correct annotation. For
     * now, without annotation the global config associated state is provided.
     */
    @Binds
    fun provideGlobalConfigurationState(
        @GlobalConfig configurationState: ConfigurationState
    ): ConfigurationState

    companion object {
        @SysUISingleton
        @Provides
        @GlobalConfig
        fun provideGlobalConfigurationState(
            configStateFactory: ConfigurationStateImpl.Factory,
            configurationController: ConfigurationController,
            @Application context: Context,
        ): ConfigurationState {
            return configStateFactory.create(context, configurationController)
        }
    }
}
