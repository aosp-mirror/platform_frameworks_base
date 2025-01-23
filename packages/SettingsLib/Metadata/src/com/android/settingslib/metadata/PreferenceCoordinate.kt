/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.metadata

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Coordinate to locate a preference.
 *
 * Within an app, the preference screen coordinate (unique among screens) plus preference key
 * (unique on the screen) is used to locate a preference.
 */
open class PreferenceCoordinate : PreferenceScreenCoordinate {
    val key: String

    constructor(screenKey: String, key: String) : this(screenKey, null, key)

    constructor(screenKey: String, args: Bundle?, key: String) : super(screenKey, args) {
        this.key = key
    }

    constructor(parcel: Parcel) : super(parcel) {
        this.key = parcel.readString()!!
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        super.writeToParcel(parcel, flags)
        parcel.writeString(key)
    }

    override fun describeContents() = 0

    override fun equals(other: Any?) =
        super.equals(other) && key == (other as PreferenceCoordinate).key

    override fun hashCode() = super.hashCode() xor key.hashCode()

    companion object CREATOR : Parcelable.Creator<PreferenceCoordinate> {

        override fun createFromParcel(parcel: Parcel) = PreferenceCoordinate(parcel)

        override fun newArray(size: Int) = arrayOfNulls<PreferenceCoordinate>(size)
    }
}

/** Coordinate to locate a preference screen. */
open class PreferenceScreenCoordinate : Parcelable {
    /** Unique preference screen key. */
    val screenKey: String

    /** Arguments to create parameterized preference screen. */
    val args: Bundle?

    constructor(screenKey: String, args: Bundle?) {
        this.screenKey = screenKey
        this.args = args
    }

    constructor(parcel: Parcel) {
        screenKey = parcel.readString()!!
        args = parcel.readBundle(javaClass.classLoader)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(screenKey)
        parcel.writeBundle(args)
    }

    override fun describeContents() = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PreferenceScreenCoordinate
        return screenKey == other.screenKey && args.contentEquals(other.args)
    }

    // "args" is not included intentionally, otherwise we need to take care of array, etc.
    override fun hashCode() = screenKey.hashCode()

    companion object CREATOR : Parcelable.Creator<PreferenceScreenCoordinate> {

        override fun createFromParcel(parcel: Parcel) = PreferenceScreenCoordinate(parcel)

        override fun newArray(size: Int) = arrayOfNulls<PreferenceScreenCoordinate>(size)
    }
}
