/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.education.data.repository.contextualEducationRepository
import com.android.systemui.education.data.repository.fakeEduClock
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.tutorialSchedulerRepository
import com.android.systemui.keyboard.data.repository.keyboardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.data.repository.touchpadRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardTouchpadStatsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.keyboardTouchpadEduStatsInteractor
    private val keyboardRepository = kosmos.keyboardRepository
    private val touchpadRepository = kosmos.touchpadRepository
    private val repository = kosmos.contextualEducationRepository
    private val fakeClock = kosmos.fakeEduClock
    private val tutorialSchedulerRepository = kosmos.tutorialSchedulerRepository
    private val initialDelayElapsedDuration =
        KeyboardTouchpadEduStatsInteractorImpl.initialDelayDuration + 1.seconds

    @Test
    fun dataUpdatedOnIncrementSignalCountWhenTouchpadConnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            touchpadRepository.setIsAnyTouchpadConnected(true)

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountWhenTouchpadDisconnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            touchpadRepository.setIsAnyTouchpadConnected(false)

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataUpdatedOnIncrementSignalCountWhenKeyboardConnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            keyboardRepository.setIsAnyKeyboardConnected(true)

            val model by collectLastValue(repository.readGestureEduModelFlow(ALL_APPS))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(ALL_APPS)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountWhenKeyboardDisconnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            keyboardRepository.setIsAnyKeyboardConnected(false)

            val model by collectLastValue(repository.readGestureEduModelFlow(ALL_APPS))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(ALL_APPS)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataAddedOnUpdateShortcutTriggerTime() =
        testScope.runTest {
            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            assertThat(model?.lastShortcutTriggeredTime).isNull()
            underTest.updateShortcutTriggerTime(BACK)
            assertThat(model?.lastShortcutTriggeredTime).isEqualTo(kosmos.fakeEduClock.instant())
        }

    @Test
    fun dataUpdatedOnIncrementSignalCountAfterInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            tutorialSchedulerRepository.updateLaunchTime(DeviceType.TOUCHPAD, fakeClock.instant())

            fakeClock.offset(initialDelayElapsedDuration)
            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountBeforeInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            tutorialSchedulerRepository.updateLaunchTime(DeviceType.TOUCHPAD, fakeClock.instant())

            // No offset to the clock to simulate update before initial delay
            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountWithoutOobeLaunchTime() =
        testScope.runTest {
            // No update to OOBE launch time to simulate no OOBE is launched yet
            setUpForDeviceConnection()

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    private suspend fun setUpForInitialDelayElapse() {
        tutorialSchedulerRepository.updateLaunchTime(DeviceType.TOUCHPAD, fakeClock.instant())
        tutorialSchedulerRepository.updateLaunchTime(DeviceType.KEYBOARD, fakeClock.instant())
        fakeClock.offset(initialDelayElapsedDuration)
    }

    private fun setUpForDeviceConnection() {
        touchpadRepository.setIsAnyTouchpadConnected(true)
        keyboardRepository.setIsAnyKeyboardConnected(true)
    }

    @After
    fun clear() {
        testScope.launch { tutorialSchedulerRepository.clearDataStore() }
    }
}
