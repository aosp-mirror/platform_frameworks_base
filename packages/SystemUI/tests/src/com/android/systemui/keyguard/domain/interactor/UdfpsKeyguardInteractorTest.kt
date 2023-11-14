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
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
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
import org.mockito.Mockito.verify
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
    private lateinit var shadeRepository: FakeShadeRepository
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var powerInteractor: PowerInteractor

    @Mock private lateinit var burnInHelper: BurnInHelperWrapper
    @Mock private lateinit var dialogManager: SystemUIDialogManager

    private lateinit var underTest: UdfpsKeyguardInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        configRepository = FakeConfigurationRepository()
        featureFlags = FakeFeatureFlags().apply { set(Flags.FACE_AUTH_REFACTOR, false) }
        KeyguardInteractorFactory.create(featureFlags = featureFlags).let {
            keyguardInteractor = it.keyguardInteractor
            keyguardRepository = it.repository
        }
        bouncerRepository = FakeKeyguardBouncerRepository()
        shadeRepository = FakeShadeRepository()
        fakeCommandQueue = FakeCommandQueue()
        burnInInteractor =
            BurnInInteractor(
                context,
                burnInHelper,
                testScope.backgroundScope,
                configRepository,
                keyguardInteractor
            )
        powerInteractor = PowerInteractorFactory.create().powerInteractor

        underTest =
            UdfpsKeyguardInteractor(
                configRepository,
                burnInInteractor,
                keyguardInteractor,
                shadeRepository,
                dialogManager,
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
            assertThat(burnInOffsets?.progress).isEqualTo(0f)
            assertThat(burnInOffsets?.y).isEqualTo(0)
            assertThat(burnInOffsets?.x).isEqualTo(0)

            // WHEN we're in the middle of the doze amount change
            keyguardRepository.setDozeAmount(.50f)
            runCurrent()

            // THEN burn in is updated (between 0 and the full offset)
            assertThat(burnInOffsets?.progress).isGreaterThan(0f)
            assertThat(burnInOffsets?.y).isGreaterThan(0)
            assertThat(burnInOffsets?.x).isGreaterThan(0)
            assertThat(burnInOffsets?.progress).isLessThan(burnInProgress)
            assertThat(burnInOffsets?.y).isLessThan(burnInYOffset)
            assertThat(burnInOffsets?.x).isLessThan(burnInXOffset)

            // WHEN we're fully dozing
            keyguardRepository.setDozeAmount(1f)
            runCurrent()

            // THEN burn in offsets are updated to final current values (for the given time)
            assertThat(burnInOffsets?.progress).isEqualTo(burnInProgress)
            assertThat(burnInOffsets?.y).isEqualTo(burnInYOffset)
            assertThat(burnInOffsets?.x).isEqualTo(burnInXOffset)
        }

    @Test
    fun dialogHideAffordances() =
        testScope.runTest {
            val dialogHideAffordancesRequest by
                collectLastValue(underTest.dialogHideAffordancesRequest)
            runCurrent()
            val captor = argumentCaptor<SystemUIDialogManager.Listener>()
            verify(dialogManager).registerListener(captor.capture())

            captor.value.shouldHideAffordances(false)
            assertThat(dialogHideAffordancesRequest).isEqualTo(false)

            captor.value.shouldHideAffordances(true)
            assertThat(dialogHideAffordancesRequest).isEqualTo(true)

            captor.value.shouldHideAffordances(false)
            assertThat(dialogHideAffordancesRequest).isEqualTo(false)
        }

    @Test
    fun shadeExpansion_updates() =
        testScope.runTest {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            val shadeExpansion by collectLastValue(underTest.shadeExpansion)
            assertThat(shadeExpansion).isEqualTo(0f)

            shadeRepository.setUdfpsTransitionToFullShadeProgress(.5f)
            assertThat(shadeExpansion).isEqualTo(.5f)

            shadeRepository.setUdfpsTransitionToFullShadeProgress(.7f)
            assertThat(shadeExpansion).isEqualTo(.7f)

            shadeRepository.setUdfpsTransitionToFullShadeProgress(.22f)
            assertThat(shadeExpansion).isEqualTo(.22f)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            assertThat(shadeExpansion).isEqualTo(1f)
        }

    @Test
    fun qsProgress_updates() =
        testScope.runTest {
            val qsProgress by collectLastValue(underTest.qsProgress)
            assertThat(qsProgress).isEqualTo(0f)

            shadeRepository.setQsExpansion(.22f)
            assertThat(qsProgress).isEqualTo(.44f)

            shadeRepository.setQsExpansion(.5f)
            assertThat(qsProgress).isEqualTo(1f)

            shadeRepository.setQsExpansion(.7f)
            assertThat(qsProgress).isEqualTo(1f)
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
        keyguardRepository.dozeTimeTick()

        bouncerRepository.setAlternateVisible(false)
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        bouncerRepository.setPrimaryShow(false)
        powerInteractor.setAwakeForTest()
    }
}
