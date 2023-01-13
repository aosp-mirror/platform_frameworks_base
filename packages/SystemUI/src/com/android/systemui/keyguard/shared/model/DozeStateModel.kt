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
 * limitations under the License
 */
package com.android.systemui.keyguard.shared.model

/** Model device doze states. */
enum class DozeStateModel {
    /** Default state. Transition to INITIALIZED to get Doze going. */
    UNINITIALIZED,
    /** Doze components are set up. Followed by transition to DOZE or DOZE_AOD. */
    INITIALIZED,
    /** Regular doze. Device is asleep and listening for pulse triggers. */
    DOZE,
    /** Deep doze. Device is asleep and is not listening for pulse triggers. */
    DOZE_SUSPEND_TRIGGERS,
    /** Always-on doze. Device is asleep, showing UI and listening for pulse triggers. */
    DOZE_AOD,
    /** Pulse has been requested. Device is awake and preparing UI */
    DOZE_REQUEST_PULSE,
    /** Pulse is showing. Device is awake and showing UI. */
    DOZE_PULSING,
    /** Pulse is showing with bright wallpaper. Device is awake and showing UI. */
    DOZE_PULSING_BRIGHT,
    /** Pulse is done showing. Followed by transition to DOZE or DOZE_AOD. */
    DOZE_PULSE_DONE,
    /** Doze is done. DozeService is finished. */
    FINISH,
    /** AOD, but the display is temporarily off. */
    DOZE_AOD_PAUSED,
    /** AOD, prox is near, transitions to DOZE_AOD_PAUSED after a timeout. */
    DOZE_AOD_PAUSING,
    /** Always-on doze. Device is awake, showing docking UI and listening for pulse triggers. */
    DOZE_AOD_DOCKED;

    companion object {
        fun isDozeOff(model: DozeStateModel): Boolean {
            return model == UNINITIALIZED || model == FINISH
        }
    }
}
