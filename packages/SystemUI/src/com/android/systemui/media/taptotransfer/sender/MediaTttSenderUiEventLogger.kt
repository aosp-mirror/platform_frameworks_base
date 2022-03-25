/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.util.Log
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** A class for analytics logging for the media tap-to-transfer chip on the sender device. */
@SysUISingleton
class MediaTttSenderUiEventLogger @Inject constructor(private val logger: UiEventLogger) {
    /** Logs that the sender chip has changed states. */
    fun logSenderStateChange(chipState: ChipStateSender) {
        logger.log(chipState.uiEvent)
    }

    /**
     * Logs that the undo button was clicked.
     *
     * @param undoUiEvent the uiEvent specific to which undo button was clicked.
     */
    fun logUndoClicked(undoUiEvent: UiEventLogger.UiEventEnum) {
        val isUndoEvent =
            undoUiEvent == MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_RECEIVER_CLICKED
                    || undoUiEvent ==
                    MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED
        if (!isUndoEvent) {
            Log.w(
                MediaTttSenderUiEventLogger::class.simpleName!!,
            "Must pass an undo-specific UiEvent."
            )
            return
        }
        logger.log(undoUiEvent)
    }
}

enum class MediaTttSenderUiEvents(val metricId: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "The undo button on the media ttt chip on the sender device was clicked " +
            "to undo the transfer to the receiver device")
    MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_RECEIVER_CLICKED(971),
    @UiEvent(doc = "The undo button on the media ttt chip on the sender device was clicked " +
            "to undo the transfer back to this device")
    MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED(972),

    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_START_CAST(973),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_END_CAST(974),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_TRIGGERED(975),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_TRIGGERED(976),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_SUCCEEDED(977),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_SUCCEEDED(978),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED(979),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_FAILED(980),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_SENDER_* docs")
    MEDIA_TTT_SENDER_FAR_FROM_RECEIVER(981);

    override fun getId() = metricId
}
