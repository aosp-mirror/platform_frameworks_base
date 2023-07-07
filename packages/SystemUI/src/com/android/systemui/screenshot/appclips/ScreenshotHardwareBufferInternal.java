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

package com.android.systemui.screenshot.appclips;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ParcelableColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Parcel;
import android.os.Parcelable;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

/**
 * An internal version of {@link ScreenshotHardwareBuffer} that helps with parceling the information
 * necessary for creating a {@link Bitmap}.
 */
class ScreenshotHardwareBufferInternal implements Parcelable {

    public static final Creator<ScreenshotHardwareBufferInternal> CREATOR =
            new Creator<>() {
                @Override
                public ScreenshotHardwareBufferInternal createFromParcel(Parcel in) {
                    return new ScreenshotHardwareBufferInternal(in);
                }

                @Override
                public ScreenshotHardwareBufferInternal[] newArray(int size) {
                    return new ScreenshotHardwareBufferInternal[size];
                }
            };
    private final HardwareBuffer mHardwareBuffer;
    private final ParcelableColorSpace mParcelableColorSpace;

    ScreenshotHardwareBufferInternal(
            ScreenshotHardwareBuffer screenshotHardwareBuffer) {
        mHardwareBuffer = screenshotHardwareBuffer.getHardwareBuffer();
        mParcelableColorSpace = new ParcelableColorSpace(
                screenshotHardwareBuffer.getColorSpace());
    }

    private ScreenshotHardwareBufferInternal(Parcel in) {
        mHardwareBuffer = in.readParcelable(HardwareBuffer.class.getClassLoader(),
                HardwareBuffer.class);
        mParcelableColorSpace = in.readParcelable(ParcelableColorSpace.class.getClassLoader(),
                ParcelableColorSpace.class);
    }

    /**
     * Returns a {@link Bitmap} represented by the underlying data and successively closes the
     * internal {@link HardwareBuffer}. See,
     * {@link Bitmap#wrapHardwareBuffer(HardwareBuffer, ColorSpace)} and
     * {@link HardwareBuffer#close()} for more information.
     */
    Bitmap createBitmapThenCloseBuffer() {
        Bitmap bitmap = Bitmap.wrapHardwareBuffer(mHardwareBuffer,
                mParcelableColorSpace.getColorSpace());
        mHardwareBuffer.close();
        return bitmap;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mHardwareBuffer, flags);
        dest.writeParcelable(mParcelableColorSpace, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ScreenshotHardwareBufferInternal)) {
            return false;
        }

        ScreenshotHardwareBufferInternal other = (ScreenshotHardwareBufferInternal) obj;
        return mHardwareBuffer.equals(other.mHardwareBuffer) && mParcelableColorSpace.equals(
                other.mParcelableColorSpace);
    }
}
