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
import android.os.Handler
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject

/** ViewModel for Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
internal class BluetoothTileDialogViewModel
@Inject
constructor(
    private val deviceItemInteractor: DeviceItemInteractor,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    @Main private val uiHandler: Handler
) : DeviceItemOnClickCallback {
    private var deviceItems: List<DeviceItem> = emptyList()

    @VisibleForTesting
    var dialog: BluetoothTileDialog? = null

    /**
     * Shows the dialog.
     *
     * @param context The context in which the dialog is displayed.
     * @param view The view from which the dialog is shown.
     */
    fun showDialog(context: Context, view: View?) {
        dismissDialog()

        deviceItems = deviceItemInteractor.getDeviceItems(context)

        uiHandler.post {
            dialog = BluetoothTileDialog(deviceItems, this, context)

            view?.let { dialogLaunchAnimator.showFromView(dialog!!, it) } ?: dialog!!.show()
        }
    }

    override fun onDeviceItemClicked(deviceItem: DeviceItem, position: Int) {
        if (deviceItemInteractor.updateDeviceItemOnClick(deviceItem)) {
            dialog?.onDeviceItemUpdated(deviceItem, position)
        }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }
}

internal interface DeviceItemOnClickCallback {
    fun onDeviceItemClicked(deviceItem: DeviceItem, position: Int)
}
