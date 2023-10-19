/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.mediaprojection

import android.media.projection.IMediaProjectionManager
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_APP as METRICS_CREATION_SOURCE_APP
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_CAST as METRICS_CREATION_SOURCE_CAST
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER as METRICS_CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN as METRICS_CREATION_SOURCE_UNKNOWN
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_PERMISSION_REQUEST_DISPLAYED as METRICS_STATE_PERMISSION_REQUEST_DISPLAYED
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/**
 * Helper class for requesting that the server emit logs describing the MediaProjection setup
 * experience.
 */
@SysUISingleton
class MediaProjectionMetricsLogger
@Inject
constructor(private val service: IMediaProjectionManager) {
    /**
     * Request to log that the permission was requested.
     *
     * @param sessionCreationSource The entry point requesting permission to capture.
     */
    fun notifyProjectionInitiated(sessionCreationSource: SessionCreationSource) {
        notifyToServer(
            MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED,
            sessionCreationSource
        )
    }

    fun notifyPermissionRequestDisplayed() {
        notifyToServer(METRICS_STATE_PERMISSION_REQUEST_DISPLAYED, SessionCreationSource.UNKNOWN)
    }

    /**
     * Request to log that the permission request moved to the given state.
     *
     * Should not be used for the initialization state, since that should use {@link
     * MediaProjectionMetricsLogger#notifyProjectionInitiated(Int)} and pass the
     * sessionCreationSource.
     */
    fun notifyPermissionProgress(state: Int) {
        // TODO validate state is valid
        notifyToServer(state, SessionCreationSource.UNKNOWN)
    }

    /**
     * Notifies system server that we are handling a particular state during the consent flow.
     *
     * Only used for emitting atoms.
     *
     * @param state The state that SystemUI is handling during the consent flow. Must be a valid
     *   state defined in the MediaProjectionState enum.
     * @param sessionCreationSource Only set if the state is MEDIA_PROJECTION_STATE_INITIATED.
     *   Indicates the entry point for requesting the permission. Must be a valid state defined in
     *   the SessionCreationSource enum.
     */
    private fun notifyToServer(state: Int, sessionCreationSource: SessionCreationSource) {
        Log.v(TAG, "FOO notifyToServer of state $state and source $sessionCreationSource")
        try {
            service.notifyPermissionRequestStateChange(
                Process.myUid(),
                state,
                sessionCreationSource.toMetricsConstant()
            )
        } catch (e: RemoteException) {
            Log.e(
                TAG,
                "Error notifying server of permission flow state $state from source " +
                    "$sessionCreationSource",
                e
            )
        }
    }

    companion object {
        const val TAG = "MediaProjectionMetricsLogger"
    }
}

enum class SessionCreationSource {
    APP,
    CAST,
    SYSTEM_UI_SCREEN_RECORDER,
    UNKNOWN;

    fun toMetricsConstant(): Int =
        when (this) {
            APP -> METRICS_CREATION_SOURCE_APP
            CAST -> METRICS_CREATION_SOURCE_CAST
            SYSTEM_UI_SCREEN_RECORDER -> METRICS_CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER
            UNKNOWN -> METRICS_CREATION_SOURCE_UNKNOWN
        }
}
