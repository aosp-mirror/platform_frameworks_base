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

package com.android.systemui.statusbar.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@SmallTest
class KeyguardStatusBarViewModelTest : SysuiTestCase() {
    private val testScope = TestScope()
    private val keyguardRepository = FakeKeyguardRepository()
    private val keyguardInteractor =
        KeyguardInteractor(
            keyguardRepository,
            mock<CommandQueue>(),
            FakeFeatureFlagsClassic(),
            FakeSceneContainerFlags(),
            FakeKeyguardBouncerRepository(),
            FakeConfigurationRepository(),
            FakeShadeRepository()
        ) {
            SceneTestUtils(this).sceneInteractor()
        }

    private val underTest =
        KeyguardStatusBarViewModel(
            testScope.backgroundScope,
            keyguardInteractor,
        )

    @Test
    fun isVisible_dozing_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            keyguardRepository.setIsDozing(true)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_statusBarStateShade_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_statusBarStateShadeLocked_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_statusBarStateKeyguard_andNotDozing_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            keyguardRepository.setIsDozing(false)

            assertThat(latest).isTrue()
        }
}
