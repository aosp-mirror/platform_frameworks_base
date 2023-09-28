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

package com.android.systemui.mediaprojection

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * Class that represents an area that should be captured. Currently it has only a launch cookie that
 * represents a task but we potentially could add more identifiers e.g. for a pair of tasks.
 */
data class MediaProjectionCaptureTarget(val launchCookie: IBinder?) : Parcelable {

    constructor(parcel: Parcel) : this(parcel.readStrongBinder())

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(launchCookie)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MediaProjectionCaptureTarget> {
        override fun createFromParcel(parcel: Parcel): MediaProjectionCaptureTarget {
            return MediaProjectionCaptureTarget(parcel)
        }

        override fun newArray(size: Int): Array<MediaProjectionCaptureTarget?> {
            return arrayOfNulls(size)
        }
    }
}
