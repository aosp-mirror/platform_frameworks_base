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

package com.android.systemui.statusbar.notification.collection.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.util.Assert
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.isNotEmpty
import java.io.PrintWriter
import javax.inject.Inject

/**
 * A debug mode provider which is used by both the legacy and new notification pipelines to
 * block unwanted notifications from appearing to the user, primarily for integration testing.
 *
 * The only configuration is a list of allowed packages.  When this list is empty, the feature is
 * disabled.  When SystemUI starts up, this feature is disabled.
 *
 * To enabled filtering, provide the list of packages in a comma-separated list using the command:
 *
 * `$ adb shell am broadcast -a com.android.systemui.action.SET_NOTIF_DEBUG_MODE
 *          --esal allowed_packages <comma-separated-packages>`
 *
 * To disable filtering, send the action without a list:
 *
 * `$ adb shell am broadcast -a com.android.systemui.action.SET_NOTIF_DEBUG_MODE`
 *
 * NOTE: this feature only works on debug builds, and when the broadcaster is root.
 */
@SysUISingleton
class DebugModeFilterProvider @Inject constructor(
    private val context: Context,
    dumpManager: DumpManager
) : Dumpable {
    private var allowedPackages: List<String> = emptyList()
    private val listeners = ListenerSet<Runnable>()

    init {
        dumpManager.registerDumpable(this)
    }

    /**
     * Register a runnable to be invoked when the allowed packages changes, which would mean the
     * result of [shouldFilterOut] may have changed for some entries.
     */
    fun registerInvalidationListener(listener: Runnable) {
        Assert.isMainThread()
        if (!Build.isDebuggable()) {
            return
        }
        val needsInitialization = listeners.isEmpty()
        listeners.addIfAbsent(listener)
        if (needsInitialization) {
            val filter = IntentFilter().apply { addAction(ACTION_SET_NOTIF_DEBUG_MODE) }
            val permission = NOTIF_DEBUG_MODE_PERMISSION
            context.registerReceiver(mReceiver, filter, permission, null, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "Registered: $mReceiver")
        }
    }

    /**
     * Determine if the given entry should be hidden from the user in debug mode.
     * Will always return false in release.
     */
    fun shouldFilterOut(entry: NotificationEntry): Boolean {
        if (allowedPackages.isEmpty()) {
            return false
        }
        return entry.sbn.packageName !in allowedPackages
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("initialized: ${listeners.isNotEmpty()}")
        pw.println("allowedPackages: ${allowedPackages.size}")
        allowedPackages.forEachIndexed { i, pkg ->
            pw.println("  [$i]: $pkg")
        }
    }

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action
            if (ACTION_SET_NOTIF_DEBUG_MODE == action) {
                allowedPackages = intent.extras?.getStringArrayList(EXTRA_ALLOWED_PACKAGES)
                    ?: emptyList()
                Log.d(TAG, "Updated allowedPackages: $allowedPackages")
                listeners.forEach(Runnable::run)
            } else {
                Log.d(TAG, "Malformed intent: $intent")
            }
        }
    }

    companion object {
        private const val TAG = "DebugModeFilterProvider"
        private const val ACTION_SET_NOTIF_DEBUG_MODE =
            "com.android.systemui.action.SET_NOTIF_DEBUG_MODE"
        private const val NOTIF_DEBUG_MODE_PERMISSION =
            "com.android.systemui.permission.NOTIF_DEBUG_MODE"
        private const val EXTRA_ALLOWED_PACKAGES = "allowed_packages"
    }
}
