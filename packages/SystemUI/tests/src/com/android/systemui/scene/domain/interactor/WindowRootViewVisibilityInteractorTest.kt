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

package com.android.systemui.scene.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@SmallTest
class WindowRootViewVisibilityInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val windowRootViewVisibilityRepository = WindowRootViewVisibilityRepository()
    private val keyguardRepository = FakeKeyguardRepository()

    private val underTest =
        WindowRootViewVisibilityInteractor(
            testScope.backgroundScope,
            windowRootViewVisibilityRepository,
            keyguardRepository,
        )

    @Test
    fun isLockscreenOrShadeVisible_true() {
        underTest.setIsLockscreenOrShadeVisible(true)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isTrue()
    }

    @Test
    fun isLockscreenOrShadeVisible_false() {
        underTest.setIsLockscreenOrShadeVisible(false)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isFalse()
    }

    @Test
    fun isLockscreenOrShadeVisible_matchesRepo() {
        windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(true)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isTrue()

        windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(false)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isFalse()
    }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_notVisible_false() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)
            setWakefulness(WakefulnessState.AWAKE)

            underTest.setIsLockscreenOrShadeVisible(false)

            assertThat(actual).isFalse()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_deviceAsleep_false() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)
            underTest.setIsLockscreenOrShadeVisible(true)

            setWakefulness(WakefulnessState.ASLEEP)

            assertThat(actual).isFalse()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_visibleAndAwake_true() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)

            underTest.setIsLockscreenOrShadeVisible(true)
            setWakefulness(WakefulnessState.AWAKE)

            assertThat(actual).isTrue()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_visibleAndStartingToWake_true() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)

            underTest.setIsLockscreenOrShadeVisible(true)
            setWakefulness(WakefulnessState.STARTING_TO_WAKE)

            assertThat(actual).isTrue()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_visibleAndStartingToSleep_true() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)

            underTest.setIsLockscreenOrShadeVisible(true)
            setWakefulness(WakefulnessState.STARTING_TO_SLEEP)

            assertThat(actual).isTrue()
        }

    private fun setWakefulness(state: WakefulnessState) {
        val model = WakefulnessModel(state, WakeSleepReason.OTHER, WakeSleepReason.OTHER)
        keyguardRepository.setWakefulnessModel(model)
    }
}
