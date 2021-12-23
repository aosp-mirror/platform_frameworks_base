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

package com.android.systemui.media.taptotransfer.common

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class MediaTttChipControllerCommonTest : SysuiTestCase() {
    private lateinit var controllerCommon: MediaTttChipControllerCommon<MediaTttChipState>

    private lateinit var appIconDrawable: Drawable
    @Mock
    private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        appIconDrawable = Icon.createWithResource(context, R.drawable.ic_cake).loadDrawable(context)
        controllerCommon = TestControllerCommon(context, windowManager)
    }

    @Test
    fun displayChip_chipAdded() {
        controllerCommon.displayChip(MediaTttChipState(appIconDrawable))

        verify(windowManager).addView(any(), any())
    }

    @Test
    fun displayChip_twice_chipNotAddedTwice() {
        controllerCommon.displayChip(MediaTttChipState(appIconDrawable))
        reset(windowManager)

        controllerCommon.displayChip(MediaTttChipState(appIconDrawable))
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun removeChip_chipRemoved() {
        // First, add the chip
        controllerCommon.displayChip(MediaTttChipState(appIconDrawable))

        // Then, remove it
        controllerCommon.removeChip()

        verify(windowManager).removeView(any())
    }

    @Test
    fun removeChip_noAdd_viewNotRemoved() {
        controllerCommon.removeChip()

        verify(windowManager, never()).removeView(any())
    }

    inner class TestControllerCommon(
        context: Context,
        windowManager: WindowManager
    ) : MediaTttChipControllerCommon<MediaTttChipState>(
        context, windowManager, R.layout.media_ttt_chip
    ) {
        override fun updateChipView(chipState: MediaTttChipState, currentChipView: ViewGroup) {
        }
    }
}
