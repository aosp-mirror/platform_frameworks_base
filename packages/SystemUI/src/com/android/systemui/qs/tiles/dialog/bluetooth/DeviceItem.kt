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

import android.graphics.drawable.Drawable
import com.android.settingslib.bluetooth.CachedBluetoothDevice

enum class DeviceItemType {
    ACTIVE_MEDIA_BLUETOOTH_DEVICE,
    AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
    CONNECTED_BLUETOOTH_DEVICE,
    SAVED_BLUETOOTH_DEVICE,
}

data class DeviceItem(
    val type: DeviceItemType,
    val cachedBluetoothDevice: CachedBluetoothDevice,
    val deviceName: String = "",
    val connectionSummary: String = "",
    val iconWithDescription: Pair<Drawable, String>? = null,
    val background: Int? = null,
    var isEnabled: Boolean = true,
    var actionAccessibilityLabel: String = "",
)
