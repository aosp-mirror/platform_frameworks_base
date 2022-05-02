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
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Since every user switcher chip will user the exact same information and logic on whether or not
 * to show, and what data to show, it makes sense to create a single tracker here
 */
@SysUISingleton
class StatusBarUserInfoTracker @Inject constructor(
    private val userInfoController: UserInfoController,
    private val userManager: UserManager,
    private val dumpManager: DumpManager,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor
) : CallbackController<CurrentUserChipInfoUpdatedListener>, Dumpable {
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
        dumpManager.registerDumpable(TAG, this)
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

    /**
     * Force a check to [UserManager.isUserSwitcherEnabled], and update listeners if the value has
     * changed
     */
    fun checkEnabled() {
        backgroundExecutor.execute {
            // Check on a background thread to avoid main thread Binder calls
            val wasEnabled = userSwitcherEnabled
            userSwitcherEnabled = userManager.isUserSwitcherEnabled

            if (wasEnabled != userSwitcherEnabled) {
                mainExecutor.execute {
                    notifyListenersSettingChanged()
                }
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("  userSwitcherEnabled=$userSwitcherEnabled")
        pw.println("  listening=$listening")
    }
}

interface CurrentUserChipInfoUpdatedListener {
    fun onCurrentUserChipInfoUpdated()
    fun onStatusBarUserSwitcherSettingChanged(enabled: Boolean) {}
}

private const val TAG = "StatusBarUserInfoTracker"
