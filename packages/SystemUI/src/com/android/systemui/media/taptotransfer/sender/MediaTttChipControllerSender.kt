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

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.MediaTttChipControllerCommon
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **sending** device. This
 * chip is shown when a user is transferring media to/from this device and a receiver device.
 */
@SysUISingleton
class MediaTttChipControllerSender @Inject constructor(
    context: Context,
    windowManager: WindowManager,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
) : MediaTttChipControllerCommon<ChipStateSender>(
    context, windowManager, R.layout.media_ttt_chip
) {

    /** Displays the chip view for the given state. */
    override fun updateChipView(chipState: ChipStateSender, currentChipView: ViewGroup) {
        // App icon
        setIcon(chipState, currentChipView)

        // Text
        currentChipView.requireViewById<TextView>(R.id.text).apply {
            text = context.getString(chipState.chipText, chipState.otherDeviceName)
        }

        // Loading
        val showLoading = chipState is TransferInitiated
        currentChipView.requireViewById<View>(R.id.loading).visibility =
            if (showLoading) { View.VISIBLE } else { View.GONE }

        // Undo
        val undoClickListener: View.OnClickListener? =
            if (chipState is TransferSucceeded && chipState.undoRunnable != null)
                View.OnClickListener { chipState.undoRunnable.run() }
            else
                null
        val undoView = currentChipView.requireViewById<View>(R.id.undo)
        undoView.visibility = if (undoClickListener != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        undoView.setOnClickListener(undoClickListener)

        // Future handling
        if (chipState is TransferInitiated) {
            addFutureCallback(chipState)
        }
    }

    /**
     * Adds the appropriate callbacks to [chipState.future] so that we update the chip correctly
     * when the future resolves.
     */
    private fun addFutureCallback(chipState: TransferInitiated) {
        // Listen to the future on a background thread so we don't occupy the main thread while we
        // wait for it to complete.
        backgroundExecutor.execute {
            try {
                val undoRunnable = chipState.future.get(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Make UI changes on the main thread
                mainExecutor.execute {
                    displayChip(
                        TransferSucceeded(
                            chipState.appIconDrawable, chipState.otherDeviceName, undoRunnable
                        )
                    )
                }
            } catch (ex: Exception) {
                // TODO(b/203800327): Maybe show a failure chip here if UX decides we need one.
                mainExecutor.execute {
                    removeChip()
                }
            }
        }
    }
}

private const val TRANSFER_TIMEOUT_SECONDS = 10L
