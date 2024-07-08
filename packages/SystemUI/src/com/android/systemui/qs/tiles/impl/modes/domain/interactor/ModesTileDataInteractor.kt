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

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

import android.app.Flags
import android.os.UserHandle
import android.provider.Settings.Global.ZEN_MODE_OFF
import com.android.settingslib.notification.data.repository.ZenModeRepository
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class ModesTileDataInteractor @Inject constructor(val zenModeRepository: ZenModeRepository) :
    QSTileDataInteractor<ModesTileModel> {
    // TODO(b/346519570): This should be checking for any enabled modes.
    private val zenModeActive =
        zenModeRepository.globalZenMode.map { it != ZEN_MODE_OFF }.distinctUntilChanged()

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<ModesTileModel> {
        return zenModeActive.map { ModesTileModel(isActivated = it) }
    }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(Flags.modesUi())
}
