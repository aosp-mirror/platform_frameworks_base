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

package com.android.systemui.qs.panels.dagger

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.qs.panels.data.repository.GridLayoutTypeRepository
import com.android.systemui.qs.panels.data.repository.GridLayoutTypeRepositoryImpl
import com.android.systemui.qs.panels.data.repository.IconTilesRepository
import com.android.systemui.qs.panels.data.repository.IconTilesRepositoryImpl
import com.android.systemui.qs.panels.domain.interactor.GridTypeConsistencyInteractor
import com.android.systemui.qs.panels.domain.interactor.InfiniteGridConsistencyInteractor
import com.android.systemui.qs.panels.domain.interactor.NoopGridConsistencyInteractor
import com.android.systemui.qs.panels.shared.model.GridConsistencyLog
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.qs.panels.shared.model.IconLabelVisibilityLog
import com.android.systemui.qs.panels.shared.model.InfiniteGridLayoutType
import com.android.systemui.qs.panels.shared.model.PartitionedGridLayoutType
import com.android.systemui.qs.panels.shared.model.StretchedGridLayoutType
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.panels.ui.compose.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.PartitionedGridLayout
import com.android.systemui.qs.panels.ui.compose.StretchedGridLayout
import com.android.systemui.qs.panels.ui.viewmodel.IconLabelVisibilityViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconLabelVisibilityViewModelImpl
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModelImpl
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridSizeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridSizeViewModelImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Named

@Module
interface PanelsModule {
    @Binds fun bindIconTilesRepository(impl: IconTilesRepositoryImpl): IconTilesRepository

    @Binds
    fun bindGridLayoutTypeRepository(impl: GridLayoutTypeRepositoryImpl): GridLayoutTypeRepository

    @Binds
    fun bindDefaultGridConsistencyInteractor(
        impl: NoopGridConsistencyInteractor
    ): GridTypeConsistencyInteractor

    @Binds fun bindIconTilesViewModel(impl: IconTilesViewModelImpl): IconTilesViewModel

    @Binds fun bindGridSizeViewModel(impl: InfiniteGridSizeViewModelImpl): InfiniteGridSizeViewModel

    @Binds
    fun bindIconLabelVisibilityViewModel(
        impl: IconLabelVisibilityViewModelImpl
    ): IconLabelVisibilityViewModel

    @Binds @Named("Default") fun bindDefaultGridLayout(impl: PartitionedGridLayout): GridLayout

    companion object {
        @Provides
        @SysUISingleton
        @GridConsistencyLog
        fun providesGridConsistencyLog(factory: LogBufferFactory): LogBuffer {
            return factory.create("GridConsistencyLog", 50)
        }

        @Provides
        @SysUISingleton
        @IconLabelVisibilityLog
        fun providesIconTileLabelVisibilityLog(factory: LogBufferFactory): LogBuffer {
            return factory.create("IconLabelVisibilityLog", 50)
        }

        @Provides
        @IntoSet
        fun provideGridLayout(gridLayout: InfiniteGridLayout): Pair<GridLayoutType, GridLayout> {
            return Pair(InfiniteGridLayoutType, gridLayout)
        }

        @Provides
        @IntoSet
        fun provideStretchedGridLayout(
            gridLayout: StretchedGridLayout
        ): Pair<GridLayoutType, GridLayout> {
            return Pair(StretchedGridLayoutType, gridLayout)
        }

        @Provides
        @IntoSet
        fun providePartitionedGridLayout(
            gridLayout: PartitionedGridLayout
        ): Pair<GridLayoutType, GridLayout> {
            return Pair(PartitionedGridLayoutType, gridLayout)
        }

        @Provides
        fun provideGridLayoutMap(
            entries: Set<@JvmSuppressWildcards Pair<GridLayoutType, GridLayout>>
        ): Map<GridLayoutType, GridLayout> {
            return entries.toMap()
        }

        @Provides
        fun provideGridLayoutTypes(
            entries: Set<@JvmSuppressWildcards Pair<GridLayoutType, GridLayout>>
        ): Set<GridLayoutType> {
            return entries.map { it.first }.toSet()
        }

        @Provides
        @IntoSet
        fun provideGridConsistencyInteractor(
            consistencyInteractor: InfiniteGridConsistencyInteractor
        ): Pair<GridLayoutType, GridTypeConsistencyInteractor> {
            return Pair(InfiniteGridLayoutType, consistencyInteractor)
        }

        @Provides
        @IntoSet
        fun provideStretchedGridConsistencyInteractor(
            consistencyInteractor: NoopGridConsistencyInteractor
        ): Pair<GridLayoutType, GridTypeConsistencyInteractor> {
            return Pair(StretchedGridLayoutType, consistencyInteractor)
        }

        @Provides
        @IntoSet
        fun providePartitionedGridConsistencyInteractor(
            consistencyInteractor: NoopGridConsistencyInteractor
        ): Pair<GridLayoutType, GridTypeConsistencyInteractor> {
            return Pair(PartitionedGridLayoutType, consistencyInteractor)
        }

        @Provides
        fun provideGridConsistencyInteractorMap(
            entries: Set<@JvmSuppressWildcards Pair<GridLayoutType, GridTypeConsistencyInteractor>>
        ): Map<GridLayoutType, GridTypeConsistencyInteractor> {
            return entries.toMap()
        }
    }
}
