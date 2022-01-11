/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shared.mediattt

import android.os.Parcel
import android.os.Parcelable

/**
 * Represents a device that can send or receive media. Includes any device information necessary for
 * SysUI to display an informative chip to the user.
 */
class DeviceInfo(val name: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString())

    override fun writeToParcel(dest: Parcel?, flags: Int) {
        dest?.writeString(name)
    }

    override fun describeContents() = 0

    override fun toString() = "name: $name"

    companion object CREATOR : Parcelable.Creator<DeviceInfo> {
        override fun createFromParcel(parcel: Parcel) = DeviceInfo(parcel)
        override fun newArray(size: Int) = arrayOfNulls<DeviceInfo?>(size)
    }
}
