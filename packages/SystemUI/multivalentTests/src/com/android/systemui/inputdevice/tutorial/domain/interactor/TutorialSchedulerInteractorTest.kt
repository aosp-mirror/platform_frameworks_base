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

package com.android.systemui.inputdevice.tutorial.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import com.android.systemui.inputdevice.tutorial.domain.interactor.TutorialSchedulerInteractor.TutorialType
import com.android.systemui.inputdevice.tutorial.inputDeviceTutorialLogger
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.commandline.commandRegistry
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.data.repository.FakeTouchpadRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TutorialSchedulerInteractorTest : SysuiTestCase() {

    private lateinit var underTest: TutorialSchedulerInteractor
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyboardRepository = FakeKeyboardRepository()
    private val touchpadRepository = FakeTouchpadRepository()
    private lateinit var schedulerRepository: TutorialSchedulerRepository

    @Before
    fun setup() {
        schedulerRepository =
            TutorialSchedulerRepository(
                context,
                testScope.backgroundScope,
                dataStoreName = "TutorialSchedulerInteractorTest",
            )
        underTest =
            TutorialSchedulerInteractor(
                keyboardRepository,
                touchpadRepository,
                schedulerRepository,
                kosmos.inputDeviceTutorialLogger,
                kosmos.commandRegistry,
                testScope.backgroundScope,
            )
    }

    @Test
    fun connectKeyboard_delayElapse_notifyForKeyboard() = runTestAndClear {
        keyboardRepository.setIsAnyKeyboardConnected(true)
        testScope.advanceTimeBy(LAUNCH_DELAY)
        notifyAndAssert(TutorialType.KEYBOARD)
    }

    @Test
    fun connectBothDevices_delayElapse_notifyForBoth() = runTestAndClear {
        keyboardRepository.setIsAnyKeyboardConnected(true)
        touchpadRepository.setIsAnyTouchpadConnected(true)
        testScope.advanceTimeBy(LAUNCH_DELAY)

        notifyAndAssert(TutorialType.BOTH)
    }

    @Test
    fun connectBothDevice_delayNotElapse_notifyNothing() = runTestAndClear {
        keyboardRepository.setIsAnyKeyboardConnected(true)
        touchpadRepository.setIsAnyTouchpadConnected(true)
        testScope.advanceTimeBy(A_SHORT_PERIOD_OF_TIME)

        notifyAndAssert(TutorialType.NONE)
    }

    @Test
    fun nothingConnect_delayElapse_notifyNothing() = runTestAndClear {
        keyboardRepository.setIsAnyKeyboardConnected(false)
        touchpadRepository.setIsAnyTouchpadConnected(false)
        testScope.advanceTimeBy(LAUNCH_DELAY)

        notifyAndAssert(TutorialType.NONE)
    }

    @Test
    fun connectKeyboard_thenTouchpad_delayElapse_notifyForBoth() = runTestAndClear {
        keyboardRepository.setIsAnyKeyboardConnected(true)
        testScope.advanceTimeBy(A_SHORT_PERIOD_OF_TIME)
        touchpadRepository.setIsAnyTouchpadConnected(true)
        testScope.advanceTimeBy(REMAINING_TIME)

        notifyAndAssert(TutorialType.BOTH)
    }

    @Test
    fun connectKeyboard_thenTouchpad_removeKeyboard_delayElapse_notifyNothing() = runTestAndClear {
        keyboardRepository.setIsAnyKeyboardConnected(true)
        testScope.advanceTimeBy(A_SHORT_PERIOD_OF_TIME)
        touchpadRepository.setIsAnyTouchpadConnected(true)
        keyboardRepository.setIsAnyKeyboardConnected(false)
        testScope.advanceTimeBy(REMAINING_TIME)

        notifyAndAssert(TutorialType.NONE)
    }

    private fun runTestAndClear(block: suspend () -> Unit) =
        testScope.runTest {
            try {
                block()
            } finally {
                schedulerRepository.clear()
            }
        }

    private fun notifyAndAssert(expectedTutorial: TutorialType) =
        testScope.backgroundScope.launch {
            val actualTutorial = underTest.tutorials.first()
            assertThat(actualTutorial).isEqualTo(expectedTutorial)

            when (expectedTutorial) {
                TutorialType.KEYBOARD -> {
                    assertThat(schedulerRepository.isNotified(DeviceType.KEYBOARD)).isTrue()
                    assertThat(schedulerRepository.isNotified(DeviceType.TOUCHPAD)).isFalse()
                }
                TutorialType.TOUCHPAD -> {
                    assertThat(schedulerRepository.isNotified(DeviceType.KEYBOARD)).isFalse()
                    assertThat(schedulerRepository.isNotified(DeviceType.TOUCHPAD)).isTrue()
                }
                TutorialType.BOTH -> {
                    assertThat(schedulerRepository.isNotified(DeviceType.KEYBOARD)).isTrue()
                    assertThat(schedulerRepository.isNotified(DeviceType.TOUCHPAD)).isTrue()
                }
                TutorialType.NONE -> {
                    assertThat(schedulerRepository.isNotified(DeviceType.KEYBOARD)).isFalse()
                    assertThat(schedulerRepository.isNotified(DeviceType.TOUCHPAD)).isFalse()
                }
            }
        }

    companion object {
        private val LAUNCH_DELAY = 72.hours
        private val A_SHORT_PERIOD_OF_TIME = 2.hours
        private val REMAINING_TIME = 70.hours
    }
}
