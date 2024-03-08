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
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor.Companion.SPLIT_SHADE_WEATHER_CLOCK_BLUEPRINT_ID
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor.Companion.WEATHER_CLOCK_BLUEPRINT_ID
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.SplitShadeKeyguardBlueprint
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardBlueprintInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest by lazy { kosmos.keyguardBlueprintInteractor }
    private val clockRepository by lazy { kosmos.fakeKeyguardClockRepository }
    private val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    private val fingerprintPropertyRepository by lazy { kosmos.fakeFingerprintPropertyRepository }

    @Mock private lateinit var clockController: ClockController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testAppliesDefaultBlueprint() {
        testScope.runTest {
            val blueprint by collectLastValue(underTest.blueprint)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onConfigurationChange()
            runCurrent()

            assertThat(blueprint?.id).isEqualTo(DefaultKeyguardBlueprint.Companion.DEFAULT)
        }
    }

    @Test
    fun testAppliesSplitShadeBlueprint() {
        testScope.runTest {
            val blueprint by collectLastValue(underTest.blueprint)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onConfigurationChange()
            runCurrent()

            assertThat(blueprint?.id).isEqualTo(SplitShadeKeyguardBlueprint.Companion.ID)
        }
    }

    @Test
    fun fingerprintPropertyInitialized_updatesBlueprint() {
        testScope.runTest {
            val blueprint by collectLastValue(underTest.blueprint)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            fingerprintPropertyRepository.supportsUdfps() // initialize properties
            runCurrent()
            assertThat(blueprint?.id).isEqualTo(SplitShadeKeyguardBlueprint.Companion.ID)
        }
    }

    @Test
    fun composeLockscreenOff_DoesAppliesSplitShadeWeatherClockBlueprint() {
        testScope.runTest {
            mSetFlagsRule.disableFlags(Flags.FLAG_COMPOSE_LOCKSCREEN)
            val blueprint by collectLastValue(underTest.blueprint)
            whenever(clockController.config)
                .thenReturn(
                    ClockConfig(
                        id = "DIGITAL_CLOCK_WEATHER",
                        name = "clock",
                        description = "clock",
                    )
                )
            clockRepository.setCurrentClock(clockController)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onConfigurationChange()
            runCurrent()

            assertThat(blueprint?.id).isNotEqualTo(SPLIT_SHADE_WEATHER_CLOCK_BLUEPRINT_ID)
        }
    }

    @Test
    fun testDoesAppliesSplitShadeWeatherClockBlueprint() {
        testScope.runTest {
            mSetFlagsRule.enableFlags(Flags.FLAG_COMPOSE_LOCKSCREEN)
            val blueprint by collectLastValue(underTest.blueprint)
            whenever(clockController.config)
                .thenReturn(
                    ClockConfig(
                        id = "DIGITAL_CLOCK_WEATHER",
                        name = "clock",
                        description = "clock",
                    )
                )
            clockRepository.setCurrentClock(clockController)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onConfigurationChange()
            runCurrent()

            assertThat(blueprint?.id).isEqualTo(SPLIT_SHADE_WEATHER_CLOCK_BLUEPRINT_ID)
        }
    }

    @Test
    fun testAppliesWeatherClockBlueprint() {
        testScope.runTest {
            mSetFlagsRule.enableFlags(Flags.FLAG_COMPOSE_LOCKSCREEN)
            val blueprint by collectLastValue(underTest.blueprint)
            whenever(clockController.config)
                .thenReturn(
                    ClockConfig(
                        id = "DIGITAL_CLOCK_WEATHER",
                        name = "clock",
                        description = "clock",
                    )
                )
            clockRepository.setCurrentClock(clockController)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onConfigurationChange()
            runCurrent()

            assertThat(blueprint?.id).isEqualTo(WEATHER_CLOCK_BLUEPRINT_ID)
        }
    }
}
