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
import com.android.systemui.inputdevice.data.model.UserDeviceConnectionStatus
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardTouchpadStatsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.keyboardTouchpadEduStatsInteractor
    private val repository = kosmos.contextualEducationRepository
    private val fakeClock = kosmos.fakeEduClock
    private val initialDelayElapsedDuration =
        KeyboardTouchpadEduStatsInteractorImpl.initialDelayDuration + 1.seconds

    @Test
    fun dataUpdatedOnIncrementSignalCountWhenTouchpadConnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            whenever(mockUserInputDeviceRepository.isAnyTouchpadConnectedForUser)
                .thenReturn(flowOf(UserDeviceConnectionStatus(isConnected = true, userId = 0)))

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountWhenTouchpadDisconnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            whenever(mockUserInputDeviceRepository.isAnyTouchpadConnectedForUser)
                .thenReturn(flowOf(UserDeviceConnectionStatus(isConnected = false, userId = 0)))

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataUpdatedOnIncrementSignalCountWhenKeyboardConnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            whenever(mockUserInputDeviceRepository.isAnyKeyboardConnectedForUser)
                .thenReturn(flowOf(UserDeviceConnectionStatus(isConnected = true, userId = 0)))

            val model by collectLastValue(repository.readGestureEduModelFlow(ALL_APPS))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(ALL_APPS)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountWhenKeyboardDisconnected() =
        testScope.runTest {
            setUpForInitialDelayElapse()
            whenever(mockUserInputDeviceRepository.isAnyKeyboardConnectedForUser)
                .thenReturn(flowOf(UserDeviceConnectionStatus(isConnected = false, userId = 0)))

            val model by collectLastValue(repository.readGestureEduModelFlow(ALL_APPS))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(ALL_APPS)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataUpdatedOnIncrementSignalCountAfterOobeLaunchInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            whenever(mockTutorialSchedulerRepository.launchTime(any<DeviceType>()))
                .thenReturn(fakeClock.instant())
            fakeClock.offset(initialDelayElapsedDuration)

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountBeforeOobeLaunchInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            whenever(mockTutorialSchedulerRepository.launchTime(any<DeviceType>()))
                .thenReturn(fakeClock.instant())

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataUpdatedOnIncrementSignalCountAfterTouchpadConnectionInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            repository.updateEduDeviceConnectionTime { model ->
                model.copy(touchpadFirstConnectionTime = fakeClock.instant())
            }
            fakeClock.offset(initialDelayElapsedDuration)

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountBeforeTouchpadConnectionInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            repository.updateEduDeviceConnectionTime { model ->
                model.copy(touchpadFirstConnectionTime = fakeClock.instant())
            }

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataUpdatedOnIncrementSignalCountAfterKeyboardConnectionInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            repository.updateEduDeviceConnectionTime { model ->
                model.copy(keyboardFirstConnectionTime = fakeClock.instant())
            }
            fakeClock.offset(initialDelayElapsedDuration)

            val model by collectLastValue(repository.readGestureEduModelFlow(ALL_APPS))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(ALL_APPS)

            assertThat(model?.signalCount).isEqualTo(originalValue + 1)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountBeforeKeyboardConnectionInitialDelay() =
        testScope.runTest {
            setUpForDeviceConnection()
            repository.updateEduDeviceConnectionTime { model ->
                model.copy(keyboardFirstConnectionTime = fakeClock.instant())
            }

            val model by collectLastValue(repository.readGestureEduModelFlow(ALL_APPS))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(ALL_APPS)

            assertThat(model?.signalCount).isEqualTo(originalValue)
        }

    @Test
    fun dataUnchangedOnIncrementSignalCountWhenNoSetupTime() =
        testScope.runTest {
            whenever(mockUserInputDeviceRepository.isAnyTouchpadConnectedForUser)
                .thenReturn(flowOf(UserDeviceConnectionStatus(isConnected = true, userId = 0)))

            val model by collectLastValue(repository.readGestureEduModelFlow(BACK))
            val originalValue = model!!.signalCount
            underTest.incrementSignalCount(BACK)

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

    private suspend fun setUpForInitialDelayElapse() {
        whenever(mockTutorialSchedulerRepository.launchTime(any<DeviceType>()))
            .thenReturn(fakeClock.instant())
        fakeClock.offset(initialDelayElapsedDuration)
    }

    private fun setUpForDeviceConnection() {
        whenever(mockUserInputDeviceRepository.isAnyTouchpadConnectedForUser)
            .thenReturn(flowOf(UserDeviceConnectionStatus(isConnected = true, userId = 0)))
        whenever(mockUserInputDeviceRepository.isAnyKeyboardConnectedForUser)
            .thenReturn(flowOf(UserDeviceConnectionStatus(isConnected = true, userId = 0)))
    }
}
