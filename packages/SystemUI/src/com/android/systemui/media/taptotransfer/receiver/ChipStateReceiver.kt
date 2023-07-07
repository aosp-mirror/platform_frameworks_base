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

package com.android.systemui.media.taptotransfer.receiver

import android.app.StatusBarManager
import android.util.Log
import com.android.internal.logging.UiEventLogger

/**
 * A class that stores all the information necessary to display the media tap-to-transfer chip on
 * the receiver device.
 */
enum class ChipStateReceiver(
    @StatusBarManager.MediaTransferSenderState val stateInt: Int,
    val uiEvent: UiEventLogger.UiEventEnum
) {
    CLOSE_TO_SENDER(
        StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
        MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_CLOSE_TO_SENDER
    ),
    FAR_FROM_SENDER(
        StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
        MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_FAR_FROM_SENDER
    ),
    TRANSFER_TO_RECEIVER_SUCCEEDED(
        StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
        MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_TRANSFER_TO_RECEIVER_SUCCEEDED,
    ),
    TRANSFER_TO_RECEIVER_FAILED(
        StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_FAILED,
        MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_TRANSFER_TO_RECEIVER_FAILED,
    );

    companion object {
        /**
         * Returns the receiver state enum associated with the given [displayState] from
         * [StatusBarManager].
         */
        fun getReceiverStateFromId(
            @StatusBarManager.MediaTransferReceiverState displayState: Int
        ): ChipStateReceiver? =
        try {
            values().first { it.stateInt == displayState }
        } catch (e: NoSuchElementException) {
            Log.e(TAG, "Could not find requested state $displayState", e)
            null
        }

        /**
         * Returns the state int from [StatusBarManager] associated with the given sender state
         * name.
         *
         * @param name the name of one of the [ChipStateReceiver] enums.
         */
        @StatusBarManager.MediaTransferReceiverState
        fun getReceiverStateIdFromName(name: String): Int = valueOf(name).stateInt
    }
}

private const val TAG = "ChipStateReceiver"
