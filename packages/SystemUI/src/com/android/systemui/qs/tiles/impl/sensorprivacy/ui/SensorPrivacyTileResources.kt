/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.sensorprivacy.ui

import com.android.systemui.res.R

sealed interface SensorPrivacyTileResources {
    fun getIconRes(isBlocked: Boolean): Int
    fun getTileLabelRes(): Int

    data object CameraPrivacyTileResources : SensorPrivacyTileResources {
        override fun getIconRes(isBlocked: Boolean): Int {
            return if (isBlocked) {
                R.drawable.qs_camera_access_icon_off
            } else {
                R.drawable.qs_camera_access_icon_on
            }
        }

        override fun getTileLabelRes(): Int {
            return R.string.quick_settings_camera_label
        }
    }

    data object MicrophonePrivacyTileResources : SensorPrivacyTileResources {
        override fun getIconRes(isBlocked: Boolean): Int {
            return if (isBlocked) {
                R.drawable.qs_mic_access_off
            } else {
                R.drawable.qs_mic_access_on
            }
        }

        override fun getTileLabelRes(): Int {
            return R.string.quick_settings_mic_label
        }
    }
}
