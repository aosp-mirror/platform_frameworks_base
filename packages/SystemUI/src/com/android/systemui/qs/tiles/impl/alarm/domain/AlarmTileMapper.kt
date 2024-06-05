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

package com.android.systemui.qs.tiles.impl.alarm.domain

import android.content.res.Resources
import android.content.res.Resources.Theme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.alarm.domain.model.AlarmTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.util.time.SystemClock
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import javax.inject.Inject

/** Maps [AlarmTileModel] to [QSTileState]. */
class AlarmTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Theme,
    private val clock: SystemClock,
) : QSTileDataToStateMapper<AlarmTileModel> {
    companion object {
        val formatter12Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("E hh:mm a")
        val formatter24Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("E HH:mm")
        val formatterDateOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("E MMM d")
    }
    override fun map(config: QSTileConfig, data: AlarmTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            when (data) {
                is AlarmTileModel.NextAlarmSet -> {
                    activationState = QSTileState.ActivationState.ACTIVE

                    val alarmDateTime =
                        LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(data.alarmClockInfo.triggerTime),
                            TimeZone.getDefault().toZoneId()
                        )

                    val nowDateTime =
                        LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(clock.currentTimeMillis()),
                            TimeZone.getDefault().toZoneId()
                        )

                    // Edge case: If it's 8:00:30 right now and alarm is requested for next week at
                    // 8:00:29, we still want to show the date. Same at nanosecond level.
                    val nextWeekThisTime = nowDateTime.plusWeeks(1).withSecond(0).withNano(0)

                    // is the alarm over a week away?
                    val shouldShowDateAndHideTime = alarmDateTime >= nextWeekThisTime

                    if (shouldShowDateAndHideTime) {
                        secondaryLabel = formatterDateOnly.format(alarmDateTime)
                    } else {
                        secondaryLabel =
                            if (data.is24HourFormat) formatter24Hour.format(alarmDateTime)
                            else formatter12Hour.format(alarmDateTime)
                    }
                }
                is AlarmTileModel.NoAlarmSet -> {
                    activationState = QSTileState.ActivationState.INACTIVE
                    secondaryLabel = resources.getString(R.string.qs_alarm_tile_no_alarm)
                }
            }
            iconRes = R.drawable.ic_alarm
            icon = { Icon.Loaded(resources.getDrawable(iconRes!!, theme), null) }
            sideViewIcon = QSTileState.SideViewIcon.Chevron
            contentDescription = label
            supportedActions = setOf(QSTileState.UserAction.CLICK)
        }
}
