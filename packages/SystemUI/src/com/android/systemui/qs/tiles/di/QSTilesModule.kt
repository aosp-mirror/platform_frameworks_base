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

package com.android.systemui.qs.tiles.di

import android.content.Context
import android.content.res.Resources.Theme
import com.android.systemui.qs.external.CustomTileStatePersister
import com.android.systemui.qs.external.CustomTileStatePersisterImpl
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerImpl
import com.android.systemui.qs.tiles.impl.custom.di.CustomTileComponent
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProviderImpl
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.Multibinds

/** Module listing subcomponents */
@Module(
    subcomponents =
        [
            CustomTileComponent::class,
        ]
)
interface QSTilesModule {

    /**
     * A map of internal QS tile ViewModels. Ensures that this can be injected even if it is empty
     */
    @Multibinds fun tileViewModelConfigs(): Map<String, QSTileConfig>

    /**
     * A map of internal QS tile ViewModels. Ensures that this can be injected even if it is empty
     */
    @Multibinds fun tileViewModelMap(): Map<String, QSTileViewModel>

    @Binds fun bindQSTileConfigProvider(impl: QSTileConfigProviderImpl): QSTileConfigProvider

    @Binds
    fun bindQSTileIntentUserInputHandler(
        impl: QSTileIntentUserInputHandlerImpl
    ): QSTileIntentUserInputHandler

    @Binds
    fun bindCustomTileStatePersister(impl: CustomTileStatePersisterImpl): CustomTileStatePersister

    companion object {

        @Provides fun provideTilesTheme(context: Context): Theme = context.theme
    }
}
