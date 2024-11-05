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

import android.content.Context
import android.media.projection.IMediaProjection
import android.media.projection.IMediaProjectionManager
import android.media.projection.MediaProjectionManager
import android.media.projection.ReviewGrantedConsentResult
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import android.window.WindowContainerToken
import javax.inject.Inject

/**
 * Helper class that handles the media projection service related actions. It simplifies invoking
 * the MediaProjectionManagerService and updating the permission consent.
 */
class MediaProjectionServiceHelper @Inject constructor() {
    companion object {
        private const val TAG = "MediaProjectionServiceHelper"
        private val service =
            IMediaProjectionManager.Stub.asInterface(
                ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE)
            )

        @JvmStatic
        @Throws(RemoteException::class)
        fun hasProjectionPermission(uid: Int, packageName: String) =
            service.hasProjectionPermission(uid, packageName)

        @JvmStatic
        @Throws(RemoteException::class)
        fun createOrReuseProjection(
            uid: Int,
            packageName: String,
            reviewGrantedConsentRequired: Boolean,
            displayId: Int,
        ): IMediaProjection {
            val existingProjection =
                if (reviewGrantedConsentRequired) service.getProjection(uid, packageName) else null
            return existingProjection
                ?: service.createProjection(
                    uid,
                    packageName,
                    MediaProjectionManager.TYPE_SCREEN_CAPTURE,
                    false /* permanentGrant */,
                    displayId,
                )
        }

        /**
         * This method is called when a host app reuses the consent token. If the token is being
         * used more than once, ask the user to review their consent and send the reviewed result.
         *
         * @param consentResult consent result to update
         * @param reviewGrantedConsentRequired if user must review already-granted consent that the
         *   host app is attempting to reuse
         * @param projection projection token associated with the consent result, or null if the
         *   result is for cancelling.
         */
        @JvmStatic
        fun setReviewedConsentIfNeeded(
            @ReviewGrantedConsentResult consentResult: Int,
            reviewGrantedConsentRequired: Boolean,
            projection: IMediaProjection?,
        ) {
            // Only send the result to the server, when the user needed to review the re-used
            // consent token.
            if (
                reviewGrantedConsentRequired && consentResult != ReviewGrantedConsentResult.UNKNOWN
            ) {
                try {
                    service.setUserReviewGrantedConsentResult(consentResult, projection)
                } catch (e: RemoteException) {
                    // If we are unable to pass back the result, capture continues with blank frames
                    Log.e(TAG, "Unable to set required consent result for token re-use", e)
                }
            }
        }
    }

    /** Updates the projected task to the task that has a matching [WindowContainerToken]. */
    fun updateTaskRecordingSession(token: WindowContainerToken): Boolean {
        return try {
            true
            // TODO: actually call the service once it is implemented
            // service.updateTaskRecordingSession(token)
        } catch (e: RemoteException) {
            Log.e(TAG, "Unable to updateTaskRecordingSession", e)
            false
        }
    }
}
