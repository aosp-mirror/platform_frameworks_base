/*
 * Copyright 2023 The Android Open Source Project
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

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * The configuration of a single virtual camera stream.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
public final class VirtualCameraStreamConfig implements Parcelable {
    // TODO(b/310857519): Check if we should increase the fps upper limit in future.
    static final int MAX_FPS_UPPER_LIMIT = 60;

    private final int mWidth;
    private final int mHeight;
    private final int mFormat;
    private final int mMaxFps;

    /**
     * Construct a new instance of {@link VirtualCameraStreamConfig} initialized with the provided
     * width, height and {@link ImageFormat}.
     *
     * @param width The width of the stream.
     * @param height The height of the stream.
     * @param format The {@link ImageFormat} of the stream.
     * @param maxFps The maximum frame rate (in frames per second) for the stream.
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public VirtualCameraStreamConfig(
            @IntRange(from = 1) int width,
            @IntRange(from = 1) int height,
            @ImageFormat.Format int format,
            @IntRange(from = 1) int maxFps) {
        this.mWidth = width;
        this.mHeight = height;
        this.mFormat = format;
        this.mMaxFps = maxFps;
    }

    private VirtualCameraStreamConfig(@NonNull Parcel in) {
        mWidth = in.readInt();
        mHeight = in.readInt();
        mFormat = in.readInt();
        mMaxFps = in.readInt();
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
        dest.writeInt(mMaxFps);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VirtualCameraStreamConfig that = (VirtualCameraStreamConfig) o;
        return mWidth == that.mWidth && mHeight == that.mHeight && mFormat == that.mFormat
                && mMaxFps == that.mMaxFps;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mWidth, mHeight, mFormat, mMaxFps);
    }

    /** Returns the {@link ImageFormat} of this stream. */
    @ImageFormat.Format
    public int getFormat() {
        return mFormat;
    }

    /** Returns the maximum frame rate (in frames per second) of this stream. */
    @IntRange(from = 1)
    public int getMaximumFramesPerSecond() {
        return mMaxFps;
    }
}
