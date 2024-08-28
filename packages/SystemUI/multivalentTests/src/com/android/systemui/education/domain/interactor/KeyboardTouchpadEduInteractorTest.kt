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

import android.content.pm.UserInfo
import android.hardware.input.InputManager
import android.hardware.input.KeyGestureEvent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.data.repository.contextualEducationRepository
import com.android.systemui.education.data.repository.fakeEduClock
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.keyboard.data.repository.keyboardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.data.repository.touchpadRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyboardTouchpadEduInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val contextualEduInteractor = kosmos.contextualEducationInteractor
    private val touchpadRepository = kosmos.touchpadRepository
    private val keyboardRepository = kosmos.keyboardRepository
    private val userRepository = kosmos.fakeUserRepository

    private val underTest: KeyboardTouchpadEduInteractor = kosmos.keyboardTouchpadEduInteractor
    private val eduClock = kosmos.fakeEduClock

    @Before
    fun setup() {
        underTest.start()
        contextualEduInteractor.start()
        userRepository.setUserInfos(USER_INFOS)
    }

    @Test
    fun newEducationInfoOnMaxSignalCountReached() =
        testScope.runTest {
            triggerMaxEducationSignals(BACK)
            val model by collectLastValue(underTest.educationTriggered)
            assertThat(model?.gestureType).isEqualTo(BACK)
        }

    @Test
    fun newEducationToastOn1stEducation() =
        testScope.runTest {
            val model by collectLastValue(underTest.educationTriggered)
            triggerMaxEducationSignals(BACK)
            assertThat(model?.educationUiType).isEqualTo(EducationUiType.Toast)
        }

    @Test
    fun newEducationNotificationOn2ndEducation() =
        testScope.runTest {
            val model by collectLastValue(underTest.educationTriggered)
            triggerMaxEducationSignals(BACK)
            // runCurrent() to trigger 1st education
            runCurrent()
            triggerMaxEducationSignals(BACK)
            assertThat(model?.educationUiType).isEqualTo(EducationUiType.Notification)
        }

    @Test
    fun noEducationInfoBeforeMaxSignalCountReached() =
        testScope.runTest {
            contextualEduInteractor.incrementSignalCount(BACK)
            val model by collectLastValue(underTest.educationTriggered)
            assertThat(model).isNull()
        }

    @Test
    fun noEducationInfoWhenShortcutTriggeredPreviously() =
        testScope.runTest {
            val model by collectLastValue(underTest.educationTriggered)
            contextualEduInteractor.updateShortcutTriggerTime(BACK)
            triggerMaxEducationSignals(BACK)
            assertThat(model).isNull()
        }

    @Test
    fun startNewUsageSessionWhen2ndSignalReceivedAfterSessionDeadline() =
        testScope.runTest {
            val model by
                collectLastValue(kosmos.contextualEducationRepository.readGestureEduModelFlow(BACK))
            contextualEduInteractor.incrementSignalCount(BACK)
            eduClock.offset(KeyboardTouchpadEduInteractor.usageSessionDuration.plus(1.seconds))
            val secondSignalReceivedTime = eduClock.instant()
            contextualEduInteractor.incrementSignalCount(BACK)

            assertThat(model)
                .isEqualTo(
                    GestureEduModel(
                        signalCount = 1,
                        usageSessionStartTime = secondSignalReceivedTime,
                        userId = 0
                    )
                )
        }

    @Test
    fun newTouchpadConnectionTimeOnFirstTouchpadConnected() =
        testScope.runTest {
            setIsAnyTouchpadConnected(true)
            val model = contextualEduInteractor.getEduDeviceConnectionTime()
            assertThat(model.touchpadFirstConnectionTime).isEqualTo(eduClock.instant())
        }

    @Test
    fun unchangedTouchpadConnectionTimeOnSecondConnection() =
        testScope.runTest {
            val firstConnectionTime = eduClock.instant()
            setIsAnyTouchpadConnected(true)
            setIsAnyTouchpadConnected(false)

            eduClock.offset(1.hours)
            setIsAnyTouchpadConnected(true)

            val model = contextualEduInteractor.getEduDeviceConnectionTime()
            assertThat(model.touchpadFirstConnectionTime).isEqualTo(firstConnectionTime)
        }

    @Test
    fun newTouchpadConnectionTimeOnUserChanged() =
        testScope.runTest {
            // Touchpad connected for user 0
            setIsAnyTouchpadConnected(true)

            // Change user
            eduClock.offset(1.hours)
            val newUserFirstConnectionTime = eduClock.instant()
            userRepository.setSelectedUserInfo(USER_INFOS[0])
            runCurrent()

            val model = contextualEduInteractor.getEduDeviceConnectionTime()
            assertThat(model.touchpadFirstConnectionTime).isEqualTo(newUserFirstConnectionTime)
        }

    @Test
    fun newKeyboardConnectionTimeOnKeyboardConnected() =
        testScope.runTest {
            setIsAnyKeyboardConnected(true)
            val model = contextualEduInteractor.getEduDeviceConnectionTime()
            assertThat(model.keyboardFirstConnectionTime).isEqualTo(eduClock.instant())
        }

    @Test
    fun unchangedKeyboardConnectionTimeOnSecondConnection() =
        testScope.runTest {
            val firstConnectionTime = eduClock.instant()
            setIsAnyKeyboardConnected(true)
            setIsAnyKeyboardConnected(false)

            eduClock.offset(1.hours)
            setIsAnyKeyboardConnected(true)

            val model = contextualEduInteractor.getEduDeviceConnectionTime()
            assertThat(model.keyboardFirstConnectionTime).isEqualTo(firstConnectionTime)
        }

    @Test
    fun newKeyboardConnectionTimeOnUserChanged() =
        testScope.runTest {
            // Keyboard connected for user 0
            setIsAnyKeyboardConnected(true)

            // Change user
            eduClock.offset(1.hours)
            val newUserFirstConnectionTime = eduClock.instant()
            userRepository.setSelectedUserInfo(USER_INFOS[0])
            runCurrent()

            val model = contextualEduInteractor.getEduDeviceConnectionTime()
            assertThat(model.keyboardFirstConnectionTime).isEqualTo(newUserFirstConnectionTime)
        }

    @Test
    fun updateShortcutTimeOnKeyboardShortcutTriggered() =
        testScope.runTest {
            // runCurrent() to trigger inputManager#registerKeyGestureEventListener in the
            // interactor
            runCurrent()
            val listenerCaptor =
                ArgumentCaptor.forClass(InputManager.KeyGestureEventListener::class.java)
            verify(kosmos.mockEduInputManager)
                .registerKeyGestureEventListener(any(), listenerCaptor.capture())

            val backGestureEvent =
                KeyGestureEvent(
                    /* deviceId= */ 1,
                    intArrayOf(KeyEvent.KEYCODE_ESCAPE),
                    KeyEvent.META_META_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_BACK
                )
            listenerCaptor.value.onKeyGestureEvent(backGestureEvent)

            val model by
                collectLastValue(kosmos.contextualEducationRepository.readGestureEduModelFlow(BACK))
            assertThat(model?.lastShortcutTriggeredTime).isEqualTo(eduClock.instant())
        }

    private suspend fun triggerMaxEducationSignals(gestureType: GestureType) {
        // Increment max number of signal to try triggering education
        for (i in 1..KeyboardTouchpadEduInteractor.MAX_SIGNAL_COUNT) {
            contextualEduInteractor.incrementSignalCount(gestureType)
        }
    }

    private fun TestScope.setIsAnyTouchpadConnected(isConnected: Boolean) {
        touchpadRepository.setIsAnyTouchpadConnected(isConnected)
        runCurrent()
    }

    private fun TestScope.setIsAnyKeyboardConnected(isConnected: Boolean) {
        keyboardRepository.setIsAnyKeyboardConnected(isConnected)
        runCurrent()
    }

    companion object {
        private val USER_INFOS =
            listOf(
                UserInfo(101, "Second User", 0),
            )
    }
}
