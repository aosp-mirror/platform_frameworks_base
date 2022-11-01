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

/** List of all possible states to transition to/from */
enum class KeyguardState {
    /**
     * For initialization as well as when the security method is set to NONE, indicating that
     * the keyguard should never be shown.
     */
    NONE,
    /* Always-on Display. The device is in a low-power mode with a minimal UI visible */
    AOD,
    /*
     * The security screen prompt UI, containing PIN, Password, Pattern, and all FPS
     * (Fingerprint Sensor) variations, for the user to verify their credentials
     */
    BOUNCER,
    /*
     * Device is actively displaying keyguard UI and is not in low-power mode. Device may be
     * unlocked if SWIPE security method is used, or if face lockscreen bypass is false.
     */
    LOCKSCREEN,

    /*
     * Keyguard is no longer visible. In most cases the user has just authenticated and keyguard
     * is being removed, but there are other cases where the user is swiping away keyguard, such as
     * with SWIPE security method or face unlock without bypass.
     */
    GONE,
}
