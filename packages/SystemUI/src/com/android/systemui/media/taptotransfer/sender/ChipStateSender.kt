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

import android.app.StatusBarManager
import android.content.Context
import android.media.MediaRoute2Info
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import com.android.internal.logging.UiEventLogger
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.media.taptotransfer.common.DEFAULT_TIMEOUT_MILLIS

/**
 * A class enumerating all the possible states of the media tap-to-transfer chip on the sender
 * device.
 *
 * @property stateInt the integer from [StatusBarManager] corresponding with this state.
 * @property stringResId the res ID of the string that should be displayed in the chip. Null if the
 *   state should not have the chip be displayed.
 * @property isMidTransfer true if the state represents that a transfer is currently ongoing.
 * @property isTransferFailure true if the state represents that the transfer has failed.
 * @property timeout the amount of time this chip should display on the screen before it times out
 *   and disappears.
 */
enum class ChipStateSender(
    @StatusBarManager.MediaTransferSenderState val stateInt: Int,
    val uiEvent: UiEventLogger.UiEventEnum,
    @StringRes val stringResId: Int?,
    val isMidTransfer: Boolean = false,
    val isTransferFailure: Boolean = false,
    val timeout: Long = DEFAULT_TIMEOUT_MILLIS
) {
    /**
     * A state representing that the two devices are close but not close enough to *start* a cast to
     * the receiver device. The chip will instruct the user to move closer in order to initiate the
     * transfer to the receiver.
     */
    ALMOST_CLOSE_TO_START_CAST(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_START_CAST,
        R.string.media_move_closer_to_start_cast,
    ),

    /**
     * A state representing that the two devices are close but not close enough to *end* a cast
     * that's currently occurring the receiver device. The chip will instruct the user to move
     * closer in order to initiate the transfer from the receiver and back onto this device (the
     * original sender).
     */
    ALMOST_CLOSE_TO_END_CAST(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_END_CAST,
        R.string.media_move_closer_to_end_cast,
    ),

    /**
     * A state representing that a transfer to the receiver device has been initiated (but not
     * completed).
     */
    TRANSFER_TO_RECEIVER_TRIGGERED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_TRIGGERED,
        R.string.media_transfer_playing_different_device,
        isMidTransfer = true,
        timeout = TRANSFER_TRIGGERED_TIMEOUT_MILLIS
    ),

    /**
     * A state representing that a transfer from the receiver device and back to this device (the
     * sender) has been initiated (but not completed).
     */
    TRANSFER_TO_THIS_DEVICE_TRIGGERED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
        R.string.media_transfer_playing_this_device,
        isMidTransfer = true,
        timeout = TRANSFER_TRIGGERED_TIMEOUT_MILLIS
    ),

    /**
     * A state representing that a transfer to the receiver device has been successfully completed.
     */
    TRANSFER_TO_RECEIVER_SUCCEEDED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_SUCCEEDED,
        R.string.media_transfer_playing_different_device
    ) {
        override fun undoClickListener(
            controllerSender: MediaTttChipControllerSender,
            routeInfo: MediaRoute2Info,
            undoCallback: IUndoMediaTransferCallback?,
            uiEventLogger: MediaTttSenderUiEventLogger
        ): View.OnClickListener? {
            if (undoCallback == null) {
                return null
            }
            return View.OnClickListener {
                uiEventLogger.logUndoClicked(
                    MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_RECEIVER_CLICKED
                )
                undoCallback.onUndoTriggered()
                // The external service should eventually send us a TransferToThisDeviceTriggered
                // state, but that may take too long to go through the binder and the user may be
                // confused ast o why the UI hasn't changed yet. So, we immediately change the UI
                // here.
                controllerSender.displayChip(
                    ChipSenderInfo(
                        TRANSFER_TO_THIS_DEVICE_TRIGGERED, routeInfo, undoCallback
                    )
                )
            }
        }
    },

    /**
     * A state representing that a transfer back to this device has been successfully completed.
     */
    TRANSFER_TO_THIS_DEVICE_SUCCEEDED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
        R.string.media_transfer_playing_this_device
    ) {
        override fun undoClickListener(
            controllerSender: MediaTttChipControllerSender,
            routeInfo: MediaRoute2Info,
            undoCallback: IUndoMediaTransferCallback?,
            uiEventLogger: MediaTttSenderUiEventLogger
        ): View.OnClickListener? {
            if (undoCallback == null) {
                return null
            }
            return View.OnClickListener {
                uiEventLogger.logUndoClicked(
                    MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED
                )
                undoCallback.onUndoTriggered()
                // The external service should eventually send us a TransferToReceiverTriggered
                // state, but that may take too long to go through the binder and the user may be
                // confused as to why the UI hasn't changed yet. So, we immediately change the UI
                // here.
                controllerSender.displayChip(
                    ChipSenderInfo(
                        TRANSFER_TO_RECEIVER_TRIGGERED, routeInfo, undoCallback
                    )
                )
            }
        }
    },

    /** A state representing that a transfer to the receiver device has failed. */
    TRANSFER_TO_RECEIVER_FAILED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED,
        R.string.media_transfer_failed,
        isTransferFailure = true
    ),

    /** A state representing that a transfer back to this device has failed. */
    TRANSFER_TO_THIS_DEVICE_FAILED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_FAILED,
        R.string.media_transfer_failed,
        isTransferFailure = true
    ),

    /** A state representing that this device is far away from any receiver device. */
    FAR_FROM_RECEIVER(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_FAR_FROM_RECEIVER,
        stringResId = null
    );

    /**
     * Returns a fully-formed string with the text that the chip should display.
     *
     * @param otherDeviceName the name of the other device involved in the transfer.
     */
    fun getChipTextString(context: Context, otherDeviceName: String): String? {
        if (stringResId == null) {
            return null
        }
        return context.getString(stringResId, otherDeviceName)
    }

    /**
     * Returns a click listener for the undo button on the chip. Returns null if this chip state
     * doesn't have an undo button.
     *
     * @param controllerSender passed as a parameter in case we want to display a new chip state
     *   when undo is clicked.
     * @param undoCallback if present, the callback that should be called when the user clicks the
     *   undo button. The undo button will only be shown if this is non-null.
     */
    open fun undoClickListener(
        controllerSender: MediaTttChipControllerSender,
        routeInfo: MediaRoute2Info,
        undoCallback: IUndoMediaTransferCallback?,
        uiEventLogger: MediaTttSenderUiEventLogger
    ): View.OnClickListener? = null

    companion object {
        /**
         * Returns the sender state enum associated with the given [displayState] from
         * [StatusBarManager].
         */
        fun getSenderStateFromId(
            @StatusBarManager.MediaTransferSenderState displayState: Int,
        ): ChipStateSender? = try {
            values().first { it.stateInt == displayState }
        } catch (e: NoSuchElementException) {
            Log.e(TAG, "Could not find requested state $displayState", e)
            null
        }

        /**
         * Returns the state int from [StatusBarManager] associated with the given sender state
         * name.
         *
         * @param name the name of one of the [ChipStateSender] enums.
         */
        @StatusBarManager.MediaTransferSenderState
        fun getSenderStateIdFromName(name: String): Int = valueOf(name).stateInt
    }
}

// Give the Transfer*Triggered states a longer timeout since those states represent an active
// process and we should keep the user informed about it as long as possible (but don't allow it to
// continue indefinitely).
private const val TRANSFER_TRIGGERED_TIMEOUT_MILLIS = 15000L

private const val TAG = "ChipStateSender"
