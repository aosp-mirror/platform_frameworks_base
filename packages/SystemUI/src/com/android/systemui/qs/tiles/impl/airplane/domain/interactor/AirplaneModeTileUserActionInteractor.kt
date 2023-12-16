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

package com.android.systemui.qs.tiles.impl.airplane.domain.interactor

import android.content.Intent
import android.provider.Settings
import android.telephony.TelephonyManager
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.airplane.domain.model.AirplaneModeTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import javax.inject.Inject

/** Handles airplane mode tile clicks and long clicks. */
class AirplaneModeTileUserActionInteractor
@Inject
constructor(
    private val airplaneModeInteractor: AirplaneModeInteractor,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<AirplaneModeTileModel> {

    override suspend fun handleInput(input: QSTileInput<AirplaneModeTileModel>) =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    when (airplaneModeInteractor.setIsAirplaneMode(!data.isEnabled)) {
                        AirplaneModeInteractor.SetResult.SUCCESS -> {
                            // do nothing
                        }
                        AirplaneModeInteractor.SetResult.BLOCKED_BY_ECM -> {
                            qsTileIntentUserActionHandler.handle(
                                action.view,
                                Intent(TelephonyManager.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS),
                            )
                        }
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.view,
                        Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    )
                }
            }
        }
}
