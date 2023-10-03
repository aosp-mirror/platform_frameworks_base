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

package com.android.systemui.power.shared.model

/**
 * The power state of the display. If the screen is [SCREEN_OFF], it is unpowered, and nothing is
 * visible including AOD.
 */
enum class ScreenPowerState {
    /** Screen is fully off. */
    SCREEN_OFF,
    /** Signal that the screen is turning on. */
    SCREEN_TURNING_ON,
    /** Screen is fully on. */
    SCREEN_ON,
    /** Signal that the screen is turning off. */
    SCREEN_TURNING_OFF
}
