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

package com.android.systemui.shared.media

import android.os.Parcel
import android.os.Parcelable

/**
 * A parcelable representing a nearby device that can be used for media transfer.
 *
 * This class includes:
 *   - [routeId] identifying the media route
 *   - [rangeZone] specifying how far away the device with the media route is from this device.
 */
class NearbyDevice(
    val routeId: String?,
    @RangeZone val rangeZone: Int
) : Parcelable {

    private constructor(parcel: Parcel) : this(
        routeId = parcel.readString() ?: null,
        rangeZone = parcel.readInt()
    )

    override fun describeContents() = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(routeId)
        out.writeInt(rangeZone)
    }

    companion object CREATOR : Parcelable.Creator<NearbyDevice?> {
        override fun createFromParcel(parcel: Parcel) = NearbyDevice(parcel)
        override fun newArray(size: Int) = arrayOfNulls<NearbyDevice?>(size)
    }
}
