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

import java.util.List;

/**
 * Class to provide information about an accessor of a shared blob.
 *
 * @hide
 */
public final class AccessorInfo implements Parcelable {
    private final String mPackageName;
    private final long mExpiryTimeMs;
    private final int mDescriptionResId;
    private final CharSequence mDescription;

    public AccessorInfo(String packageName, long expiryTimeMs,
            int descriptionResId, CharSequence description) {
        mPackageName = packageName;
        mExpiryTimeMs = expiryTimeMs;
        mDescriptionResId = descriptionResId;
        mDescription = description;
    }

    private AccessorInfo(Parcel in) {
        mPackageName = in.readString();
        mExpiryTimeMs = in.readLong();
        mDescriptionResId = in.readInt();
        mDescription = in.readCharSequence();
    }

    public String getPackageName() {
        return mPackageName;
    }

    public long getExpiryTimeMs() {
        return mExpiryTimeMs;
    }

    public int getDescriptionResId() {
        return mDescriptionResId;
    }

    public CharSequence getDescription() {
        return mDescription;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeLong(mExpiryTimeMs);
        dest.writeInt(mDescriptionResId);
        dest.writeCharSequence(mDescription);
    }

    @Override
    public String toString() {
        return "AccessorInfo {"
                + "package: " + mPackageName + ","
                + "expiryMs: " + mExpiryTimeMs + ","
                + "descriptionResId: " + mDescriptionResId + ","
                + "description: " + mDescription + ","
                + "}";
    }

    private String toShortString() {
        return mPackageName;
    }

    public static String toShortString(List<AccessorInfo> accessors) {
        final StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0, size = accessors.size(); i < size; ++i) {
            sb.append(accessors.get(i).toShortString());
            sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<AccessorInfo> CREATOR = new Creator<AccessorInfo>() {
        @Override
        @NonNull
        public AccessorInfo createFromParcel(Parcel source) {
            return new AccessorInfo(source);
        }

        @Override
        @NonNull
        public AccessorInfo[] newArray(int size) {
            return new AccessorInfo[size];
        }
    };
}
