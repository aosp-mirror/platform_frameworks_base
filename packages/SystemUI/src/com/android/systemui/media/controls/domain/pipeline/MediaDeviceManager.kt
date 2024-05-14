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

package com.android.systemui.media.controls.domain.pipeline

import android.bluetooth.BluetoothLeBroadcast
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.MediaRouter2Manager
import android.media.RoutingSessionInfo
import android.media.session.MediaController
import android.text.TextUtils
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.flags.Flags.enableLeAudioSharing
import com.android.settingslib.flags.Flags.legacyLeAudioSharing
import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.media.PhoneMediaDevice
import com.android.settingslib.media.flags.Flags
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDeviceData
import com.android.systemui.media.controls.util.LocalMediaManagerFactory
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.MediaDataUtils
import com.android.systemui.media.muteawait.MediaMuteAwaitConnectionManager
import com.android.systemui.media.muteawait.MediaMuteAwaitConnectionManagerFactory
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.Lazy
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

private const val PLAYBACK_TYPE_UNKNOWN = 0
private const val TAG = "MediaDeviceManager"
private const val DEBUG = true

/** Provides information about the route (ie. device) where playback is occurring. */
class MediaDeviceManager
@Inject
constructor(
    private val context: Context,
    private val controllerFactory: MediaControllerFactory,
    private val localMediaManagerFactory: LocalMediaManagerFactory,
    private val mr2manager: Lazy<MediaRouter2Manager>,
    private val muteAwaitConnectionManagerFactory: MediaMuteAwaitConnectionManagerFactory,
    private val configurationController: ConfigurationController,
    private val localBluetoothManager: Lazy<LocalBluetoothManager?>,
    @Main private val fgExecutor: Executor,
    @Background private val bgExecutor: Executor,
) : MediaDataManager.Listener {

    private val listeners: MutableSet<Listener> = mutableSetOf()
    private val entries: MutableMap<String, Entry> = mutableMapOf()

    /** Add a listener for changes to the media route (ie. device). */
    fun addListener(listener: Listener) = listeners.add(listener)

    /** Remove a listener that has been registered with addListener. */
    fun removeListener(listener: Listener) = listeners.remove(listener)

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
        receivedSmartspaceCardLatency: Int,
        isSsReactivated: Boolean
    ) {
        if (oldKey != null && oldKey != key) {
            val oldEntry = entries.remove(oldKey)
            oldEntry?.stop()
        }
        var entry = entries[key]
        if (entry == null || entry.token != data.token) {
            entry?.stop()
            if (data.device != null) {
                // If we were already provided device info (e.g. from RCN), keep that and don't
                // listen for updates, but process once to push updates to listeners
                processDevice(key, oldKey, data.device)
                return
            }
            val controller = data.token?.let { controllerFactory.create(it) }
            val localMediaManager =
                localMediaManagerFactory.create(data.packageName, controller?.sessionToken)
            val muteAwaitConnectionManager =
                muteAwaitConnectionManagerFactory.create(localMediaManager)
            entry = Entry(key, oldKey, controller, localMediaManager, muteAwaitConnectionManager)
            entries[key] = entry
            entry.start()
        }
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        val token = entries.remove(key)
        token?.stop()
        token?.let { listeners.forEach { it.onKeyRemoved(key, userInitiated) } }
    }

    fun dump(pw: PrintWriter) {
        with(pw) {
            println("MediaDeviceManager state:")
            entries.forEach { (key, entry) ->
                println("  key=$key")
                entry.dump(pw)
            }
        }
    }

    @MainThread
    private fun processDevice(key: String, oldKey: String?, device: MediaDeviceData?) {
        listeners.forEach { it.onMediaDeviceChanged(key, oldKey, device) }
    }

    interface Listener {
        /** Called when the route has changed for a given notification. */
        fun onMediaDeviceChanged(key: String, oldKey: String?, data: MediaDeviceData?)
        /** Called when the notification was removed. */
        fun onKeyRemoved(key: String, userInitiated: Boolean)
    }

    private inner class Entry(
        val key: String,
        val oldKey: String?,
        val controller: MediaController?,
        val localMediaManager: LocalMediaManager,
        val muteAwaitConnectionManager: MediaMuteAwaitConnectionManager,
    ) :
        LocalMediaManager.DeviceCallback,
        MediaController.Callback(),
        BluetoothLeBroadcast.Callback {

        val token
            get() = controller?.sessionToken
        private var started = false
        private var playbackType = PLAYBACK_TYPE_UNKNOWN
        private var playbackVolumeControlId: String? = null
        private var current: MediaDeviceData? = null
            set(value) {
                val sameWithoutIcon = value != null && value.equalsWithoutIcon(field)
                if (!started || !sameWithoutIcon) {
                    field = value
                    fgExecutor.execute { processDevice(key, oldKey, value) }
                }
            }
        // A device that is not yet connected but is expected to connect imminently. Because it's
        // expected to connect imminently, it should be displayed as the current device.
        private var aboutToConnectDeviceOverride: AboutToConnectDevice? = null
        private var broadcastDescription: String? = null
        private val configListener =
            object : ConfigurationController.ConfigurationListener {
                override fun onLocaleListChanged() {
                    updateCurrent()
                }
            }

        @AnyThread
        fun start() =
            bgExecutor.execute {
                if (!started) {
                    localMediaManager.registerCallback(this)
                    if (!Flags.removeUnnecessaryRouteScanning()) {
                        localMediaManager.startScan()
                    }
                    muteAwaitConnectionManager.startListening()
                    playbackType = controller?.playbackInfo?.playbackType ?: PLAYBACK_TYPE_UNKNOWN
                    playbackVolumeControlId = controller?.playbackInfo?.volumeControlId
                    controller?.registerCallback(this)
                    updateCurrent()
                    started = true
                    configurationController.addCallback(configListener)
                }
            }

        @AnyThread
        fun stop() =
            bgExecutor.execute {
                if (started) {
                    started = false
                    controller?.unregisterCallback(this)
                    if (!Flags.removeUnnecessaryRouteScanning()) {
                        localMediaManager.stopScan()
                    }
                    localMediaManager.unregisterCallback(this)
                    muteAwaitConnectionManager.stopListening()
                    configurationController.removeCallback(configListener)
                }
            }

        fun dump(pw: PrintWriter) {
            val routingSession =
                controller?.let { mr2manager.get().getRoutingSessionForMediaController(it) }
            val selectedRoutes = routingSession?.let { mr2manager.get().getSelectedRoutes(it) }
            with(pw) {
                println("    current device is ${current?.name}")
                val type = controller?.playbackInfo?.playbackType
                println("    PlaybackType=$type (1 for local, 2 for remote) cached=$playbackType")
                val volumeControlId = controller?.playbackInfo?.volumeControlId
                println("    volumeControlId=$volumeControlId cached= $playbackVolumeControlId")
                println("    routingSession=$routingSession")
                println("    selectedRoutes=$selectedRoutes")
                println("    currentConnectedDevice=${localMediaManager.currentConnectedDevice}")
            }
        }

        @WorkerThread
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) {
            val newPlaybackType = info?.playbackType ?: PLAYBACK_TYPE_UNKNOWN
            val newPlaybackVolumeControlId = info?.volumeControlId
            if (
                newPlaybackType == playbackType &&
                    newPlaybackVolumeControlId == playbackVolumeControlId
            ) {
                return
            }
            playbackType = newPlaybackType
            playbackVolumeControlId = newPlaybackVolumeControlId
            updateCurrent()
        }

        override fun onDeviceListUpdate(devices: List<MediaDevice>?) =
            bgExecutor.execute { updateCurrent() }

        override fun onSelectedDeviceStateChanged(device: MediaDevice, state: Int) {
            bgExecutor.execute { updateCurrent() }
        }

        override fun onAboutToConnectDeviceAdded(
            deviceAddress: String,
            deviceName: String,
            deviceIcon: Drawable?
        ) {
            aboutToConnectDeviceOverride =
                AboutToConnectDevice(
                    fullMediaDevice = localMediaManager.getMediaDeviceById(deviceAddress),
                    backupMediaDeviceData =
                        MediaDeviceData(
                            /* enabled */ enabled = true,
                            /* icon */ deviceIcon,
                            /* name */ deviceName,
                            /* showBroadcastButton */ showBroadcastButton = false
                        )
                )
            updateCurrent()
        }

        override fun onAboutToConnectDeviceRemoved() {
            aboutToConnectDeviceOverride = null
            updateCurrent()
        }

        override fun onBroadcastStarted(reason: Int, broadcastId: Int) {
            if (DEBUG) {
                Log.d(TAG, "onBroadcastStarted(), reason = $reason , broadcastId = $broadcastId")
            }
            updateCurrent()
        }

        override fun onBroadcastStartFailed(reason: Int) {
            if (DEBUG) {
                Log.d(TAG, "onBroadcastStartFailed(), reason = $reason")
            }
        }

        override fun onBroadcastMetadataChanged(
            broadcastId: Int,
            metadata: BluetoothLeBroadcastMetadata
        ) {
            if (DEBUG) {
                Log.d(
                    TAG,
                    "onBroadcastMetadataChanged(), broadcastId = $broadcastId , " +
                        "metadata = $metadata"
                )
            }
            updateCurrent()
        }

        override fun onBroadcastStopped(reason: Int, broadcastId: Int) {
            if (DEBUG) {
                Log.d(TAG, "onBroadcastStopped(), reason = $reason , broadcastId = $broadcastId")
            }
            updateCurrent()
        }

        override fun onBroadcastStopFailed(reason: Int) {
            if (DEBUG) {
                Log.d(TAG, "onBroadcastStopFailed(), reason = $reason")
            }
        }

        override fun onBroadcastUpdated(reason: Int, broadcastId: Int) {
            if (DEBUG) {
                Log.d(TAG, "onBroadcastUpdated(), reason = $reason , broadcastId = $broadcastId")
            }
            updateCurrent()
        }

        override fun onBroadcastUpdateFailed(reason: Int, broadcastId: Int) {
            if (DEBUG) {
                Log.d(
                    TAG,
                    "onBroadcastUpdateFailed(), reason = $reason , " + "broadcastId = $broadcastId"
                )
            }
        }

        override fun onPlaybackStarted(reason: Int, broadcastId: Int) {}

        override fun onPlaybackStopped(reason: Int, broadcastId: Int) {}

        @WorkerThread
        private fun updateCurrent() {
            if (isLeAudioBroadcastEnabled()) {
                if (enableLeAudioSharing()) {
                    current =
                        MediaDeviceData(
                            enabled = false,
                            icon =
                                context.getDrawable(
                                    com.android.settingslib.R.drawable.ic_bt_le_audio_sharing
                                ),
                            name = context.getString(R.string.audio_sharing_description),
                            intent = null,
                            showBroadcastButton = false
                        )
                } else {
                    current =
                        MediaDeviceData(
                            /* enabled */ true,
                            /* icon */ context.getDrawable(R.drawable.settings_input_antenna),
                            /* name */ broadcastDescription,
                            /* intent */ null,
                            /* showBroadcastButton */ showBroadcastButton = true
                        )
                }
            } else {
                val aboutToConnect = aboutToConnectDeviceOverride
                if (
                    aboutToConnect != null &&
                        aboutToConnect.fullMediaDevice == null &&
                        aboutToConnect.backupMediaDeviceData != null
                ) {
                    // Only use [backupMediaDeviceData] when we don't have [fullMediaDevice].
                    current = aboutToConnect.backupMediaDeviceData
                    return
                }
                val device =
                    aboutToConnect?.fullMediaDevice ?: localMediaManager.currentConnectedDevice
                val routingSession =
                    controller?.let { mr2manager.get().getRoutingSessionForMediaController(it) }

                // If we have a controller but get a null route, then don't trust the device
                val enabled = device != null && (controller == null || routingSession != null)

                val name = getDeviceName(device, routingSession)
                if (DEBUG) {
                    Log.d(TAG, "new device name $name")
                }
                current =
                    MediaDeviceData(
                        enabled,
                        device?.iconWithoutBackground,
                        name,
                        id = device?.id,
                        showBroadcastButton = false
                    )
            }
        }

        /** Return a display name for the current device / route, or null if not possible */
        private fun getDeviceName(
            device: MediaDevice?,
            routingSession: RoutingSessionInfo?,
        ): String? {
            val selectedRoutes = routingSession?.let { mr2manager.get().getSelectedRoutes(it) }

            if (DEBUG) {
                Log.d(
                    TAG,
                    "device is $device, controller $controller," +
                        " routingSession ${routingSession?.name}" +
                        " or ${selectedRoutes?.firstOrNull()?.name}"
                )
            }

            if (controller == null) {
                // In resume state, we don't have a controller - just use the device name
                return device?.name
            }

            if (routingSession == null) {
                // This happens when casting from apps that do not support MediaRouter2
                // The output switcher can't show anything useful here, so set to null
                return null
            }

            // If this is a user route (app / cast provided), use the provided name
            if (!routingSession.isSystemSession) {
                return routingSession.name?.toString() ?: device?.name
            }

            selectedRoutes?.firstOrNull()?.let {
                if (device is PhoneMediaDevice) {
                    // Get the (localized) name for this phone device
                    return PhoneMediaDevice.getSystemRouteNameFromType(context, it)
                } else {
                    // If it's another type of device (in practice, Bluetooth), use the route name
                    return it.name.toString()
                }
            }
            return null
        }

        @WorkerThread
        private fun isLeAudioBroadcastEnabled(): Boolean {
            if (!enableLeAudioSharing() && !legacyLeAudioSharing()) return false
            val localBluetoothManager = localBluetoothManager.get()
            if (localBluetoothManager != null) {
                val profileManager = localBluetoothManager.profileManager
                if (profileManager != null) {
                    val bluetoothLeBroadcast = profileManager.leAudioBroadcastProfile
                    if (bluetoothLeBroadcast != null && bluetoothLeBroadcast.isEnabled(null)) {
                        getBroadcastingInfo(bluetoothLeBroadcast)
                        return true
                    } else if (DEBUG) {
                        Log.d(TAG, "Can not get LocalBluetoothLeBroadcast")
                    }
                } else if (DEBUG) {
                    Log.d(TAG, "Can not get LocalBluetoothProfileManager")
                }
            } else if (DEBUG) {
                Log.d(TAG, "Can not get LocalBluetoothManager")
            }
            return false
        }

        @WorkerThread
        private fun getBroadcastingInfo(bluetoothLeBroadcast: LocalBluetoothLeBroadcast) {
            val currentBroadcastedApp = bluetoothLeBroadcast.appSourceName
            // TODO(b/233698402): Use the package name instead of app label to avoid the
            // unexpected result.
            // Check the current media app's name is the same with current broadcast app's name
            // or not.
            val mediaApp =
                MediaDataUtils.getAppLabel(
                    context,
                    localMediaManager.packageName,
                    context.getString(R.string.bt_le_audio_broadcast_dialog_unknown_name)
                )
            val isCurrentBroadcastedApp = TextUtils.equals(mediaApp, currentBroadcastedApp)
            if (isCurrentBroadcastedApp) {
                broadcastDescription =
                    context.getString(R.string.broadcasting_description_is_broadcasting)
            } else {
                broadcastDescription = currentBroadcastedApp
            }
        }
    }
}

/**
 * A class storing information for the about-to-connect device. See
 * [LocalMediaManager.DeviceCallback.onAboutToConnectDeviceAdded] for more information.
 *
 * @property fullMediaDevice a full-fledged [MediaDevice] object representing the device. If
 *   non-null, prefer using [fullMediaDevice] over [backupMediaDeviceData].
 * @property backupMediaDeviceData a backup [MediaDeviceData] object containing the minimum
 *   information required to display the device. Only use if [fullMediaDevice] is null.
 */
private data class AboutToConnectDevice(
    val fullMediaDevice: MediaDevice? = null,
    val backupMediaDeviceData: MediaDeviceData? = null
)
