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

package com.android.systemui.education.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.education.data.repository.fakeEduClock
import com.android.systemui.education.shared.model.EducationInfo
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.tutorialSchedulerRepository
import com.android.systemui.keyboard.data.repository.keyboardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.data.repository.touchpadRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
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
    private val tutorialSchedulerRepository = kosmos.tutorialSchedulerRepository
    private val overviewProxyService = kosmos.mockOverviewProxyService

    private val underTest: KeyboardTouchpadEduInteractor = kosmos.keyboardTouchpadEduInteractor
    private val eduClock = kosmos.fakeEduClock
    private val initialDelayElapsedDuration =
        KeyboardTouchpadEduInteractor.initialDelayDuration + 1.seconds
    private val minIntervalForEduNotification =
        KeyboardTouchpadEduInteractor.minIntervalBetweenEdu + 1.seconds

    @Before
    fun setup() {
        underTest.start()
        contextualEduInteractor.start()
        testScope.launch {
            contextualEduInteractor.updateKeyboardFirstConnectionTime()
            contextualEduInteractor.updateTouchpadFirstConnectionTime()
        }
    }

    @Test
    fun newEducationToastBeforeMaxToastsPerSessionTriggered() =
        testScope.runTest {
            setUpForDeviceConnection()
            setUpForInitialDelayElapse()
            val model by collectLastValue(underTest.educationTriggered)

            triggerEducation(HOME)

            assertThat(model).isEqualTo(EducationInfo(HOME, EducationUiType.Toast, userId = 0))
        }

    @Test
    fun noEducationToastAfterMaxToastsPerSessionTriggered() =
        testScope.runTest {
            setUpForDeviceConnection()
            setUpForInitialDelayElapse()
            val models by collectValues(underTest.educationTriggered.filterNotNull())
            // Show two toasts of other gestures
            triggerEducation(HOME)
            triggerEducation(BACK)

            triggerEducation(OVERVIEW)

            // No new toast education besides the 2 triggered at first
            val firstEdu = EducationInfo(HOME, EducationUiType.Toast, userId = 0)
            val secondEdu = EducationInfo(BACK, EducationUiType.Toast, userId = 0)
            assertThat(models).containsExactly(firstEdu, secondEdu).inOrder()
        }

    @Test
    fun newEducationToastAfterMinIntervalElapsedWhenMaxToastsPerSessionTriggered() =
        testScope.runTest {
            setUpForDeviceConnection()
            setUpForInitialDelayElapse()
            val models by collectValues(underTest.educationTriggered.filterNotNull())
            // Show two toasts of other gestures
            triggerEducation(HOME)
            triggerEducation(BACK)

            // Trigger toast after an usage session has elapsed
            eduClock.offset(KeyboardTouchpadEduInteractor.usageSessionDuration + 1.seconds)
            triggerEducation(OVERVIEW)

            val firstEdu = EducationInfo(HOME, EducationUiType.Toast, userId = 0)
            val secondEdu = EducationInfo(BACK, EducationUiType.Toast, userId = 0)
            val thirdEdu = EducationInfo(OVERVIEW, EducationUiType.Toast, userId = 0)
            assertThat(models).containsExactly(firstEdu, secondEdu, thirdEdu).inOrder()
        }

    @Test
    fun newEducationNotificationAfterMaxToastsPerSessionTriggered() =
        testScope.runTest {
            setUpForDeviceConnection()
            setUpForInitialDelayElapse()
            val models by collectValues(underTest.educationTriggered.filterNotNull())
            triggerEducation(BACK)

            // Offset to let min interval for notification elapse so we could show edu notification
            // for BACK. It would be a new usage session too because the interval (7 days) is
            // longer than a usage session (3 days)
            eduClock.offset(minIntervalForEduNotification)
            triggerEducation(HOME)
            triggerEducation(OVERVIEW)
            triggerEducation(BACK)

            val firstEdu = EducationInfo(BACK, EducationUiType.Toast, userId = 0)
            val secondEdu = EducationInfo(HOME, EducationUiType.Toast, userId = 0)
            val thirdEdu = EducationInfo(OVERVIEW, EducationUiType.Toast, userId = 0)
            val fourthEdu = EducationInfo(BACK, EducationUiType.Notification, userId = 0)
            assertThat(models).containsExactly(firstEdu, secondEdu, thirdEdu, fourthEdu).inOrder()
        }

    private suspend fun setUpForInitialDelayElapse() {
        tutorialSchedulerRepository.updateLaunchTime(DeviceType.TOUCHPAD, eduClock.instant())
        tutorialSchedulerRepository.updateLaunchTime(DeviceType.KEYBOARD, eduClock.instant())
        eduClock.offset(initialDelayElapsedDuration)
    }

    private fun setUpForDeviceConnection() {
        touchpadRepository.setIsAnyTouchpadConnected(true)
        keyboardRepository.setIsAnyKeyboardConnected(true)
    }

    private fun getOverviewProxyListener(): OverviewProxyListener {
        val listenerCaptor = argumentCaptor<OverviewProxyListener>()
        verify(overviewProxyService).addCallback(listenerCaptor.capture())
        return listenerCaptor.firstValue
    }

    private fun TestScope.triggerEducation(gestureType: GestureType) {
        // Increment max number of signal to try triggering education
        for (i in 1..KeyboardTouchpadEduInteractor.MAX_SIGNAL_COUNT) {
            val listener = getOverviewProxyListener()
            listener.updateContextualEduStats(/* isTrackpadGesture= */ false, gestureType)
        }
        runCurrent()
    }
}
