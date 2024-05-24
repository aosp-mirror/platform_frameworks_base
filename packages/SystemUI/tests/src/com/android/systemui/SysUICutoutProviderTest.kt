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

package com.android.systemui

import android.graphics.Rect
import android.view.Display
import android.view.DisplayAdjustments
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.Surface
import android.view.Surface.Rotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SysUICutoutProviderTest : SysuiTestCase() {

    private val fakeProtectionLoader = FakeCameraProtectionLoader(context)

    @Test
    fun cutoutInfoForCurrentDisplay_noCutout_returnsNull() {
        val noCutoutDisplay = createDisplay(cutout = null)
        val noCutoutDisplayContext = context.createDisplayContext(noCutoutDisplay)
        val provider = SysUICutoutProvider(noCutoutDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()

        assertThat(sysUICutout).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_returnsCutout() {
        val cutoutDisplay = createDisplay()
        val cutoutDisplayContext = context.createDisplayContext(cutoutDisplay)
        val provider = SysUICutoutProvider(cutoutDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cutout).isEqualTo(cutoutDisplay.cutout)
    }

    @Test
    fun cutoutInfoForCurrentDisplay_noAssociatedProtection_returnsNoProtection() {
        val cutoutDisplay = createDisplay()
        val cutoutDisplayContext = context.createDisplayContext(cutoutDisplay)
        val provider = SysUICutoutProvider(cutoutDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_outerDisplay_protectionAssociated_returnsProtection() {
        fakeProtectionLoader.addOuterCameraProtection(displayUniqueId = OUTER_DISPLAY_UNIQUE_ID)
        val outerDisplayContext = context.createDisplayContext(OUTER_DISPLAY)
        val provider = SysUICutoutProvider(outerDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection).isNotNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_outerDisplay_protectionNotAvailable_returnsNullProtection() {
        fakeProtectionLoader.clearProtectionInfoList()
        val outerDisplayContext = context.createDisplayContext(OUTER_DISPLAY)
        val provider = SysUICutoutProvider(outerDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_displayWithNullId_protectionsWithNoId_returnsNullProtection() {
        fakeProtectionLoader.addOuterCameraProtection(displayUniqueId = "")
        val displayContext = context.createDisplayContext(createDisplay(uniqueId = null))
        val provider = SysUICutoutProvider(displayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_displayWithEmptyId_protectionsWithNoId_returnsNullProtection() {
        fakeProtectionLoader.addOuterCameraProtection(displayUniqueId = "")
        val displayContext = context.createDisplayContext(createDisplay(uniqueId = ""))
        val provider = SysUICutoutProvider(displayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    @Test
    fun cutoutInfo_rotation0_returnsOriginalProtectionBounds() {
        val provider =
            setUpProviderWithCameraProtection(
                displayWidth = 500,
                displayHeight = 1000,
                rotation = Surface.ROTATION_0,
                protectionBounds =
                    Rect(/* left = */ 440, /* top = */ 10, /* right = */ 490, /* bottom = */ 110)
            )

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection!!.bounds)
            .isEqualTo(
                Rect(/* left = */ 440, /* top = */ 10, /* right = */ 490, /* bottom = */ 110)
            )
    }

    @Test
    fun cutoutInfo_rotation90_returnsRotatedProtectionBounds() {
        val provider =
            setUpProviderWithCameraProtection(
                displayWidth = 500,
                displayHeight = 1000,
                rotation = Surface.ROTATION_90,
                protectionBounds =
                    Rect(/* left = */ 440, /* top = */ 10, /* right = */ 490, /* bottom = */ 110)
            )

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection!!.bounds)
            .isEqualTo(Rect(/* left = */ 10, /* top = */ 10, /* right = */ 110, /* bottom = */ 60))
    }

    @Test
    fun cutoutInfo_withRotation_doesNotMutateOriginalBounds() {
        val displayNaturalWidth = 500
        val displayNaturalHeight = 1000
        val originalProtectionBounds =
            Rect(/* left = */ 440, /* top = */ 10, /* right = */ 490, /* bottom = */ 110)
        // Safe copy as we don't know at which layer the mutation could happen
        val originalProtectionBoundsCopy = Rect(originalProtectionBounds)
        val display =
            createDisplay(
                uniqueId = OUTER_DISPLAY_UNIQUE_ID,
                rotation = Surface.ROTATION_180,
                width = displayNaturalWidth,
                height = displayNaturalHeight,
            )
        fakeProtectionLoader.addOuterCameraProtection(
            displayUniqueId = OUTER_DISPLAY_UNIQUE_ID,
            bounds = originalProtectionBounds
        )
        val provider =
            SysUICutoutProvider(context.createDisplayContext(display), fakeProtectionLoader)

        // Here we get the rotated bounds once
        provider.cutoutInfoForCurrentDisplayAndRotation()

        // Rotate display back to original rotation
        whenever(display.rotation).thenReturn(Surface.ROTATION_0)

        // Now the bounds should match the original ones. We are checking for mutation since Rect
        // is mutable and has many methods that mutate the instance, and it is easy to do it by
        // mistake.
        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!
        assertThat(sysUICutout.cameraProtection!!.bounds).isEqualTo(originalProtectionBoundsCopy)
    }

    @Test
    fun cutoutInfo_rotation180_returnsRotatedProtectionBounds() {
        val provider =
            setUpProviderWithCameraProtection(
                displayWidth = 500,
                displayHeight = 1000,
                rotation = Surface.ROTATION_180,
                protectionBounds =
                    Rect(/* left = */ 440, /* top = */ 10, /* right = */ 490, /* bottom = */ 110)
            )

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection!!.bounds)
            .isEqualTo(Rect(/* left = */ 10, /* top = */ 890, /* right = */ 60, /* bottom = */ 990))
    }

    @Test
    fun cutoutInfo_rotation270_returnsRotatedProtectionBounds() {
        val provider =
            setUpProviderWithCameraProtection(
                displayWidth = 500,
                displayHeight = 1000,
                rotation = Surface.ROTATION_270,
                protectionBounds =
                    Rect(/* left = */ 440, /* top = */ 10, /* right = */ 490, /* bottom = */ 110)
            )

        val sysUICutout = provider.cutoutInfoForCurrentDisplayAndRotation()!!

        assertThat(sysUICutout.cameraProtection!!.bounds)
            .isEqualTo(
                Rect(/* left = */ 890, /* top = */ 440, /* right = */ 990, /* bottom = */ 490)
            )
    }

    private fun setUpProviderWithCameraProtection(
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int = Surface.ROTATION_0,
        protectionBounds: Rect,
    ): SysUICutoutProvider {
        val display =
            createDisplay(
                uniqueId = OUTER_DISPLAY_UNIQUE_ID,
                rotation = rotation,
                width =
                    if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
                        displayWidth
                    } else {
                        displayHeight
                    },
                height =
                    if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180)
                        displayHeight
                    else displayWidth,
            )
        fakeProtectionLoader.addOuterCameraProtection(
            displayUniqueId = OUTER_DISPLAY_UNIQUE_ID,
            bounds = protectionBounds
        )
        return SysUICutoutProvider(context.createDisplayContext(display), fakeProtectionLoader)
    }

    companion object {
        private const val OUTER_DISPLAY_UNIQUE_ID = "outer"
        private val OUTER_DISPLAY = createDisplay(uniqueId = OUTER_DISPLAY_UNIQUE_ID)

        private fun createDisplay(
            width: Int = 500,
            height: Int = 1000,
            @Rotation rotation: Int = Surface.ROTATION_0,
            uniqueId: String? = "uniqueId",
            cutout: DisplayCutout? = mock<DisplayCutout>()
        ) =
            mock<Display> {
                whenever(this.getDisplayInfo(any())).thenAnswer {
                    val displayInfo = it.arguments[0] as DisplayInfo
                    displayInfo.rotation = rotation
                    displayInfo.logicalWidth = width
                    displayInfo.logicalHeight = height
                    return@thenAnswer true
                }
                // Setting width and height to smaller values to simulate real behavior of this API
                // not always returning the real display size
                whenever(this.width).thenReturn(width - 5)
                whenever(this.height).thenReturn(height - 10)
                whenever(this.rotation).thenReturn(rotation)
                whenever(this.displayAdjustments).thenReturn(DisplayAdjustments())
                whenever(this.cutout).thenReturn(cutout)
                whenever(this.uniqueId).thenReturn(uniqueId)
            }
    }
}
