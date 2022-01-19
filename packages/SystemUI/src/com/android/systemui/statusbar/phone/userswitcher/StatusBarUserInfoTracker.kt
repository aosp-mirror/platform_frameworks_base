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

import android.graphics.drawable.Drawable
import android.os.UserManager

import com.android.systemui.DejankUtils.whitelistIpcs
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener

import javax.inject.Inject

/**
 * Since every user switcher chip will user the exact same information and logic on whether or not
 * to show, and what data to show, it makes sense to create a single tracker here
 */
@SysUISingleton
class StatusBarUserInfoTracker @Inject constructor(
    private val userInfoController: UserInfoController,
    private val userManager: UserManager
) : CallbackController<CurrentUserChipInfoUpdatedListener> {
    var currentUserName: String? = null
        private set
    var currentUserAvatar: Drawable? = null
        private set
    var userSwitcherEnabled = false
        private set
    private var listening = false

    private val listeners = mutableListOf<CurrentUserChipInfoUpdatedListener>()

    private val userInfoChangedListener = OnUserInfoChangedListener { name, picture, _ ->
        currentUserAvatar = picture
        currentUserName = name
        notifyListenersUserInfoChanged()
    }

    init {
        startListening()
    }

    override fun addCallback(listener: CurrentUserChipInfoUpdatedListener) {
        if (listeners.isEmpty()) {
            startListening()
        }

        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun removeCallback(listener: CurrentUserChipInfoUpdatedListener) {
        listeners.remove(listener)

        if (listeners.isEmpty()) {
            stopListening()
        }
    }

    private fun notifyListenersUserInfoChanged() {
        listeners.forEach {
            it.onCurrentUserChipInfoUpdated()
        }
    }

    private fun notifyListenersSettingChanged() {
        listeners.forEach {
            it.onStatusBarUserSwitcherSettingChanged(userSwitcherEnabled)
        }
    }

    private fun startListening() {
        listening = true
        userInfoController.addCallback(userInfoChangedListener)
    }

    private fun stopListening() {
        listening = false
        userInfoController.removeCallback(userInfoChangedListener)
    }

    private fun checkUserSwitcherEnabled() {
        whitelistIpcs {
            userSwitcherEnabled = userManager.isUserSwitcherEnabled
        }
    }

    /**
     * Force a check to [UserManager.isUserSwitcherEnabled], and update listeners if the value has
     * changed
     */
    fun checkEnabled() {
        val wasEnabled = userSwitcherEnabled
        checkUserSwitcherEnabled()

        if (wasEnabled != userSwitcherEnabled) {
            notifyListenersSettingChanged()
        }
    }
}

interface CurrentUserChipInfoUpdatedListener {
    fun onCurrentUserChipInfoUpdated()
    fun onStatusBarUserSwitcherSettingChanged(enabled: Boolean) {}
}
