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
 * A data class representing a device settings item in bluetooth device details config.
 *
 * @property enabled Whether the service is enabled.
 * @property extras Extra bundle
 */
data class DeviceSettingsProviderServiceStatus(
    val enabled: Boolean,
    val extras: Bundle = Bundle.EMPTY,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.run {
            writeBoolean(enabled)
            writeBundle(extras)
        }
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<DeviceSettingsProviderServiceStatus> =
            object : Parcelable.Creator<DeviceSettingsProviderServiceStatus> {
                override fun createFromParcel(parcel: Parcel) =
                    parcel.run {
                        DeviceSettingsProviderServiceStatus(
                            enabled = readBoolean(),
                            extras = readBundle((Bundle::class.java.classLoader)) ?: Bundle.EMPTY,
                        )
                    }

                override fun newArray(size: Int): Array<DeviceSettingsProviderServiceStatus?> {
                    return arrayOfNulls(size)
                }
            }
    }
}
