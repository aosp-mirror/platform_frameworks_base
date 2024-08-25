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

package com.android.systemui.qs.tiles.impl.alarm.domain.interactor

import android.content.Intent
import android.provider.AlarmClock
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.alarm.domain.model.AlarmTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

/** Handles alarm tile clicks. */
class AlarmTileUserActionInteractor
@Inject
constructor(
    private val inputHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<AlarmTileModel> {
    override suspend fun handleInput(input: QSTileInput<AlarmTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (
                        data is AlarmTileModel.NextAlarmSet &&
                            data.alarmClockInfo.showIntent != null
                    ) {
                        val pendingIndent = data.alarmClockInfo.showIntent
                        inputHandler.handle(action.expandable, pendingIndent, true)
                    } else {
                        inputHandler.handle(
                            action.expandable,
                            Intent(AlarmClock.ACTION_SHOW_ALARMS)
                        )
                    }
                }
                is QSTileUserAction.LongClick -> {}
                is QSTileUserAction.ToggleClick -> {}
            }
        }
}
