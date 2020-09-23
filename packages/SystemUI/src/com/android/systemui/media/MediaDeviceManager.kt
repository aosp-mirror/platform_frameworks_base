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

import android.media.MediaRouter2Manager
import android.media.session.MediaController
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

private const val PLAYBACK_TYPE_UNKNOWN = 0

/**
 * Provides information about the route (ie. device) where playback is occurring.
 */
class MediaDeviceManager @Inject constructor(
    private val controllerFactory: MediaControllerFactory,
    private val localMediaManagerFactory: LocalMediaManagerFactory,
    private val mr2manager: MediaRouter2Manager,
    @Main private val fgExecutor: Executor,
    @Background private val bgExecutor: Executor,
    dumpManager: DumpManager
) : MediaDataManager.Listener, Dumpable {

    private val listeners: MutableSet<Listener> = mutableSetOf()
    private val entries: MutableMap<String, Entry> = mutableMapOf()

    init {
        dumpManager.registerDumpable(javaClass.name, this)
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
                controllerFactory.create(it)
            }
            entry = Entry(key, oldKey, controller,
                    localMediaManagerFactory.create(data.packageName))
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

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>) {
        with(pw) {
            println("MediaDeviceManager state:")
            entries.forEach {
                key, entry ->
                println("  key=$key")
                entry.dump(fd, pw, args)
            }
        }
    }

    @MainThread
    private fun processDevice(key: String, oldKey: String?, device: MediaDevice?) {
        val enabled = device != null
        val data = MediaDeviceData(enabled, device?.iconWithoutBackground, device?.name)
        listeners.forEach {
            it.onMediaDeviceChanged(key, oldKey, data)
        }
    }

    interface Listener {
        /** Called when the route has changed for a given notification. */
        fun onMediaDeviceChanged(key: String, oldKey: String?, data: MediaDeviceData?)
        /** Called when the notification was removed. */
        fun onKeyRemoved(key: String)
    }

    private inner class Entry(
        val key: String,
        val oldKey: String?,
        val controller: MediaController?,
        val localMediaManager: LocalMediaManager
    ) : LocalMediaManager.DeviceCallback, MediaController.Callback() {

        val token
            get() = controller?.sessionToken
        private var started = false
        private var playbackType = PLAYBACK_TYPE_UNKNOWN
        private var current: MediaDevice? = null
            set(value) {
                if (!started || value != field) {
                    field = value
                    fgExecutor.execute {
                        processDevice(key, oldKey, value)
                    }
                }
            }

        @AnyThread
        fun start() = bgExecutor.execute {
            localMediaManager.registerCallback(this)
            localMediaManager.startScan()
            playbackType = controller?.playbackInfo?.playbackType ?: PLAYBACK_TYPE_UNKNOWN
            controller?.registerCallback(this)
            updateCurrent()
            started = true
        }

        @AnyThread
        fun stop() = bgExecutor.execute {
            started = false
            controller?.unregisterCallback(this)
            localMediaManager.stopScan()
            localMediaManager.unregisterCallback(this)
        }

        fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>) {
            val routingSession = controller?.let {
                mr2manager.getRoutingSessionForMediaController(it)
            }
            val selectedRoutes = routingSession?.let {
                mr2manager.getSelectedRoutes(it)
            }
            with(pw) {
                println("    current device is ${current?.name}")
                val type = controller?.playbackInfo?.playbackType
                println("    PlaybackType=$type (1 for local, 2 for remote) cached=$playbackType")
                println("    routingSession=$routingSession")
                println("    selectedRoutes=$selectedRoutes")
            }
        }

        @WorkerThread
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) {
            val newPlaybackType = info?.playbackType ?: PLAYBACK_TYPE_UNKNOWN
            if (newPlaybackType == playbackType) {
                return
            }
            playbackType = newPlaybackType
            updateCurrent()
        }

        override fun onDeviceListUpdate(devices: List<MediaDevice>?) = bgExecutor.execute {
            updateCurrent()
        }

        override fun onSelectedDeviceStateChanged(device: MediaDevice, state: Int) {
            bgExecutor.execute {
                updateCurrent()
            }
        }

        @WorkerThread
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
