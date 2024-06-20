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

package com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor

import android.app.UiModeManager
import android.content.Intent
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.uimodenight.domain.model.UiModeNightTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Handles ui mode night tile clicks. */
class UiModeNightTileUserActionInteractor
@Inject
constructor(
    @Background private val backgroundContext: CoroutineContext,
    private val uiModeManager: UiModeManager,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<UiModeNightTileModel> {

    override suspend fun handleInput(input: QSTileInput<UiModeNightTileModel>) =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (!input.data.isPowerSave) {
                        withContext(backgroundContext) {
                            uiModeManager.setNightModeActivated(!input.data.isNightMode)
                        }
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_DARK_THEME_SETTINGS)
                    )
                }
            }
        }
}
