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

package com.android.systemui.devicepolicy

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL
import android.content.ComponentName

/** Returns true if the admin of [userId] disallows keyguard shortcuts. */
fun DevicePolicyManager.areKeyguardShortcutsDisabled(
    admin: ComponentName? = null,
    userId: Int
): Boolean {
    val flags = getKeyguardDisabledFeatures(admin, userId)
    return flags and KEYGUARD_DISABLE_SHORTCUTS_ALL == KEYGUARD_DISABLE_SHORTCUTS_ALL ||
        flags and KEYGUARD_DISABLE_FEATURES_ALL == KEYGUARD_DISABLE_FEATURES_ALL
}
