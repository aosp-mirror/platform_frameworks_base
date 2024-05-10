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

package com.android.systemui.qs.tiles.impl.custom.di

import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.custom.CustomTileInteractor
import com.android.systemui.qs.tiles.impl.custom.CustomTileMapper
import com.android.systemui.qs.tiles.impl.custom.CustomTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileDefaultsRepositoryImpl
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileRepositoryImpl
import com.android.systemui.qs.tiles.impl.custom.di.bound.CustomTileBoundComponent
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import dagger.Binds
import dagger.Module

/** Provides bindings for QSTile interfaces */
@Module(subcomponents = [CustomTileBoundComponent::class])
interface CustomTileModule {

    @Binds
    fun bindDataInteractor(
        dataInteractor: CustomTileInteractor
    ): QSTileDataInteractor<CustomTileDataModel>

    @Binds
    fun bindUserActionInteractor(
        userActionInteractor: CustomTileUserActionInteractor
    ): QSTileUserActionInteractor<CustomTileDataModel>

    @Binds
    fun bindMapper(customTileMapper: CustomTileMapper): QSTileDataToStateMapper<CustomTileDataModel>

    @Binds
    fun bindCustomTileDefaultsRepository(
        impl: CustomTileDefaultsRepositoryImpl
    ): CustomTileDefaultsRepository

    @Binds fun bindCustomTileRepository(impl: CustomTileRepositoryImpl): CustomTileRepository
}
