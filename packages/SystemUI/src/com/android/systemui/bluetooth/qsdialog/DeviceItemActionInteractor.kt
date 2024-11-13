/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.bluetooth.qsdialog

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.A2dpProfile
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.HeadsetProfile
import com.android.settingslib.bluetooth.HearingAidProfile
import com.android.settingslib.bluetooth.LeAudioProfile
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.bluetooth.qsdialog.DeviceItemActionInteractor.LaunchSettingsCriteria.Companion.getCurrentConnectedLeByGroupId
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@SysUISingleton
class DeviceItemActionInteractor
@Inject
constructor(
    private val activityStarter: ActivityStarter,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val localBluetoothManager: LocalBluetoothManager?,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val logger: BluetoothTileDialogLogger,
    private val uiEventLogger: UiEventLogger,
) {
    private val leAudioProfile: LeAudioProfile?
        get() = localBluetoothManager?.profileManager?.leAudioProfile

    private val assistantProfile: LocalBluetoothLeBroadcastAssistant?
        get() = localBluetoothManager?.profileManager?.leAudioBroadcastAssistantProfile

    private val launchSettingsCriteriaList: List<LaunchSettingsCriteria>
        get() =
            listOf(
                InSharingClickedNoSource(localBluetoothManager, backgroundDispatcher, logger),
                NotSharingClickedNonConnect(
                    leAudioProfile,
                    assistantProfile,
                    backgroundDispatcher,
                    logger
                ),
                NotSharingClickedActive(
                    leAudioProfile,
                    assistantProfile,
                    backgroundDispatcher,
                    logger
                )
            )

    suspend fun onClick(deviceItem: DeviceItem, dialog: SystemUIDialog) {
        withContext(backgroundDispatcher) {
            logger.logDeviceClick(deviceItem.cachedBluetoothDevice.address, deviceItem.type)
            if (
                BluetoothUtils.isAudioSharingEnabled() &&
                    localBluetoothManager != null &&
                    leAudioProfile != null &&
                    assistantProfile != null
            ) {
                val inAudioSharing = BluetoothUtils.isBroadcasting(localBluetoothManager)
                logger.logDeviceClickInAudioSharingWhenEnabled(inAudioSharing)

                val criteriaMatched =
                    launchSettingsCriteriaList.firstOrNull {
                        it.matched(inAudioSharing, deviceItem)
                    }
                if (criteriaMatched != null) {
                    uiEventLogger.log(criteriaMatched.getClickUiEvent(deviceItem))
                    launchSettings(deviceItem.cachedBluetoothDevice.device, dialog)
                    return@withContext
                }
            }
            deviceItem.cachedBluetoothDevice.apply {
                when (deviceItem.type) {
                    DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE -> {
                        disconnect()
                        uiEventLogger.log(BluetoothTileDialogUiEvent.ACTIVE_DEVICE_DISCONNECT)
                    }
                    DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE -> {
                        uiEventLogger.log(BluetoothTileDialogUiEvent.AUDIO_SHARING_DEVICE_CLICKED)
                    }
                    DeviceItemType.AVAILABLE_AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE -> {
                        // TODO(b/360759048): pop up dialog
                        uiEventLogger.log(
                            BluetoothTileDialogUiEvent.AVAILABLE_AUDIO_SHARING_DEVICE_CLICKED
                        )
                    }
                    DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE -> {
                        setActive()
                        uiEventLogger.log(BluetoothTileDialogUiEvent.CONNECTED_DEVICE_SET_ACTIVE)
                    }
                    DeviceItemType.CONNECTED_BLUETOOTH_DEVICE -> {
                        disconnect()
                        uiEventLogger.log(
                            BluetoothTileDialogUiEvent.CONNECTED_OTHER_DEVICE_DISCONNECT
                        )
                    }
                    DeviceItemType.SAVED_BLUETOOTH_DEVICE -> {
                        connect()
                        uiEventLogger.log(BluetoothTileDialogUiEvent.SAVED_DEVICE_CONNECT)
                    }
                }
            }
        }
    }

    private fun launchSettings(device: BluetoothDevice, dialog: SystemUIDialog) {
        val intent =
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                putExtra(
                    EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                    Bundle().apply {
                        putParcelable(LocalBluetoothLeBroadcast.EXTRA_BLUETOOTH_DEVICE, device)
                    }
                )
            }
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        activityStarter.postStartActivityDismissingKeyguard(
            intent,
            0,
            dialogTransitionAnimator.createActivityTransitionController(dialog)
        )
    }

    private interface LaunchSettingsCriteria {
        suspend fun matched(inAudioSharing: Boolean, deviceItem: DeviceItem): Boolean

        suspend fun getClickUiEvent(deviceItem: DeviceItem): BluetoothTileDialogUiEvent

        companion object {
            suspend fun getCurrentConnectedLeByGroupId(
                leAudioProfile: LeAudioProfile,
                assistantProfile: LocalBluetoothLeBroadcastAssistant,
                @Background backgroundDispatcher: CoroutineDispatcher,
                logger: BluetoothTileDialogLogger,
            ): Map<Int, List<BluetoothDevice>> {
                return withContext(backgroundDispatcher) {
                    assistantProfile
                        .getDevicesMatchingConnectionStates(
                            intArrayOf(BluetoothProfile.STATE_CONNECTED)
                        )
                        ?.filterNotNull()
                        ?.groupBy { leAudioProfile.getGroupId(it) }
                        ?.also { logger.logConnectedLeByGroupId(it) } ?: emptyMap()
                }
            }
        }
    }

    private class InSharingClickedNoSource(
        private val localBluetoothManager: LocalBluetoothManager?,
        @Background private val backgroundDispatcher: CoroutineDispatcher,
        private val logger: BluetoothTileDialogLogger,
    ) : LaunchSettingsCriteria {
        // If currently broadcasting and the clicked device is not connected to the source
        override suspend fun matched(inAudioSharing: Boolean, deviceItem: DeviceItem): Boolean {
            return withContext(backgroundDispatcher) {
                val matched =
                    inAudioSharing &&
                        deviceItem.isMediaDevice &&
                        !BluetoothUtils.hasConnectedBroadcastSource(
                            deviceItem.cachedBluetoothDevice,
                            localBluetoothManager
                        )

                if (matched) {
                    logger.logLaunchSettingsCriteriaMatched("InSharingClickedNoSource", deviceItem)
                }

                matched
            }
        }

        override suspend fun getClickUiEvent(deviceItem: DeviceItem) =
            if (deviceItem.isLeAudioSupported)
                BluetoothTileDialogUiEvent.LAUNCH_SETTINGS_IN_SHARING_LE_DEVICE_CLICKED
            else BluetoothTileDialogUiEvent.LAUNCH_SETTINGS_IN_SHARING_NON_LE_DEVICE_CLICKED
    }

    private class NotSharingClickedNonConnect(
        private val leAudioProfile: LeAudioProfile?,
        private val assistantProfile: LocalBluetoothLeBroadcastAssistant?,
        @Background private val backgroundDispatcher: CoroutineDispatcher,
        private val logger: BluetoothTileDialogLogger,
    ) : LaunchSettingsCriteria {
        // If not broadcasting, having one device connected, and clicked on a not yet connected LE
        // audio device
        override suspend fun matched(inAudioSharing: Boolean, deviceItem: DeviceItem): Boolean {
            return withContext(backgroundDispatcher) {
                val matched =
                    leAudioProfile?.let { leAudio ->
                        assistantProfile?.let { assistant ->
                            !inAudioSharing &&
                                getCurrentConnectedLeByGroupId(
                                        leAudio,
                                        assistant,
                                        backgroundDispatcher,
                                        logger
                                    )
                                    .size == 1 &&
                                deviceItem.isNotConnectedLeAudioSupported
                        }
                    } ?: false

                if (matched) {
                    logger.logLaunchSettingsCriteriaMatched(
                        "NotSharingClickedNonConnect",
                        deviceItem
                    )
                }

                matched
            }
        }

        override suspend fun getClickUiEvent(deviceItem: DeviceItem) =
            BluetoothTileDialogUiEvent.LAUNCH_SETTINGS_NOT_SHARING_SAVED_LE_DEVICE_CLICKED
    }

    private class NotSharingClickedActive(
        private val leAudioProfile: LeAudioProfile?,
        private val assistantProfile: LocalBluetoothLeBroadcastAssistant?,
        @Background private val backgroundDispatcher: CoroutineDispatcher,
        private val logger: BluetoothTileDialogLogger,
    ) : LaunchSettingsCriteria {
        // If not broadcasting, having two device connected, clicked on the active LE audio
        // device
        override suspend fun matched(inAudioSharing: Boolean, deviceItem: DeviceItem): Boolean {
            return withContext(backgroundDispatcher) {
                val matched =
                    leAudioProfile?.let { leAudio ->
                        assistantProfile?.let { assistant ->
                            !inAudioSharing &&
                                getCurrentConnectedLeByGroupId(
                                        leAudio,
                                        assistant,
                                        backgroundDispatcher,
                                        logger
                                    )
                                    .size == 2 &&
                                deviceItem.isActiveLeAudioSupported
                        }
                    } ?: false

                if (matched) {
                    logger.logLaunchSettingsCriteriaMatched(
                        "NotSharingClickedConnected",
                        deviceItem
                    )
                }

                matched
            }
        }

        override suspend fun getClickUiEvent(deviceItem: DeviceItem) =
            BluetoothTileDialogUiEvent.LAUNCH_SETTINGS_NOT_SHARING_ACTIVE_LE_DEVICE_CLICKED
    }

    private companion object {
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        val DeviceItem.isLeAudioSupported: Boolean
            get() =
                cachedBluetoothDevice.profiles.any { profile ->
                    profile is LeAudioProfile && profile.isEnabled(cachedBluetoothDevice.device)
                }

        val DeviceItem.isNotConnectedLeAudioSupported: Boolean
            get() = type == DeviceItemType.SAVED_BLUETOOTH_DEVICE && isLeAudioSupported

        val DeviceItem.isActiveLeAudioSupported: Boolean
            get() = type == DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE && isLeAudioSupported

        val DeviceItem.isMediaDevice: Boolean
            get() =
                cachedBluetoothDevice.uiAccessibleProfiles.any {
                    it is A2dpProfile ||
                        it is HearingAidProfile ||
                        it is LeAudioProfile ||
                        it is HeadsetProfile
                }
    }
}
