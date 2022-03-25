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

package com.android.systemui.statusbar.phone

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.test.suitebuilder.annotation.SmallTest
import android.view.Display
import android.view.DisplayCutout
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.leak.RotationUtils
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.leak.RotationUtils.Rotation
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@SmallTest
class StatusBarContentInsetsProviderTest : SysuiTestCase() {

    @Mock private lateinit var dc: DisplayCutout
    @Mock private lateinit var contextMock: Context
    @Mock private lateinit var display: Display
    private lateinit var configurationController: ConfigurationController

    private val configuration = Configuration()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(contextMock.display).thenReturn(display)

        context.ensureTestableResources()
        `when`(contextMock.resources).thenReturn(context.resources)
        `when`(contextMock.resources.configuration).thenReturn(configuration)
        `when`(contextMock.createConfigurationContext(any())).thenAnswer {
            context.createConfigurationContext(it.arguments[0] as Configuration)
        }

        configurationController = ConfigurationControllerImpl(contextMock)
    }

    @Test
    fun testGetBoundingRectForPrivacyChipForRotation_noCutout() {
        val screenBounds = Rect(0, 0, 1080, 2160)
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val chipWidth = 30
        val dotWidth = 10

        var isRtl = false
        var targetRotation = ROTATION_NONE
        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                null,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        var chipBounds = getPrivacyChipBoundingRectForInsets(bounds, dotWidth, chipWidth, isRtl)
        /* 1080 - 20 (rounded corner) - 30 (chip),
        *  0 (sb top)
        *  1080 - 20 (rounded corner) + 10 ( dot),
        *  100 (sb height portrait)
        */
        var expected = Rect(1030, 0, 1070, 100)
        assertRects(expected, chipBounds, currentRotation, targetRotation)
        isRtl = true
        chipBounds = getPrivacyChipBoundingRectForInsets(bounds, dotWidth, chipWidth, isRtl)
        /* 0 + 20 (rounded corner) - 10 (dot),
        *  0 (sb top)
        *  0 + 20 (rounded corner) + 30 (chip),
        *  100 (sb height portrait)
        */
        expected = Rect(10, 0, 50, 100)
        assertRects(expected, chipBounds, currentRotation, targetRotation)

        isRtl = false
        targetRotation = ROTATION_LANDSCAPE
        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        chipBounds = getPrivacyChipBoundingRectForInsets(bounds, dotWidth, chipWidth, isRtl)
        /* 2160 - 20 (rounded corner) - 30 (chip),
        *  0 (sb top)
        *  2160 - 20 (rounded corner) + 10 ( dot),
        *  60 (sb height landscape)
        */
        expected = Rect(2110, 0, 2150, 60)
        assertRects(expected, chipBounds, currentRotation, targetRotation)
        isRtl = true
        chipBounds = getPrivacyChipBoundingRectForInsets(bounds, dotWidth, chipWidth, isRtl)
        /* 0 + 20 (rounded corner) - 10 (dot),
        *  0 (sb top)
        *  0 + 20 (rounded corner) + 30 (chip),
        *  60 (sb height landscape)
        */
        expected = Rect(10, 0, 50, 60)
        assertRects(expected, chipBounds, currentRotation, targetRotation)
    }

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_topLeftCutout() {
        // GIVEN a device in portrait mode with width < height and a display cutout in the top-left
        val screenBounds = Rect(0, 0, 1080, 2160)
        val dcBounds = Rect(0, 0, 100, 100)
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val isRtl = false
        val dotWidth = 10

        `when`(dc.boundingRects).thenReturn(listOf(dcBounds))

        // THEN rotations which share a short side should use the greater value between rounded
        // corner padding and the display cutout's size
        var targetRotation = ROTATION_NONE
        var expectedBounds = Rect(dcBounds.right,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(dcBounds.height(),
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        // THEN the side that does NOT share a short side with the display cutout ignores the
        // display cutout bounds
        targetRotation = ROTATION_UPSIDE_DOWN
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.width() - minRightPadding,
                sbHeightPortrait)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        // Phone in portrait, seascape (rot_270) bounds
        targetRotation = ROTATION_SEASCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - dcBounds.height() - dotWidth,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_nonCornerCutout() {
        // GIVEN phone in portrait mode, where width < height and the cutout is not in the corner
        // the assumption here is that if the cutout does NOT touch the corner then we have room to
        // layout the status bar in the given space.

        val screenBounds = Rect(0, 0, 1080, 2160)
        // cutout centered at the top
        val dcBounds = Rect(490, 0, 590, 100)
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val isRtl = false
        val dotWidth = 10

        `when`(dc.boundingRects).thenReturn(listOf(dcBounds))

        // THEN only the landscape/seascape rotations should avoid the cutout area because of the
        // potential letterboxing
        var targetRotation = ROTATION_NONE
        var expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(dcBounds.height(),
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_UPSIDE_DOWN
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_SEASCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - dcBounds.height() - dotWidth,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_noCutout() {
        // GIVEN device in portrait mode, where width < height and no cutout
        val currentRotation = ROTATION_NONE
        val screenBounds = Rect(0, 0, 1080, 2160)
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val isRtl = false
        val dotWidth = 10

        // THEN content insets should only use rounded corner padding
        var targetRotation = ROTATION_NONE
        var expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                null, /* no cutout */
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)
        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                null, /* no cutout */
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)
        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_UPSIDE_DOWN
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.width() - minRightPadding,
                sbHeightPortrait)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                null, /* no cutout */
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)
        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                null, /* no cutout */
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)
        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun testMinLeftRight_accountsForDisplayCutout() {
        // GIVEN a device in portrait mode with width < height and a display cutout in the top-left
        val screenBounds = Rect(0, 0, 1080, 2160)
        val dcBounds = Rect(0, 0, 100, 100)
        val minLeftPadding = 80
        val minRightPadding = 150
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val isRtl = false
        val dotWidth = 10

        `when`(dc.boundingRects).thenReturn(listOf(dcBounds))

        // THEN left should be set to the display cutout width, and right should use the minRight
        var targetRotation = ROTATION_NONE
        var expectedBounds = Rect(dcBounds.right,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun testDisplayChanged_returnsUpdatedInsets() {
        // GIVEN: get insets on the first display and switch to the second display
        val provider = StatusBarContentInsetsProvider(contextMock, configurationController,
            mock(DumpManager::class.java))

        givenDisplay(
            screenBounds = Rect(0, 0, 1080, 2160),
            displayUniqueId = "1"
        )
        val firstDisplayInsets = provider.getStatusBarContentAreaForRotation(ROTATION_NONE)
        givenDisplay(
            screenBounds = Rect(0, 0, 800, 600),
            displayUniqueId = "2"
        )
        configurationController.onConfigurationChanged(configuration)

        // WHEN: get insets on the second display
        val secondDisplayInsets = provider.getStatusBarContentAreaForRotation(ROTATION_NONE)

        // THEN: insets are updated
        assertThat(firstDisplayInsets).isNotEqualTo(secondDisplayInsets)
    }

    @Test
    fun testDisplayChangedAndReturnedBack_returnsTheSameInsets() {
        // GIVEN: get insets on the first display, switch to the second display,
        // get insets and switch back
        val provider = StatusBarContentInsetsProvider(contextMock, configurationController,
            mock(DumpManager::class.java))
        givenDisplay(
            screenBounds = Rect(0, 0, 1080, 2160),
            displayUniqueId = "1"
        )
        val firstDisplayInsetsFirstCall = provider
            .getStatusBarContentAreaForRotation(ROTATION_NONE)
        givenDisplay(
            screenBounds = Rect(0, 0, 800, 600),
            displayUniqueId = "2"
        )
        configurationController.onConfigurationChanged(configuration)
        provider.getStatusBarContentAreaForRotation(ROTATION_NONE)
        givenDisplay(
            screenBounds = Rect(0, 0, 1080, 2160),
            displayUniqueId = "1"
        )
        configurationController.onConfigurationChanged(configuration)

        // WHEN: get insets on the first display again
        val firstDisplayInsetsSecondCall = provider
            .getStatusBarContentAreaForRotation(ROTATION_NONE)

        // THEN: insets for the first and second calls for the first display are the same
        assertThat(firstDisplayInsetsFirstCall).isEqualTo(firstDisplayInsetsSecondCall)
    }

    private fun givenDisplay(screenBounds: Rect, displayUniqueId: String) {
        `when`(display.uniqueId).thenReturn(displayUniqueId)
        configuration.windowConfiguration.maxBounds = screenBounds
    }

    private fun assertRects(
        expected: Rect,
        actual: Rect,
        @Rotation currentRotation: Int,
        @Rotation targetRotation: Int
    ) {
        assertTrue(
                "Rects must match. currentRotation=${RotationUtils.toString(currentRotation)}" +
                " targetRotation=${RotationUtils.toString(targetRotation)}" +
                " expected=$expected actual=$actual",
                expected.equals(actual))
    }
}