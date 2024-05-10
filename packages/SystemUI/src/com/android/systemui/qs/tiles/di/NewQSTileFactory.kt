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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.custom.di.CustomTileComponent
import com.android.systemui.qs.tiles.impl.custom.di.QSTileConfigModule
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModelAdapter
import javax.inject.Inject
import javax.inject.Provider

// TODO(b/http://b/299909989): Rename the factory after rollout
@SysUISingleton
class NewQSTileFactory
@Inject
constructor(
    qsTileConfigProvider: QSTileConfigProvider,
    private val adapterFactory: QSTileViewModelAdapter.Factory,
    private val tileMap:
        Map<String, @JvmSuppressWildcards Provider<@JvmSuppressWildcards QSTileViewModel>>,
    private val customTileComponentBuilder: CustomTileComponent.Builder,
    private val customTileViewModelFactory: QSTileViewModelFactory.Component<CustomTileDataModel>,
) : QSFactory {

    init {
        QSPipelineFlagsRepository.assertNewTiles()
        for (viewModelTileSpec in tileMap.keys) {
            require(qsTileConfigProvider.hasConfig(viewModelTileSpec)) {
                "No config for $viewModelTileSpec"
            }
        }
    }

    override fun createTile(tileSpec: String): QSTile? {
        val viewModel: QSTileViewModel =
            when (val spec = TileSpec.create(tileSpec)) {
                is TileSpec.CustomTileSpec -> null
                is TileSpec.PlatformTileSpec -> tileMap[tileSpec]?.get()
                is TileSpec.Invalid -> null
            }
                ?: return null
        return adapterFactory.create(viewModel)
    }

    private fun createCustomTileViewModel(spec: TileSpec.CustomTileSpec): QSTileViewModel =
        customTileViewModelFactory.create(spec) { config ->
            customTileComponentBuilder.qsTileConfigModule(QSTileConfigModule(config)).build()
        }
}
