/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.brightness.BrightnessController
import com.android.systemui.statusbar.policy.BrightnessMirrorController
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnit

@SmallTest
class QuickQSBrightnessControllerTest : SysuiTestCase() {

    @Mock
    lateinit var brightnessController: BrightnessController
    @get:Rule
    val mockito = MockitoJUnit.rule()

    lateinit var quickQSBrightnessController: QuickQSBrightnessController

    @Before
    fun setUp() {
        quickQSBrightnessController = QuickQSBrightnessController(
                brightnessControllerFactory = { brightnessController })
    }

    @Test
    fun testSliderIsShownWhenInitializedInSplitShade() {
        quickQSBrightnessController.init(shouldUseSplitNotificationShade = true)

        verify(brightnessController).showSlider()
    }

    @Test
    fun testSliderIsShownWhenRefreshedInSplitShade() {
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = true)

        verify(brightnessController, times(1)).showSlider()
    }

    @Test
    fun testSliderIsHiddenWhenRefreshedInNonSplitShade() {
        // needs to be shown first
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = true)
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = false)

        verify(brightnessController).hideSlider()
    }

    @Test
    fun testSliderChangesVisibilityWhenRotating() {
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = true)
        verify(brightnessController, times(1)).showSlider()

        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = false)
        verify(brightnessController, times(1)).hideSlider()
    }

    @Test
    fun testCallbacksAreRegisteredOnlyOnce() {
        // this flow simulates expanding shade in portrait...
        quickQSBrightnessController.setListening(true)
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = false)
        // ... and rotating to landscape/split shade where slider is visible
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = true)

        verify(brightnessController, times(1)).registerCallbacks()
    }

    @Test
    fun testCallbacksAreRegisteredOnlyOnceWhenRotatingPhone() {
        quickQSBrightnessController.setListening(true)
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = true)
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = false)
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = true)

        verify(brightnessController, times(1)).registerCallbacks()
    }

    @Test
    fun testCallbacksAreNotRegisteredWhenSliderNotVisible() {
        quickQSBrightnessController.setListening(true)
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = false)

        verify(brightnessController, never()).registerCallbacks()
    }

    @Test
    fun testMirrorIsSetWhenSliderIsShown() {
        val mirrorController = mock(BrightnessMirrorController::class.java)
        quickQSBrightnessController.setMirror(mirrorController)
        quickQSBrightnessController.refreshVisibility(shouldUseSplitNotificationShade = true)

        verify(brightnessController).setMirror(mirrorController)
    }
}