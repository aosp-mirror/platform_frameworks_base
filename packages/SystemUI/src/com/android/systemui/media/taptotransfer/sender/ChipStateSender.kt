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
import android.graphics.drawable.Drawable
import android.view.View
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.media.taptotransfer.common.MediaTttChipState

/**
 * A class that stores all the information necessary to display the media tap-to-transfer chip on
 * the sender device.
 *
 * This is a sealed class where each subclass represents a specific chip state. Each subclass can
 * contain additional information that is necessary for only that state.
 */
sealed class ChipStateSender(
    appIconDrawable: Drawable,
    appIconContentDescription: String
) : MediaTttChipState(appIconDrawable, appIconContentDescription) {
    /** Returns a fully-formed string with the text that the chip should display. */
    abstract fun getChipTextString(context: Context): String

    /** Returns true if the loading icon should be displayed and false otherwise. */
    open fun showLoading(): Boolean = false

    /**
     * Returns a click listener for the undo button on the chip. Returns null if this chip state
     * doesn't have an undo button.
     *
     * @param controllerSender passed as a parameter in case we want to display a new chip state
     *   when undo is clicked.
     */
    open fun undoClickListener(
        controllerSender: MediaTttChipControllerSender
    ): View.OnClickListener? = null
}

/**
 * A state representing that the two devices are close but not close enough to *start* a cast to
 * the receiver device. The chip will instruct the user to move closer in order to initiate the
 * transfer to the receiver.
 *
 * @property otherDeviceName the name of the other device involved in the transfer.
 */
class AlmostCloseToStartCast(
    appIconDrawable: Drawable,
    appIconContentDescription: String,
    private val otherDeviceName: String,
) : ChipStateSender(appIconDrawable, appIconContentDescription) {
    override fun getChipTextString(context: Context): String {
        return context.getString(R.string.media_move_closer_to_start_cast, otherDeviceName)
    }
}

/**
 * A state representing that the two devices are close but not close enough to *end* a cast that's
 * currently occurring the receiver device. The chip will instruct the user to move closer in order
 * to initiate the transfer from the receiver and back onto this device (the original sender).
 *
 * @property otherDeviceName the name of the other device involved in the transfer.
 */
class AlmostCloseToEndCast(
    appIconDrawable: Drawable,
    appIconContentDescription: String,
    private val otherDeviceName: String,
) : ChipStateSender(appIconDrawable, appIconContentDescription) {
    override fun getChipTextString(context: Context): String {
        return context.getString(R.string.media_move_closer_to_end_cast, otherDeviceName)
    }
}

/**
 * A state representing that a transfer to the receiver device has been initiated (but not
 * completed).
 *
 * @property otherDeviceName the name of the other device involved in the transfer.
 */
class TransferToReceiverTriggered(
    appIconDrawable: Drawable,
    appIconContentDescription: String,
    private val otherDeviceName: String
) : ChipStateSender(appIconDrawable, appIconContentDescription) {
    override fun getChipTextString(context: Context): String {
        return context.getString(R.string.media_transfer_playing_different_device, otherDeviceName)
    }

    override fun showLoading() = true
}

/**
 * A state representing that a transfer from the receiver device and back to this device (the
 * sender) has been initiated (but not completed).
 */
class TransferToThisDeviceTriggered(
    appIconDrawable: Drawable,
    appIconContentDescription: String
) : ChipStateSender(appIconDrawable, appIconContentDescription) {
    override fun getChipTextString(context: Context): String {
        return context.getString(R.string.media_transfer_playing_this_device)
    }

    override fun showLoading() = true
}

/**
 * A state representing that a transfer to the receiver device has been successfully completed.
 *
 * @property otherDeviceName the name of the other device involved in the transfer.
 * @property undoCallback if present, the callback that should be called when the user clicks the
 *   undo button. The undo button will only be shown if this is non-null.
 */
class TransferToReceiverSucceeded(
    appIconDrawable: Drawable,
    appIconContentDescription: String,
    private val otherDeviceName: String,
    val undoCallback: IUndoMediaTransferCallback? = null
) : ChipStateSender(appIconDrawable, appIconContentDescription) {
    override fun getChipTextString(context: Context): String {
        return context.getString(R.string.media_transfer_playing_different_device, otherDeviceName)
    }

    override fun undoClickListener(
        controllerSender: MediaTttChipControllerSender
    ): View.OnClickListener? {
        if (undoCallback == null) {
            return null
        }

        return View.OnClickListener {
            this.undoCallback.onUndoTriggered()
            // The external service should eventually send us a TransferToThisDeviceTriggered state,
            // but that may take too long to go through the binder and the user may be confused as
            // to why the UI hasn't changed yet. So, we immediately change the UI here.
            controllerSender.displayChip(
                TransferToThisDeviceTriggered(
                    this.appIconDrawable,
                    this.appIconContentDescription
                )
            )
        }
    }
}

/**
 * A state representing that a transfer back to this device has been successfully completed.
 *
 * @property otherDeviceName the name of the other device involved in the transfer.
 * @property undoCallback if present, the callback that should be called when the user clicks the
 *   undo button. The undo button will only be shown if this is non-null.
 */
class TransferToThisDeviceSucceeded(
    appIconDrawable: Drawable,
    appIconContentDescription: String,
    private val otherDeviceName: String,
    val undoCallback: IUndoMediaTransferCallback? = null
) : ChipStateSender(appIconDrawable, appIconContentDescription) {
    override fun getChipTextString(context: Context): String {
        return context.getString(R.string.media_transfer_playing_this_device)
    }

    override fun undoClickListener(
        controllerSender: MediaTttChipControllerSender
    ): View.OnClickListener? {
        if (undoCallback == null) {
            return null
        }

        return View.OnClickListener {
            this.undoCallback.onUndoTriggered()
            // The external service should eventually send us a TransferToReceiverTriggered state,
            // but that may take too long to go through the binder and the user may be confused as
            // to why the UI hasn't changed yet. So, we immediately change the UI here.
            controllerSender.displayChip(
                TransferToReceiverTriggered(
                    this.appIconDrawable,
                    this.appIconContentDescription,
                    this.otherDeviceName
                )
            )
        }
    }
}

/** A state representing that a transfer has failed. */
class TransferFailed(
    appIconDrawable: Drawable,
    appIconContentDescription: String
) : ChipStateSender(appIconDrawable, appIconContentDescription) {
    override fun getChipTextString(context: Context): String {
        return context.getString(R.string.media_transfer_failed)
    }
}
