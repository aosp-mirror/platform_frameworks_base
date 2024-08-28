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
import android.os.Parcelable

/**
 * A data class representing a bluetooth device details config.
 *
 * @property mainContentItems The setting items to be shown in main page.
 * @property moreSettingsItems The setting items to be shown in more settings page.
 * @property moreSettingsHelpItem The help item displayed on the top right corner of the page.
 * @property extras Extra bundle
 */
data class DeviceSettingsConfig(
    val mainContentItems: List<DeviceSettingItem>,
    val moreSettingsItems: List<DeviceSettingItem>,
    val moreSettingsHelpItem: DeviceSettingItem?,
    val extras: Bundle = Bundle.EMPTY,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.run {
            writeTypedList(mainContentItems)
            writeTypedList(moreSettingsItems)
            writeParcelable(moreSettingsHelpItem, flags)
            writeBundle(extras)
        }
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<DeviceSettingsConfig> =
            object : Parcelable.Creator<DeviceSettingsConfig> {
                override fun createFromParcel(parcel: Parcel): DeviceSettingsConfig =
                    parcel.run {
                        DeviceSettingsConfig(
                            mainContentItems =
                                arrayListOf<DeviceSettingItem>().also {
                                    readTypedList(it, DeviceSettingItem.CREATOR)
                                },
                            moreSettingsItems =
                                arrayListOf<DeviceSettingItem>().also {
                                    readTypedList(it, DeviceSettingItem.CREATOR)
                                },
                            moreSettingsHelpItem = readParcelable(
                                DeviceSettingItem::class.java.classLoader
                            )
                        )
                    }

                override fun newArray(size: Int): Array<DeviceSettingsConfig?> {
                    return arrayOfNulls(size)
                }
            }
    }
}
