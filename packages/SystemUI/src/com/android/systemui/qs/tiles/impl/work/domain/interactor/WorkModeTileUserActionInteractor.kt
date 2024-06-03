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

package com.android.systemui.qs.tiles.impl.work.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.work.domain.model.WorkModeTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.phone.ManagedProfileController
import javax.inject.Inject

/** Handles airplane mode tile clicks and long clicks. */
class WorkModeTileUserActionInteractor
@Inject
constructor(
    private val profileController: ManagedProfileController,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<WorkModeTileModel> {
    override suspend fun handleInput(input: QSTileInput<WorkModeTileModel>) =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (data is WorkModeTileModel.HasActiveProfile) {
                        profileController.setWorkModeEnabled(!data.isEnabled)
                    }
                }
                is QSTileUserAction.LongClick -> {
                    if (data is WorkModeTileModel.HasActiveProfile) {
                        qsTileIntentUserActionHandler.handle(
                            action.expandable,
                            Intent(Settings.ACTION_MANAGED_PROFILE_SETTINGS)
                        )
                    }
                }
            }
        }
}
