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

package com.android.systemui.qs.tiles.impl.night.domain.interactor

import android.content.Intent
import android.hardware.display.ColorDisplayManager.AUTO_MODE_CUSTOM_TIME
import android.provider.Settings
import com.android.systemui.accessibility.data.repository.NightDisplayRepository
import com.android.systemui.accessibility.qs.QSAccessibilityModule
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.impl.night.domain.model.NightDisplayTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

/** Handles night display tile clicks. */
class NightDisplayTileUserActionInteractor
@Inject
constructor(
    private val nightDisplayRepository: NightDisplayRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val qsLogger: QSTileLogger,
) : QSTileUserActionInteractor<NightDisplayTileModel> {
    override suspend fun handleInput(input: QSTileInput<NightDisplayTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    // Enroll in forced auto mode if eligible.
                    if (data.isEnrolledInForcedNightDisplayAutoMode) {
                        nightDisplayRepository.setNightDisplayAutoMode(AUTO_MODE_CUSTOM_TIME, user)
                        qsLogger.logInfo(spec, "Enrolled in forced night display auto mode")
                    }
                    nightDisplayRepository.setNightDisplayActivated(!data.isActivated, user)
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
                    )
                }
            }
        }

    companion object {
        val spec = TileSpec.create(QSAccessibilityModule.NIGHT_DISPLAY_TILE_SPEC)
    }
}
