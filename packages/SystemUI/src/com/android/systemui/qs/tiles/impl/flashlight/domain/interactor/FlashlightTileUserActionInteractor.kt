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

package com.android.systemui.qs.tiles.impl.flashlight.domain.interactor

import android.app.ActivityManager
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.flashlight.domain.model.FlashlightTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject

/** Handles flashlight tile clicks. */
class FlashlightTileUserActionInteractor
@Inject
constructor(
    private val flashlightController: FlashlightController,
) : QSTileUserActionInteractor<FlashlightTileModel> {

    override suspend fun handleInput(input: QSTileInput<FlashlightTileModel>) =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (
                        !ActivityManager.isUserAMonkey() &&
                            input.data is FlashlightTileModel.FlashlightAvailable
                    ) {
                        flashlightController.setFlashlight(!input.data.isEnabled)
                    }
                }
                is QSTileUserAction.ToggleClick -> {}
                else -> {}
            }
        }
}
