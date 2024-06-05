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

package com.android.systemui.camera

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.text.TextUtils
import com.android.systemui.res.R
import android.util.Log

class CameraIntents {
    companion object {
        val DEFAULT_SECURE_CAMERA_INTENT_ACTION = MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        val DEFAULT_INSECURE_CAMERA_INTENT_ACTION = MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        private val VIDEO_CAMERA_INTENT_ACTION = MediaStore.INTENT_ACTION_VIDEO_CAMERA
        const val EXTRA_LAUNCH_SOURCE = "com.android.systemui.camera_launch_source"
        const val TAG = "CameraIntents"

        @JvmStatic
        fun getOverrideCameraPackage(context: Context, userId: Int): String? {
            val packageName = context.resources.getString(R.string.config_cameraGesturePackage)!!
            try {
                if (!TextUtils.isEmpty(packageName)
                        && context.packageManager.getApplicationInfoAsUser(packageName, 0, userId).enabled ?: false) {
                    return packageName
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Missing cameraGesturePackage $packageName", e)
            }
            return null
        }

        @JvmStatic
        fun getInsecureCameraIntent(context: Context, userId: Int): Intent {
            val intent = Intent(DEFAULT_INSECURE_CAMERA_INTENT_ACTION)
            getOverrideCameraPackage(context, userId)?.let { intent.setPackage(it) }
            return intent
        }

        @JvmStatic
        fun getSecureCameraIntent(context: Context, userId: Int): Intent {
            val intent = Intent(DEFAULT_SECURE_CAMERA_INTENT_ACTION)
            getOverrideCameraPackage(context, userId)?.let { intent.setPackage(it) }
            return intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        @JvmStatic
        fun isSecureCameraIntent(intent: Intent?): Boolean {
            return intent?.getAction()?.equals(DEFAULT_SECURE_CAMERA_INTENT_ACTION) ?: false
        }

        @JvmStatic
        fun isInsecureCameraIntent(intent: Intent?): Boolean {
            return intent?.getAction()?.equals(DEFAULT_INSECURE_CAMERA_INTENT_ACTION) ?: false
        }

        /** Returns an [Intent] that can be used to start the camera in video mode. */
        @JvmStatic
        fun getVideoCameraIntent(userId: Int): Intent {
            return Intent(VIDEO_CAMERA_INTENT_ACTION)
        }
    }
}
