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

import android.view.Display
import android.view.DisplayAdjustments
import android.view.DisplayCutout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
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

        val sysUICutout = provider.cutoutInfoForCurrentDisplay()

        assertThat(sysUICutout).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_returnsCutout() {
        val cutoutDisplay = createDisplay()
        val cutoutDisplayContext = context.createDisplayContext(cutoutDisplay)
        val provider = SysUICutoutProvider(cutoutDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplay()!!

        assertThat(sysUICutout.cutout).isEqualTo(cutoutDisplay.cutout)
    }

    @Test
    fun cutoutInfoForCurrentDisplay_noAssociatedProtection_returnsNoProtection() {
        val cutoutDisplay = createDisplay()
        val cutoutDisplayContext = context.createDisplayContext(cutoutDisplay)
        val provider = SysUICutoutProvider(cutoutDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplay()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_outerDisplay_protectionAssociated_returnsProtection() {
        fakeProtectionLoader.addOuterCameraProtection(displayUniqueId = OUTER_DISPLAY_UNIQUE_ID)
        val outerDisplayContext = context.createDisplayContext(OUTER_DISPLAY)
        val provider = SysUICutoutProvider(outerDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplay()!!

        assertThat(sysUICutout.cameraProtection).isNotNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_outerDisplay_protectionNotAvailable_returnsNullProtection() {
        fakeProtectionLoader.clearProtectionInfoList()
        val outerDisplayContext = context.createDisplayContext(OUTER_DISPLAY)
        val provider = SysUICutoutProvider(outerDisplayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplay()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_displayWithNullId_protectionsWithNoId_returnsNullProtection() {
        fakeProtectionLoader.addOuterCameraProtection(displayUniqueId = "")
        val displayContext = context.createDisplayContext(createDisplay(uniqueId = null))
        val provider = SysUICutoutProvider(displayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplay()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    @Test
    fun cutoutInfoForCurrentDisplay_displayWithEmptyId_protectionsWithNoId_returnsNullProtection() {
        fakeProtectionLoader.addOuterCameraProtection(displayUniqueId = "")
        val displayContext = context.createDisplayContext(createDisplay(uniqueId = ""))
        val provider = SysUICutoutProvider(displayContext, fakeProtectionLoader)

        val sysUICutout = provider.cutoutInfoForCurrentDisplay()!!

        assertThat(sysUICutout.cameraProtection).isNull()
    }

    companion object {
        private const val OUTER_DISPLAY_UNIQUE_ID = "outer"
        private val OUTER_DISPLAY = createDisplay(uniqueId = OUTER_DISPLAY_UNIQUE_ID)

        private fun createDisplay(
            uniqueId: String? = "uniqueId",
            cutout: DisplayCutout? = mock<DisplayCutout>()
        ) =
            mock<Display> {
                whenever(this.displayAdjustments).thenReturn(DisplayAdjustments())
                whenever(this.cutout).thenReturn(cutout)
                whenever(this.uniqueId).thenReturn(uniqueId)
            }
    }
}
