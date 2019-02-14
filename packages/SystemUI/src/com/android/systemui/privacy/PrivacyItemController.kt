/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dependency.BG_HANDLER_NAME
import com.android.systemui.Dependency.MAIN_HANDLER_NAME
import com.android.systemui.R
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.Dumpable
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class PrivacyItemController @Inject constructor(
        val context: Context,
        private val appOpsController: AppOpsController,
        @Named(MAIN_HANDLER_NAME) private val uiHandler: Handler,
        @Named(BG_HANDLER_NAME) private val bgHandler: Handler
) : Dumpable {

    companion object {
        val OPS = intArrayOf(AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION)
        val intents = listOf(Intent.ACTION_USER_FOREGROUND,
                Intent.ACTION_MANAGED_PROFILE_ADDED,
                Intent.ACTION_MANAGED_PROFILE_REMOVED)
        const val TAG = "PrivacyItemController"
        const val SYSTEM_UID = 1000
    }

    @VisibleForTesting
    internal var privacyList = emptyList<PrivacyItem>()
        get() = field.toList() // Provides a shallow copy of the list

    private val userManager = context.getSystemService(UserManager::class.java)
    private var currentUserIds = emptyList<Int>()
    private var listening = false
    val systemApp =
            PrivacyApplication(context.getString(R.string.device_services), SYSTEM_UID, context)
    private val callbacks = mutableListOf<WeakReference<Callback>>()

    private val notifyChanges = Runnable {
        callbacks.forEach { it.get()?.privacyChanged(privacyList) }
    }

    private val updateListAndNotifyChanges = Runnable {
        updatePrivacyList()
        uiHandler.post(notifyChanges)
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
            context.unregisterReceiver(field)
            field = value
            registerReceiver()
        }

    private fun unregisterReceiver() {
        context.unregisterReceiver(userSwitcherReceiver)
    }

    private fun registerReceiver() {
        context.registerReceiverAsUser(userSwitcherReceiver, UserHandle.ALL, IntentFilter().apply {
            intents.forEach {
                addAction(it)
            }
        }, null, null)
    }

    private fun update(updateUsers: Boolean) {
        if (updateUsers) {
            val currentUser = ActivityManager.getCurrentUser()
            currentUserIds = userManager.getProfiles(currentUser).map { it.id }
        }
        bgHandler.post(updateListAndNotifyChanges)
    }

    @VisibleForTesting
    internal fun setListening(listen: Boolean) {
        if (listening == listen) return
        listening = listen
        if (listening) {
            appOpsController.addCallback(OPS, cb)
            registerReceiver()
            update(true)
        } else {
            appOpsController.removeCallback(OPS, cb)
            unregisterReceiver()
        }
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        callbacks.add(callback)
        if (callbacks.isNotEmpty() && !listening) setListening(true)
        // Notify this callback if we didn't set to listening
        else uiHandler.post(NotifyChangesToCallback(callback.get(), privacyList))
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        // Removes also if the callback is null
        callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        if (callbacks.isEmpty()) setListening(false)
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun updatePrivacyList() {
        privacyList = currentUserIds.flatMap { appOpsController.getActiveAppOpsForUser(it) }
                .mapNotNull { toPrivacyItem(it) }.distinct()
    }

    private fun toPrivacyItem(appOpItem: AppOpItem): PrivacyItem? {
        val type: PrivacyType = when (appOpItem.code) {
            AppOpsManager.OP_CAMERA -> PrivacyType.TYPE_CAMERA
            AppOpsManager.OP_COARSE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_FINE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_RECORD_AUDIO -> PrivacyType.TYPE_MICROPHONE
            else -> return null
        }
        if (appOpItem.uid == SYSTEM_UID) return PrivacyItem(type, systemApp)
        val app = PrivacyApplication(appOpItem.packageName, appOpItem.uid, context)
        return PrivacyItem(type, app)
    }

    // Used by containing class to get notified of changes
    interface Callback {
        fun privacyChanged(privacyItems: List<PrivacyItem>)
    }

    internal inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in intents) {
                update(true)
            }
        }
    }

    private class NotifyChangesToCallback(
        private val callback: Callback?,
        private val list: List<PrivacyItem>
    ) : Runnable {
        override fun run() {
            callback?.privacyChanged(list)
        }
    }

    override fun dump(fd: FileDescriptor?, pw: PrintWriter?, args: Array<out String>?) {
        pw?.println("PrivacyItemController state:")
        pw?.println("  Listening: $listening")
        pw?.println("  Current user ids: $currentUserIds")
        pw?.println("  Privacy Items:")
        privacyList.forEach {
            pw?.print("    ")
            pw?.println(it.toString())
        }
        pw?.println("  Callbacks:")
        callbacks.forEach {
            it.get()?.let {
                pw?.print("    ")
                pw?.println(it.toString())
            }
        }
    }
}