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
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
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

    private lateinit var fakeMainClock: FakeSystemClock
    private lateinit var fakeMainExecutor: FakeExecutor
    private lateinit var fakeBackgroundClock: FakeSystemClock
    private lateinit var fakeBackgroundExecutor: FakeExecutor

    private lateinit var mediaTttChipController: MediaTttChipController

    @Mock
    private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        fakeMainClock = FakeSystemClock()
        fakeMainExecutor = FakeExecutor(fakeMainClock)
        fakeBackgroundClock = FakeSystemClock()
        fakeBackgroundExecutor = FakeExecutor(fakeBackgroundClock)
        mediaTttChipController = MediaTttChipController(
            context, windowManager, fakeMainExecutor, fakeBackgroundExecutor
        )
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
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferInitiated_futureNotResolvedYet_loadingIcon_noUndo() {
        val future: SettableFuture<Runnable?> = SettableFuture.create()
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME, future))

        // Don't resolve the future in any way and don't run our executors

        // Assert we're still in the loading state
        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferInitiated_futureResolvedSuccessfully_switchesToTransferSucceeded() {
        val future: SettableFuture<Runnable?> = SettableFuture.create()
        val undoRunnable = Runnable { }

        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME, future))

        future.set(undoRunnable)
        fakeBackgroundExecutor.advanceClockToLast()
        fakeBackgroundExecutor.runAllReady()
        fakeMainExecutor.advanceClockToLast()
        val numRun = fakeMainExecutor.runAllReady()

        // Assert we ran the future callback
        assertThat(numRun).isEqualTo(1)
        // Assert that we've moved to the successful state
        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun transferInitiated_futureCancelled_chipRemoved() {
        val future: SettableFuture<Runnable?> = SettableFuture.create()

        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME, future))

        future.cancel(true)
        fakeBackgroundExecutor.advanceClockToLast()
        fakeBackgroundExecutor.runAllReady()
        fakeMainExecutor.advanceClockToLast()
        val numRun = fakeMainExecutor.runAllReady()

        // Assert we ran the future callback
        assertThat(numRun).isEqualTo(1)
        // Assert that we've hidden the chip
        verify(windowManager).removeView(any())
    }

    @Test
    fun transferInitiated_futureNotResolvedAfterTimeout_chipRemoved() {
        val future: SettableFuture<Runnable?> = SettableFuture.create()
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME, future))

        // We won't set anything on the future, but we will still run the executors so that we're
        // waiting on the future resolving. If we have a bug in our code, then this test will time
        // out because we're waiting on the future indefinitely.
        fakeBackgroundExecutor.advanceClockToLast()
        fakeBackgroundExecutor.runAllReady()
        fakeMainExecutor.advanceClockToLast()
        val numRun = fakeMainExecutor.runAllReady()

        // Assert we eventually decide to not wait for the future anymore
        assertThat(numRun).isEqualTo(1)
        // Assert we've hidden the chip
        verify(windowManager).removeView(any())
    }

    @Test
    fun transferSucceededNullUndoRunnable_chipTextContainsDeviceName_noLoadingIcon_noUndo() {
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME, undoRunnable = null))

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferSucceededWithUndoRunnable_chipTextContainsDeviceName_noLoadingIcon_undoWithClick() {
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME) { })

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().hasOnClickListeners()).isTrue()
    }

    @Test
    fun transferSucceededWithUndoRunnable_undoButtonClickRunsRunnable() {
        var runnableRun = false
        val runnable = Runnable { runnableRun = true }

        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME, runnable))
        getChipView().getUndoButton().performClick()

        assertThat(runnableRun).isTrue()
    }

    @Test
    fun changeFromCloserToTransferToTransferInitiated_loadingIconAppears() {
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME, TEST_FUTURE))

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_loadingIconDisappears() {
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME, TEST_FUTURE))
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME))

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_undoButtonAppears() {
        mediaTttChipController.displayChip(TransferInitiated(DEVICE_NAME, TEST_FUTURE))
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME) { })

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferSucceededToMoveCloser_undoButtonDisappears() {
        mediaTttChipController.displayChip(TransferSucceeded(DEVICE_NAME))
        mediaTttChipController.displayChip(MoveCloserToTransfer(DEVICE_NAME))

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.GONE)
    }

    private fun LinearLayout.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    private fun LinearLayout.getLoadingIconVisibility(): Int =
        this.requireViewById<View>(R.id.loading).visibility

    private fun LinearLayout.getUndoButton(): View = this.requireViewById(R.id.undo)

    private fun getChipView(): LinearLayout {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as LinearLayout
    }
}

private const val DEVICE_NAME = "My Tablet"
// Use a settable future that hasn't yet been set so that we don't immediately switch to the success
// state.
private val TEST_FUTURE: SettableFuture<Runnable?> = SettableFuture.create()
