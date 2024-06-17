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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeInteractorLegacyImplTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val configurationRepository = kosmos.fakeConfigurationRepository
    val keyguardRepository = kosmos.fakeKeyguardRepository
    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val sceneInteractor = kosmos.sceneInteractor
    val shadeRepository = kosmos.fakeShadeRepository
    val userRepository = kosmos.fakeUserRepository

    val underTest = kosmos.shadeInteractorLegacyImpl

    @Test
    fun fullShadeExpansionWhenShadeLocked() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            shadeRepository.setLockscreenShadeExpansion(0.5f)

            assertThat(actual).isEqualTo(1f)
        }

    @Test
    fun fullShadeExpansionWhenStatusBarStateIsNotShadeLocked() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            shadeRepository.setLockscreenShadeExpansion(0.5f)
            assertThat(actual).isEqualTo(0.5f)

            shadeRepository.setLockscreenShadeExpansion(0.8f)
            assertThat(actual).isEqualTo(0.8f)
        }

    @Test
    fun shadeExpansionWhenInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(.7f)
            runCurrent()

            // THEN legacy shade expansion is passed through
            assertThat(actual).isEqualTo(.7f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsCollapsed() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyShadeExpansion(.6f)

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(.6f)
        }

    @Test
    fun userInteractingWithShade_shadeDraggedUpAndDown() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged halfway and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(.6f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade completes expansion stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadePartiallyExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade partially expanded
            shadeRepository.setLegacyShadeExpansion(.4f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN tracking is stopped
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade goes back to collapsed
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeCollapsed() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade expanded and not tracking input
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged up halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithQs_qsDraggedUpAndDown() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isUserInteractingWithQs)
            // GIVEN qs collapsed and not tracking input
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN qs tracking starts
            shadeRepository.setLegacyQsTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged down halfway
            shadeRepository.setQsExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully expanded but tracking is not stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully collapsed but tracking is not stopped
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged halfway and tracking is stopped
            shadeRepository.setQsExpansion(.6f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs completes expansion stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }
}
