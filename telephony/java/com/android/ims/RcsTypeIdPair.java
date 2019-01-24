/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.ims;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A utility class to pass RCS IDs and types in RPC calls
 *
 * @hide
 */
public class RcsTypeIdPair implements Parcelable {
    private int mType;
    private int mId;

    public RcsTypeIdPair(int type, int id) {
        mType = type;
        mId = id;
    }

    public int getType() {
        return mType;
    }

    public void setType(int type) {
        mType = type;
    }

    public int getId() {
        return mId;
    }

    public void setId(int id) {
        mId = id;
    }

    public RcsTypeIdPair(Parcel in) {
        mType = in.readInt();
        mId = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mId);
    }

    public static final Creator<RcsTypeIdPair> CREATOR =
            new Creator<RcsTypeIdPair>() {
                @Override
                public RcsTypeIdPair createFromParcel(Parcel in) {
                    return new RcsTypeIdPair(in);
                }

                @Override
                public RcsTypeIdPair[] newArray(int size) {
                    return new RcsTypeIdPair[size];
                }
            };
}
