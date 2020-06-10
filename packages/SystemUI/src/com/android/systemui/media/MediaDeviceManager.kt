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
import android.media.MediaRouter2Manager
import android.media.session.MediaController
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
    private val mr2manager: MediaRouter2Manager,
    @Main private val fgExecutor: Executor,
    private val mediaDataManager: MediaDataManager
) : MediaDataManager.Listener {
    private val listeners: MutableSet<Listener> = mutableSetOf()
    private val entries: MutableMap<String, Token> = mutableMapOf()

    init {
        mediaDataManager.addListener(this)
    }

    /**
     * Add a listener for changes to the media route (ie. device).
     */
    fun addListener(listener: Listener) = listeners.add(listener)

    /**
     * Remove a listener that has been registered with addListener.
     */
    fun removeListener(listener: Listener) = listeners.remove(listener)

    override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
        if (oldKey != null && oldKey != key) {
            val oldEntry = entries.remove(oldKey)
            oldEntry?.stop()
        }
        var entry = entries[key]
        if (entry == null || entry?.token != data.token) {
            entry?.stop()
            val controller = data.token?.let {
                MediaController(context, it)
            }
            entry = Token(key, controller, localMediaManagerFactory.create(data.packageName))
            entries[key] = entry
            entry.start()
        }
    }

    override fun onMediaDataRemoved(key: String) {
        val token = entries.remove(key)
        token?.stop()
        token?.let {
            listeners.forEach {
                it.onKeyRemoved(key)
            }
        }
    }

    private fun processDevice(key: String, device: MediaDevice?) {
        val enabled = device != null
        val data = MediaDeviceData(enabled, device?.iconWithoutBackground, device?.name)
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
        val controller: MediaController?,
        val localMediaManager: LocalMediaManager
    ) : LocalMediaManager.DeviceCallback {
        val token
            get() = controller?.sessionToken
        private var started = false
        private var current: MediaDevice? = null
            set(value) {
                if (!started || value != field) {
                    field = value
                    processDevice(key, value)
                }
            }
        fun start() {
            localMediaManager.registerCallback(this)
            localMediaManager.startScan()
            updateCurrent()
            started = true
        }
        fun stop() {
            started = false
            localMediaManager.stopScan()
            localMediaManager.unregisterCallback(this)
        }
        override fun onDeviceListUpdate(devices: List<MediaDevice>?) = fgExecutor.execute {
            updateCurrent()
        }
        override fun onSelectedDeviceStateChanged(device: MediaDevice, state: Int) {
            fgExecutor.execute {
                updateCurrent()
            }
        }
        private fun updateCurrent() {
            val device = localMediaManager.getCurrentConnectedDevice()
            controller?.let {
                val route = mr2manager.getRoutingSessionForMediaController(it)
                // If we get a null route, then don't trust the device. Just set to null to disable the
                // output switcher chip.
                current = if (route != null) device else null
            } ?: run {
                current = device
            }
        }
    }
}
