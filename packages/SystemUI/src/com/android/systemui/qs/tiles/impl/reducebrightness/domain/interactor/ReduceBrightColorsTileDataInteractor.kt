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

package com.android.systemui.qs.tiles.impl.reducebrightness.domain.interactor

import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.ReduceBrightColorsController
import com.android.systemui.qs.dagger.QSFlagsModule.RBC_AVAILABLE
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.reducebrightness.domain.model.ReduceBrightColorsTileModel
import com.android.systemui.util.kotlin.isEnabled
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Observes reduce bright colors state changes providing the [ReduceBrightColorsTileModel]. */
class ReduceBrightColorsTileDataInteractor
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    @Named(RBC_AVAILABLE) private val isAvailable: Boolean,
    private val reduceBrightColorsController: ReduceBrightColorsController,
) : QSTileDataInteractor<ReduceBrightColorsTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<ReduceBrightColorsTileModel> {
        return reduceBrightColorsController
            .isEnabled()
            .distinctUntilChanged()
            .map { ReduceBrightColorsTileModel(it) }
            .flowOn(bgCoroutineContext)
    }
    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(isAvailable)
}
