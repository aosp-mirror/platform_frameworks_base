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
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardBlueprintRepository
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.SplitShadeKeyguardBlueprint
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardBlueprintInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest by lazy { kosmos.keyguardBlueprintInteractor }
    private val keyguardBlueprintRepository by lazy { kosmos.keyguardBlueprintRepository }
    private val clockRepository by lazy { kosmos.fakeKeyguardClockRepository }
    private val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    private val fingerprintPropertyRepository by lazy { kosmos.fakeFingerprintPropertyRepository }

    @Mock private lateinit var clockController: ClockController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(clockController.config).thenReturn(ClockConfig("TEST", "Test", ""))
        fingerprintPropertyRepository.setProperties(
            sensorId = 1,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.POWER_BUTTON,
            sensorLocations = mapOf(),
        )
    }

    @Test
    fun testAppliesDefaultBlueprint() {
        testScope.runTest {
            val blueprintId by collectLastValue(underTest.blueprintId)
            kosmos.shadeRepository.setShadeLayoutWide(false)
            configurationRepository.onConfigurationChange()

            runCurrent()
            advanceUntilIdle()
            assertThat(blueprintId).isEqualTo(DefaultKeyguardBlueprint.Companion.DEFAULT)
        }
    }

    @Test
    @DisableSceneContainer
    fun testAppliesSplitShadeBlueprint() {
        testScope.runTest {
            val blueprintId by collectLastValue(underTest.blueprintId)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            configurationRepository.onConfigurationChange()

            runCurrent()
            advanceUntilIdle()
            assertThat(blueprintId).isEqualTo(SplitShadeKeyguardBlueprint.Companion.ID)
        }
    }

    @Test
    @EnableSceneContainer
    fun testDoesNotApplySplitShadeBlueprint() {
        testScope.runTest {
            val blueprintId by collectLastValue(underTest.blueprintId)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            clockRepository.setCurrentClock(clockController)
            configurationRepository.onConfigurationChange()

            runCurrent()
            advanceUntilIdle()
            assertThat(blueprintId).isEqualTo(DefaultKeyguardBlueprint.DEFAULT)
        }
    }

    @Test
    @DisableSceneContainer
    fun fingerprintPropertyInitialized_updatesBlueprint() {
        testScope.runTest {
            underTest.start()
            reset(keyguardBlueprintRepository)

            fingerprintPropertyRepository.supportsUdfps() // initialize properties

            runCurrent()
            advanceUntilIdle()
            verify(keyguardBlueprintRepository, times(2)).refreshBlueprint(any())
        }
    }

    @Test
    fun testRefreshFromConfigChange() {
        testScope.runTest {
            underTest.start()
            reset(keyguardBlueprintRepository)

            configurationRepository.onConfigurationChange()

            runCurrent()
            advanceUntilIdle()
            verify(keyguardBlueprintRepository, times(2)).refreshBlueprint(any())
        }
    }
}
