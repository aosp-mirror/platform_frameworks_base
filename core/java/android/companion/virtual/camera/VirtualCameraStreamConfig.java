/*
 * Copyright (C) 2023 The Android Open Source Project
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
package android.companion.virtual.camera;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtual.flags.Flags;
import android.graphics.ImageFormat;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The configuration of a single virtual camera stream.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
public final class VirtualCameraStreamConfig implements Parcelable {

    private final int mWidth;
    private final int mHeight;
    private final int mFormat;

    /**
     * Construct a new instance of {@link VirtualCameraStreamConfig} initialized with the provided
     * width, height and {@link ImageFormat}
     *
     * @param width The width of the stream.
     * @param height The height of the stream.
     * @param format The {@link ImageFormat} of the stream.
     */
    public VirtualCameraStreamConfig(
            @IntRange(from = 1) int width,
            @IntRange(from = 1) int height,
            @ImageFormat.Format int format) {
        this.mWidth = width;
        this.mHeight = height;
        this.mFormat = format;
    }

    private VirtualCameraStreamConfig(@NonNull Parcel in) {
        mWidth = in.readInt();
        mHeight = in.readInt();
        mFormat = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        dest.writeInt(mFormat);
    }

    @NonNull
    public static final Creator<VirtualCameraStreamConfig> CREATOR =
            new Creator<>() {
                @Override
                public VirtualCameraStreamConfig createFromParcel(Parcel in) {
                    return new VirtualCameraStreamConfig(in);
                }

                @Override
                public VirtualCameraStreamConfig[] newArray(int size) {
                    return new VirtualCameraStreamConfig[size];
                }
            };

    /** Returns the width of this stream. */
    @IntRange(from = 1)
    public int getWidth() {
        return mWidth;
    }

    /** Returns the height of this stream. */
    @IntRange(from = 1)
    public int getHeight() {
        return mHeight;
    }

    /** Returns the {@link ImageFormat} of this stream. */
    @ImageFormat.Format
    public int getFormat() {
        return mFormat;
    }
}
