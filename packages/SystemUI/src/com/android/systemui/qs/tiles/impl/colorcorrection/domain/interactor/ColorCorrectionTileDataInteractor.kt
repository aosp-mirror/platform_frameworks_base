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

package com.android.systemui.qs.tiles.impl.colorcorrection.domain.interactor

import android.os.UserHandle
import com.android.systemui.accessibility.data.repository.ColorCorrectionRepository
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.model.ColorCorrectionTileModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Observes color correction state changes providing the [ColorCorrectionTileModel]. */
class ColorCorrectionTileDataInteractor
@Inject
constructor(
    private val colorCorrectionRepository: ColorCorrectionRepository,
) : QSTileDataInteractor<ColorCorrectionTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<ColorCorrectionTileModel> {
        return colorCorrectionRepository.isEnabled(user).map { ColorCorrectionTileModel(it) }
    }
    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}
