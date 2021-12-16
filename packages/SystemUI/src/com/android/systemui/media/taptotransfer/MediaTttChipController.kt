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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

const val TAG = "MediaTapToTransfer"

/**
 * A controller to display and hide the Media Tap-To-Transfer chip. This chip is shown when a user
 * is currently playing media on a local "media cast sender" device (e.g. a phone) and gets close
 * enough to a "media cast receiver" device (e.g. a tablet). This chip encourages the user to
 * transfer the media from the sender device to the receiver device.
 */
@SysUISingleton
class MediaTttChipController @Inject constructor(
    private val context: Context,
    private val windowManager: WindowManager,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
) {

    @SuppressLint("WrongConstant") // We're allowed to use TYPE_VOLUME_OVERLAY
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL)
        type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        title = "Media Tap-To-Transfer Chip View"
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /** The chip view currently being displayed. Null if the chip is not being displayed. */
    private var chipView: LinearLayout? = null

    /** Displays the chip view for the given state. */
    fun displayChip(chipState: MediaTttChipState) {
        val oldChipView = chipView
        if (chipView == null) {
            chipView = LayoutInflater
                .from(context)
                .inflate(R.layout.media_ttt_chip, null) as LinearLayout
        }
        val currentChipView = chipView!!

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

        // Add view if necessary
        if (oldChipView == null) {
            windowManager.addView(chipView, windowLayoutParams)
        }
    }

    /** Hides the chip. */
    fun removeChip() {
        if (chipView == null) { return }
        windowManager.removeView(chipView)
        chipView = null
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
                    displayChip(TransferSucceeded(chipState.otherDeviceName, undoRunnable))
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
