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
import android.os.RemoteException
import android.util.Log
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_APP as METRICS_CREATION_SOURCE_APP
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_CAST as METRICS_CREATION_SOURCE_CAST
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER as METRICS_CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN as METRICS_CREATION_SOURCE_UNKNOWN
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
     * @param hostUid The UID of the package that initiates MediaProjection.
     * @param sessionCreationSource The entry point requesting permission to capture.
     */
    fun notifyProjectionInitiated(hostUid: Int, sessionCreationSource: SessionCreationSource) {
        try {
            service.notifyPermissionRequestInitiated(
                hostUid,
                sessionCreationSource.toMetricsConstant()
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "Error notifying server of projection initiated", e)
        }
    }

    /**
     * Request to log that the permission request was displayed.
     *
     * @param hostUid The UID of the package that initiates MediaProjection.
     */
    fun notifyPermissionRequestDisplayed(hostUid: Int) {
        try {
            service.notifyPermissionRequestDisplayed(hostUid)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error notifying server of projection displayed", e)
        }
    }

    /**
     * Request to log that the permission request was cancelled.
     *
     * @param hostUid The UID of the package that initiates MediaProjection.
     */
    fun notifyProjectionRequestCancelled(hostUid: Int) {
        try {
            service.notifyPermissionRequestCancelled(hostUid)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error notifying server of projection cancelled", e)
        }
    }

    /**
     * Request to log that the app selector was displayed.
     *
     * @param hostUid The UID of the package that initiates MediaProjection.
     */
    fun notifyAppSelectorDisplayed(hostUid: Int) {
        try {
            service.notifyAppSelectorDisplayed(hostUid)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error notifying server of app selector displayed", e)
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
