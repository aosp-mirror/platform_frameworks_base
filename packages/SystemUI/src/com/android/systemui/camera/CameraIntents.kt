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
import android.provider.MediaStore
import android.text.TextUtils

import com.android.systemui.R

interface CameraIntents {
    companion object {
        const val DEFAULT_SECURE_CAMERA_INTENT_ACTION =
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        const val DEFAULT_INSECURE_CAMERA_INTENT_ACTION =
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA

        @JvmStatic
        fun getOverrideCameraPackage(context: Context): String? {
            context.resources.getString(R.string.config_cameraGesturePackage)?.let {
                if (!TextUtils.isEmpty(it)) {
                    return it
                }
            }
            return null
        }

        @JvmStatic
        fun getInsecureCameraIntent(context: Context): Intent {
            val intent = Intent(DEFAULT_INSECURE_CAMERA_INTENT_ACTION)
            getOverrideCameraPackage(context)?.let {
                intent.setPackage(it)
            }
            return intent
        }

        @JvmStatic
        fun getSecureCameraIntent(context: Context): Intent {
            val intent = Intent(DEFAULT_SECURE_CAMERA_INTENT_ACTION)
            getOverrideCameraPackage(context)?.let {
                intent.setPackage(it)
            }
            return intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        @JvmStatic
        fun isSecureCameraIntent(intent: Intent): Boolean {
            return intent.getAction().equals(DEFAULT_SECURE_CAMERA_INTENT_ACTION)
        }

        @JvmStatic
        fun isInsecureCameraIntent(intent: Intent): Boolean {
            return intent.getAction().equals(DEFAULT_INSECURE_CAMERA_INTENT_ACTION)
        }
    }
}
