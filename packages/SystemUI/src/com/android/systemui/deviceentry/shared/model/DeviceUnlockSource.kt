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
 * Source of the device unlock.
 *
 * @property dismissesLockscreen whether unlock with this authentication method dismisses the
 *   lockscreen and enters the device.
 */
sealed class DeviceUnlockSource(val dismissesLockscreen: Boolean) {

    data object Fingerprint : DeviceUnlockSource(true)
    data object FaceWithBypass : DeviceUnlockSource(dismissesLockscreen = true)
    data object FaceWithoutBypass : DeviceUnlockSource(dismissesLockscreen = false)
    data object TrustAgent : DeviceUnlockSource(dismissesLockscreen = false)
    data object BouncerInput : DeviceUnlockSource(dismissesLockscreen = true)
}
