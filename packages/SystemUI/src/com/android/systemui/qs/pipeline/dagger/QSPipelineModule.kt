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

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.qs.pipeline.data.model.RestoreProcessor
import com.android.systemui.qs.pipeline.data.repository.DefaultTilesQSHostRepository
import com.android.systemui.qs.pipeline.data.repository.DefaultTilesRepository
import com.android.systemui.qs.pipeline.data.repository.InstalledTilesComponentRepository
import com.android.systemui.qs.pipeline.data.repository.InstalledTilesComponentRepositoryImpl
import com.android.systemui.qs.pipeline.data.repository.MinimumTilesRepository
import com.android.systemui.qs.pipeline.data.repository.MinimumTilesResourceRepository
import com.android.systemui.qs.pipeline.data.repository.QSSettingsRestoredBroadcastRepository
import com.android.systemui.qs.pipeline.data.repository.QSSettingsRestoredRepository
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository
import com.android.systemui.qs.pipeline.data.repository.TileSpecSettingsRepository
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractorImpl
import com.android.systemui.qs.pipeline.domain.startable.QSPipelineCoreStartable
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractorImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.Multibinds

@Module(includes = [QSAutoAddModule::class, RestoreProcessorsModule::class])
abstract class QSPipelineModule {

    /** Implementation for [TileSpecRepository] */
    @Binds
    abstract fun provideTileSpecRepository(impl: TileSpecSettingsRepository): TileSpecRepository

    @Multibinds abstract fun provideRestoreProcessors(): Set<RestoreProcessor>

    @Binds
    abstract fun provideDefaultTilesRepository(
        impl: DefaultTilesQSHostRepository
    ): DefaultTilesRepository

    @Binds
    abstract fun bindCurrentTilesInteractor(
        impl: CurrentTilesInteractorImpl
    ): CurrentTilesInteractor

    @Binds
    abstract fun provideInstalledTilesPackageRepository(
        impl: InstalledTilesComponentRepositoryImpl
    ): InstalledTilesComponentRepository

    @Binds
    abstract fun provideDisabledByPolicyInteractor(
        impl: DisabledByPolicyInteractorImpl
    ): DisabledByPolicyInteractor

    @Binds
    @IntoMap
    @ClassKey(QSPipelineCoreStartable::class)
    abstract fun provideCoreStartable(startable: QSPipelineCoreStartable): CoreStartable

    @Binds
    abstract fun provideQSSettingsRestoredRepository(
        impl: QSSettingsRestoredBroadcastRepository
    ): QSSettingsRestoredRepository

    @Binds
    abstract fun provideMinimumTilesRepository(
        impl: MinimumTilesResourceRepository
    ): MinimumTilesRepository

    companion object {
        /**
         * Provides a logging buffer for all logs related to the new Quick Settings pipeline to log
         * the list of current tiles.
         */
        @Provides
        @SysUISingleton
        @QSTileListLog
        fun provideQSTileListLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create(QSPipelineLogger.TILE_LIST_TAG, maxSize = 700, systrace = false)
        }

        @Provides
        @SysUISingleton
        @QSRestoreLog
        fun providesQSRestoreLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create(QSPipelineLogger.RESTORE_TAG, maxSize = 50, systrace = false)
        }
    }
}
