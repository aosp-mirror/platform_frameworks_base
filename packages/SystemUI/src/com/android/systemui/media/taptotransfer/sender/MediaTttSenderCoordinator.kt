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

import android.app.StatusBarManager
import android.content.Context
import android.media.MediaRoute2Info
import android.util.Log
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.temporarydisplay.chipbar.ChipSenderInfo
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.temporarydisplay.chipbar.SENDER_TAG
import javax.inject.Inject

/**
 * A coordinator for showing/hiding the Media Tap-To-Transfer UI on the **sending** device. This UI
 * is shown when a user is transferring media to/from this device and a receiver device.
 */
@SysUISingleton
class MediaTttSenderCoordinator
@Inject
constructor(
    private val chipbarCoordinator: ChipbarCoordinator,
    private val commandQueue: CommandQueue,
    private val context: Context,
    @MediaTttSenderLogger private val logger: MediaTttLogger,
    private val mediaTttFlags: MediaTttFlags,
    private val uiEventLogger: MediaTttSenderUiEventLogger,
) : CoreStartable {

    private val commandQueueCallbacks =
        object : CommandQueue.Callbacks {
            override fun updateMediaTapToTransferSenderDisplay(
                @StatusBarManager.MediaTransferSenderState displayState: Int,
                routeInfo: MediaRoute2Info,
                undoCallback: IUndoMediaTransferCallback?
            ) {
                this@MediaTttSenderCoordinator.updateMediaTapToTransferSenderDisplay(
                    displayState,
                    routeInfo,
                    undoCallback
                )
            }
        }

    override fun start() {
        if (mediaTttFlags.isMediaTttEnabled()) {
            commandQueue.addCallback(commandQueueCallbacks)
        }
    }

    private fun updateMediaTapToTransferSenderDisplay(
        @StatusBarManager.MediaTransferSenderState displayState: Int,
        routeInfo: MediaRoute2Info,
        undoCallback: IUndoMediaTransferCallback?
    ) {
        val chipState: ChipStateSender? = ChipStateSender.getSenderStateFromId(displayState)
        val stateName = chipState?.name ?: "Invalid"
        logger.logStateChange(stateName, routeInfo.id, routeInfo.clientPackageName)

        if (chipState == null) {
            Log.e(SENDER_TAG, "Unhandled MediaTransferSenderState $displayState")
            return
        }
        uiEventLogger.logSenderStateChange(chipState)

        if (chipState == ChipStateSender.FAR_FROM_RECEIVER) {
            chipbarCoordinator.removeView(removalReason = ChipStateSender.FAR_FROM_RECEIVER.name)
        } else {
            chipbarCoordinator.displayView(ChipSenderInfo(chipState, routeInfo, undoCallback))
        }
    }
}
