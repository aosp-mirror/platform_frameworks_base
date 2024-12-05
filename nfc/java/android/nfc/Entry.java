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
package android.nfc;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;


/** @hide */
public final class Entry implements Parcelable {
    private final byte mType;
    private final byte mNfceeId;
    private final String mEntry;
    private final String mRoutingType;

    public Entry(String entry, byte type, byte nfceeId, String routingType) {
        mEntry = entry;
        mType = type;
        mNfceeId = nfceeId;
        mRoutingType = routingType;
    }

    public byte getType() {
        return mType;
    }

    public byte getNfceeId() {
        return mNfceeId;
    }

    public String getEntry() {
        return mEntry;
    }

    public String getRoutingType() {
        return mRoutingType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private Entry(Parcel in) {
        this.mEntry = in.readString();
        this.mNfceeId = in.readByte();
        this.mType = in.readByte();
        this.mRoutingType = in.readString();
    }

    public static final @NonNull Parcelable.Creator<Entry> CREATOR =
            new Parcelable.Creator<Entry>() {
                @Override
                public Entry createFromParcel(Parcel in) {
                    return new Entry(in);
                }

                @Override
                public Entry[] newArray(int size) {
                    return new Entry[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mEntry);
        dest.writeByte(mNfceeId);
        dest.writeByte(mType);
        dest.writeString(mRoutingType);
    }
}
