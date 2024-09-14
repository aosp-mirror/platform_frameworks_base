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
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.touchpad.data.repository.FakeTouchpadRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class TutorialSchedulerInteractorTest : SysuiTestCase() {

    private lateinit var underTest: TutorialSchedulerInteractor
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private lateinit var dataStoreScope: CoroutineScope
    private val keyboardRepository = FakeKeyboardRepository()
    private val touchpadRepository = FakeTouchpadRepository()
    private lateinit var schedulerRepository: TutorialSchedulerRepository

    @Before
    fun setup() {
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined)
        schedulerRepository =
            TutorialSchedulerRepository(
                context,
                dataStoreScope,
                dataStoreName = "TutorialSchedulerInteractorTest"
            )
        underTest =
            TutorialSchedulerInteractor(keyboardRepository, touchpadRepository, schedulerRepository)
    }

    @After
    fun clear() {
        runBlocking { schedulerRepository.clearDataStore() }
        dataStoreScope.cancel()
    }

    @Test
    fun connectKeyboard_delayElapse_launchForKeyboard() =
        testScope.runTest {
            launchAndAssert(TutorialType.KEYBOARD)

            keyboardRepository.setIsAnyKeyboardConnected(true)
            advanceTimeBy(LAUNCH_DELAY)
        }

    @Test
    fun connectBothDevices_delayElapse_launchForBoth() =
        testScope.runTest {
            launchAndAssert(TutorialType.BOTH)

            keyboardRepository.setIsAnyKeyboardConnected(true)
            touchpadRepository.setIsAnyTouchpadConnected(true)
            advanceTimeBy(LAUNCH_DELAY)
        }

    @Test
    fun connectBothDevice_delayNotElapse_launchNothing() =
        testScope.runTest {
            launchAndAssert(TutorialType.NONE)

            keyboardRepository.setIsAnyKeyboardConnected(true)
            touchpadRepository.setIsAnyTouchpadConnected(true)
            advanceTimeBy(A_SHORT_PERIOD_OF_TIME)
        }

    @Test
    fun nothingConnect_delayElapse_launchNothing() =
        testScope.runTest {
            launchAndAssert(TutorialType.NONE)

            keyboardRepository.setIsAnyKeyboardConnected(false)
            touchpadRepository.setIsAnyTouchpadConnected(false)
            advanceTimeBy(LAUNCH_DELAY)
        }

    @Test
    fun connectKeyboard_thenTouchpad_delayElapse_launchForBoth() =
        testScope.runTest {
            launchAndAssert(TutorialType.BOTH)

            keyboardRepository.setIsAnyKeyboardConnected(true)
            advanceTimeBy(A_SHORT_PERIOD_OF_TIME)
            touchpadRepository.setIsAnyTouchpadConnected(true)
            advanceTimeBy(REMAINING_TIME)
        }

    @Test
    fun connectKeyboard_thenTouchpad_removeKeyboard_delayElapse_launchNothing() =
        testScope.runTest {
            launchAndAssert(TutorialType.NONE)

            keyboardRepository.setIsAnyKeyboardConnected(true)
            advanceTimeBy(A_SHORT_PERIOD_OF_TIME)
            touchpadRepository.setIsAnyTouchpadConnected(true)
            keyboardRepository.setIsAnyKeyboardConnected(false)
            advanceTimeBy(REMAINING_TIME)
        }

    private suspend fun launchAndAssert(expectedTutorial: TutorialType) =
        testScope.backgroundScope.launch {
            val actualTutorial = underTest.tutorials.first()
            assertThat(actualTutorial).isEqualTo(expectedTutorial)

            // TODO: need to update after we move launch into the tutorial
            when (expectedTutorial) {
                TutorialType.KEYBOARD -> {
                    assertThat(schedulerRepository.isLaunched(DeviceType.KEYBOARD)).isTrue()
                    assertThat(schedulerRepository.isLaunched(DeviceType.TOUCHPAD)).isFalse()
                }
                TutorialType.TOUCHPAD -> {
                    assertThat(schedulerRepository.isLaunched(DeviceType.KEYBOARD)).isFalse()
                    assertThat(schedulerRepository.isLaunched(DeviceType.TOUCHPAD)).isTrue()
                }
                TutorialType.BOTH -> {
                    assertThat(schedulerRepository.isLaunched(DeviceType.KEYBOARD)).isTrue()
                    assertThat(schedulerRepository.isLaunched(DeviceType.TOUCHPAD)).isTrue()
                }
                TutorialType.NONE -> {
                    assertThat(schedulerRepository.isLaunched(DeviceType.KEYBOARD)).isFalse()
                    assertThat(schedulerRepository.isLaunched(DeviceType.TOUCHPAD)).isFalse()
                }
            }
        }

    companion object {
        private val LAUNCH_DELAY = 72.hours
        private val A_SHORT_PERIOD_OF_TIME = 2.hours
        private val REMAINING_TIME = 70.hours
    }
}
