/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi;

import android.os.Parcelable;
import android.text.TextUtils;
import android.os.Parcel;

import java.util.Arrays;
import java.util.Objects;

/**
 * A class representing icon information.
 */
public final class IconInfo implements Parcelable {
    /**
     * Name of the icon file.
     */
    private final String mFilename;

    /**
     * Raw binary data of the icon.
     */
    private final byte[] mData;

    public IconInfo(String filename, byte[] data) {
        mFilename = filename;
        mData = data;
    }

    public IconInfo(IconInfo source) {
        if (source == null) {
            mFilename = null;
            mData = null;
            return;
        }

        mFilename = source.mFilename;
        if (source.mData != null) {
            mData = Arrays.copyOf(source.mData, source.mData.length);
        } else {
            mData = null;
        }
    }

    public String getFilename() {
        return mFilename;
    }

    public byte[] getData() {
        return mData;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof IconInfo)) {
            return false;
        }
        IconInfo that = (IconInfo) thatObject;
        return TextUtils.equals(mFilename, that.mFilename)
                && Arrays.equals(mData, that.mData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFilename, mData);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mFilename);
        dest.writeByteArray(mData);
    }

    public static final Creator<IconInfo> CREATOR =
        new Creator<IconInfo>() {
            @Override
            public IconInfo createFromParcel(Parcel in) {
                String filename = in.readString();
                byte[] data = in.createByteArray();
                return new IconInfo(filename, data);
            }

            @Override
            public IconInfo[] newArray(int size) {
                return new IconInfo[size];
            }
        };
}
