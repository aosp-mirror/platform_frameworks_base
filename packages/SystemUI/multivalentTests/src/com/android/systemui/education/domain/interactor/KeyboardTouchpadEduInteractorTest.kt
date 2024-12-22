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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyboardTouchpadEduInteractorTest(private val gestureType: GestureType) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val contextualEduInteractor = kosmos.contextualEducationInteractor
    private val touchpadRepository = kosmos.touchpadRepository
    private val keyboardRepository = kosmos.keyboardRepository
    private val userRepository = kosmos.fakeUserRepository

    private val underTest: KeyboardTouchpadEduInteractor = kosmos.keyboardTouchpadEduInteractor
    private val eduClock = kosmos.fakeEduClock
    private val minDurationForNextEdu =
        KeyboardTouchpadEduInteractor.minIntervalBetweenEdu + 1.seconds

    @Before
    fun setup() {
        underTest.start()
        contextualEduInteractor.start()
        userRepository.setUserInfos(USER_INFOS)
        testScope.launch {
            contextualEduInteractor.updateKeyboardFirstConnectionTime()
            contextualEduInteractor.updateTouchpadFirstConnectionTime()
        }
    }

    @Test
    fun newEducationInfoOnMaxSignalCountReached() =
        testScope.runTest {
            triggerMaxEducationSignals(gestureType)
            val model by collectLastValue(underTest.educationTriggered)

            assertThat(model?.gestureType).isEqualTo(gestureType)
        }

    @Test
    fun newEducationToastOn1stEducation() =
        testScope.runTest {
            val model by collectLastValue(underTest.educationTriggered)
            triggerMaxEducationSignals(gestureType)

            assertThat(model?.educationUiType).isEqualTo(EducationUiType.Toast)
        }

    @Test
    fun newEducationNotificationOn2ndEducation() =
        testScope.runTest {
            val model by collectLastValue(underTest.educationTriggered)
            triggerMaxEducationSignals(gestureType)
            // runCurrent() to trigger 1st education
            runCurrent()

            eduClock.offset(minDurationForNextEdu)
            triggerMaxEducationSignals(gestureType)

            assertThat(model?.educationUiType).isEqualTo(EducationUiType.Notification)
        }

    @Test
    fun noEducationInfoBeforeMaxSignalCountReached() =
        testScope.runTest {
            contextualEduInteractor.incrementSignalCount(gestureType)
            val model by collectLastValue(underTest.educationTriggered)
            assertThat(model).isNull()
        }

    @Test
    fun noEducationInfoWhenShortcutTriggeredPreviously() =
        testScope.runTest {
            val model by collectLastValue(underTest.educationTriggered)
            contextualEduInteractor.updateShortcutTriggerTime(gestureType)
            triggerMaxEducationSignals(gestureType)
            assertThat(model).isNull()
        }

    @Test
    fun no2ndEducationBeforeMinEduIntervalReached() =
        testScope.runTest {
            val models by collectValues(underTest.educationTriggered)
            triggerMaxEducationSignals(gestureType)
            runCurrent()

            // Offset a duration that is less than the required education interval
            eduClock.offset(1.seconds)
            triggerMaxEducationSignals(gestureType)
            runCurrent()

            assertThat(models.filterNotNull().size).isEqualTo(1)
        }

    @Test
    fun noNewEducationInfoAfterMaxEducationCountReached() =
        testScope.runTest {
            val models by collectValues(underTest.educationTriggered)
            // Trigger 2 educations
            triggerMaxEducationSignals(gestureType)
            runCurrent()
            eduClock.offset(minDurationForNextEdu)
            triggerMaxEducationSignals(gestureType)
            runCurrent()

            // Try triggering 3rd education
            eduClock.offset(minDurationForNextEdu)
            triggerMaxEducationSignals(gestureType)

            assertThat(models.filterNotNull().size).isEqualTo(2)
        }

    @Test
    fun startNewUsageSessionWhen2ndSignalReceivedAfterSessionDeadline() =
        testScope.runTest {
            val model by
                collectLastValue(
                    kosmos.contextualEducationRepository.readGestureEduModelFlow(gestureType)
                )
            contextualEduInteractor.incrementSignalCount(gestureType)
            eduClock.offset(KeyboardTouchpadEduInteractor.usageSessionDuration.plus(1.seconds))
            val secondSignalReceivedTime = eduClock.instant()
            contextualEduInteractor.incrementSignalCount(gestureType)

            assertThat(model)
                .isEqualTo(
                    GestureEduModel(
                        signalCount = 1,
                        usageSessionStartTime = secondSignalReceivedTime,
                        userId = 0,
                        gestureType = gestureType
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
            // Only All Apps needs to update the keyboard shortcut
            assumeTrue(gestureType == ALL_APPS)
            kosmos.contextualEducationRepository.setKeyboardShortcutTriggered(ALL_APPS)

            val model by
                collectLastValue(
                    kosmos.contextualEducationRepository.readGestureEduModelFlow(ALL_APPS)
                )
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

    private suspend fun setUpForDeviceConnection() {
        contextualEduInteractor.updateKeyboardFirstConnectionTime()
        contextualEduInteractor.updateTouchpadFirstConnectionTime()
    }

    companion object {
        private val USER_INFOS = listOf(UserInfo(101, "Second User", 0))

        @JvmStatic
        @Parameters(name = "{0}")
        fun getGestureTypes(): List<GestureType> {
            return listOf(BACK, HOME, OVERVIEW, ALL_APPS)
        }
    }
}
