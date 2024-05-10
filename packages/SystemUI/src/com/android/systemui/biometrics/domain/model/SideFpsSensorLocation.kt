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

package com.android.systemui.biometrics.domain.model

data class SideFpsSensorLocation(
    /** Pixel offset from the left of the screen */
    val left: Int,
    /** Pixel offset from the top of the screen */
    val top: Int,
    /** Length of the SFPS sensor in pixels in current display density */
    val length: Int,
    /**
     * Whether the sensor is vertical when the device is in its default orientation (Rotation_0 or
     * Rotation_180)
     */
    val isSensorVerticalInDefaultOrientation: Boolean
)
