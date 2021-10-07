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

import android.graphics.Rect
import android.test.suitebuilder.annotation.SmallTest
import android.view.DisplayCutout
import android.view.WindowMetrics
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.leak.RotationUtils
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.leak.RotationUtils.Rotation
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class StatusBarContentInsetsProviderTest : SysuiTestCase() {
    @Mock private lateinit var dc: DisplayCutout
    @Mock private lateinit var windowMetrics: WindowMetrics

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
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

        `when`(windowMetrics.bounds).thenReturn(screenBounds)

        var isRtl = false
        var targetRotation = ROTATION_NONE
        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                null,
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)

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
                windowMetrics,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding)

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

        `when`(windowMetrics.bounds).thenReturn(screenBounds)
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
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)

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
                windowMetrics,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding)

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
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        // Phone in portrait, seascape (rot_270) bounds
        targetRotation = ROTATION_SEASCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - dcBounds.height(),
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                windowMetrics,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding)

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

        `when`(windowMetrics.bounds).thenReturn(screenBounds)
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
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)

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
                windowMetrics,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding)

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
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_SEASCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - dcBounds.height(),
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                windowMetrics,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding)

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

        `when`(windowMetrics.bounds).thenReturn(screenBounds)

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
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)
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
                windowMetrics,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding)
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
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)
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
                windowMetrics,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding)
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

        `when`(windowMetrics.bounds).thenReturn(screenBounds)
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
                windowMetrics,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
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