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

package com.android.settingslib.bluetooth.devicesettings

import android.os.Bundle
import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceSettingItemTest {

    @Test
    fun parcelOperation() {
        val item =
            DeviceSettingItem(
                settingId = 1,
                packageName = "package_name",
                className = "class_name",
                intentAction = "intent_action",
                preferenceKey = "key1",
                highlighted = true,
                extras = Bundle().apply { putString("key1", "value1") },
            )

        val fromParcel = writeAndRead(item)

        assertThat(fromParcel.settingId).isEqualTo(item.settingId)
        assertThat(fromParcel.packageName).isEqualTo(item.packageName)
        assertThat(fromParcel.className).isEqualTo(item.className)
        assertThat(fromParcel.intentAction).isEqualTo(item.intentAction)
        assertThat(fromParcel.preferenceKey).isEqualTo(item.preferenceKey)
        assertThat(fromParcel.extras.getString("key1")).isEqualTo(item.extras.getString("key1"))
    }

    private fun writeAndRead(item: DeviceSettingItem): DeviceSettingItem {
        val parcel = Parcel.obtain()
        item.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        return DeviceSettingItem.CREATOR.createFromParcel(parcel)
    }
}
