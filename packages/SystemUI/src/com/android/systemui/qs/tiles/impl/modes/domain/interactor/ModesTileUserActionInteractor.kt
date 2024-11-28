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

import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.ui.dialog.ModesDialogDelegate
import javax.inject.Inject

@SysUISingleton
class ModesTileUserActionInteractor
@Inject
constructor(
    private val qsTileIntentUserInputHandler: QSTileIntentUserInputHandler,
    // TODO(b/353896370): The domain layer should not have to depend on the UI layer.
    private val dialogDelegate: ModesDialogDelegate,
    private val zenModeInteractor: ZenModeInteractor,
) : QSTileUserActionInteractor<ModesTileModel> {
    val longClickIntent = Intent(Settings.ACTION_ZEN_MODE_SETTINGS)

    override suspend fun handleInput(input: QSTileInput<ModesTileModel>) {
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    handleClick(action.expandable)
                }
                is QSTileUserAction.ToggleClick -> {
                    handleToggleClick(input.data)
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserInputHandler.handle(action.expandable, longClickIntent)
                }
            }
        }
    }

    suspend fun handleClick(expandable: Expandable?) {
        // Show a dialog with the list of modes to configure.
        dialogDelegate.showDialog(expandable)
    }

    fun handleToggleClick(modesTileModel: ModesTileModel) {
        if (QSComposeFragment.isUnexpectedlyInLegacyMode()) {
            return
        }

        // If no modes are on, turn on DND since it's the highest-priority mode. Otherwise, turn
        // them all off.
        // We want this toggle to work as a shortcut to DND in most cases, but it should still
        // correctly toggle the tile state to "off" as the user would expect when more modes are on.
        if (modesTileModel.activeModes.isEmpty()) {
            val dnd = zenModeInteractor.dndMode.value
            if (dnd == null) {
                Log.wtf(TAG, "Triggered DND but it's null!?")
                return
            }
            zenModeInteractor.activateMode(dnd)
        } else {
            zenModeInteractor.deactivateAllModes()
        }
    }

    companion object {
        const val TAG = "ModesTileUserActionInteractor"
    }
}
