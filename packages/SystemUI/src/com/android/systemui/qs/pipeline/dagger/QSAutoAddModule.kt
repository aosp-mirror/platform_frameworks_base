/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.dagger

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.qs.pipeline.data.repository.AutoAddRepository
import com.android.systemui.qs.pipeline.data.repository.AutoAddSettingRepository
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.Multibinds

@Module(
    includes =
        [
            BaseAutoAddableModule::class,
        ]
)
abstract class QSAutoAddModule {

    @Binds abstract fun bindAutoAddRepository(impl: AutoAddSettingRepository): AutoAddRepository

    @Multibinds abstract fun providesAutoAddableSet(): Set<AutoAddable>

    companion object {
        /**
         * Provides a logging buffer for all logs related to the new Quick Settings pipeline to log
         * auto added tiles.
         */
        @Provides
        @SysUISingleton
        @QSAutoAddLog
        fun provideQSAutoAddLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create(QSPipelineLogger.AUTO_ADD_TAG, maxSize = 100, systrace = false)
        }
    }
}
