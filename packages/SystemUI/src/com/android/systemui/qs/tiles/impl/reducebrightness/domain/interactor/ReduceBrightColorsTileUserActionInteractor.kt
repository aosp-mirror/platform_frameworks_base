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

import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import com.android.systemui.accessibility.extradim.ExtraDimDialogManager
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.ReduceBrightColorsController
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.reducebrightness.domain.model.ReduceBrightColorsTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

/** Handles reduce bright colors tile clicks. */
class ReduceBrightColorsTileUserActionInteractor
@Inject
constructor(
    @Main private val resources: Resources,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val reduceBrightColorsController: ReduceBrightColorsController,
    private val extraDimDialogManager: ExtraDimDialogManager,
) : QSTileUserActionInteractor<ReduceBrightColorsTileModel> {

    val isInUpgradeMode: Boolean = reduceBrightColorsController.isInUpgradeMode(resources)

    override suspend fun handleInput(input: QSTileInput<ReduceBrightColorsTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (isInUpgradeMode) {
                        extraDimDialogManager.dismissKeyguardIfNeededAndShowDialog(
                            action.expandable
                        )
                        return@with
                    }
                    reduceBrightColorsController.setReduceBrightColorsActivated(
                        !input.data.isEnabled
                    )
                }
                is QSTileUserAction.LongClick -> {
                    if (isInUpgradeMode) {
                        extraDimDialogManager.dismissKeyguardIfNeededAndShowDialog(
                            action.expandable
                        )
                        return@with
                    }
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_REDUCE_BRIGHT_COLORS_SETTINGS),
                    )
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }
}
