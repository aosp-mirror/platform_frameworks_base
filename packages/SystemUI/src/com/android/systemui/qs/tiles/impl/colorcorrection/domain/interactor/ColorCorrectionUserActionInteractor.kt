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

import android.content.Intent
import android.provider.Settings
import com.android.systemui.accessibility.data.repository.ColorCorrectionRepository
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.model.ColorCorrectionTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

/** Handles color correction tile clicks. */
class ColorCorrectionUserActionInteractor
@Inject
constructor(
    private val colorCorrectionRepository: ColorCorrectionRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<ColorCorrectionTileModel> {

    override suspend fun handleInput(input: QSTileInput<ColorCorrectionTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    colorCorrectionRepository.setIsEnabled(
                        !data.isEnabled,
                        user,
                    )
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_COLOR_CORRECTION_SETTINGS)
                    )
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }
}
