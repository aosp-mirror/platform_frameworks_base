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

package com.android.systemui.communal.domain.interactor

import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_STARTED
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalTutorialInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: CommunalTutorialInteractor
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var communalTutorialRepository: FakeCommunalTutorialRepository
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var communalInteractor: CommunalInteractor

    @Before
    fun setUp() {
        keyguardRepository = kosmos.fakeKeyguardRepository
        communalTutorialRepository = kosmos.fakeCommunalTutorialRepository
        communalRepository = kosmos.fakeCommunalRepository
        communalInteractor = kosmos.communalInteractor

        underTest = kosmos.communalTutorialInteractor

        setCommunalAvailable(true)
    }

    @Test
    fun tutorialUnavailable_whenKeyguardNotVisible() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)
            keyguardRepository.setKeyguardShowing(false)
            assertThat(isTutorialAvailable).isFalse()
        }

    @Test
    fun tutorialUnavailable_whenTutorialIsCompleted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalRepository.setIsCommunalHubShowing(false)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            assertThat(isTutorialAvailable).isFalse()
        }

    @Test
    fun tutorialUnavailable_whenCommunalNotAvailable() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            setCommunalAvailable(false)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)
            keyguardRepository.setKeyguardShowing(true)
            assertThat(isTutorialAvailable).isFalse()
        }

    @Test
    fun tutorialAvailable_whenTutorialNotStarted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalRepository.setIsCommunalHubShowing(false)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)
            assertThat(isTutorialAvailable).isTrue()
        }

    @Test
    fun tutorialAvailable_whenTutorialIsStarted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalRepository.setIsCommunalHubShowing(true)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)
            assertThat(isTutorialAvailable).isTrue()
        }

    @Test
    fun tutorialState_notStartedAndCommunalSceneShowing_tutorialStarted() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)

            communalRepository.setIsCommunalHubShowing(true)

            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialState_startedAndCommunalSceneShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)

            communalRepository.setIsCommunalHubShowing(true)

            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialState_completedAndCommunalSceneShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            communalRepository.setIsCommunalHubShowing(true)

            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialState_notStartedAndCommunalSceneNotShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)

            communalRepository.setIsCommunalHubShowing(false)

            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_NOT_STARTED)
        }

    @Test
    fun tutorialState_startedAndCommunalSceneNotShowing_tutorialCompleted() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            communalRepository.setIsCommunalHubShowing(true)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)

            communalRepository.setIsCommunalHubShowing(false)

            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialState_completedAndCommunalSceneNotShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            communalRepository.setIsCommunalHubShowing(true)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            communalRepository.setIsCommunalHubShowing(false)

            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    private fun setCommunalAvailable(available: Boolean) {
        if (available) {
            communalRepository.setIsCommunalEnabled(true)
            keyguardRepository.setIsEncryptedOrLockdown(false)
        } else {
            keyguardRepository.setIsEncryptedOrLockdown(true)
        }
    }
}
