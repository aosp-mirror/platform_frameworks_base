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
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Future

@SmallTest
class MediaTttChipControllerSenderTest : SysuiTestCase() {
    private lateinit var appIconDrawable: Drawable
    private lateinit var fakeMainClock: FakeSystemClock
    private lateinit var fakeMainExecutor: FakeExecutor
    private lateinit var fakeBackgroundClock: FakeSystemClock
    private lateinit var fakeBackgroundExecutor: FakeExecutor

    private lateinit var controllerSender: MediaTttChipControllerSender

    @Mock
    private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        appIconDrawable = Icon.createWithResource(context, R.drawable.ic_cake).loadDrawable(context)
        fakeMainClock = FakeSystemClock()
        fakeMainExecutor = FakeExecutor(fakeMainClock)
        fakeBackgroundClock = FakeSystemClock()
        fakeBackgroundExecutor = FakeExecutor(fakeBackgroundClock)
        controllerSender = MediaTttChipControllerSender(
            context, windowManager, fakeMainExecutor, fakeBackgroundExecutor
        )
    }

    @Test
    fun moveCloserToTransfer_appIcon_chipTextContainsDeviceName_noLoadingIcon_noUndo() {
        controllerSender.displayChip(moveCloserToTransfer())

        val chipView = getChipView()
        assertThat(chipView.getAppIconDrawable()).isEqualTo(appIconDrawable)
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferInitiated_futureNotResolvedYet_appIcon_loadingIcon_noUndo() {
        val future: SettableFuture<Runnable?> = SettableFuture.create()
        controllerSender.displayChip(transferInitiated(future))

        // Don't resolve the future in any way and don't run our executors

        // Assert we're still in the loading state
        val chipView = getChipView()
        assertThat(chipView.getAppIconDrawable()).isEqualTo(appIconDrawable)
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferInitiated_futureResolvedSuccessfully_switchesToTransferSucceeded() {
        val future: SettableFuture<Runnable?> = SettableFuture.create()
        val undoRunnable = Runnable { }

        controllerSender.displayChip(transferInitiated(future))

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

        controllerSender.displayChip(transferInitiated(future))

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
        controllerSender.displayChip(transferInitiated(future))

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
    fun transferSucceeded_appIcon_chipTextContainsDeviceName_noLoadingIcon() {
        controllerSender.displayChip(transferSucceeded())

        val chipView = getChipView()
        assertThat(chipView.getAppIconDrawable()).isEqualTo(appIconDrawable)
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
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
    fun changeFromCloserToTransferToTransferInitiated_loadingIconAppears() {
        controllerSender.displayChip(moveCloserToTransfer())
        controllerSender.displayChip(transferInitiated())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_loadingIconDisappears() {
        controllerSender.displayChip(transferInitiated())
        controllerSender.displayChip(transferSucceeded())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_undoButtonAppears() {
        controllerSender.displayChip(transferInitiated())
        controllerSender.displayChip(transferSucceeded { })

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferSucceededToMoveCloser_undoButtonDisappears() {
        controllerSender.displayChip(transferSucceeded())
        controllerSender.displayChip(moveCloserToTransfer())

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.GONE)
    }

    private fun LinearLayout.getAppIconDrawable(): Drawable =
        (this.requireViewById<ImageView>(R.id.app_icon)).drawable

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

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun moveCloserToTransfer() = MoveCloserToTransfer(appIconDrawable, DEVICE_NAME)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferInitiated(
        future: Future<Runnable?> = TEST_FUTURE
    ) = TransferInitiated(appIconDrawable, DEVICE_NAME, future)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferSucceeded(
        undoRunnable: Runnable? = null
    ) = TransferSucceeded(appIconDrawable, DEVICE_NAME, undoRunnable)
}

private const val DEVICE_NAME = "My Tablet"
// Use a settable future that hasn't yet been set so that we don't immediately switch to the success
// state.
private val TEST_FUTURE: SettableFuture<Runnable?> = SettableFuture.create()
