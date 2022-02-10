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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
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
        appIconDrawable = context.getDrawable(R.drawable.ic_cake)!!
        controllerCommon = TestControllerCommon(context, windowManager)
    }

    @Test
    fun displayChip_chipAdded() {
        controllerCommon.displayChip(getState())

        verify(windowManager).addView(any(), any())
    }

    @Test
    fun displayChip_twice_chipNotAddedTwice() {
        controllerCommon.displayChip(getState())
        reset(windowManager)

        controllerCommon.displayChip(getState())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun removeChip_chipRemoved() {
        // First, add the chip
        controllerCommon.displayChip(getState())

        // Then, remove it
        controllerCommon.removeChip()

        verify(windowManager).removeView(any())
    }

    @Test
    fun removeChip_noAdd_viewNotRemoved() {
        controllerCommon.removeChip()

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun setIcon_viewHasIconAndContentDescription() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        val state = TestChipState(PACKAGE_NAME)
        controllerCommon.setIcon(state, chipView)

        assertThat(chipView.getAppIconView().drawable).isEqualTo(appIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription)
                .isEqualTo(state.getAppName(context))
    }

    private fun getState() = MediaTttChipState(PACKAGE_NAME)

    private fun getChipView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
    }

    private fun ViewGroup.getAppIconView() = this.requireViewById<ImageView>(R.id.app_icon)

    inner class TestControllerCommon(
        context: Context,
        windowManager: WindowManager
    ) : MediaTttChipControllerCommon<MediaTttChipState>(
        context, windowManager, R.layout.media_ttt_chip
    ) {
        override fun updateChipView(chipState: MediaTttChipState, currentChipView: ViewGroup) {
        }
    }

    inner class TestChipState(appPackageName: String?) : MediaTttChipState(appPackageName) {
        override fun getAppIcon(context: Context) = appIconDrawable
    }
}

private const val PACKAGE_NAME = "com.android.systemui"
