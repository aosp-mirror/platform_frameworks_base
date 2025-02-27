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
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@SysUISingleton
class AudioSharingDeviceItemActionInteractorImpl
@Inject
constructor(
    private val activityStarter: ActivityStarter,
    private val audioSharingInteractor: AudioSharingInteractor,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val localBluetoothManager: LocalBluetoothManager?,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val logger: BluetoothTileDialogLogger,
    private val uiEventLogger: UiEventLogger,
    private val delegateFactory: AudioSharingDialogDelegate.Factory,
    private val deviceItemActionInteractorImpl: DeviceItemActionInteractorImpl,
) : DeviceItemActionInteractor {

    override suspend fun onClick(deviceItem: DeviceItem, dialog: SystemUIDialog) {
        withContext(backgroundDispatcher) {
            if (!audioSharingInteractor.audioSharingAvailable()) {
                return@withContext deviceItemActionInteractorImpl.onClick(deviceItem, dialog)
            }
            val inAudioSharing = BluetoothUtils.isBroadcasting(localBluetoothManager)
            logger.logDeviceClickInAudioSharingWhenEnabled(inAudioSharing)

            when {
                deviceItem.type == DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE -> {
                    // Do nothing if the device is in audio sharing session
                    uiEventLogger.log(BluetoothTileDialogUiEvent.AUDIO_SHARING_DEVICE_CLICKED)
                }
                deviceItem.type ==
                    DeviceItemType.AVAILABLE_AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE -> {
                    if (audioSharingInteractor.qsDialogImprovementAvailable()) {
                        withContext(mainDispatcher) {
                            delegateFactory
                                .create(deviceItem.cachedBluetoothDevice)
                                .createDialog()
                                .let { dialogTransitionAnimator.showFromDialog(it, dialog) }
                        }
                    } else {
                        launchSettings(deviceItem.cachedBluetoothDevice.device, dialog)
                        logger.logLaunchSettingsCriteriaMatched(
                            "AvailableAudioSharingDeviceClicked",
                            deviceItem,
                        )
                    }
                    uiEventLogger.log(
                        BluetoothTileDialogUiEvent.AVAILABLE_AUDIO_SHARING_DEVICE_CLICKED
                    )
                }
                inSharingAndDeviceNoSource(inAudioSharing, deviceItem) -> {
                    launchSettings(deviceItem.cachedBluetoothDevice.device, dialog)
                    logger.logLaunchSettingsCriteriaMatched("InSharingClickedNoSource", deviceItem)
                    uiEventLogger.log(
                        if (deviceItem.isLeAudioSupported)
                            BluetoothTileDialogUiEvent.LAUNCH_SETTINGS_IN_SHARING_LE_DEVICE_CLICKED
                        else
                            BluetoothTileDialogUiEvent
                                .LAUNCH_SETTINGS_IN_SHARING_NON_LE_DEVICE_CLICKED
                    )
                }
                else -> {
                    deviceItemActionInteractorImpl.onClick(deviceItem, dialog)
                }
            }
        }
    }

    private fun inSharingAndDeviceNoSource(
        inAudioSharing: Boolean,
        deviceItem: DeviceItem,
    ): Boolean {
        return inAudioSharing &&
            deviceItem.isMediaDevice &&
            !BluetoothUtils.hasConnectedBroadcastSource(
                deviceItem.cachedBluetoothDevice,
                localBluetoothManager,
            )
    }

    private fun launchSettings(device: BluetoothDevice, dialog: SystemUIDialog) {
        val intent =
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                putExtra(
                    EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                    Bundle().apply {
                        putParcelable(LocalBluetoothLeBroadcast.EXTRA_BLUETOOTH_DEVICE, device)
                    },
                )
            }
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        activityStarter.postStartActivityDismissingKeyguard(
            intent,
            0,
            dialogTransitionAnimator.createActivityTransitionController(dialog),
        )
    }

    private companion object {
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        val DeviceItem.isLeAudioSupported: Boolean
            get() =
                cachedBluetoothDevice.profiles.any { profile ->
                    profile is LeAudioProfile && profile.isEnabled(cachedBluetoothDevice.device)
                }

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
