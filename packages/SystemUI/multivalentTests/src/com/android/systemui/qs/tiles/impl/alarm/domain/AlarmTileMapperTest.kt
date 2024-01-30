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

import android.app.AlarmManager
import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.alarm.domain.model.AlarmTileModel
import com.android.systemui.qs.tiles.impl.alarm.qsAlarmTileConfig
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AlarmTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val alarmTileConfig = kosmos.qsAlarmTileConfig
    // Using lazy (versus =) to make sure we override the right context -- see b/311612168
    private val mapper by lazy {
        AlarmTileMapper(
            context.orCreateTestableResources
                .apply { addOverride(R.drawable.ic_alarm, TestStubDrawable()) }
                .resources,
            context.theme
        )
    }

    @Test
    fun notAlarmSet() {
        val inputModel = AlarmTileModel.NoAlarmSet

        val outputState = mapper.map(alarmTileConfig, inputModel)

        val expectedState =
            createAlarmTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(R.string.qs_alarm_tile_no_alarm)
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun nextAlarmSet24HourFormat() {
        val triggerTime = 1L
        val inputModel =
            AlarmTileModel.NextAlarmSet(true, AlarmManager.AlarmClockInfo(triggerTime, null))

        val outputState = mapper.map(alarmTileConfig, inputModel)

        val localDateTime =
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(triggerTime),
                TimeZone.getDefault().toZoneId()
            )
        val expectedSecondaryLabel = AlarmTileMapper.formatter24Hour.format(localDateTime)
        val expectedState =
            createAlarmTileState(QSTileState.ActivationState.ACTIVE, expectedSecondaryLabel)
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun nextAlarmSet12HourFormat() {
        val triggerTime = 1L
        val inputModel =
            AlarmTileModel.NextAlarmSet(false, AlarmManager.AlarmClockInfo(triggerTime, null))

        val outputState = mapper.map(alarmTileConfig, inputModel)

        val localDateTime =
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(triggerTime),
                TimeZone.getDefault().toZoneId()
            )
        val expectedSecondaryLabel = AlarmTileMapper.formatter12Hour.format(localDateTime)
        val expectedState =
            createAlarmTileState(QSTileState.ActivationState.ACTIVE, expectedSecondaryLabel)
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createAlarmTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String
    ): QSTileState {
        val label = context.getString(R.string.status_bar_alarm)
        return QSTileState(
            { Icon.Loaded(context.getDrawable(R.drawable.ic_alarm)!!, null) },
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK),
            label,
            null,
            QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }
}
