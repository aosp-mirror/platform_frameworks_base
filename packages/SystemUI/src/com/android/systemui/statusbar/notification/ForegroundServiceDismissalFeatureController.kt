/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification

import android.content.Context
import android.provider.DeviceConfig
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NOTIFICATIONS_ALLOW_FGS_DISMISSAL
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.DeviceConfigProxy
import javax.inject.Inject

private var sIsEnabled: Boolean? = null

/**
 * Feature controller for NOTIFICATIONS_ALLOW_FGS_DISMISSAL config.
 */
// TODO: this is really boilerplatey, make a base class that just wraps the device config
@SysUISingleton
class ForegroundServiceDismissalFeatureController @Inject constructor(
    val proxy: DeviceConfigProxy,
    val context: Context
) {
    fun isForegroundServiceDismissalEnabled(): Boolean {
        return isEnabled(proxy)
    }
}

private fun isEnabled(proxy: DeviceConfigProxy): Boolean {
    if (sIsEnabled == null) {
        sIsEnabled = proxy.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI, NOTIFICATIONS_ALLOW_FGS_DISMISSAL, false)
    }

    return sIsEnabled!!
}
