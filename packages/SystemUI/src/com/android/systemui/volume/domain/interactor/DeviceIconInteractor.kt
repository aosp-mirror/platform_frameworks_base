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

package com.android.systemui.volume.domain.interactor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioDeviceInfo
import com.android.settingslib.R
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.media.DeviceIconUtil
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject

/** Utility class to load an icon for a [CachedBluetoothDevice]. */
@VolumePanelScope
@SuppressLint("UseCompatLoadingForDrawables")
class DeviceIconInteractor @Inject constructor(@Application private val context: Context) {

    private val iconUtil: DeviceIconUtil = DeviceIconUtil(context)

    fun loadIcon(@AudioDeviceInfo.AudioDeviceType type: Int): Drawable? =
        context.getDrawable(iconUtil.getIconResIdFromAudioDeviceType(type))

    fun loadIcon(cachedDevice: CachedBluetoothDevice): Drawable? {
        return if (BluetoothUtils.isAdvancedUntetheredDevice(cachedDevice.device))
            context.getDrawable(R.drawable.ic_earbuds_advanced)
        else BluetoothUtils.getBtClassDrawableWithDescription(context, cachedDevice).first
    }
}
