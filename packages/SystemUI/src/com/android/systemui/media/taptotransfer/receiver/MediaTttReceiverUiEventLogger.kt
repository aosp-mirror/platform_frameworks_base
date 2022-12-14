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

package com.android.systemui.media.taptotransfer.receiver

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** A class for analytics logging for the media tap-to-transfer chip on the receiver device. */
@SysUISingleton
class MediaTttReceiverUiEventLogger @Inject constructor(private val logger: UiEventLogger) {
    /** Logs that the receiver chip has changed states. */
    fun logReceiverStateChange(chipState: ChipStateReceiver) {
        logger.log(chipState.uiEvent)
    }
}

enum class MediaTttReceiverUiEvents(val metricId: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_RECEIVER_* docs")
    MEDIA_TTT_RECEIVER_CLOSE_TO_SENDER(982),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_RECEIVER_* docs")
    MEDIA_TTT_RECEIVER_FAR_FROM_SENDER(983),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_RECEIVER_* docs")
    MEDIA_TTT_RECEIVER_TRANSFER_TO_RECEIVER_SUCCEEDED(1263),
    @UiEvent(doc = "See android.app.StatusBarManager.MEDIA_TRANSFER_RECEIVER_* docs")
    MEDIA_TTT_RECEIVER_TRANSFER_TO_RECEIVER_FAILED(1264);

    override fun getId() = metricId
}
