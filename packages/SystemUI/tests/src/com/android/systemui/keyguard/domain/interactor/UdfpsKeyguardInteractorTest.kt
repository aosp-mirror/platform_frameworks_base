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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class UdfpsKeyguardInteractorTest : SysuiTestCase() {
    private val burnInProgress = 1f
    private val burnInYOffset = 20
    private val burnInXOffset = 10

    private lateinit var testScope: TestScope
    private lateinit var configRepository: FakeConfigurationRepository
    private lateinit var bouncerRepository: KeyguardBouncerRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var fakeCommandQueue: FakeCommandQueue
    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var burnInInteractor: BurnInInteractor

    @Mock private lateinit var burnInHelper: BurnInHelperWrapper

    private lateinit var underTest: UdfpsKeyguardInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = TestScope()
        configRepository = FakeConfigurationRepository()
        keyguardRepository = FakeKeyguardRepository()
        bouncerRepository = FakeKeyguardBouncerRepository()
        fakeCommandQueue = FakeCommandQueue()
        featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.REFACTOR_UDFPS_KEYGUARD_VIEWS, true)
                set(Flags.FACE_AUTH_REFACTOR, false)
            }
        burnInInteractor =
            BurnInInteractor(
                context,
                burnInHelper,
                testScope.backgroundScope,
                configRepository,
                FakeSystemClock(),
            )

        underTest =
            UdfpsKeyguardInteractor(
                configRepository,
                burnInInteractor,
                KeyguardInteractor(
                    keyguardRepository,
                    fakeCommandQueue,
                    featureFlags,
                    bouncerRepository,
                    configRepository,
                ),
            )
    }

    @Test
    fun dozeChanges_updatesUdfpsAodModel() =
        testScope.runTest {
            val burnInOffsets by collectLastValue(underTest.burnInOffsets)
            initializeBurnInOffsets()

            // WHEN we're not dozing
            setAwake()
            runCurrent()

            // THEN burn in offsets are 0
            assertThat(burnInOffsets?.burnInProgress).isEqualTo(0f)
            assertThat(burnInOffsets?.burnInYOffset).isEqualTo(0)
            assertThat(burnInOffsets?.burnInXOffset).isEqualTo(0)

            // WHEN we're in the middle of the doze amount change
            keyguardRepository.setDozeAmount(.50f)
            runCurrent()

            // THEN burn in is updated (between 0 and the full offset)
            assertThat(burnInOffsets?.burnInProgress).isGreaterThan(0f)
            assertThat(burnInOffsets?.burnInYOffset).isGreaterThan(0)
            assertThat(burnInOffsets?.burnInXOffset).isGreaterThan(0)
            assertThat(burnInOffsets?.burnInProgress).isLessThan(burnInProgress)
            assertThat(burnInOffsets?.burnInYOffset).isLessThan(burnInYOffset)
            assertThat(burnInOffsets?.burnInXOffset).isLessThan(burnInXOffset)

            // WHEN we're fully dozing
            keyguardRepository.setDozeAmount(1f)
            runCurrent()

            // THEN burn in offsets are updated to final current values (for the given time)
            assertThat(burnInOffsets?.burnInProgress).isEqualTo(burnInProgress)
            assertThat(burnInOffsets?.burnInYOffset).isEqualTo(burnInYOffset)
            assertThat(burnInOffsets?.burnInXOffset).isEqualTo(burnInXOffset)
        }

    private fun initializeBurnInOffsets() {
        whenever(burnInHelper.burnInProgressOffset()).thenReturn(burnInProgress)
        whenever(burnInHelper.burnInOffset(anyInt(), /* xAxis */ eq(true)))
            .thenReturn(burnInXOffset)
        whenever(burnInHelper.burnInOffset(anyInt(), /* xAxis */ eq(false)))
            .thenReturn(burnInYOffset)
    }

    private fun setAwake() {
        keyguardRepository.setDozeAmount(0f)
        burnInInteractor.dozeTimeTick()

        bouncerRepository.setAlternateVisible(false)
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        bouncerRepository.setPrimaryShow(false)
        keyguardRepository.setWakefulnessModel(
            WakefulnessModel(
                WakefulnessState.AWAKE,
                WakeSleepReason.POWER_BUTTON,
                WakeSleepReason.POWER_BUTTON,
            )
        )
    }
}
