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

package com.android.systemui.statusbar.phone

/** An enumeration of the different locations that host a status bar. */
enum class StatusBarLocation {
    /** Home screen or in-app. */
    HOME,
    /** Keyguard (aka lockscreen). */
    KEYGUARD,
    /** Quick settings (inside the shade). */
    QS,
    /** ShadeCarrierGroup (above QS status bar in expanded mode). */
    SHADE_CARRIER_GROUP,
}
