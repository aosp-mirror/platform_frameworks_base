/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.text.FontConfig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a font update request. Currently only font install request is supported.
 * @hide
 */
public final class FontUpdateRequest implements Parcelable {

    public static final int TYPE_UPDATE_FONT_FILE = 0;
    public static final int TYPE_UPDATE_FONT_FAMILY = 1;

    @IntDef(prefix = "TYPE_", value = {
            TYPE_UPDATE_FONT_FILE,
            TYPE_UPDATE_FONT_FAMILY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    public static final Creator<FontUpdateRequest> CREATOR = new Creator<FontUpdateRequest>() {
        @Override
        public FontUpdateRequest createFromParcel(Parcel in) {
            return new FontUpdateRequest(in);
        }

        @Override
        public FontUpdateRequest[] newArray(int size) {
            return new FontUpdateRequest[size];
        }
    };

    private final @Type int mType;
    // NonNull if mType == TYPE_UPDATE_FONT_FILE.
    @Nullable
    private final ParcelFileDescriptor mFd;
    // NonNull if mType == TYPE_UPDATE_FONT_FILE.
    @Nullable
    private final byte[] mSignature;
    // NonNull if mType == TYPE_UPDATE_FONT_FAMILY.
    @Nullable
    private final FontConfig.FontFamily mFontFamily;

    public FontUpdateRequest(@NonNull ParcelFileDescriptor fd, @NonNull byte[] signature) {
        mType = TYPE_UPDATE_FONT_FILE;
        mFd = fd;
        mSignature = signature;
        mFontFamily = null;
    }

    public FontUpdateRequest(@NonNull FontConfig.FontFamily fontFamily) {
        mType = TYPE_UPDATE_FONT_FAMILY;
        mFd = null;
        mSignature = null;
        mFontFamily = fontFamily;
    }

    protected FontUpdateRequest(Parcel in) {
        mType = in.readInt();
        mFd = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
        mSignature = in.readBlob();
        mFontFamily = in.readParcelable(FontConfig.FontFamily.class.getClassLoader());
    }

    public @Type int getType() {
        return mType;
    }

    @Nullable
    public ParcelFileDescriptor getFd() {
        return mFd;
    }

    @Nullable
    public byte[] getSignature() {
        return mSignature;
    }

    @Nullable
    public FontConfig.FontFamily getFontFamily() {
        return mFontFamily;
    }

    @Override
    public int describeContents() {
        return mFd != null ? mFd.describeContents() : 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeParcelable(mFd, flags);
        dest.writeBlob(mSignature);
        dest.writeParcelable(mFontFamily, flags);
    }
}
