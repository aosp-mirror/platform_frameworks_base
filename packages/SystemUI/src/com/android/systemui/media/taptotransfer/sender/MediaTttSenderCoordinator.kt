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
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.media.taptotransfer.common.MediaTttUtils
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.temporarydisplay.ViewPriority
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.temporarydisplay.chipbar.ChipbarEndItem
import com.android.systemui.temporarydisplay.chipbar.ChipbarInfo
import java.io.PrintWriter
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
    private val dumpManager: DumpManager,
    private val logger: MediaTttSenderLogger,
    private val mediaTttFlags: MediaTttFlags,
    private val uiEventLogger: MediaTttSenderUiEventLogger,
) : CoreStartable, Dumpable {

    // Since the media transfer display is similar to a heads-up notification, use the same timeout.
    private val defaultTimeout = context.resources.getInteger(R.integer.heads_up_notification_decay)

    // A map to store instance id and current chip state per id.
    private var stateMap: MutableMap<String, Pair<InstanceId, ChipStateSender>> = mutableMapOf()

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
            dumpManager.registerNormalDumpable(this)
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

        val currentStateForId: ChipStateSender? = stateMap[routeInfo.id]?.second
        val instanceId: InstanceId =
            stateMap[routeInfo.id]?.first
                ?: chipbarCoordinator.tempViewUiEventLogger.getNewInstanceId()
        if (!ChipStateSender.isValidStateTransition(currentStateForId, chipState)) {
            // ChipStateSender.FAR_FROM_RECEIVER is the default state when there is no state.
            logger.logInvalidStateTransitionError(
                currentState = currentStateForId?.name ?: ChipStateSender.FAR_FROM_RECEIVER.name,
                chipState.name
            )
            return
        }
        uiEventLogger.logSenderStateChange(chipState, instanceId)

        if (chipState == ChipStateSender.FAR_FROM_RECEIVER) {
            // Return early if we're not displaying a chip for this ID anyway
            if (currentStateForId == null) return

            val removalReason = ChipStateSender.FAR_FROM_RECEIVER.name
            if (
                currentStateForId.transferStatus == TransferStatus.IN_PROGRESS ||
                    currentStateForId.transferStatus == TransferStatus.SUCCEEDED
            ) {
                // Don't remove the chip if we're in progress or succeeded, since the user should
                // still be able to see the status of the transfer.
                logger.logRemovalBypass(
                    removalReason,
                    bypassReason = "transferStatus=${currentStateForId.transferStatus.name}"
                )
                return
            }

            // No need to store the state since it is the default state
            removeIdFromStore(routeInfo.id, reason = removalReason)
            chipbarCoordinator.removeView(routeInfo.id, removalReason)
        } else {
            stateMap[routeInfo.id] = Pair(instanceId, chipState)
            logger.logStateMap(stateMap)
            chipbarCoordinator.registerListener(displayListener)
            chipbarCoordinator.displayView(
                createChipbarInfo(
                    chipState,
                    routeInfo,
                    undoCallback,
                    context,
                    logger,
                    instanceId,
                )
            )
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
        logger: MediaTttSenderLogger,
        instanceId: InstanceId,
    ): ChipbarInfo {
        val packageName = checkNotNull(routeInfo.clientPackageName)
        val otherDeviceName =
            if (routeInfo.name.isBlank()) {
                context.getString(R.string.media_ttt_default_device_type)
            } else {
                routeInfo.name.toString()
            }
        val icon =
            MediaTttUtils.getIconInfoFromPackageName(context, packageName, isReceiver = false) {
                logger.logPackageNotFound(packageName)
            }

        val timeout =
            when (chipStateSender.timeoutLength) {
                TimeoutLength.DEFAULT -> defaultTimeout
                TimeoutLength.LONG -> 2 * defaultTimeout
            }

        return ChipbarInfo(
            // Display the app's icon as the start icon
            startIcon = icon.toTintedIcon(),
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
                                instanceId,
                            )
                        } else {
                            null
                        }
                    }
                },
            vibrationEffect = chipStateSender.transferStatus.vibrationEffect,
            allowSwipeToDismiss = true,
            windowTitle = MediaTttUtils.WINDOW_TITLE_SENDER,
            wakeReason = MediaTttUtils.WAKE_REASON_SENDER,
            timeoutMs = timeout,
            id = routeInfo.id,
            priority = ViewPriority.NORMAL,
            instanceId = instanceId,
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
        instanceId: InstanceId,
    ): ChipbarEndItem.Button {
        val onClickListener =
            View.OnClickListener {
                uiEventLogger.logUndoClicked(uiEvent, instanceId)
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

    private val displayListener =
        TemporaryViewDisplayController.Listener { id, reason -> removeIdFromStore(id, reason) }

    private fun removeIdFromStore(id: String, reason: String) {
        logger.logStateMapRemoval(id, reason)
        stateMap.remove(id)
        logger.logStateMap(stateMap)
        if (stateMap.isEmpty()) {
            chipbarCoordinator.unregisterListener(displayListener)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Current sender states:")
        pw.println(stateMap.toString())
    }
}
