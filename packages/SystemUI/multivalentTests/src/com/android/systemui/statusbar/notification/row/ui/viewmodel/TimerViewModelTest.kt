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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import android.app.PendingIntent
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notification.row.data.repository.fakeNotificationRowRepository
import com.android.systemui.statusbar.notification.row.shared.IconModel
import com.android.systemui.statusbar.notification.row.shared.RichOngoingNotificationFlag
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel.TimerState.Paused
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
@SmallTest
@EnableFlags(RichOngoingNotificationFlag.FLAG_NAME)
class TimerViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeNotificationRowRepository

    private var contentModel: TimerContentModel?
        get() = repository.richOngoingContentModel.value as? TimerContentModel
        set(value) {
            repository.richOngoingContentModel.value = value
        }

    private lateinit var underTest: TimerViewModel

    @Before
    fun setup() {
        underTest = kosmos.getTimerViewModel(repository)
    }

    @Test
    fun labelShowsTheTimerName() =
        testScope.runTest {
            val label by collectLastValue(underTest.label)
            contentModel = pausedTimer(name = "Example Timer Name")
            assertThat(label).isEqualTo("Example Timer Name")
        }

    @Test
    fun pausedTimeRemainingFormatsWell() =
        testScope.runTest {
            val label by collectLastValue(underTest.pausedTime)
            contentModel = pausedTimer(timeRemaining = Duration.ofMinutes(3))
            assertThat(label).isEqualTo("3:00")
            contentModel = pausedTimer(timeRemaining = Duration.ofSeconds(119))
            assertThat(label).isEqualTo("1:59")
            contentModel = pausedTimer(timeRemaining = Duration.ofSeconds(121))
            assertThat(label).isEqualTo("2:01")
            contentModel = pausedTimer(timeRemaining = Duration.ofHours(1))
            assertThat(label).isEqualTo("1:00:00")
            contentModel = pausedTimer(timeRemaining = Duration.ofHours(24))
            assertThat(label).isEqualTo("24:00:00")
        }

    private fun pausedTimer(
        icon: IconModel = mock(),
        name: String = "example",
        timeRemaining: Duration = Duration.ofMinutes(3),
        resumeIntent: PendingIntent? = null,
        resetIntent: PendingIntent? = null
    ) =
        TimerContentModel(
            icon = icon,
            name = name,
            state =
                Paused(
                    timeRemaining = timeRemaining,
                    resumeIntent = resumeIntent,
                    resetIntent = resetIntent,
                )
        )
}
