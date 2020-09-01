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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IdRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Class to provide information about a lease (acquired using
 * {@link BlobStoreManager#acquireLease(BlobHandle, int)} or one of it's variants)
 * for a shared blob.
 *
 * @hide
 */
@TestApi
public final class LeaseInfo implements Parcelable {
    private final String mPackageName;
    private final long mExpiryTimeMillis;
    private final int mDescriptionResId;
    private final CharSequence mDescription;

    public LeaseInfo(@NonNull String packageName, @CurrentTimeMillisLong long expiryTimeMs,
            @IdRes int descriptionResId, @Nullable CharSequence description) {
        mPackageName = packageName;
        mExpiryTimeMillis = expiryTimeMs;
        mDescriptionResId = descriptionResId;
        mDescription = description;
    }

    private LeaseInfo(Parcel in) {
        mPackageName = in.readString();
        mExpiryTimeMillis = in.readLong();
        mDescriptionResId = in.readInt();
        mDescription = in.readCharSequence();
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @CurrentTimeMillisLong
    public long getExpiryTimeMillis() {
        return mExpiryTimeMillis;
    }

    @IdRes
    public int getDescriptionResId() {
        return mDescriptionResId;
    }

    @Nullable
    public CharSequence getDescription() {
        return mDescription;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeLong(mExpiryTimeMillis);
        dest.writeInt(mDescriptionResId);
        dest.writeCharSequence(mDescription);
    }

    @Override
    public String toString() {
        return "LeaseInfo {"
                + "package: " + mPackageName + ","
                + "expiryMs: " + mExpiryTimeMillis + ","
                + "descriptionResId: " + mDescriptionResId + ","
                + "description: " + mDescription + ","
                + "}";
    }

    private String toShortString() {
        return mPackageName;
    }

    static String toShortString(List<LeaseInfo> leaseInfos) {
        final StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0, size = leaseInfos.size(); i < size; ++i) {
            sb.append(leaseInfos.get(i).toShortString());
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
    public static final Creator<LeaseInfo> CREATOR = new Creator<LeaseInfo>() {
        @Override
        @NonNull
        public LeaseInfo createFromParcel(Parcel source) {
            return new LeaseInfo(source);
        }

        @Override
        @NonNull
        public LeaseInfo[] newArray(int size) {
            return new LeaseInfo[size];
        }
    };
}
