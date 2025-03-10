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

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.graphics.PixelFormat;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Metadata of ambient backlight.
 *
 * <p>A metadata instance is sent from ambient backlight hardware in a {@link AmbientBacklightEvent}
 * with {@link AmbientBacklightEvent#AMBIENT_BACKLIGHT_EVENT_METADATA}.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public final class AmbientBacklightMetadata implements Parcelable {
    @NonNull
    private final String mPackageName;
    private final int mCompressAlgorithm;
    private final int mSource;
    private final int mColorFormat;
    private final int mHorizontalZonesNumber;
    private final int mVerticalZonesNumber;
    @NonNull
    private final int[] mZonesColors;

    /**
     * Constructs AmbientBacklightMetadata.
     */
    public AmbientBacklightMetadata(
            @NonNull String packageName,
            @AmbientBacklightSettings.CompressAlgorithm int compressAlgorithm,
            @AmbientBacklightSettings.Source int source,
            @PixelFormat.Format int colorFormat,
            int horizontalZonesNumber,
            int verticalZonesNumber,
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

    /**
     * Gets package name of the metadata.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Gets compress algorithm.
     */
    @AmbientBacklightSettings.CompressAlgorithm
    public int getCompressAlgorithm() {
        return mCompressAlgorithm;
    }

    /**
     * Gets source of ambient backlight detection.
     */
    @AmbientBacklightSettings.Source
    public int getSource() {
        return mSource;
    }

    /**
     * Gets color format.
     */
    @PixelFormat.Format
    public int getColorFormat() {
        return mColorFormat;
    }

    /**
     * Gets the number of horizontal color zones.
     *
     * <p>A color zone is represented by one single aggregated color. The number should not be
     * larger than 128.
     */
    @IntRange(from = 0, to = 128)
    public int getHorizontalZonesNumber() {
        return mHorizontalZonesNumber;
    }

    /**
     * Gets the number of vertical color zones.
     *
     * <p>A color zone is represented by one single aggregated color. The number should not be
     * larger than 80.
     */
    @IntRange(from = 0, to = 80)
    public int getVerticalZonesNumber() {
        return mVerticalZonesNumber;
    }

    /**
     * Gets color data of all available color zones.
     *
     * <p>The format of the color data can be found at {@link #getColorFormat()}.
     *
     * @return an array of color data, in row by row (left-to-right then top-to-bottom) order of the
     * color zones.
     *
     * @see #getHorizontalZonesNumber()
     * @see #getVerticalZonesNumber()
     */
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
