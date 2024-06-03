/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.brightness.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.brightness.data.repository.fakeScreenBrightnessRepository
import com.android.systemui.brightness.data.repository.screenBrightnessRepository
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.shared.model.LinearBrightness
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScreenBrightnessInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest =
        with(kosmos) {
            ScreenBrightnessInteractor(
                screenBrightnessRepository,
                applicationCoroutineScope,
                mock<TableLogBuffer>()
            )
        }

    @Test
    fun gammaBrightness() =
        with(kosmos) {
            testScope.runTest {
                val gammaBrightness by collectLastValue(underTest.gammaBrightness)

                val brightness = 0.3f
                val min = 0f
                val max = 1f

                with(fakeScreenBrightnessRepository) {
                    setBrightness(LinearBrightness(brightness))
                    setMinMaxBrightness(LinearBrightness(min), LinearBrightness(max))
                }
                runCurrent()

                assertThat(gammaBrightness?.value)
                    .isEqualTo(BrightnessUtils.convertLinearToGammaFloat(brightness, min, max))
            }
        }

    @Test
    fun gammaBrightness_constrained() =
        with(kosmos) {
            testScope.runTest {
                val gammaBrightness by collectLastValue(underTest.gammaBrightness)

                val brightness = 0.3f
                val min = 0.2f
                val max = 0.8f

                with(fakeScreenBrightnessRepository) {
                    setBrightness(LinearBrightness(brightness))
                    setMinMaxBrightness(LinearBrightness(min), LinearBrightness(max))
                }
                runCurrent()

                assertThat(gammaBrightness?.value)
                    .isEqualTo(BrightnessUtils.convertLinearToGammaFloat(brightness, min, max))
            }
        }

    @Test
    fun setTemporaryBrightness() =
        with(kosmos) {
            testScope.runTest {
                val temporaryBrightness by
                    collectLastValue(fakeScreenBrightnessRepository.temporaryBrightness)
                val brightness by collectLastValue(underTest.gammaBrightness)

                val gammaBrightness = 30000
                underTest.setTemporaryBrightness(GammaBrightness(gammaBrightness))

                val (min, max) = fakeScreenBrightnessRepository.getMinMaxLinearBrightness()

                val expectedTemporaryBrightness =
                    BrightnessUtils.convertGammaToLinearFloat(
                        gammaBrightness,
                        min.floatValue,
                        max.floatValue
                    )
                assertThat(temporaryBrightness!!.floatValue)
                    .isWithin(1e-5f)
                    .of(expectedTemporaryBrightness)
                assertThat(brightness!!.value).isNotEqualTo(gammaBrightness)
            }
        }

    @Test
    fun setBrightness() =
        with(kosmos) {
            testScope.runTest {
                val brightness by collectLastValue(fakeScreenBrightnessRepository.linearBrightness)

                val gammaBrightness = 30000
                underTest.setBrightness(GammaBrightness(gammaBrightness))

                val (min, max) = fakeScreenBrightnessRepository.getMinMaxLinearBrightness()

                val expectedBrightness =
                    BrightnessUtils.convertGammaToLinearFloat(
                        gammaBrightness,
                        min.floatValue,
                        max.floatValue
                    )
                assertThat(brightness!!.floatValue).isWithin(1e-5f).of(expectedBrightness)
            }
        }

    @Test
    fun maxGammaBrightness() {
        assertThat(underTest.maxGammaBrightness)
            .isEqualTo(GammaBrightness(BrightnessUtils.GAMMA_SPACE_MAX))
    }

    @Test
    fun minGammaBrightness() {
        assertThat(underTest.minGammaBrightness)
            .isEqualTo(GammaBrightness(BrightnessUtils.GAMMA_SPACE_MIN))
    }
}
