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

import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.alarm.domain.model.AlarmTileModel
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.util.time.DateFormatUtil
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Observes alarm state changes providing the [AlarmTileModel]. */
class AlarmTileDataInteractor
@Inject
constructor(
    private val alarmController: NextAlarmController,
    private val dateFormatUtil: DateFormatUtil
) : QSTileDataInteractor<AlarmTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<AlarmTileModel> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
            val alarmCallback =
                NextAlarmController.NextAlarmChangeCallback {
                    val model =
                        if (it == null) AlarmTileModel.NoAlarmSet
                        else AlarmTileModel.NextAlarmSet(dateFormatUtil.is24HourFormat, it)
                    trySend(model)
                }
            alarmController.addCallback(alarmCallback)

            awaitClose { alarmController.removeCallback(alarmCallback) }
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}
