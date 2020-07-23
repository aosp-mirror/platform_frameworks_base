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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.os.UserManager
import android.provider.DeviceConfig
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.Dumpable
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivacyItemController @Inject constructor(
    context: Context,
    private val appOpsController: AppOpsController,
    @Main uiExecutor: DelayableExecutor,
    @Background private val bgExecutor: Executor,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val deviceConfigProxy: DeviceConfigProxy,
    private val userManager: UserManager,
    dumpManager: DumpManager
) : Dumpable {

    @VisibleForTesting
    internal companion object {
        val OPS = intArrayOf(AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION)
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_SWITCHED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        }
        const val TAG = "PrivacyItemController"
    }

    @VisibleForTesting
    internal var privacyList = emptyList<PrivacyItem>()
        @Synchronized get() = field.toList() // Returns a shallow copy of the list
        @Synchronized set

    private fun isPermissionsHubEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED, false)
    }

    private var currentUserIds = emptyList<Int>()
    private var listening = false
    private val callbacks = mutableListOf<WeakReference<Callback>>()
    private val internalUiExecutor = MyExecutor(uiExecutor)

    private val notifyChanges = Runnable {
        val list = privacyList
        callbacks.forEach { it.get()?.onPrivacyItemsChanged(list) }
    }

    private val updateListAndNotifyChanges = Runnable {
        updatePrivacyList()
        uiExecutor.execute(notifyChanges)
    }

    var indicatorsAvailable = isPermissionsHubEnabled()
        private set
    @VisibleForTesting
    internal val devicePropertiesChangedListener =
            object : DeviceConfig.OnPropertiesChangedListener {
        override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
            if (DeviceConfig.NAMESPACE_PRIVACY.equals(properties.getNamespace()) &&
                    properties.getKeyset().contains(
                    SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED)) {
                val flag = properties.getBoolean(
                        SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED, false)
                if (indicatorsAvailable != flag) {
                    // This is happening already in the UI executor, so we can iterate in the
                    indicatorsAvailable = flag
                    callbacks.forEach { it.get()?.onFlagChanged(flag) }
                }

                internalUiExecutor.updateListeningState()
            }
        }
    }

    private val cb = object : AppOpsController.Callback {
        override fun onActiveStateChanged(
            code: Int,
            uid: Int,
            packageName: String,
            active: Boolean
        ) {
            val userId = UserHandle.getUserId(uid)
            if (userId in currentUserIds) {
                update(false)
            }
        }
    }

    @VisibleForTesting
    internal var userSwitcherReceiver = Receiver()
        set(value) {
            unregisterReceiver()
            field = value
            if (listening) registerReceiver()
        }

    init {
        deviceConfigProxy.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_PRIVACY,
                uiExecutor,
                devicePropertiesChangedListener)
        dumpManager.registerDumpable(TAG, this)
    }

    private fun unregisterReceiver() {
        broadcastDispatcher.unregisterReceiver(userSwitcherReceiver)
    }

    private fun registerReceiver() {
        broadcastDispatcher.registerReceiver(userSwitcherReceiver, intentFilter,
                null /* handler */, UserHandle.ALL)
    }

    private fun update(updateUsers: Boolean) {
        bgExecutor.execute {
            if (updateUsers) {
                val currentUser = ActivityManager.getCurrentUser()
                currentUserIds = userManager.getProfiles(currentUser).map { it.id }
            }
            updateListAndNotifyChanges.run()
        }
    }

    /**
     * Updates listening status based on whether there are callbacks and the indicators are enabled
     *
     * This is only called from private (add/remove)Callback and from the config listener, all in
     * main thread.
     */
    private fun setListeningState() {
        val listen = !callbacks.isEmpty() and indicatorsAvailable
        if (listening == listen) return
        listening = listen
        if (listening) {
            appOpsController.addCallback(OPS, cb)
            registerReceiver()
            update(true)
        } else {
            appOpsController.removeCallback(OPS, cb)
            unregisterReceiver()
            // Make sure that we remove all indicators and notify listeners if we are not
            // listening anymore due to indicators being disabled
            update(false)
        }
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        callbacks.add(callback)
        if (callbacks.isNotEmpty() && !listening) {
            internalUiExecutor.updateListeningState()
        }
        // Notify this callback if we didn't set to listening
        else if (listening) {
            internalUiExecutor.execute(NotifyChangesToCallback(callback.get(), privacyList))
        }
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        // Removes also if the callback is null
        callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        if (callbacks.isEmpty()) {
            internalUiExecutor.updateListeningState()
        }
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun updatePrivacyList() {
        if (!listening) {
            privacyList = emptyList()
            return
        }
        val list = currentUserIds.flatMap { appOpsController.getActiveAppOpsForUser(it) }
                .mapNotNull { toPrivacyItem(it) }.distinct()
        privacyList = list
    }

    private fun toPrivacyItem(appOpItem: AppOpItem): PrivacyItem? {
        val type: PrivacyType = when (appOpItem.code) {
            AppOpsManager.OP_CAMERA -> PrivacyType.TYPE_CAMERA
            AppOpsManager.OP_COARSE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_FINE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_RECORD_AUDIO -> PrivacyType.TYPE_MICROPHONE
            else -> return null
        }
        val app = PrivacyApplication(appOpItem.packageName, appOpItem.uid)
        return PrivacyItem(type, app)
    }

    interface Callback {
        fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>)
        @JvmDefault
        fun onFlagChanged(flag: Boolean) {}
    }

    internal inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intentFilter.hasAction(intent.action)) {
                update(true)
            }
        }
    }

    private class NotifyChangesToCallback(
        private val callback: Callback?,
        private val list: List<PrivacyItem>
    ) : Runnable {
        override fun run() {
            callback?.onPrivacyItemsChanged(list)
        }
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("PrivacyItemController state:")
        pw.println("  Listening: $listening")
        pw.println("  Current user ids: $currentUserIds")
        pw.println("  Privacy Items:")
        privacyList.forEach {
            pw.print("    ")
            pw.println(it.toString())
        }
        pw.println("  Callbacks:")
        callbacks.forEach {
            it.get()?.let {
                pw.print("    ")
                pw.println(it.toString())
            }
        }
    }

    private inner class MyExecutor(
        private val delegate: DelayableExecutor
    ) : Executor {

        private var listeningCanceller: Runnable? = null

        override fun execute(command: Runnable) {
            delegate.execute(command)
        }

        fun updateListeningState() {
            listeningCanceller?.run()
            listeningCanceller = delegate.executeDelayed({ setListeningState() }, 0L)
        }
    }
}