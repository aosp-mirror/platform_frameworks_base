/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes the way to store scan result.
 *
 * @hide
 */
@SystemApi
public final class ResultStorageDescriptor implements Parcelable {
    private int mType;
    private int mOffset;
    private int mLength;

    public int getType() {
        return mType;
    }

    public int getOffset() {
        return mOffset;
    }

    public int getLength() {
        return mLength;
    }

    /**
     * Constructor of {@link ResultStorageDescriptor}
     *
     * @param type Type of the data.
     * @param offset Offset from start of the advertise packet payload.
     * @param length Byte length of the data
     */
    public ResultStorageDescriptor(int type, int offset, int length) {
        mType = type;
        mOffset = offset;
        mLength = length;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mOffset);
        dest.writeInt(mLength);
    }

    private ResultStorageDescriptor(Parcel in) {
        ReadFromParcel(in);
    }

    private void ReadFromParcel(Parcel in) {
        mType = in.readInt();
        mOffset = in.readInt();
        mLength = in.readInt();
    }

    public static final Parcelable.Creator<ResultStorageDescriptor>
            CREATOR = new Creator<ResultStorageDescriptor>() {
                    @Override
                public ResultStorageDescriptor createFromParcel(Parcel source) {
                    return new ResultStorageDescriptor(source);
                }

                    @Override
                public ResultStorageDescriptor[] newArray(int size) {
                    return new ResultStorageDescriptor[size];
                }
            };
}
