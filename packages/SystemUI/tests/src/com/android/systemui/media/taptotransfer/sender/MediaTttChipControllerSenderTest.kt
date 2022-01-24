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

package com.android.systemui.media.taptotransfer.sender

import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
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
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class MediaTttChipControllerSenderTest : SysuiTestCase() {
    private lateinit var appIconDrawable: Drawable

    private lateinit var controllerSender: MediaTttChipControllerSender

    @Mock
    private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        appIconDrawable = Icon.createWithResource(context, R.drawable.ic_cake).loadDrawable(context)
        controllerSender = MediaTttChipControllerSender(context, windowManager)
    }

    @Test
    fun moveCloserToStartCast_appIcon_deviceName_noLoadingIcon_noUndo_noFailureIcon() {
        controllerSender.displayChip(moveCloserToStartCast())

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(appIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_ICON_CONTENT_DESC)
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun moveCloserToEndCast_appIcon_deviceName_noLoadingIcon_noUndo_noFailureIcon() {
        controllerSender.displayChip(moveCloserToEndCast())

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(appIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_ICON_CONTENT_DESC)
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToReceiverTriggered_appIcon_loadingIcon_noUndo_noFailureIcon() {
        controllerSender.displayChip(transferToReceiverTriggered())

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(appIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_ICON_CONTENT_DESC)
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferSucceeded_appIcon_deviceName_noLoadingIcon_noFailureIcon() {
        controllerSender.displayChip(transferSucceeded())

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(appIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_ICON_CONTENT_DESC)
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferSucceededNullUndoRunnable_noUndo() {
        controllerSender.displayChip(transferSucceeded(undoRunnable = null))

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferSucceededWithUndoRunnable_undoWithClick() {
        controllerSender.displayChip(transferSucceeded { })

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().hasOnClickListeners()).isTrue()
    }

    @Test
    fun transferSucceededWithUndoRunnable_undoButtonClickRunsRunnable() {
        var runnableRun = false
        val runnable = Runnable { runnableRun = true }

        controllerSender.displayChip(transferSucceeded(undoRunnable = runnable))
        getChipView().getUndoButton().performClick()

        assertThat(runnableRun).isTrue()
    }

    @Test
    fun transferFailed_appIcon_noDeviceName_noLoadingIcon_noUndo_failureIcon() {
        controllerSender.displayChip(transferFailed())

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(appIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_ICON_CONTENT_DESC)
        assertThat(chipView.getChipText()).doesNotContain(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromCloserToStartToTransferTriggered_loadingIconAppears() {
        controllerSender.displayChip(moveCloserToStartCast())
        controllerSender.displayChip(transferToReceiverTriggered())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferSucceeded_loadingIconDisappears() {
        controllerSender.displayChip(transferToReceiverTriggered())
        controllerSender.displayChip(transferSucceeded())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferSucceeded_undoButtonAppears() {
        controllerSender.displayChip(transferToReceiverTriggered())
        controllerSender.displayChip(transferSucceeded { })

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferSucceededToMoveCloserToStart_undoButtonDisappears() {
        controllerSender.displayChip(transferSucceeded())
        controllerSender.displayChip(moveCloserToStartCast())

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferFailed_failureIconAppears() {
        controllerSender.displayChip(transferToReceiverTriggered())
        controllerSender.displayChip(transferFailed())

        assertThat(getChipView().getFailureIcon().visibility).isEqualTo(View.VISIBLE)
    }

    private fun LinearLayout.getAppIconView() = this.requireViewById<ImageView>(R.id.app_icon)

    private fun LinearLayout.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    private fun LinearLayout.getLoadingIconVisibility(): Int =
        this.requireViewById<View>(R.id.loading).visibility

    private fun LinearLayout.getUndoButton(): View = this.requireViewById(R.id.undo)

    private fun LinearLayout.getFailureIcon(): View = this.requireViewById(R.id.failure_icon)

    private fun getChipView(): LinearLayout {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as LinearLayout
    }

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun moveCloserToStartCast() =
        MoveCloserToStartCast(appIconDrawable, APP_ICON_CONTENT_DESC, DEVICE_NAME)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun moveCloserToEndCast() =
        MoveCloserToEndCast(appIconDrawable, APP_ICON_CONTENT_DESC, DEVICE_NAME)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferToReceiverTriggered() =
        TransferToReceiverTriggered(appIconDrawable, APP_ICON_CONTENT_DESC, DEVICE_NAME)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferSucceeded(
        undoRunnable: Runnable? = null
    ) = TransferSucceeded(appIconDrawable, APP_ICON_CONTENT_DESC, DEVICE_NAME, undoRunnable)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferFailed() =
        TransferFailed(appIconDrawable, APP_ICON_CONTENT_DESC, DEVICE_NAME)
}

private const val DEVICE_NAME = "My Tablet"
private const val APP_ICON_CONTENT_DESC = "Content description"
