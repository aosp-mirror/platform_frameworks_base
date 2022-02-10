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
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.os.Handler
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.MediaTttChipControllerCommon
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **receiving** device.
 *
 * This chip is shown when a user is transferring media to/from a sending device and this device.
 */
@SysUISingleton
class MediaTttChipControllerReceiver @Inject constructor(
    commandQueue: CommandQueue,
    context: Context,
    windowManager: WindowManager,
    @Main private val mainHandler: Handler,
) : MediaTttChipControllerCommon<ChipStateReceiver>(
    context, windowManager, R.layout.media_ttt_chip_receiver
) {
    private val commandQueueCallbacks = object : CommandQueue.Callbacks {
        override fun updateMediaTapToTransferReceiverDisplay(
            @StatusBarManager.MediaTransferReceiverState displayState: Int,
            routeInfo: MediaRoute2Info,
            appIcon: Icon?,
            appName: CharSequence?
        ) {
            this@MediaTttChipControllerReceiver.updateMediaTapToTransferReceiverDisplay(
                displayState, routeInfo, appIcon, appName
            )
        }
    }

    init {
        commandQueue.addCallback(commandQueueCallbacks)
    }

    private fun updateMediaTapToTransferReceiverDisplay(
        @StatusBarManager.MediaTransferReceiverState displayState: Int,
        routeInfo: MediaRoute2Info,
        appIcon: Icon?,
        appName: CharSequence?
    ) {
        when(displayState) {
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER -> {
                val packageName = routeInfo.packageName
                if (appIcon == null) {
                    displayChip(ChipStateReceiver(packageName, null, appName))
                } else {
                    appIcon.loadDrawableAsync(
                        context,
                        Icon.OnDrawableLoadedListener { drawable ->
                            displayChip(
                                ChipStateReceiver(packageName, drawable, appName)
                            )},
                        // Notify the listener on the main handler since the listener will update
                        // the UI.
                        mainHandler
                    )
                }
            }
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER -> removeChip()
            else ->
                Log.e(RECEIVER_TAG, "Unhandled MediaTransferReceiverState $displayState")
        }
    }

    override fun updateChipView(chipState: ChipStateReceiver, currentChipView: ViewGroup) {
        setIcon(chipState, currentChipView)
    }
}

private const val RECEIVER_TAG = "MediaTapToTransferRcvr"
