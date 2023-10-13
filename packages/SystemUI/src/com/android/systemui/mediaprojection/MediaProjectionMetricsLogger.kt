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
import com.android.internal.util.FrameworkStatsLog
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
    fun notifyPermissionProgress(state: Int, sessionCreationSource: Int) {
        // TODO check that state & SessionCreationSource matches expected values
        notifyToServer(state, sessionCreationSource)
    }

    /**
     * Request to log that the permission request moved to the given state.
     *
     * Should not be used for the initialization state, since that
     */
    fun notifyPermissionProgress(state: Int) {
        // TODO validate state is valid
        notifyToServer(
            state,
            FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN)
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
    private fun notifyToServer(state: Int, sessionCreationSource: Int) {
        Log.v(TAG, "FOO notifyToServer of state $state and source $sessionCreationSource")
        try {
            service.notifyPermissionRequestStateChange(
                Process.myUid(), state, sessionCreationSource)
        } catch (e: RemoteException) {
            Log.e(
                TAG,
                "Error notifying server of permission flow state $state from source $sessionCreationSource",
                e)
        }
    }

    companion object {
        const val TAG = "MediaProjectionMetricsLogger"
    }
}
