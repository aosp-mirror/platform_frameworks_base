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

package com.android.systemui.media

import android.content.Context
import android.service.notification.StatusBarNotification
import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.systemui.dagger.qualifiers.Main
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides information about the route (ie. device) where playback is occurring.
 */
@Singleton
class MediaDeviceManager @Inject constructor(
    private val context: Context,
    private val localMediaManagerFactory: LocalMediaManagerFactory,
    private val featureFlag: MediaFeatureFlag,
    @Main private val fgExecutor: Executor
) {
    private val listeners: MutableSet<Listener> = mutableSetOf()
    private val entries: MutableMap<String, Token> = mutableMapOf()

    /**
     * Add a listener for changes to the media route (ie. device).
     */
    fun addListener(listener: Listener) = listeners.add(listener)

    /**
     * Remove a listener that has been registered with addListener.
     */
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        if (featureFlag.enabled && isMediaNotification(sbn)) {
            var tok = entries[key]
            if (tok == null) {
                tok = Token(key, localMediaManagerFactory.create(sbn.packageName))
                entries[key] = tok
                tok.start()
            }
        } else {
            onNotificationRemoved(key)
        }
    }

    fun onNotificationRemoved(key: String) {
        val token = entries.remove(key)
        token?.stop()
        token?.let {
            listeners.forEach {
                it.onKeyRemoved(key)
            }
        }
    }

    private fun processDevice(key: String, device: MediaDevice?) {
        val data = MediaDeviceData(device?.icon, device?.name)
        listeners.forEach {
            it.onMediaDeviceChanged(key, data)
        }
    }

    interface Listener {
        /** Called when the route has changed for a given notification. */
        fun onMediaDeviceChanged(key: String, data: MediaDeviceData?)
        /** Called when the notification was removed. */
        fun onKeyRemoved(key: String)
    }

    private inner class Token(
        val key: String,
        val localMediaManager: LocalMediaManager
    ) : LocalMediaManager.DeviceCallback {
        private var current: MediaDevice? = null
            set(value) {
                if (value != field) {
                    field = value
                    processDevice(key, value)
                }
            }
        fun start() {
            localMediaManager.registerCallback(this)
            localMediaManager.startScan()
            current = localMediaManager.getCurrentConnectedDevice()
        }
        fun stop() {
            localMediaManager.stopScan()
            localMediaManager.unregisterCallback(this)
        }
        override fun onDeviceListUpdate(devices: List<MediaDevice>?) = fgExecutor.execute {
            current = localMediaManager.getCurrentConnectedDevice()
        }
        override fun onSelectedDeviceStateChanged(device: MediaDevice, state: Int) {
            fgExecutor.execute {
                current = device
            }
        }
    }
}
