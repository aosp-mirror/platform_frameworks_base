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
 * limitations under the License
 */

package com.android.systemui.qs.dagger

import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QSHostAdapter
import com.android.systemui.qs.QSTileHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.QsEventLoggerImpl
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedRepository
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedSharedPrefsRepository
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractorImpl
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides

@Module
interface QSHostModule {

    @Binds fun provideQsHost(controllerImpl: QSHostAdapter): QSHost

    @Binds fun provideEventLogger(impl: QsEventLoggerImpl): QsEventLogger

    @Module
    companion object {
        private const val MAX_QS_INSTANCE_ID = 1 shl 20

        @Provides
        @JvmStatic
        fun providePanelInteractor(
            featureFlags: QSPipelineFlagsRepository,
            qsHost: QSTileHost,
            panelInteractorImpl: PanelInteractorImpl
        ): PanelInteractor {
            return if (featureFlags.pipelineEnabled) {
                panelInteractorImpl
            } else {
                qsHost
            }
        }

        @Provides
        @JvmStatic
        fun provideCustomTileAddedRepository(
            featureFlags: QSPipelineFlagsRepository,
            qsHost: QSTileHost,
            customTileAddedRepository: CustomTileAddedSharedPrefsRepository
        ): CustomTileAddedRepository {
            return if (featureFlags.pipelineEnabled) {
                customTileAddedRepository
            } else {
                qsHost
            }
        }
    }
}
