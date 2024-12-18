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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.data.repository.IconAndNameCustomRepository
import com.android.systemui.qs.panels.data.repository.StockTilesRepository
import com.android.systemui.qs.panels.domain.model.EditTilesModel
import com.android.systemui.qs.panels.shared.model.EditTileData
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProvider
import javax.inject.Inject

@SysUISingleton
class EditTilesListInteractor
@Inject
constructor(
    private val stockTilesRepository: StockTilesRepository,
    private val qsTileConfigProvider: QSTileConfigProvider,
    private val iconAndNameCustomRepository: IconAndNameCustomRepository,
) {
    /**
     * Provides a list of the tiles to edit, with their UI information (icon, labels).
     *
     * The icons have the label as their content description.
     */
    suspend fun getTilesToEdit(): EditTilesModel {
        val stockTiles =
            stockTilesRepository.stockTiles.map {
                if (qsTileConfigProvider.hasConfig(it.spec)) {
                    val config = qsTileConfigProvider.getConfig(it.spec)
                    EditTileData(
                        it,
                        Icon.Resource(
                            config.uiConfig.iconRes,
                            ContentDescription.Resource(config.uiConfig.labelRes)
                        ),
                        Text.Resource(config.uiConfig.labelRes),
                        null,
                        category = config.category,
                    )
                } else {
                    EditTileData(
                        it,
                        Icon.Resource(
                            android.R.drawable.star_on,
                            ContentDescription.Loaded(it.spec)
                        ),
                        Text.Loaded(it.spec),
                        null,
                        category = TileCategory.UNKNOWN,
                    )
                }
            }
        return EditTilesModel(stockTiles, iconAndNameCustomRepository.getCustomTileData())
    }
}
