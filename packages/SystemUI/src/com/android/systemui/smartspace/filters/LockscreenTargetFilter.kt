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
package com.android.systemui.smartspace.filters

import android.app.smartspace.SmartspaceTarget
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.smartspace.SmartspaceTargetFilter
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.Executor
import javax.inject.Inject

/** {@link SmartspaceTargetFilter} for smartspace targets that show above the lockscreen. */
class LockscreenTargetFilter
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    private val execution: Execution,
    @Main private val handler: Handler,
    private val contentResolver: ContentResolver,
    @Main private val uiExecutor: Executor
) : SmartspaceTargetFilter {
    private var listeners: MutableSet<SmartspaceTargetFilter.Listener> = mutableSetOf()
    private var showSensitiveContentForCurrentUser = false
        set(value) {
            val existing = field
            field = value
            if (existing != field) {
                listeners.forEach { it.onCriteriaChanged() }
            }
        }
    private var showSensitiveContentForManagedUser = false
        set(value) {
            val existing = field
            field = value
            if (existing != field) {
                listeners.forEach { it.onCriteriaChanged() }
            }
        }

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                execution.assertIsMainThread()
                updateUserContentSettings()
            }
        }

    private var managedUserHandle: UserHandle? = null

    override fun addListener(listener: SmartspaceTargetFilter.Listener) {
        listeners.add(listener)

        if (listeners.size != 1) {
            return
        }

        userTracker.addCallback(userTrackerCallback, uiExecutor)

        contentResolver.registerContentObserver(
            secureSettings.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
            true,
            settingsObserver,
            UserHandle.USER_ALL
        )

        updateUserContentSettings()
    }

    override fun removeListener(listener: SmartspaceTargetFilter.Listener) {
        listeners.remove(listener)

        if (listeners.isNotEmpty()) {
            return
        }

        userTracker.removeCallback(userTrackerCallback)
        contentResolver.unregisterContentObserver(settingsObserver)
    }

    override fun filterSmartspaceTarget(t: SmartspaceTarget): Boolean {
        return when (t.userHandle) {
            userTracker.userHandle -> {
                !t.isSensitive || showSensitiveContentForCurrentUser
            }
            managedUserHandle -> {
                // Really, this should be "if this managed profile is associated with the current
                // active user", but we don't have a good way to check that, so instead we cheat:
                // Only the primary user can have an associated managed profile, so only show
                // content for the managed profile if the primary user is active
                userTracker.userHandle.identifier == UserHandle.USER_SYSTEM &&
                    (!t.isSensitive || showSensitiveContentForManagedUser)
            }
            else -> {
                false
            }
        }
    }

    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                execution.assertIsMainThread()
                updateUserContentSettings()
            }
        }

    private fun getWorkProfileUser(): UserHandle? {
        for (userInfo in userTracker.userProfiles) {
            if (userInfo.isManagedProfile) {
                return userInfo.userHandle
            }
        }
        return null
    }

    private fun updateUserContentSettings() {
        val setting = Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS

        showSensitiveContentForCurrentUser =
            secureSettings.getIntForUser(setting, 0, userTracker.userId) == 1

        managedUserHandle = getWorkProfileUser()
        val managedId = managedUserHandle?.identifier
        if (managedId != null) {
            showSensitiveContentForManagedUser =
                secureSettings.getIntForUser(setting, 0, managedId) == 1
        }

        listeners.forEach { it.onCriteriaChanged() }
    }
}
