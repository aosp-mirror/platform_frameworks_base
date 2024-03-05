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

package com.android.systemui.qs.tiles.impl.rotation.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.rotation.domain.model.RotationLockTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.policy.RotationLockController
import javax.inject.Inject

/** Handles rotation lock tile clicks. */
class RotationLockTileUserActionInteractor
@Inject
constructor(
    private val controller: RotationLockController,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<RotationLockTileModel> {

    override suspend fun handleInput(input: QSTileInput<RotationLockTileModel>) {
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    controller.setRotationLocked(!data.isRotationLocked, CALLER)
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.view,
                        Intent(Settings.ACTION_AUTO_ROTATE_SETTINGS)
                    )
                }
            }
        }
    }

    companion object {
        private const val CALLER = "QSTileUserActionInteractor#handleInput"
    }
}
