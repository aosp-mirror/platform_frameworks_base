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
package android.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Paint;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Results returned from a Font Provider to the system.
 * @hide
 */
public final class FontResult implements Parcelable {
    private final ParcelFileDescriptor mFileDescriptor;
    private final int mTtcIndex;
    private final String mFontVariationSettings;
    private final int mStyle;

    /**
     * Creates a FontResult with all the information needed about a provided font.
     * @param fileDescriptor A ParcelFileDescriptor pointing to the font file. This shoult point to
     *                       a real file or shared memory, as the client will mmap the given file
     *                       descriptor. Pipes, sockets and other non-mmap-able file descriptors
     *                       will fail to load in the client application.
     * @param ttcIndex If providing a TTC_INDEX file, the index to point to. Otherwise, 0.
     * @param fontVariationSettings If providing a variation font, the settings for it. May be null.
     * @param style One of {@link android.graphics.Typeface#NORMAL},
     *              {@link android.graphics.Typeface#BOLD}, {@link android.graphics.Typeface#ITALIC}
     *              or {@link android.graphics.Typeface#BOLD_ITALIC}
     */
    public FontResult(@NonNull ParcelFileDescriptor fileDescriptor, int ttcIndex,
            @Nullable String fontVariationSettings, int style) {
        mFileDescriptor = Preconditions.checkNotNull(fileDescriptor);
        mTtcIndex = ttcIndex;
        mFontVariationSettings = fontVariationSettings;
        mStyle = style;
    }

    public ParcelFileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }

    public int getTtcIndex() {
        return mTtcIndex;
    }

    public String getFontVariationSettings() {
        return mFontVariationSettings;
    }

    public int getStyle() {
        return mStyle;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mFileDescriptor, flags);
        dest.writeInt(mTtcIndex);
        dest.writeString(mFontVariationSettings);
        dest.writeInt(mStyle);
    }

    private FontResult(Parcel in) {
        mFileDescriptor = in.readParcelable(null);
        mTtcIndex = in.readInt();
        mFontVariationSettings = in.readString();
        mStyle = in.readInt();
    }

    public static final Parcelable.Creator<FontResult> CREATOR =
            new Parcelable.Creator<FontResult>() {
                @Override
                public FontResult createFromParcel(Parcel in) {
                    return new FontResult(in);
                }

                @Override
                public FontResult[] newArray(int size) {
                    return new FontResult[size];
                }
            };
}
