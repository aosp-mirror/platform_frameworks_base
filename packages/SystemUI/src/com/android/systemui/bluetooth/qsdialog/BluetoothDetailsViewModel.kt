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

import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.res.R

class BluetoothDetailsViewModel(onLongClick: () -> Unit) : TileDetailsViewModel() {
    private val _onLongClick = onLongClick

    @Composable
    override fun GetContentView() {
        AndroidView(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            factory = { context ->
                // Inflate with the existing dialog xml layout
                LayoutInflater.from(context).inflate(R.layout.bluetooth_tile_dialog, null)
                // TODO: b/378513956 - Implement the bluetooth details view
            },
        )
    }

    override fun clickOnSettingsButton() {
        _onLongClick()
    }

    override fun getTitle(): String {
        // TODO: b/378513956 Update the placeholder text
        return "Bluetooth"
    }

    override fun getSubTitle(): String {
        // TODO: b/378513956 Update the placeholder text
        return "Tap to connect or disconnect a device"
    }
}
