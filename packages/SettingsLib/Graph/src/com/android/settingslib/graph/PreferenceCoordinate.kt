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

package com.android.settingslib.graph

import android.os.Parcel
import android.os.Parcelable

/**
 * Coordinate to locate a preference.
 *
 * Within an app, the preference screen key (unique among screens) plus preference key (unique on
 * the screen) is used to locate a preference.
 */
data class PreferenceCoordinate(val screenKey: String, val key: String) : Parcelable {

    constructor(parcel: Parcel) : this(parcel.readString()!!, parcel.readString()!!)

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(screenKey)
        parcel.writeString(key)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<PreferenceCoordinate> {

        override fun createFromParcel(parcel: Parcel) = PreferenceCoordinate(parcel)

        override fun newArray(size: Int) = arrayOfNulls<PreferenceCoordinate>(size)
    }
}
