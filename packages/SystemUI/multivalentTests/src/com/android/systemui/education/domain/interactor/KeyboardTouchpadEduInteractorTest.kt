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
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.data.repository.contextualEducationRepository
import com.android.systemui.education.data.repository.fakeEduClock
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardTouchpadEduInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val contextualEduInteractor = kosmos.contextualEducationInteractor
    private val underTest: KeyboardTouchpadEduInteractor = kosmos.keyboardTouchpadEduInteractor
    private val eduClock = kosmos.fakeEduClock

    @Before
    fun setup() {
        underTest.start()
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
    @kotlinx.coroutines.ExperimentalCoroutinesApi
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
                        usageSessionStartTime = secondSignalReceivedTime
                    )
                )
        }

    private suspend fun triggerMaxEducationSignals(gestureType: GestureType) {
        // Increment max number of signal to try triggering education
        for (i in 1..KeyboardTouchpadEduInteractor.MAX_SIGNAL_COUNT) {
            contextualEduInteractor.incrementSignalCount(gestureType)
        }
    }
}
