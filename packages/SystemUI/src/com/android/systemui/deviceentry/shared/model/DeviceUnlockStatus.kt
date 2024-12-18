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

package com.android.systemui.deviceentry.shared.model

/**
 * Wrapper class that combines whether device is unlocked or not, along with the authentication
 * method used to unlock the device.
 *
 * @property isUnlocked whether device is unlocked or not.
 * @property deviceUnlockSource source that unlocked the device, null if lockscreen is not secure or
 *   if [isUnlocked] is false.
 */
data class DeviceUnlockStatus(
    val isUnlocked: Boolean,
    val deviceUnlockSource: DeviceUnlockSource?
) {
    init {
        assert(isUnlocked || deviceUnlockSource == null) {
            "deviceUnlockSource must be null when device is locked."
        }
    }
}
