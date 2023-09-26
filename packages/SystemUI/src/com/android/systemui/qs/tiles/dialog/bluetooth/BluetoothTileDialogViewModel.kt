/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialog.Companion.ACTION_BLUETOOTH_DEVICE_DETAILS
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialog.Companion.ACTION_PAIR_NEW_DEVICE
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialog.Companion.ACTION_PREVIOUSLY_CONNECTED_DEVICE
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialog.Companion.MAX_DEVICE_ITEM_ENTRY
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** ViewModel for Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
internal class BluetoothTileDialogViewModel
@Inject
constructor(
    private val deviceItemInteractor: DeviceItemInteractor,
    private val bluetoothStateInteractor: BluetoothStateInteractor,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val activityStarter: ActivityStarter,
    private val uiEventLogger: UiEventLogger,
    @Application private val coroutineScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : BluetoothTileDialogCallback {

    private var job: Job? = null

    @VisibleForTesting internal var dialog: BluetoothTileDialog? = null

    /**
     * Shows the dialog.
     *
     * @param context The context in which the dialog is displayed.
     * @param view The view from which the dialog is shown.
     */
    fun showDialog(context: Context, view: View?) {
        dismissDialog()

        var updateDeviceItemJob: Job? = null

        job =
            coroutineScope.launch(mainDispatcher) {
                dialog = createBluetoothTileDialog(context)
                view?.let { dialogLaunchAnimator.showFromView(dialog!!, it) } ?: dialog!!.show()
                updateDeviceItemJob?.cancel()
                updateDeviceItemJob = launch { deviceItemInteractor.updateDeviceItems(context) }

                bluetoothStateInteractor.updateBluetoothStateFlow
                    .filterNotNull()
                    .onEach {
                        dialog!!.onBluetoothStateUpdated(it)
                        updateDeviceItemJob?.cancel()
                        updateDeviceItemJob = launch {
                            deviceItemInteractor.updateDeviceItems(context)
                        }
                    }
                    .launchIn(this)

                deviceItemInteractor.updateDeviceItemsFlow
                    .onEach {
                        updateDeviceItemJob?.cancel()
                        updateDeviceItemJob = launch {
                            deviceItemInteractor.updateDeviceItems(context)
                        }
                    }
                    .launchIn(this)

                deviceItemInteractor.deviceItemFlow
                    .filterNotNull()
                    .onEach {
                        dialog!!.onDeviceItemUpdated(
                            it.take(MAX_DEVICE_ITEM_ENTRY),
                            showSeeAll = it.size > MAX_DEVICE_ITEM_ENTRY,
                            showPairNewDevice = bluetoothStateInteractor.isBluetoothEnabled
                        )
                    }
                    .launchIn(this)

                dialog!!
                    .bluetoothStateSwitchedFlow
                    .onEach { bluetoothStateInteractor.isBluetoothEnabled = it }
                    .launchIn(this)

                dialog!!
                    .deviceItemClickedFlow
                    .onEach {
                        if (deviceItemInteractor.updateDeviceItemOnClick(it.first)) {
                            dialog!!.onDeviceItemUpdatedAtPosition(it.first, it.second)
                        }
                    }
                    .launchIn(this)
            }
    }

    private fun createBluetoothTileDialog(context: Context): BluetoothTileDialog {
        return BluetoothTileDialog(
                bluetoothStateInteractor.isBluetoothEnabled,
                this@BluetoothTileDialogViewModel,
                uiEventLogger,
                context
            )
            .apply { SystemUIDialog.registerDismissListener(this) { dismissDialog() } }
    }

    override fun onDeviceItemGearClicked(deviceItem: DeviceItem, view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.DEVICE_GEAR_CLICKED)
        val intent =
            Intent(ACTION_BLUETOOTH_DEVICE_DETAILS).apply {
                putExtra(
                    ":settings:show_fragment_args",
                    Bundle().apply {
                        putString("device_address", deviceItem.cachedBluetoothDevice.address)
                    }
                )
            }
        startSettingsActivity(intent, view)
    }

    override fun onSeeAllClicked(view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.SEE_ALL_CLICKED)
        startSettingsActivity(Intent(ACTION_PREVIOUSLY_CONNECTED_DEVICE), view)
    }

    override fun onPairNewDeviceClicked(view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.PAIR_NEW_DEVICE_CLICKED)
        startSettingsActivity(Intent(ACTION_PAIR_NEW_DEVICE), view)
    }

    private fun dismissDialog() {
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
    }

    private fun startSettingsActivity(intent: Intent, view: View) {
        dialog?.run {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            activityStarter.postStartActivityDismissingKeyguard(
                intent,
                0,
                dialogLaunchAnimator.createActivityLaunchController(view)
            )
        }
    }
}

internal interface BluetoothTileDialogCallback {
    fun onDeviceItemGearClicked(deviceItem: DeviceItem, view: View)
    fun onSeeAllClicked(view: View)
    fun onPairNewDeviceClicked(view: View)
}
