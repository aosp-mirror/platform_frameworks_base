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
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class BurnInInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val configurationRepository = kosmos.fakeConfigurationRepository
    val fakeKeyguardRepository = kosmos.fakeKeyguardRepository

    private val burnInOffset = 7
    private var burnInProgress = 0f

    @Mock private lateinit var burnInHelperWrapper: BurnInHelperWrapper

    private lateinit var underTest: BurnInInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        context
            .getOrCreateTestableResources()
            .addOverride(R.dimen.burn_in_prevention_offset_y, burnInOffset)
        whenever(burnInHelperWrapper.burnInOffset(anyInt(), anyBoolean())).thenReturn(burnInOffset)
        setBurnInProgress(.65f)

        underTest =
            BurnInInteractor(
                context,
                burnInHelperWrapper,
                kosmos.applicationCoroutineScope,
                kosmos.configurationInteractor,
                kosmos.keyguardInteractor,
            )
    }

    @Test
    fun udfpsBurnInOffset_updatesOnResolutionScaleChange() =
        testScope.runTest {
            val udfpsBurnInOffsetX by collectLastValue(underTest.deviceEntryIconXOffset)
            val udfpsBurnInOffsetY by collectLastValue(underTest.deviceEntryIconYOffset)
            assertThat(udfpsBurnInOffsetX).isEqualTo(burnInOffset)
            assertThat(udfpsBurnInOffsetY).isEqualTo(burnInOffset)

            configurationRepository.setScaleForResolution(3f)
            assertThat(udfpsBurnInOffsetX).isEqualTo(burnInOffset * 3)
            assertThat(udfpsBurnInOffsetY).isEqualTo(burnInOffset * 3)

            configurationRepository.setScaleForResolution(.5f)
            assertThat(udfpsBurnInOffsetX).isEqualTo(burnInOffset / 2)
            assertThat(udfpsBurnInOffsetY).isEqualTo(burnInOffset / 2)
        }

    @Test
    fun udfpsBurnInProgress_updatesOnDozeTimeTick() =
        testScope.runTest {
            val udfpsBurnInProgress by collectLastValue(underTest.udfpsProgress)
            assertThat(udfpsBurnInProgress).isEqualTo(burnInProgress)

            setBurnInProgress(.88f)
            fakeKeyguardRepository.dozeTimeTick(10)
            assertThat(udfpsBurnInProgress).isEqualTo(burnInProgress)

            setBurnInProgress(.92f)
            fakeKeyguardRepository.dozeTimeTick(20)
            assertThat(udfpsBurnInProgress).isEqualTo(burnInProgress)

            setBurnInProgress(.32f)
            fakeKeyguardRepository.dozeTimeTick(30)
            assertThat(udfpsBurnInProgress).isEqualTo(burnInProgress)
        }

    @Test
    fun keyguardBurnIn() =
        testScope.runTest {
            whenever(burnInHelperWrapper.burnInScale()).thenReturn(0.5f)

            val burnInModel by
                collectLastValue(
                    underTest.burnIn(
                        xDimenResourceId = R.dimen.burn_in_prevention_offset_x,
                        yDimenResourceId = R.dimen.burn_in_prevention_offset_y
                    )
                )

            // After time tick, returns the configured values
            fakeKeyguardRepository.dozeTimeTick(10)
            assertThat(burnInModel)
                .isEqualTo(
                    BurnInModel(
                        translationX = burnInOffset.toInt(),
                        translationY = burnInOffset.toInt(),
                        scale = 0.5f,
                    )
                )
        }

    private fun setBurnInProgress(progress: Float) {
        burnInProgress = progress
        whenever(burnInHelperWrapper.burnInProgressOffset()).thenReturn(burnInProgress)
    }
}
