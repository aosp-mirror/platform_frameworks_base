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
import android.view.View
import com.android.internal.logging.UiEventLogger
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.media.taptotransfer.common.MediaTttUtils
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.temporarydisplay.ViewPriority
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.temporarydisplay.chipbar.ChipbarEndItem
import com.android.systemui.temporarydisplay.chipbar.ChipbarInfo
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
    @MediaTttSenderLogger private val logger: MediaTttLogger<ChipbarInfo>,
    private val mediaTttFlags: MediaTttFlags,
    private val uiEventLogger: MediaTttSenderUiEventLogger,
) : CoreStartable {

    private var displayedState: ChipStateSender? = null
    // A map to store current chip state per id.
    private var stateMap: MutableMap<String, ChipStateSender> = mutableMapOf()

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
            logger.logStateChangeError(displayState)
            return
        }

        val currentState = stateMap[routeInfo.id]
        if (!ChipStateSender.isValidStateTransition(currentState, chipState)) {
            // ChipStateSender.FAR_FROM_RECEIVER is the default state when there is no state.
            logger.logInvalidStateTransitionError(
                currentState = currentState?.name ?: ChipStateSender.FAR_FROM_RECEIVER.name,
                chipState.name
            )
            return
        }
        uiEventLogger.logSenderStateChange(chipState)

        stateMap.put(routeInfo.id, chipState)
        if (chipState == ChipStateSender.FAR_FROM_RECEIVER) {
            // No need to store the state since it is the default state
            stateMap.remove(routeInfo.id)
            // Return early if we're not displaying a chip anyway
            val currentDisplayedState = displayedState ?: return

            val removalReason = ChipStateSender.FAR_FROM_RECEIVER.name
            if (
                currentDisplayedState.transferStatus == TransferStatus.IN_PROGRESS ||
                    currentDisplayedState.transferStatus == TransferStatus.SUCCEEDED
            ) {
                // Don't remove the chip if we're in progress or succeeded, since the user should
                // still be able to see the status of the transfer.
                logger.logRemovalBypass(
                    removalReason,
                    bypassReason = "transferStatus=${currentDisplayedState.transferStatus.name}"
                )
                return
            }

            displayedState = null
            chipbarCoordinator.removeView(routeInfo.id, removalReason)
        } else {
            displayedState = chipState
            chipbarCoordinator.displayView(
                createChipbarInfo(
                    chipState,
                    routeInfo,
                    undoCallback,
                    context,
                    logger,
                )
            ) { stateMap.remove(routeInfo.id) }
        }
    }

    /**
     * Creates an instance of [ChipbarInfo] that can be sent to [ChipbarCoordinator] for display.
     */
    private fun createChipbarInfo(
        chipStateSender: ChipStateSender,
        routeInfo: MediaRoute2Info,
        undoCallback: IUndoMediaTransferCallback?,
        context: Context,
        logger: MediaTttLogger<ChipbarInfo>,
    ): ChipbarInfo {
        val packageName = routeInfo.clientPackageName
        val otherDeviceName =
            if (routeInfo.name.isBlank()) {
                context.getString(R.string.media_ttt_default_device_type)
            } else {
                routeInfo.name.toString()
            }

        return ChipbarInfo(
            // Display the app's icon as the start icon
            startIcon =
                MediaTttUtils.getIconInfoFromPackageName(context, packageName, logger)
                    .toTintedIcon(),
            text = chipStateSender.getChipTextString(context, otherDeviceName),
            endItem =
                when (chipStateSender.endItem) {
                    null -> null
                    is SenderEndItem.Loading -> ChipbarEndItem.Loading
                    is SenderEndItem.Error -> ChipbarEndItem.Error
                    is SenderEndItem.UndoButton -> {
                        if (undoCallback != null) {
                            getUndoButton(
                                undoCallback,
                                chipStateSender.endItem.uiEventOnClick,
                                chipStateSender.endItem.newState,
                                routeInfo,
                            )
                        } else {
                            null
                        }
                    }
                },
            vibrationEffect = chipStateSender.transferStatus.vibrationEffect,
            windowTitle = MediaTttUtils.WINDOW_TITLE_SENDER,
            wakeReason = MediaTttUtils.WAKE_REASON_SENDER,
            timeoutMs = chipStateSender.timeout,
            id = routeInfo.id,
            priority = ViewPriority.NORMAL,
        )
    }

    /**
     * Returns an undo button for the chip.
     *
     * When the button is clicked: [undoCallback] will be triggered, [uiEvent] will be logged, and
     * this coordinator will transition to [newState].
     */
    private fun getUndoButton(
        undoCallback: IUndoMediaTransferCallback,
        uiEvent: UiEventLogger.UiEventEnum,
        @StatusBarManager.MediaTransferSenderState newState: Int,
        routeInfo: MediaRoute2Info,
    ): ChipbarEndItem.Button {
        val onClickListener =
            View.OnClickListener {
                uiEventLogger.logUndoClicked(uiEvent)
                undoCallback.onUndoTriggered()

                // The external service should eventually send us a new TransferTriggered state, but
                // but that may take too long to go through the binder and the user may be confused
                // as to why the UI hasn't changed yet. So, we immediately change the UI here.
                updateMediaTapToTransferSenderDisplay(
                    newState,
                    routeInfo,
                    // Since we're force-updating the UI, we don't have any [undoCallback] from the
                    // external service (and TransferTriggered states don't have undo callbacks
                    // anyway).
                    undoCallback = null,
                )
            }

        return ChipbarEndItem.Button(
            Text.Resource(R.string.media_transfer_undo),
            onClickListener,
        )
    }
}
