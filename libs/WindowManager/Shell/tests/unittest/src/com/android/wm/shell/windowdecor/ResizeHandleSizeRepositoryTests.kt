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

package com.android.wm.shell.windowdecor

import android.content.Context
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for {@link ResizeHandleSizeRepository}. Validate that get/reset/set work correctly.
 *
 * Build/Install/Run: atest WMShellUnitTests:ResizeHandleSizeRepositoryTests
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class ResizeHandleSizeRepositoryTests {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val resizeHandleSizeRepository = ResizeHandleSizeRepository()

    @JvmField @Rule val setFlagsRule = SetFlagsRule()

    @Test
    fun testOverrideResizeEdgeHandlePixels_flagEnabled_resetSucceeds() {
        setFlagsRule.enableFlags(Flags.FLAG_ENABLE_WINDOWING_EDGE_DRAG_RESIZE)
        // Reset does nothing when no override is set.
        val originalEdgeHandle =
            resizeHandleSizeRepository.getResizeEdgeHandlePixels(resources)
        resizeHandleSizeRepository.resetResizeEdgeHandlePixels()
        assertThat(resizeHandleSizeRepository.getResizeEdgeHandlePixels(resources))
            .isEqualTo(originalEdgeHandle)

        // Now try to set the value; reset should succeed.
        resizeHandleSizeRepository.setResizeEdgeHandlePixels(originalEdgeHandle + 2)
        resizeHandleSizeRepository.resetResizeEdgeHandlePixels()
        assertThat(resizeHandleSizeRepository.getResizeEdgeHandlePixels(resources))
            .isEqualTo(originalEdgeHandle)
    }

    @Test
    fun testOverrideResizeEdgeHandlePixels_flagDisabled_resetFails() {
        setFlagsRule.disableFlags(Flags.FLAG_ENABLE_WINDOWING_EDGE_DRAG_RESIZE)
        // Reset does nothing when no override is set.
        val originalEdgeHandle =
            resizeHandleSizeRepository.getResizeEdgeHandlePixels(resources)
        resizeHandleSizeRepository.resetResizeEdgeHandlePixels()
        assertThat(resizeHandleSizeRepository.getResizeEdgeHandlePixels(resources))
            .isEqualTo(originalEdgeHandle)

        // Now try to set the value; reset should do nothing.
        val newEdgeHandle = originalEdgeHandle + 2
        resizeHandleSizeRepository.setResizeEdgeHandlePixels(newEdgeHandle)
        resizeHandleSizeRepository.resetResizeEdgeHandlePixels()
        assertThat(resizeHandleSizeRepository.getResizeEdgeHandlePixels(resources))
            .isEqualTo(originalEdgeHandle)
    }
}
