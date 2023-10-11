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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class CommunalTutorialInteractorTest : SysuiTestCase() {

    @Mock private lateinit var userTracker: UserTracker

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: CommunalTutorialInteractor
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var communalTutorialRepository: FakeCommunalTutorialRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val withDeps = KeyguardInteractorFactory.create()
        keyguardInteractor = withDeps.keyguardInteractor
        keyguardRepository = withDeps.repository
        communalTutorialRepository = FakeCommunalTutorialRepository()

        underTest =
            CommunalTutorialInteractor(
                keyguardInteractor = keyguardInteractor,
                communalTutorialRepository = communalTutorialRepository,
            )

        whenever(userTracker.userHandle).thenReturn(mock())
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
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            assertThat(isTutorialAvailable).isFalse()
        }

    @Test
    fun tutorialAvailable_whenTutorialNotStarted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)
            assertThat(isTutorialAvailable).isTrue()
        }

    @Test
    fun tutorialAvailable_whenTutorialIsStarted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)
            assertThat(isTutorialAvailable).isTrue()
        }
}
