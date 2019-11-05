/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.compat;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class is a parcelable version of {@link com.android.server.compat.Change}.
 *
 * @hide
 */
public class CompatibilityChangeInfo implements Parcelable {
    private final long mChangeId;
    private final @Nullable String mName;
    private final int mEnableAfterTargetSdk;
    private final boolean mDisabled;

    public long getId() {
        return mChangeId;
    }

    @Nullable
    public String getName() {
        return mName;
    }

    public int getEnableAfterTargetSdk() {
        return mEnableAfterTargetSdk;
    }

    public boolean getDisabled() {
        return mDisabled;
    }

    public CompatibilityChangeInfo(
            Long changeId, String name, int enableAfterTargetSdk, boolean disabled) {
        this.mChangeId = changeId;
        this.mName = name;
        this.mEnableAfterTargetSdk = enableAfterTargetSdk;
        this.mDisabled = disabled;
    }

    private CompatibilityChangeInfo(Parcel in) {
        mChangeId = in.readLong();
        mName = in.readString();
        mEnableAfterTargetSdk = in.readInt();
        mDisabled = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mChangeId);
        dest.writeString(mName);
        dest.writeInt(mEnableAfterTargetSdk);
        dest.writeBoolean(mDisabled);
    }

    public static final Parcelable.Creator<CompatibilityChangeInfo> CREATOR =
            new Parcelable.Creator<CompatibilityChangeInfo>() {

                @Override
                public CompatibilityChangeInfo createFromParcel(Parcel in) {
                    return new CompatibilityChangeInfo(in);
                }

                @Override
                public CompatibilityChangeInfo[] newArray(int size) {
                    return new CompatibilityChangeInfo[size];
                }
            };
}
