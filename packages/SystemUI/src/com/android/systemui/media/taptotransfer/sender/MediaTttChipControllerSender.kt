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
import com.android.systemui.media.taptotransfer.common.MediaTttChipControllerCommon
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **sending** device. This
 * chip is shown when a user is transferring media to/from this device and a receiver device.
 */
@SysUISingleton
class MediaTttChipControllerSender @Inject constructor(
    context: Context,
    windowManager: WindowManager,
) : MediaTttChipControllerCommon<ChipStateSender>(
    context, windowManager, R.layout.media_ttt_chip
) {

    /** Displays the chip view for the given state. */
    override fun updateChipView(chipState: ChipStateSender, currentChipView: ViewGroup) {
        // App icon
        setIcon(chipState, currentChipView)

        // Text
        currentChipView.requireViewById<TextView>(R.id.text).apply {
            text = chipState.getChipTextString(context)
        }

        // Loading
        currentChipView.requireViewById<View>(R.id.loading).visibility =
            if (chipState.showLoading()) { View.VISIBLE } else { View.GONE }

        // Undo
        val undoClickListener: View.OnClickListener? =
            if (chipState is TransferToReceiverSucceeded && chipState.undoCallback != null)
                View.OnClickListener {
                    chipState.undoCallback.onUndoTriggered()
                    // The external service should eventually send us a
                    // TransferToThisDeviceTriggered state, but that may take too long to go through
                    // the binder and the user may be confused as to why the UI hasn't changed yet.
                    // So, we immediately change the UI here.
                    displayChip(
                        TransferToThisDeviceTriggered(
                            chipState.appIconDrawable,
                            chipState.appIconContentDescription
                        )
                    )
                }
            else
                null
        val undoView = currentChipView.requireViewById<View>(R.id.undo)
        undoView.visibility = if (undoClickListener != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        undoView.setOnClickListener(undoClickListener)

        // Failure
        val showFailure = chipState is TransferFailed
        currentChipView.requireViewById<View>(R.id.failure_icon).visibility =
            if (showFailure) { View.VISIBLE } else { View.GONE }
    }
}
