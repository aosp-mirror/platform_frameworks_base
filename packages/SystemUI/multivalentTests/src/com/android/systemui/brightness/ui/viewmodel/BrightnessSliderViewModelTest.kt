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

package com.android.systemui.brightness.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.brightness.data.repository.fakeScreenBrightnessRepository
import com.android.systemui.brightness.domain.interactor.brightnessPolicyEnforcementInteractor
import com.android.systemui.brightness.domain.interactor.screenBrightnessInteractor
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.shared.model.LinearBrightness
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessSliderViewModelTest : SysuiTestCase() {

    private val minBrightness = 0f
    private val maxBrightness = 1f

    private val kosmos = testKosmos()

    private val underTest =
        with(kosmos) {
            BrightnessSliderViewModel(
                screenBrightnessInteractor,
                brightnessPolicyEnforcementInteractor,
                applicationCoroutineScope,
            )
        }

    @Before
    fun setUp() {
        kosmos.fakeScreenBrightnessRepository.setMinMaxBrightness(
            LinearBrightness(minBrightness),
            LinearBrightness(maxBrightness)
        )
    }

    @Test
    fun brightnessChangeInRepository_changeInFlow() =
        with(kosmos) {
            testScope.runTest {
                val gammaBrightness by collectLastValue(underTest.currentBrightness)

                var brightness = 0.6f
                fakeScreenBrightnessRepository.setBrightness(LinearBrightness(brightness))

                assertThat(gammaBrightness!!.value)
                    .isEqualTo(
                        BrightnessUtils.convertLinearToGammaFloat(
                            brightness,
                            minBrightness,
                            maxBrightness
                        )
                    )

                brightness = 0.2f
                fakeScreenBrightnessRepository.setBrightness(LinearBrightness(brightness))

                assertThat(gammaBrightness!!.value)
                    .isEqualTo(
                        BrightnessUtils.convertLinearToGammaFloat(
                            brightness,
                            minBrightness,
                            maxBrightness
                        )
                    )
            }
        }

    @Test
    fun maxGammaBrightness() {
        assertThat(underTest.maxBrightness)
            .isEqualTo(GammaBrightness(BrightnessUtils.GAMMA_SPACE_MAX))
    }

    @Test
    fun minGammaBrightness() {
        assertThat(underTest.minBrightness)
            .isEqualTo(GammaBrightness(BrightnessUtils.GAMMA_SPACE_MIN))
    }

    @Test
    fun dragging_temporaryBrightnessSet_currentBrightnessDoesntChange() =
        with(kosmos) {
            testScope.runTest {
                val temporaryBrightness by
                    collectLastValue(fakeScreenBrightnessRepository.temporaryBrightness)
                val brightness by collectLastValue(underTest.currentBrightness)

                val newBrightness = underTest.maxBrightness.value / 3
                val expectedTemporaryBrightness =
                    BrightnessUtils.convertGammaToLinearFloat(
                        newBrightness,
                        minBrightness,
                        maxBrightness
                    )
                val drag = Drag.Dragging(GammaBrightness(newBrightness))

                underTest.onDrag(drag)

                assertThat(temporaryBrightness!!.floatValue)
                    .isWithin(1e-5f)
                    .of(expectedTemporaryBrightness)
                assertThat(brightness!!.value).isNotEqualTo(newBrightness)
            }
        }

    @Test
    fun draggingStopped_currentBrightnessChanges() =
        with(kosmos) {
            testScope.runTest {
                val brightness by collectLastValue(underTest.currentBrightness)

                val newBrightness = underTest.maxBrightness.value / 3
                val drag = Drag.Stopped(GammaBrightness(newBrightness))

                underTest.onDrag(drag)

                assertThat(brightness!!.value).isEqualTo(newBrightness)
            }
        }

    @Test
    fun label() {
        assertThat(underTest.label)
            .isEqualTo(Text.Resource(R.string.quick_settings_brightness_dialog_title))
    }

    @Test
    fun icon() {
        assertThat(underTest.icon)
            .isEqualTo(
                Icon.Resource(
                    R.drawable.ic_brightness_full,
                    ContentDescription.Resource(underTest.label.res),
                )
            )
    }
}
