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

package com.android.systemui.media.muteawait

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioDeviceAttributes
import android.media.AudioManager
import com.android.settingslib.media.DeviceIconUtil
import com.android.settingslib.media.LocalMediaManager
import com.android.systemui.dagger.qualifiers.Main
import java.util.concurrent.Executor

/**
 * A class responsible for keeping track of devices that have muted audio playback until the device
 * is connected. The device connection expected to happen imminently, so we'd like to display the
 * device name in the media player. When the about-to-connect device changes, [localMediaManager]
 * will be notified.
 *
 * See [AudioManager.muteAwaitConnection] and b/206614671 for more details.
 */
class MediaMuteAwaitConnectionManager constructor(
    @Main private val mainExecutor: Executor,
    private val localMediaManager: LocalMediaManager,
    private val context: Context,
    private val deviceIconUtil: DeviceIconUtil,
    private val logger: MediaMuteAwaitLogger
) {
    var currentMutedDevice: AudioDeviceAttributes? = null

    val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val muteAwaitConnectionChangeListener = object : AudioManager.MuteAwaitConnectionCallback() {
        override fun onMutedUntilConnection(device: AudioDeviceAttributes, mutedUsages: IntArray) {
            logger.logMutedDeviceAdded(device.address, device.name, mutedUsages.hasMedia())
            if (mutedUsages.hasMedia()) {
                // There should only be one device that's mutedUntilConnection at a time, so we can
                // safely override any previous value.
                currentMutedDevice = device
                localMediaManager.dispatchAboutToConnectDeviceAdded(
                    device.address, device.name, device.getIcon()
                )
            }
        }

        override fun onUnmutedEvent(
            @UnmuteEvent unmuteEvent: Int,
            device: AudioDeviceAttributes,
            mutedUsages: IntArray
        ) {
            val isMostRecentDevice = currentMutedDevice == device
            logger.logMutedDeviceRemoved(
                device.address, device.name, mutedUsages.hasMedia(), isMostRecentDevice
            )
            if (isMostRecentDevice && mutedUsages.hasMedia()) {
                currentMutedDevice = null
                localMediaManager.dispatchAboutToConnectDeviceRemoved()
            }
        }
    }

    /** Start listening for mute await events. */
    fun startListening() {
        audioManager.registerMuteAwaitConnectionCallback(
                mainExecutor, muteAwaitConnectionChangeListener
        )
        val currentDevice = audioManager.mutingExpectedDevice
        if (currentDevice != null) {
            currentMutedDevice = currentDevice
            localMediaManager.dispatchAboutToConnectDeviceAdded(
                currentDevice.address, currentDevice.name, currentDevice.getIcon()
            )
        }
    }

    /** Stop listening for mute await events. */
    fun stopListening() {
        audioManager.unregisterMuteAwaitConnectionCallback(muteAwaitConnectionChangeListener)
    }

    private fun AudioDeviceAttributes.getIcon(): Drawable {
        return deviceIconUtil.getIconFromAudioDeviceType(this.type, context)
    }

    private fun IntArray.hasMedia() = USAGE_MEDIA in this
}
