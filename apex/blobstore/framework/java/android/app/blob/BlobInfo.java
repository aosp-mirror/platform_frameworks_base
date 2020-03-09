/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app.blob;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;

/**
 * Class to provide information about a shared blob.
 *
 * @hide
 */
public final class BlobInfo implements Parcelable {
    private final long mId;
    private final long mExpiryTimeMs;
    private final CharSequence mLabel;
    private final List<AccessorInfo> mAccessors;

    public BlobInfo(long id, long expiryTimeMs, CharSequence label,
            List<AccessorInfo> accessors) {
        mId = id;
        mExpiryTimeMs = expiryTimeMs;
        mLabel = label;
        mAccessors = accessors;
    }

    private BlobInfo(Parcel in) {
        mId = in.readLong();
        mExpiryTimeMs = in.readLong();
        mLabel = in.readCharSequence();
        mAccessors = in.readArrayList(null /* classloader */);
    }

    public long getId() {
        return mId;
    }

    public long getExpiryTimeMs() {
        return mExpiryTimeMs;
    }

    public CharSequence getLabel() {
        return mLabel;
    }

    public List<AccessorInfo> getAccessors() {
        return Collections.unmodifiableList(mAccessors);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId);
        dest.writeLong(mExpiryTimeMs);
        dest.writeCharSequence(mLabel);
        dest.writeList(mAccessors);
    }

    @Override
    public String toString() {
        return toShortString();
    }

    private String toShortString() {
        return "BlobInfo {"
                + "id: " + mId + ","
                + "expiryMs: " + mExpiryTimeMs + ","
                + "label: " + mLabel + ","
                + "accessors: " + AccessorInfo.toShortString(mAccessors) + ","
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<BlobInfo> CREATOR = new Creator<BlobInfo>() {
        @Override
        @NonNull
        public BlobInfo createFromParcel(Parcel source) {
            return new BlobInfo(source);
        }

        @Override
        @NonNull
        public BlobInfo[] newArray(int size) {
            return new BlobInfo[size];
        }
    };
}
