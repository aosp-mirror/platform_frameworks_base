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

package android.media.quality;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * @hide
 */
public class AmbientBacklightMetadata implements Parcelable {
    @NonNull
    private final String mPackageName;
    private final int mCompressAlgorithm;
    private final int mSource;
    private final int mColorFormat;
    private final int mHorizontalZonesNumber;
    private final int mVerticalZonesNumber;
    @NonNull
    private final int[] mZonesColors;

    public AmbientBacklightMetadata(@NonNull String packageName, int compressAlgorithm,
            int source, int colorFormat, int horizontalZonesNumber, int verticalZonesNumber,
            @NonNull int[] zonesColors) {
        mPackageName = packageName;
        mCompressAlgorithm = compressAlgorithm;
        mSource = source;
        mColorFormat = colorFormat;
        mHorizontalZonesNumber = horizontalZonesNumber;
        mVerticalZonesNumber = verticalZonesNumber;
        mZonesColors = zonesColors;
    }

    private AmbientBacklightMetadata(Parcel in) {
        mPackageName = in.readString();
        mCompressAlgorithm = in.readInt();
        mSource = in.readInt();
        mColorFormat = in.readInt();
        mHorizontalZonesNumber = in.readInt();
        mVerticalZonesNumber = in.readInt();
        mZonesColors = in.createIntArray();
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    public int getCompressAlgorithm() {
        return mCompressAlgorithm;
    }

    public int getSource() {
        return mSource;
    }

    public int getColorFormat() {
        return mColorFormat;
    }

    public int getHorizontalZonesNumber() {
        return mHorizontalZonesNumber;
    }

    public int getVerticalZonesNumber() {
        return mVerticalZonesNumber;
    }

    @NonNull
    public int[] getZonesColors() {
        return mZonesColors;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeInt(mCompressAlgorithm);
        dest.writeInt(mSource);
        dest.writeInt(mColorFormat);
        dest.writeInt(mHorizontalZonesNumber);
        dest.writeInt(mVerticalZonesNumber);
        dest.writeIntArray(mZonesColors);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Parcelable.Creator<AmbientBacklightMetadata> CREATOR =
            new Parcelable.Creator<AmbientBacklightMetadata>() {
                public AmbientBacklightMetadata createFromParcel(Parcel in) {
                    return new AmbientBacklightMetadata(in);
                }

                public AmbientBacklightMetadata[] newArray(int size) {
                    return new AmbientBacklightMetadata[size];
                }
            };

    @Override
    public String toString() {
        return "AmbientBacklightMetadata{packageName=" + mPackageName
                + ", compressAlgorithm=" + mCompressAlgorithm + ", source=" + mSource
                + ", colorFormat=" + mColorFormat + ", horizontalZonesNumber="
                + mHorizontalZonesNumber + ", verticalZonesNumber=" + mVerticalZonesNumber
                + ", zonesColors=" + Arrays.toString(mZonesColors) + "}";
    }
}
