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

package com.android.systemui.media.taptotransfer

import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
class MediaTttChipControllerTest : SysuiTestCase() {

    private lateinit var mediaTttChipController: MediaTttChipController

    @Mock
    private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mediaTttChipController = MediaTttChipController(context, windowManager)
    }

    @Test
    fun displayChip_chipAdded() {
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))

        verify(windowManager).addView(any(), any())
    }

    @Test
    fun displayChip_twice_chipNotAddedTwice() {
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))
        reset(windowManager)

        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun removeChip_chipRemoved() {
        // First, add the chip
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))

        // Then, remove it
        mediaTttChipController.removeChip()

        verify(windowManager).removeView(any())
    }

    @Test
    fun removeChip_noAdd_viewNotRemoved() {
        mediaTttChipController.removeChip()

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun moveCloserToTransfer_chipTextContainsDeviceName_noLoadingIcon_noUndo() {
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButtonVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun transferInitiated_chipTextContainsDeviceName_loadingIcon_noUndo() {
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME))

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButtonVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun transferSucceeded_chipTextContainsDeviceName_noLoadingIcon_undo() {
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME))

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButtonVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromCloserToTransferToTransferInitiated_loadingIconAppears() {
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME))

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_loadingIconDisappears() {
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME))
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME))

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_undoButtonAppears() {
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME))
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME))

        assertThat(getChipView().getUndoButtonVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferSucceededToMoveCloser_undoButtonDisappears() {
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME))
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))

        assertThat(getChipView().getUndoButtonVisibility()).isEqualTo(View.GONE)
    }

    private fun LinearLayout.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    private fun LinearLayout.getLoadingIconVisibility(): Int =
        this.requireViewById<View>(R.id.loading).visibility

    private fun LinearLayout.getUndoButtonVisibility(): Int =
        this.requireViewById<View>(R.id.undo).visibility

    private fun getChipView(): LinearLayout {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as LinearLayout
    }
}

private const val DEVICE_NAME = "My Tablet"
