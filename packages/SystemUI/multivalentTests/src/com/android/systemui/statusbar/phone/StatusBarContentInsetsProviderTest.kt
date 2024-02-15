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
import android.view.Display
import android.view.DisplayCutout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.CameraProtectionInfo
import com.android.systemui.SysUICutoutInformation
import com.android.systemui.SysUICutoutProvider
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.leak.RotationUtils
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.leak.RotationUtils.Rotation
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any

@RunWith(AndroidJUnit4::class)
@SmallTest
class StatusBarContentInsetsProviderTest : SysuiTestCase() {

    private val sysUICutout = mock<SysUICutoutInformation>()
    private val dc = mock<DisplayCutout>()
    private val contextMock = mock<Context>()
    private val display = mock<Display>()
    private val configuration = Configuration()

    private lateinit var configurationController: ConfigurationController

    @Before
    fun setup() {
        whenever(sysUICutout.cutout).thenReturn(dc)
        whenever(contextMock.display).thenReturn(display)

        context.ensureTestableResources()
        whenever(contextMock.resources).thenReturn(context.resources)
        whenever(contextMock.resources.configuration).thenReturn(configuration)
        whenever(contextMock.createConfigurationContext(any())).thenAnswer {
            context.createConfigurationContext(it.arguments[0] as Configuration)
        }
        configurationController = ConfigurationControllerImpl(contextMock)
        setNoCutout()
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
        val statusBarContentHeight = 15

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
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

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
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

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
    fun privacyChipBoundingRectForInsets_usesTopInset() {
        val chipWidth = 30
        val dotWidth = 10
        val isRtl = false
        val contentRect =
                Rect(/* left = */ 0, /* top = */ 10, /* right = */ 1000, /* bottom = */ 100)

        val chipBounds =
                getPrivacyChipBoundingRectForInsets(contentRect, dotWidth, chipWidth, isRtl)

        assertThat(chipBounds.top).isEqualTo(contentRect.top)
    }

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_topLeftCutout_noCameraProtection() {
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
        val statusBarContentHeight = 15

        setCutoutBounds(top = dcBounds)

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
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(dcBounds.height(),
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

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
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

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
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_topLeftCutout_withCameraProtection() {
        // GIVEN a device in portrait mode with width < height and a display cutout in the top-left
        val screenBounds = Rect(0, 0, 1080, 2160)
        val dcBounds = Rect(0, 0, 100, 100)
        val protectionBounds = Rect(10, 10, 110, 110)
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val isRtl = false
        val dotWidth = 10
        val statusBarContentHeight = 15

        setCameraProtectionBounds(protectionBounds)
        setCutoutBounds(top = dcBounds)

        // THEN rotations which share a short side should use the greater value between rounded
        // corner padding, the display cutout's size, and the camera protections' size.
        var targetRotation = ROTATION_NONE
        var expectedBounds = Rect(protectionBounds.right,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(protectionBounds.bottom,
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

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
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        // Phone in portrait, seascape (rot_270) bounds
        targetRotation = ROTATION_SEASCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - protectionBounds.bottom - dotWidth,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    private fun Rect(left: Int, top: Int, right: Int, bottom: Int) =
        android.graphics.Rect(left, top, right, bottom)

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_topRightCutout_noCameraProtection() {
        val screenBounds = Rect(0, 0, 1000, 2000)
        val dcWidth = 100
        val dcHeight = 50
        val dcBoundsPortrait =
            Rect(
                left = screenBounds.right - dcWidth,
                top = 0,
                right = screenBounds.right,
                bottom = dcHeight
            )
        val dcBoundsLandscape = Rect(left = 0, top = 0, right = dcHeight, bottom = dcWidth)
        val dcBoundsSeascape =
            Rect(
                left = screenBounds.right - dcHeight,
                top = screenBounds.bottom - dcWidth,
                right = screenBounds.right - dcHeight,
                bottom = screenBounds.bottom - dcWidth
            )
        val dcBoundsUpsideDown =
            Rect(
                left = 0,
                top = screenBounds.bottom - dcHeight,
                right = dcWidth,
                bottom = screenBounds.bottom - dcHeight
            )
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val isRtl = false
        val dotWidth = 10
        val statusBarContentHeight = 15

        var targetRotation = ROTATION_NONE
        setCutoutBounds(top = dcBoundsPortrait)
        var expectedBounds =
            Rect(
                left = minLeftPadding,
                top = 0,
                right = dcBoundsPortrait.left - dotWidth,
                bottom = sbHeightPortrait
            )

        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        setCutoutBounds(top = dcBoundsLandscape)
        expectedBounds =
            Rect(
                left = dcBoundsLandscape.height(),
                top = 0,
                right = screenBounds.height() - minRightPadding,
                bottom = sbHeightLandscape
            )

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_UPSIDE_DOWN
        setCutoutBounds(bottom = dcBoundsUpsideDown)
        expectedBounds =
            Rect(
                left = minLeftPadding,
                top = 0,
                right = screenBounds.width() - minRightPadding,
                bottom = sbHeightPortrait
            )

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_SEASCAPE
        setCutoutBounds(bottom = dcBoundsSeascape)
        expectedBounds =
            Rect(
                left = minLeftPadding,
                top = 0,
                right = screenBounds.height() - minRightPadding,
                bottom = sbHeightLandscape
            )

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_topRightCutout_withCameraProtection() {
        // GIVEN a device in portrait mode with width < height and a display cutout in the top-left
        val screenBounds = Rect(0, 0, 1000, 2000)
        val dcBounds = Rect(900, 0, 1000, 100)
        val protectionBounds = Rect(890, 10, 990, 110)
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val isRtl = false
        val dotWidth = 10
        val statusBarContentHeight = 15

        setCameraProtectionBounds(protectionBounds)

        var targetRotation = ROTATION_NONE
        setCutoutBounds(top = dcBounds)
        var expectedBounds =
            Rect(minLeftPadding, 0, protectionBounds.left - dotWidth, sbHeightPortrait)

        var bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(protectionBounds.bottom,
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

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
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        // Phone in portrait, seascape (rot_270) bounds
        targetRotation = ROTATION_SEASCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - protectionBounds.bottom - dotWidth,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun calculateInsetsForRotationWithRotatedResources_bottomAlignedMarginDisabled_noTopInset() {
        setNoCutout()

        val bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation = ROTATION_NONE,
                targetRotation = ROTATION_NONE,
                sysUICutout = sysUICutout,
                maxBounds = Rect(0, 0, 1080, 2160),
                statusBarHeight = 100,
                minLeft = 0,
                minRight = 0,
                isRtl = false,
                dotWidth = 10,
                bottomAlignedMargin = BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight = 15)

        assertThat(bounds.top).isEqualTo(0)
    }

    @Test
    fun calculateInsetsForRotationWithRotatedResources_bottomAlignedMargin_topBasedOnMargin() {
        whenever(dc.boundingRects).thenReturn(emptyList())

        val bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation = ROTATION_NONE,
                targetRotation = ROTATION_NONE,
                sysUICutout = sysUICutout,
                maxBounds = Rect(0, 0, 1080, 2160),
                statusBarHeight = 100,
                minLeft = 0,
                minRight = 0,
                isRtl = false,
                dotWidth = 10,
                bottomAlignedMargin = 5,
                statusBarContentHeight = 15)

        // Content in the status bar is centered vertically. To achieve the bottom margin we want,
        // we need to "shrink" the height of the status bar until the centered content has the
        // desired bottom margin. To achieve this shrinking, we use top inset/padding.
        // "New" SB height = bottom margin * 2 + content height
        // Top inset = SB height - "New" SB height
        val expectedTopInset = 75
        assertThat(bounds.top).isEqualTo(expectedTopInset)
    }

    @Test
    fun testCalculateInsetsForRotationWithRotatedResources_nonCornerCutout() {
        // GIVEN phone in portrait mode, where width < height and the cutout is not in the corner
        // the assumption here is that if the cutout does NOT touch the corner then we have room to
        // layout the status bar in the given space.

        val screenBounds = Rect(0, 0, 1080, 2160)
        // cutout centered at the top
        val dcBounds = Rect(490, 0, 590, 100)
        val protectionBounds = Rect(480, 10, 600, 90)
        val minLeftPadding = 20
        val minRightPadding = 20
        val sbHeightPortrait = 100
        val sbHeightLandscape = 60
        val currentRotation = ROTATION_NONE
        val isRtl = false
        val dotWidth = 10
        val statusBarContentHeight = 15

        setCameraProtectionBounds(protectionBounds)
        setCutoutBounds(top = dcBounds)

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
                sysUICutout = sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_LANDSCAPE
        expectedBounds = Rect(dcBounds.height(),
                0,
                screenBounds.height() - minRightPadding,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout = sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_UPSIDE_DOWN
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout = sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)

        targetRotation = ROTATION_SEASCAPE
        expectedBounds = Rect(minLeftPadding,
                0,
                screenBounds.height() - dcBounds.height() - dotWidth,
                sbHeightLandscape)

        bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout = sysUICutout,
                screenBounds,
                sbHeightLandscape,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

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
        val statusBarContentHeight = 15

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
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)
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
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)
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
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)
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
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)
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
        val statusBarContentHeight = 15

        setCutoutBounds(top = dcBounds)

        // THEN left should be set to the display cutout width, and right should use the minRight
        val targetRotation = ROTATION_NONE
        val expectedBounds = Rect(dcBounds.right,
                0,
                screenBounds.right - minRightPadding,
                sbHeightPortrait)

        val bounds = calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                sysUICutout,
                screenBounds,
                sbHeightPortrait,
                minLeftPadding,
                minRightPadding,
                isRtl,
                dotWidth,
                BOTTOM_ALIGNED_MARGIN_NONE,
                statusBarContentHeight)

        assertRects(expectedBounds, bounds, currentRotation, targetRotation)
    }

    @Test
    fun testDisplayChanged_returnsUpdatedInsets() {
        // GIVEN: get insets on the first display and switch to the second display
        val provider = StatusBarContentInsetsProvider(contextMock, configurationController,
            mock<DumpManager>(), mock<CommandRegistry>(), mock<SysUICutoutProvider>())

        configuration.windowConfiguration.setMaxBounds(Rect(0, 0, 1080, 2160))
        val firstDisplayInsets = provider.getStatusBarContentAreaForRotation(ROTATION_NONE)

        configuration.windowConfiguration.setMaxBounds(Rect(0, 0, 800, 600))

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
            mock<DumpManager>(), mock<CommandRegistry>(), mock<SysUICutoutProvider>())

        configuration.windowConfiguration.setMaxBounds(Rect(0, 0, 1080, 2160))
        val firstDisplayInsetsFirstCall = provider
            .getStatusBarContentAreaForRotation(ROTATION_NONE)

        configuration.windowConfiguration.setMaxBounds(Rect(0, 0, 800, 600))
        provider.getStatusBarContentAreaForRotation(ROTATION_NONE)

        configuration.windowConfiguration.setMaxBounds(Rect(0, 0, 1080, 2160))

        // WHEN: get insets on the first display again
        val firstDisplayInsetsSecondCall = provider
            .getStatusBarContentAreaForRotation(ROTATION_NONE)

        // THEN: insets for the first and second calls for the first display are the same
        assertThat(firstDisplayInsetsFirstCall).isEqualTo(firstDisplayInsetsSecondCall)
    }

    // Regression test for b/245799099
    @Test
    fun onMaxBoundsChanged_listenerNotified() {
        // Start out with an existing configuration with bounds
        configuration.windowConfiguration.setMaxBounds(0, 0, 100, 100)
        configurationController.onConfigurationChanged(configuration)
        val provider = StatusBarContentInsetsProvider(contextMock, configurationController,
                mock<DumpManager>(), mock<CommandRegistry>(), mock<SysUICutoutProvider>())
        val listener = object : StatusBarContentInsetsChangedListener {
            var triggered = false

            override fun onStatusBarContentInsetsChanged() {
                triggered = true
            }
        }
        provider.addCallback(listener)

        // WHEN the config is updated with new bounds
        configuration.windowConfiguration.setMaxBounds(0, 0, 456, 789)
        configurationController.onConfigurationChanged(configuration)

        // THEN the listener is notified
        assertThat(listener.triggered).isTrue()
    }

    @Test
    fun onDensityOrFontScaleChanged_listenerNotified() {
        configuration.densityDpi = 12
        val provider = StatusBarContentInsetsProvider(contextMock, configurationController,
                mock<DumpManager>(), mock<CommandRegistry>(), mock<SysUICutoutProvider>())
        val listener = object : StatusBarContentInsetsChangedListener {
            var triggered = false

            override fun onStatusBarContentInsetsChanged() {
                triggered = true
            }
        }
        provider.addCallback(listener)

        // WHEN the config is updated
        configuration.densityDpi = 20
        configurationController.onConfigurationChanged(configuration)

        // THEN the listener is notified
        assertThat(listener.triggered).isTrue()
    }

    @Test
    fun onThemeChanged_listenerNotified() {
        val provider = StatusBarContentInsetsProvider(contextMock, configurationController,
                mock<DumpManager>(), mock<CommandRegistry>(), mock<SysUICutoutProvider>())
        val listener = object : StatusBarContentInsetsChangedListener {
            var triggered = false

            override fun onStatusBarContentInsetsChanged() {
                triggered = true
            }
        }
        provider.addCallback(listener)

        configurationController.notifyThemeChanged()

        // THEN the listener is notified
        assertThat(listener.triggered).isTrue()
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
            expected.equals(actual)
        )
    }

    private fun setNoCutout() {
        setCutoutBounds()
    }

    private fun setCutoutBounds(
        left: Rect = Rect(),
        top: Rect = Rect(),
        right: Rect = Rect(),
        bottom: Rect = Rect()
    ) {
        whenever(dc.boundingRects)
            .thenReturn(listOf(left, top, right, bottom).filter { !it.isEmpty })
        whenever(dc.boundingRectLeft).thenReturn(left)
        whenever(dc.boundingRectTop).thenReturn(top)
        whenever(dc.boundingRectRight).thenReturn(right)
        whenever(dc.boundingRectBottom).thenReturn(bottom)
    }

    private fun setCameraProtectionBounds(protectionBounds: Rect) {
        val protectionInfo =
            mock<CameraProtectionInfo> { whenever(this.cutoutBounds).thenReturn(protectionBounds) }
        whenever(sysUICutout.cameraProtection).thenReturn(protectionInfo)
    }

    companion object {
        private const val BOTTOM_ALIGNED_MARGIN_NONE = -1
    }
}
