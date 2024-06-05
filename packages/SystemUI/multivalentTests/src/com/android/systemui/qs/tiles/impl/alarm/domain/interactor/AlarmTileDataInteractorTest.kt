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

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.UserHandle
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.alarm.domain.model.AlarmTileModel
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.DateFormatUtil
import com.android.systemui.utils.leaks.FakeNextAlarmController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AlarmTileDataInteractorTest : SysuiTestCase() {
    private lateinit var dateFormatUtil: DateFormatUtil

    private val nextAlarmController = FakeNextAlarmController(LeakCheck())
    private lateinit var underTest: AlarmTileDataInteractor

    @Before
    fun setup() {
        dateFormatUtil = mock<DateFormatUtil>()
        underTest = AlarmTileDataInteractor(nextAlarmController, dateFormatUtil)
    }

    @Test
    fun alarmTriggerTimeDataMatchesTheController() = runTest {
        val expectedTriggerTime = 1L
        val alarmInfo = AlarmManager.AlarmClockInfo(expectedTriggerTime, mock<PendingIntent>())
        val dataList: List<AlarmTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        nextAlarmController.setNextAlarm(alarmInfo)
        runCurrent()
        nextAlarmController.setNextAlarm(null)
        runCurrent()

        assertThat(dataList).hasSize(3)
        assertThat(dataList[0]).isInstanceOf(AlarmTileModel.NoAlarmSet::class.java)
        assertThat(dataList[1]).isInstanceOf(AlarmTileModel.NextAlarmSet::class.java)
        val actualAlarmClockInfo = (dataList[1] as AlarmTileModel.NextAlarmSet).alarmClockInfo
        assertThat(actualAlarmClockInfo).isNotNull()
        val actualTriggerTime = actualAlarmClockInfo.triggerTime
        assertThat(actualTriggerTime).isEqualTo(expectedTriggerTime)
        assertThat(dataList[2]).isInstanceOf(AlarmTileModel.NoAlarmSet::class.java)
    }

    @Test
    fun dateFormatUtil24HourDataMatchesController() = runTest {
        val expectedValue = true
        whenever(dateFormatUtil.is24HourFormat).thenReturn(expectedValue)
        val alarmInfo = AlarmManager.AlarmClockInfo(1L, mock<PendingIntent>())
        nextAlarmController.setNextAlarm(alarmInfo)

        val model by
            collectLastValue(
                underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
            )
        runCurrent()

        assertThat(model).isNotNull()
        assertThat(model).isInstanceOf(AlarmTileModel.NextAlarmSet::class.java)
        val actualValue = (model as AlarmTileModel.NextAlarmSet).is24HourFormat
        assertThat(actualValue).isEqualTo(expectedValue)
    }

    @Test
    fun dateFormatUtil12HourDataMatchesController() = runTest {
        val expectedValue = false
        whenever(dateFormatUtil.is24HourFormat).thenReturn(expectedValue)
        val alarmInfo = AlarmManager.AlarmClockInfo(1L, mock<PendingIntent>())
        nextAlarmController.setNextAlarm(alarmInfo)

        val model by
            collectLastValue(
                underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
            )
        runCurrent()

        assertThat(model).isNotNull()
        assertThat(model).isInstanceOf(AlarmTileModel.NextAlarmSet::class.java)
        val actualValue = (model as AlarmTileModel.NextAlarmSet).is24HourFormat
        assertThat(actualValue).isEqualTo(expectedValue)
    }

    @Test
    fun alwaysAvailable() = runTest {
        val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

        assertThat(availability).hasSize(1)
        assertThat(availability.last()).isTrue()
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
