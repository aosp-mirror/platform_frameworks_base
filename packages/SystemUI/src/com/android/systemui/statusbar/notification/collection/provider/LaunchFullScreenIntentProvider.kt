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

package com.android.systemui.statusbar.notification.collection.provider

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.util.ListenerSet
import javax.inject.Inject

/**
 * A class that enables communication of decisions to launch a notification's full screen intent.
 */
@SysUISingleton
class LaunchFullScreenIntentProvider @Inject constructor() {
    companion object {
        private const val TAG = "LaunchFullScreenIntentProvider"
    }
    private val listeners = ListenerSet<Listener>()

    /**
     * Registers a listener with this provider. These listeners will be alerted whenever a full
     * screen intent should be launched for a notification entry.
     */
    fun registerListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    /** Removes the specified listener. */
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Sends a request to launch full screen intent for the given notification entry to all
     * registered listeners.
     */
    fun launchFullScreenIntent(entry: NotificationEntry) {
        if (listeners.isEmpty()) {
            // This should never happen, but we should definitely know if it does because having
            // no listeners would indicate that FSIs are getting entirely dropped on the floor.
            Log.wtf(TAG, "no listeners found when launchFullScreenIntent requested")
        }
        for (listener in listeners) {
            listener.onFullScreenIntentRequested(entry)
        }
    }

    /** Listener interface for passing full screen intent launch decisions. */
    fun interface Listener {
        /**
         * Invoked whenever a full screen intent launch is requested for the given notification
         * entry.
         */
        fun onFullScreenIntentRequested(entry: NotificationEntry)
    }
}
