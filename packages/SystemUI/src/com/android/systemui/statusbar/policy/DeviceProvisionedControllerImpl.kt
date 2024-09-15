/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.content.Context
import android.content.pm.UserInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.provider.Settings
import android.util.ArraySet
import android.util.SparseBooleanArray
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@SysUISingleton
open class DeviceProvisionedControllerImpl
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings,
    private val userTracker: UserTracker,
    private val dumpManager: DumpManager,
    @Background private val backgroundHandler: Handler,
    @Main private val mainExecutor: Executor
) : DeviceProvisionedController, DeviceProvisionedController.DeviceProvisionedListener, Dumpable {

    companion object {
        private const val ALL_USERS = -1
        private const val NO_USERS = -2
        protected const val TAG = "DeviceProvisionedControllerImpl"
    }

    private val deviceProvisionedUri = globalSettings.getUriFor(Settings.Global.DEVICE_PROVISIONED)
    private val userSetupUri = secureSettings.getUriFor(Settings.Secure.USER_SETUP_COMPLETE)

    private val deviceProvisioned = AtomicBoolean(false)
    @GuardedBy("lock") private val userSetupComplete = SparseBooleanArray()
    @GuardedBy("lock")
    private val listeners = ArraySet<DeviceProvisionedController.DeviceProvisionedListener>()

    private val lock = Any()

    private val backgroundExecutor = HandlerExecutor(backgroundHandler)

    private val initted = AtomicBoolean(false)

    private val _currentUser: Int
        get() = userTracker.userId

    override fun getCurrentUser(): Int {
        return _currentUser
    }

    private val observer =
        object : ContentObserver(backgroundHandler) {
            override fun onChange(
                selfChange: Boolean,
                uris: MutableCollection<Uri>,
                flags: Int,
                userId: Int
            ) {
                val updateDeviceProvisioned = deviceProvisionedUri in uris
                val updateUser = if (userSetupUri in uris) userId else NO_USERS
                updateValues(updateDeviceProvisioned, updateUser)
                if (updateDeviceProvisioned) {
                    onDeviceProvisionedChanged()
                }
                if (updateUser != NO_USERS) {
                    onUserSetupChanged()
                }
            }
        }

    private val userChangedCallback =
        object : UserTracker.Callback {
            @WorkerThread
            override fun onUserChanged(newUser: Int, userContext: Context) {
                updateValues(updateDeviceProvisioned = false, updateUser = newUser)
                onUserSwitched()
            }

            override fun onProfilesChanged(profiles: List<UserInfo>) {}
        }

    init {
        userSetupComplete.put(currentUser, false)
    }

    /** Call to initialize values and register observers */
    open fun init() {
        if (!initted.compareAndSet(false, true)) {
            return
        }
        dumpManager.registerDumpable(this)
        updateValues()
        userTracker.addCallback(userChangedCallback, backgroundExecutor)
        globalSettings.registerContentObserverSync(deviceProvisionedUri, observer)
        secureSettings.registerContentObserverForUserSync(
            userSetupUri,
            observer,
            UserHandle.USER_ALL
        )
    }

    @WorkerThread
    private fun updateValues(updateDeviceProvisioned: Boolean = true, updateUser: Int = ALL_USERS) {
        if (updateDeviceProvisioned) {
            deviceProvisioned.set(globalSettings.getInt(Settings.Global.DEVICE_PROVISIONED, 0) != 0)
        }
        synchronized(lock) {
            if (updateUser == ALL_USERS) {
                val n = userSetupComplete.size()
                for (i in 0 until n) {
                    val user = userSetupComplete.keyAt(i)
                    val value =
                        secureSettings.getIntForUser(
                            Settings.Secure.USER_SETUP_COMPLETE,
                            0,
                            user
                        ) != 0
                    userSetupComplete.put(user, value)
                }
            } else if (updateUser != NO_USERS) {
                val value =
                    secureSettings.getIntForUser(
                        Settings.Secure.USER_SETUP_COMPLETE,
                        0,
                        updateUser
                    ) != 0
                userSetupComplete.put(updateUser, value)
            }
        }
    }

    /**
     * Adds a listener.
     *
     * The listener will not be called when this happens.
     */
    override fun addCallback(listener: DeviceProvisionedController.DeviceProvisionedListener) {
        synchronized(lock) { listeners.add(listener) }
    }

    override fun removeCallback(listener: DeviceProvisionedController.DeviceProvisionedListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    override fun isDeviceProvisioned(): Boolean {
        return deviceProvisioned.get()
    }

    override fun isUserSetup(user: Int): Boolean {
        val index = synchronized(lock) { userSetupComplete.indexOfKey(user) }
        return if (index < 0) {
            val value =
                secureSettings.getIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 0, user) != 0
            synchronized(lock) { userSetupComplete.put(user, value) }
            value
        } else {
            synchronized(lock) { userSetupComplete.get(user, false) }
        }
    }

    override fun isCurrentUserSetup(): Boolean {
        return isUserSetup(currentUser)
    }

    override fun onDeviceProvisionedChanged() {
        dispatchChange(
            DeviceProvisionedController.DeviceProvisionedListener::onDeviceProvisionedChanged
        )
    }

    override fun onUserSetupChanged() {
        dispatchChange(DeviceProvisionedController.DeviceProvisionedListener::onUserSetupChanged)
    }

    override fun onUserSwitched() {
        dispatchChange(DeviceProvisionedController.DeviceProvisionedListener::onUserSwitched)
    }

    protected fun dispatchChange(
        callback: DeviceProvisionedController.DeviceProvisionedListener.() -> Unit
    ) {
        val listenersCopy = synchronized(lock) { ArrayList(listeners) }
        mainExecutor.execute { listenersCopy.forEach(callback) }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Device provisioned: ${deviceProvisioned.get()}")
        synchronized(lock) {
            pw.println("User setup complete: $userSetupComplete")
            pw.println("Listeners: $listeners")
        }
    }
}
