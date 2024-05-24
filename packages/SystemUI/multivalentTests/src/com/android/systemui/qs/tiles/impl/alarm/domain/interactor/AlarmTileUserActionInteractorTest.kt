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

import android.app.AlarmManager.AlarmClockInfo
import android.app.PendingIntent
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx.click
import com.android.systemui.qs.tiles.impl.alarm.domain.model.AlarmTileModel
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AlarmTileUserActionInteractorTest : SysuiTestCase() {
    private val inputHandler = FakeQSTileIntentUserInputHandler()
    private val underTest = AlarmTileUserActionInteractor(inputHandler)

    @Test
    fun handleClickWithDefaultIntent() = runTest {
        val alarmInfo = AlarmClockInfo(1L, null)
        val inputModel = AlarmTileModel.NextAlarmSet(true, alarmInfo)

        underTest.handleInput(click(inputModel))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(AlarmClock.ACTION_SHOW_ALARMS)
        }
    }

    @Test
    fun handleClickWithPendingIntent() = runTest {
        val expectedIntent = mock<PendingIntent>()
        val alarmInfo = AlarmClockInfo(1L, expectedIntent)
        val inputModel = AlarmTileModel.NextAlarmSet(true, alarmInfo)

        underTest.handleInput(click(inputModel))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOnePendingIntentInput {
            assertThat(it.pendingIntent).isEqualTo(expectedIntent)
        }
    }
}
