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

package com.android.systemui.qs.external

import android.app.StatusBarManager
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.UiEventLoggerImpl

class TileRequestDialogEventLogger @VisibleForTesting constructor(
    private val uiEventLogger: UiEventLogger,
    private val instanceIdSequence: InstanceIdSequence
) {
    companion object {
        const val MAX_INSTANCE_ID = 1 shl 20
    }

    constructor() : this(UiEventLoggerImpl(), InstanceIdSequence(MAX_INSTANCE_ID))

    /**
     * Obtain a new [InstanceId] to log a session for a dialog request.
     */
    fun newInstanceId(): InstanceId = instanceIdSequence.newInstanceId()

    /**
     * Log that the dialog has been shown to the user for a tile in the given [packageName]. This
     * call should use a new [instanceId].
     */
    fun logDialogShown(packageName: String, instanceId: InstanceId) {
        uiEventLogger.logWithInstanceId(
                TileRequestDialogEvent.TILE_REQUEST_DIALOG_SHOWN,
                /* uid */ 0,
                packageName,
                instanceId
        )
    }

    /**
     * Log the user response to the dialog being shown. Must follow a call to [logDialogShown] that
     * used the same [packageName] and [instanceId]. Only the following responses are valid:
     * * [StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED]
     * * [StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED]
     * * [StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED]
     */
    fun logUserResponse(
        @StatusBarManager.RequestResult response: Int,
        packageName: String,
        instanceId: InstanceId
    ) {
        val event = when (response) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED -> {
                TileRequestDialogEvent.TILE_REQUEST_DIALOG_DISMISSED
            }
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                TileRequestDialogEvent.TILE_REQUEST_DIALOG_TILE_NOT_ADDED
            }
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                TileRequestDialogEvent.TILE_REQUEST_DIALOG_TILE_ADDED
            }
            else -> {
                throw IllegalArgumentException("User response not valid: $response")
            }
        }
        uiEventLogger.logWithInstanceId(event, /* uid */ 0, packageName, instanceId)
    }

    /**
     * Log that the dialog will not be shown because the tile was already part of the active set.
     * Corresponds to a response of [StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED].
     */
    fun logTileAlreadyAdded(packageName: String, instanceId: InstanceId) {
        uiEventLogger.logWithInstanceId(
                TileRequestDialogEvent.TILE_REQUEST_DIALOG_TILE_ALREADY_ADDED,
                /* uid */ 0,
                packageName,
                instanceId
        )
    }
}

enum class TileRequestDialogEvent(private val _id: Int) : UiEventLogger.UiEventEnum {

    @UiEvent(doc = "Tile request dialog not shown because tile is already added.")
    TILE_REQUEST_DIALOG_TILE_ALREADY_ADDED(917),

    @UiEvent(doc = "Tile request dialog shown to user.")
    TILE_REQUEST_DIALOG_SHOWN(918),

    @UiEvent(doc = "User dismisses dialog without choosing an option.")
    TILE_REQUEST_DIALOG_DISMISSED(919),

    @UiEvent(doc = "User accepts adding tile from dialog.")
    TILE_REQUEST_DIALOG_TILE_ADDED(920),

    @UiEvent(doc = "User denies adding tile from dialog.")
    TILE_REQUEST_DIALOG_TILE_NOT_ADDED(921);

    override fun getId() = _id
}