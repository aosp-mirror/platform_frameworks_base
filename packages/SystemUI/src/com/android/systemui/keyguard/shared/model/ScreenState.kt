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

package com.android.systemui.keyguard.shared.model

import com.android.systemui.keyguard.ScreenLifecycle

enum class ScreenState {
    /** Screen is fully off. */
    SCREEN_OFF,
    /** Signal that the screen is turning on. */
    SCREEN_TURNING_ON,
    /** Screen is fully on. */
    SCREEN_ON,
    /** Signal that the screen is turning off. */
    SCREEN_TURNING_OFF;

    companion object {
        fun fromScreenLifecycleInt(value: Int): ScreenState {
            return when (value) {
                ScreenLifecycle.SCREEN_OFF -> SCREEN_OFF
                ScreenLifecycle.SCREEN_TURNING_ON -> SCREEN_TURNING_ON
                ScreenLifecycle.SCREEN_ON -> SCREEN_ON
                ScreenLifecycle.SCREEN_TURNING_OFF -> SCREEN_TURNING_OFF
                else -> throw IllegalArgumentException("Invalid screen value: $value")
            }
        }
    }
}
