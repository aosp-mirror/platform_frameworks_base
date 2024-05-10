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
package com.android.systemui.statusbar.phone.fragment

import android.content.res.Resources
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.R
import com.android.systemui.util.settings.SecureSettings

/**
 * Centralize the logic for the status bar / keyguard status bar icon blocklist. The default is
 * loaded from the config, and we currently support a system setting for the vibrate icon. It's
 * pretty likely that we would end up supporting more user-configurable settings in the future, so
 * breaking this out into its own file for now.
 *
 * Note for the future: it might be reasonable to turn this into its own class that can listen to
 * the system setting and execute a callback when it changes instead of having multiple content
 * observers.
 */
fun getStatusBarIconBlocklist(
    res: Resources,
    settings: SecureSettings
): List<String> {
    // Load the default blocklist from res
    val blocklist = res.getStringArray(
            com.android.systemui.res.R.array.config_collapsed_statusbar_icon_blocklist).toList()

    val vibrateIconSlot: String = res.getString(R.string.status_bar_volume)
    val showVibrateIcon = settings.getIntForUser(
            Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
            0,
            UserHandle.USER_CURRENT) == 0

    // Filter out vibrate icon from the blocklist if the setting is on
    return blocklist.filter { icon ->
        !icon.equals(vibrateIconSlot) || showVibrateIcon
    }
}