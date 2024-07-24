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

package com.android.systemui.shade.data.repository

import android.app.AlarmManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback
import com.android.systemui.statusbar.policy.nextAlarmController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeHeaderClockRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val nextAlarmController = kosmos.nextAlarmController

    val underTest = kosmos.shadeHeaderClockRepository

    @Test
    fun nextAlarmIntent_updates() =
        testScope.runTest {
            assertThat(underTest.nextAlarmIntent).isNull()

            val callback =
                withArgCaptor<NextAlarmChangeCallback> {
                    verify(nextAlarmController).addCallback(capture())
                }

            callback.onNextAlarmChanged(AlarmManager.AlarmClockInfo(1L, mock()))
            assertThat(underTest.nextAlarmIntent).isNotNull()
        }
}
