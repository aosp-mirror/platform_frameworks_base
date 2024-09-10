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

package com.android.systemui.qs.tiles.impl.onehanded.domain

import android.content.Intent
import android.provider.Settings
import com.android.systemui.accessibility.data.repository.OneHandedModeRepository
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.onehanded.domain.model.OneHandedModeTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

/** Handles one handed mode tile clicks. */
class OneHandedModeTileUserActionInteractor
@Inject
constructor(
    private val oneHandedModeRepository: OneHandedModeRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<OneHandedModeTileModel> {

    override suspend fun handleInput(input: QSTileInput<OneHandedModeTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    oneHandedModeRepository.setIsEnabled(
                        !data.isEnabled,
                        user,
                    )
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_ONE_HANDED_SETTINGS)
                    )
                }
            }
        }
}
