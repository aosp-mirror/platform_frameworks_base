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
import android.util.Log
import androidx.annotation.StringRes
import com.android.internal.logging.UiEventLogger
import com.android.systemui.res.R
import com.android.systemui.common.shared.model.Text

/**
 * A class enumerating all the possible states of the media tap-to-transfer chip on the sender
 * device.
 *
 * @property stateInt the integer from [StatusBarManager] corresponding with this state.
 * @property stringResId the res ID of the string that should be displayed in the chip. Null if the
 *   state should not have the chip be displayed.
 * @property transferStatus the transfer status that the chip state represents.
 * @property endItem the item that should be displayed in the end section of the chip.
 * @property timeoutLength how long the chip should display on the screen before it times out and
 *   disappears.
 */
enum class ChipStateSender(
    @StatusBarManager.MediaTransferSenderState val stateInt: Int,
    val uiEvent: UiEventLogger.UiEventEnum,
    @StringRes val stringResId: Int?,
    val transferStatus: TransferStatus,
    val endItem: SenderEndItem?,
    val timeoutLength: TimeoutLength = TimeoutLength.DEFAULT,
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
        transferStatus = TransferStatus.NOT_STARTED,
        endItem = null,
        // Give this view more time in case the loading view takes a bit to come in. (We don't want
        // this view to disappear and then the loading view to appear quickly afterwards.)
        timeoutLength = TimeoutLength.LONG,
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            return nextState == FAR_FROM_RECEIVER ||
                    nextState == TRANSFER_TO_RECEIVER_TRIGGERED
        }
    },

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
        transferStatus = TransferStatus.NOT_STARTED,
        endItem = null,
        timeoutLength = TimeoutLength.LONG,
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            return nextState == FAR_FROM_RECEIVER ||
                    nextState == TRANSFER_TO_THIS_DEVICE_TRIGGERED
        }
    },

    /**
     * A state representing that a transfer to the receiver device has been initiated (but not
     * completed).
     */
    TRANSFER_TO_RECEIVER_TRIGGERED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_TRIGGERED,
        R.string.media_transfer_playing_different_device,
        transferStatus = TransferStatus.IN_PROGRESS,
        endItem = SenderEndItem.Loading,
        // Give this view more time in case the succeeded/failed view takes a bit to come in. (We
        // don't want this view to disappear and then the next view to appear quickly afterwards.)
        timeoutLength = TimeoutLength.LONG,
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            return nextState == FAR_FROM_RECEIVER ||
                    nextState == TRANSFER_TO_RECEIVER_SUCCEEDED ||
                    nextState == TRANSFER_TO_RECEIVER_FAILED
        }
    },

    /**
     * A state representing that a transfer from the receiver device and back to this device (the
     * sender) has been initiated (but not completed).
     */
    TRANSFER_TO_THIS_DEVICE_TRIGGERED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
        R.string.media_transfer_playing_this_device,
        transferStatus = TransferStatus.IN_PROGRESS,
        endItem = SenderEndItem.Loading,
        timeoutLength = TimeoutLength.LONG,
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            return nextState == FAR_FROM_RECEIVER ||
                    nextState == TRANSFER_TO_THIS_DEVICE_SUCCEEDED ||
                    nextState == TRANSFER_TO_THIS_DEVICE_FAILED
        }
    },

    /**
     * A state representing that a transfer to the receiver device has been successfully completed.
     */
    TRANSFER_TO_RECEIVER_SUCCEEDED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_SUCCEEDED,
        R.string.media_transfer_playing_different_device,
        transferStatus = TransferStatus.SUCCEEDED,
        endItem = SenderEndItem.UndoButton(
            uiEventOnClick =
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_RECEIVER_CLICKED,
            newState =
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED
        ),
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            // Since _SUCCEEDED is the end of a transfer sequence, we should be able to move to any
            // state that represents the beginning of a new sequence.
            return stateIsStartOfSequence(nextState)
        }
    },

    /**
     * A state representing that a transfer back to this device has been successfully completed.
     */
    TRANSFER_TO_THIS_DEVICE_SUCCEEDED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
        R.string.media_transfer_playing_this_device,
        transferStatus = TransferStatus.SUCCEEDED,
        endItem = SenderEndItem.UndoButton(
            uiEventOnClick =
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED,
            newState =
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED
        ),
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            // Since _SUCCEEDED is the end of a transfer sequence, we should be able to move to any
            // state that represents the beginning of a new sequence.
            return stateIsStartOfSequence(nextState)
        }
    },

    /** A state representing that a transfer to the receiver device has failed. */
    TRANSFER_TO_RECEIVER_FAILED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED,
        R.string.media_transfer_failed,
        transferStatus = TransferStatus.FAILED,
        endItem = SenderEndItem.Error,
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            // Since _FAILED is the end of a transfer sequence, we should be able to move to any
            // state that represents the beginning of a new sequence.
            return stateIsStartOfSequence(nextState)
        }
    },

    /** A state representing that a transfer back to this device has failed. */
    TRANSFER_TO_THIS_DEVICE_FAILED(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_FAILED,
        R.string.media_transfer_failed,
        transferStatus = TransferStatus.FAILED,
        endItem = SenderEndItem.Error,
    ) {
        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            // Since _FAILED is the end of a transfer sequence, we should be able to move to any
            // state that represents the beginning of a new sequence.
            return stateIsStartOfSequence(nextState)
        }
    },

    /** A state representing that this device is far away from any receiver device. */
    FAR_FROM_RECEIVER(
        StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
        MediaTttSenderUiEvents.MEDIA_TTT_SENDER_FAR_FROM_RECEIVER,
        stringResId = null,
        transferStatus = TransferStatus.TOO_FAR,
        // We shouldn't be displaying the chipbar anyway
        endItem = null,
    ) {
        override fun getChipTextString(context: Context, otherDeviceName: String): Text {
            // TODO(b/245610654): Better way to handle this.
            throw IllegalArgumentException("FAR_FROM_RECEIVER should never be displayed, " +
                "so its string should never be fetched")
        }

        override fun isValidNextState(nextState: ChipStateSender): Boolean {
            // When far away, we can go to any state that represents the start of a transfer
            // sequence.
            return stateIsStartOfSequence(nextState)
        }
    };

    /**
     * Returns a fully-formed string with the text that the chip should display.
     *
     * Throws an NPE if [stringResId] is null.
     *
     * @param otherDeviceName the name of the other device involved in the transfer.
     */
    open fun getChipTextString(context: Context, otherDeviceName: String): Text {
        return Text.Loaded(context.getString(stringResId!!, otherDeviceName))
    }

    /**
     * Returns true if moving from this state to [nextState] is a valid transition.
     *
     * In general, we expect a media transfer go to through a sequence of states:
     * For push-to-receiver:
     *   - ALMOST_CLOSE_TO_START_CAST => TRANSFER_TO_RECEIVER_TRIGGERED =>
     *     TRANSFER_TO_RECEIVER_(SUCCEEDED|FAILED)
     *   - ALMOST_CLOSE_TO_END_CAST => TRANSFER_TO_THIS_DEVICE_TRIGGERED =>
     *     TRANSFER_TO_THIS_DEVICE_(SUCCEEDED|FAILED)
     *
     * This method should validate that the states go through approximately that sequence.
     *
     * See b/221265848 for more details.
     */
    abstract fun isValidNextState(nextState: ChipStateSender): Boolean

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

        /**
         * Validates the transition from a chip state to another.
         *
         * @param currentState is the current state of the chip.
         * @param desiredState is the desired state of the chip.
         * @return true if the transition from [currentState] to [desiredState] is valid, and false
         * otherwise.
         */
        fun isValidStateTransition(
                currentState: ChipStateSender?,
                desiredState: ChipStateSender,
        ): Boolean {
            // Far from receiver is the default state.
            if (currentState == null) {
                return FAR_FROM_RECEIVER.isValidNextState(desiredState)
            }

            // No change in state is valid.
            if (currentState == desiredState) {
                return true
            }

            return currentState.isValidNextState(desiredState)
        }

        /**
         * Returns true if [state] represents a state at the beginning of a sequence and false
         * otherwise.
         */
        private fun stateIsStartOfSequence(state: ChipStateSender): Boolean {
            return state == FAR_FROM_RECEIVER ||
                state.transferStatus == TransferStatus.NOT_STARTED ||
                // It's possible to skip the NOT_STARTED phase and go immediately into the
                // IN_PROGRESS phase.
                state.transferStatus == TransferStatus.IN_PROGRESS
        }
    }
}

/** Represents the item that should be displayed in the end section of the chip. */
sealed class SenderEndItem {
    /** A loading icon should be displayed. */
    object Loading : SenderEndItem()

    /** An error icon should be displayed. */
    object Error : SenderEndItem()

    /**
     * An undo button should be displayed.
     *
     * @property uiEventOnClick the UI event to log when this button is clicked.
     * @property newState the state that should immediately be transitioned to.
     */
    data class UndoButton(
        val uiEventOnClick: UiEventLogger.UiEventEnum,
        @StatusBarManager.MediaTransferSenderState val newState: Int,
    ) : SenderEndItem()
}

/** Represents how long the chip should be visible before it times out. */
enum class TimeoutLength {
    /** A default timeout used for temporary displays at the top of the screen. */
    DEFAULT,
    /**
     * A longer timeout. Should be used when the status is pending (e.g. loading), so that the user
     * remains informed about the process for longer and so that the UI has more time to resolve the
     * pending state before disappearing.
     */
    LONG,
}

private const val TAG = "ChipStateSender"
