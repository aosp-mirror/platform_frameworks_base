/*
 * Copyright 2014, The Android Open Source Project
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

package com.android.internal.util;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Helper class to adapt a simple String to cases where a Parcelable is expected.
 * @hide
 */
public class ParcelableString implements Parcelable {
    public String string;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(string);
    }

    public static final Parcelable.Creator<ParcelableString> CREATOR =
            new Parcelable.Creator<ParcelableString>() {
                @Override
                public ParcelableString createFromParcel(Parcel in) {
                    ParcelableString ret = new ParcelableString();
                    ret.string = in.readString();
                    return ret;
                }
                @Override
                public ParcelableString[] newArray(int size) {
                    return new ParcelableString[size];
                }
    };
}