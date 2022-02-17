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

package com.android.systemui.statusbar.phone.userswitcher

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.policy.CallbackController

import javax.inject.Inject

@SysUISingleton
class StatusBarUserSwitcherFeatureController @Inject constructor(
    private val flags: FeatureFlags
) : CallbackController<OnUserSwitcherPreferenceChangeListener> {
    private val listeners = mutableListOf<OnUserSwitcherPreferenceChangeListener>()

    init {
        flags.addListener(Flags.STATUS_BAR_USER_SWITCHER) {
            it.requestNoRestart()
            notifyListeners()
        }
    }

    fun isStatusBarUserSwitcherFeatureEnabled(): Boolean {
        return flags.isEnabled(Flags.STATUS_BAR_USER_SWITCHER)
    }

    override fun addCallback(listener: OnUserSwitcherPreferenceChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun removeCallback(listener: OnUserSwitcherPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val enabled = flags.isEnabled(Flags.STATUS_BAR_USER_SWITCHER)
        listeners.forEach {
            it.onUserSwitcherPreferenceChange(enabled)
        }
    }
}

interface OnUserSwitcherPreferenceChangeListener {
    fun onUserSwitcherPreferenceChange(enabled: Boolean)
}
