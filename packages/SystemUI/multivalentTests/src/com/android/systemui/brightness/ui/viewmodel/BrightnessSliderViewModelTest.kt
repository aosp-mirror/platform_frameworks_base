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
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.haptics.slider.sliderHapticsViewModelFactory
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.res.R
import com.android.systemui.settings.brightness.domain.interactor.brightnessMirrorShowingInteractor
import com.android.systemui.settings.brightness.ui.brightnessWarningToast
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessSliderViewModelTest : SysuiTestCase() {

    private val minBrightness = 0f
    private val maxBrightness = 1f

    private val kosmos = testKosmos()

    private val underTest by lazy {
        with(kosmos) {
            BrightnessSliderViewModel(
                screenBrightnessInteractor,
                brightnessPolicyEnforcementInteractor,
                sliderHapticsViewModelFactory,
                brightnessMirrorShowingInteractor,
                falsingInteractor,
                supportsMirroring = true,
                brightnessWarningToast,
            )
        }
    }

    @Before
    fun setUp() {
        kosmos.fakeScreenBrightnessRepository.setMinMaxBrightness(
            LinearBrightness(minBrightness),
            LinearBrightness(maxBrightness),
        )
        underTest.activateIn(kosmos.testScope)
    }

    @Test
    fun brightnessChangeInRepository_changeInFlow() =
        with(kosmos) {
            testScope.runTest {
                var brightness = 0.6f
                fakeScreenBrightnessRepository.setBrightness(LinearBrightness(brightness))
                runCurrent()

                assertThat(underTest.currentBrightness.value)
                    .isEqualTo(
                        BrightnessUtils.convertLinearToGammaFloat(
                            brightness,
                            minBrightness,
                            maxBrightness,
                        )
                    )

                brightness = 0.2f
                fakeScreenBrightnessRepository.setBrightness(LinearBrightness(brightness))
                runCurrent()

                assertThat(underTest.currentBrightness.value)
                    .isEqualTo(
                        BrightnessUtils.convertLinearToGammaFloat(
                            brightness,
                            minBrightness,
                            maxBrightness,
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

                val newBrightness = underTest.maxBrightness.value / 3
                val expectedTemporaryBrightness =
                    BrightnessUtils.convertGammaToLinearFloat(
                        newBrightness,
                        minBrightness,
                        maxBrightness,
                    )
                val drag = Drag.Dragging(GammaBrightness(newBrightness))

                underTest.onDrag(drag)

                assertThat(temporaryBrightness!!.floatValue)
                    .isWithin(1e-5f)
                    .of(expectedTemporaryBrightness)
                assertThat(underTest.currentBrightness.value).isNotEqualTo(newBrightness)
            }
        }

    @Test
    fun draggingStopped_currentBrightnessChanges() =
        with(kosmos) {
            testScope.runTest {
                val newBrightness = underTest.maxBrightness.value / 3
                val drag = Drag.Stopped(GammaBrightness(newBrightness))

                underTest.onDrag(drag)
                runCurrent()

                assertThat(underTest.currentBrightness.value).isEqualTo(newBrightness)
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

    @Test
    fun supportedMirror_mirrorShowingWhenDragging() =
        with(kosmos) {
            testScope.runTest {
                val mirrorInInteractor by
                    collectLastValue(brightnessMirrorShowingInteractor.isShowing)

                underTest.setIsDragging(true)
                assertThat(mirrorInInteractor).isEqualTo(true)
                assertThat(underTest.showMirror).isEqualTo(true)

                underTest.setIsDragging(false)
                assertThat(mirrorInInteractor).isEqualTo(false)
                assertThat(underTest.showMirror).isEqualTo(false)
            }
        }

    @Test
    fun unsupportedMirror_mirrorNeverShowing() =
        with(kosmos) {
            testScope.runTest {
                val mirrorInInteractor by
                    collectLastValue(brightnessMirrorShowingInteractor.isShowing)

                val noMirrorViewModel = brightnessSliderViewModelFactory.create(false)

                noMirrorViewModel.setIsDragging(true)
                assertThat(mirrorInInteractor).isEqualTo(false)
                assertThat(noMirrorViewModel.showMirror).isEqualTo(false)

                noMirrorViewModel.setIsDragging(false)
                assertThat(mirrorInInteractor).isEqualTo(false)
                assertThat(noMirrorViewModel.showMirror).isEqualTo(false)
            }
        }
}
