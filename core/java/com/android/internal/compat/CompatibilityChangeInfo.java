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
    private final boolean mLoggingOnly;
    private final @Nullable String mDescription;

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

    public boolean getLoggingOnly() {
        return mLoggingOnly;
    }

    public String getDescription()  {
        return mDescription;
    }

    public CompatibilityChangeInfo(
            Long changeId, String name, int enableAfterTargetSdk, boolean disabled,
            boolean loggingOnly, String description) {
        this.mChangeId = changeId;
        this.mName = name;
        this.mEnableAfterTargetSdk = enableAfterTargetSdk;
        this.mDisabled = disabled;
        this.mLoggingOnly = loggingOnly;
        this.mDescription = description;
    }

    private CompatibilityChangeInfo(Parcel in) {
        mChangeId = in.readLong();
        mName = in.readString();
        mEnableAfterTargetSdk = in.readInt();
        mDisabled = in.readBoolean();
        mLoggingOnly = in.readBoolean();
        mDescription = in.readString();
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
        dest.writeBoolean(mLoggingOnly);
        dest.writeString(mDescription);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CompatibilityChangeInfo(")
                .append(getId());
        if (getName() != null) {
            sb.append("; name=").append(getName());
        }
        if (getEnableAfterTargetSdk() != -1) {
            sb.append("; enableAfterTargetSdk=").append(getEnableAfterTargetSdk());
        }
        if (getDisabled()) {
            sb.append("; disabled");
        }
        if (getLoggingOnly()) {
            sb.append("; loggingOnly");
        }
        return sb.append(")").toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || !(o instanceof CompatibilityChangeInfo)) {
            return false;
        }
        CompatibilityChangeInfo that = (CompatibilityChangeInfo) o;
        return this.mChangeId == that.mChangeId
                && this.mName.equals(that.mName)
                && this.mEnableAfterTargetSdk == that.mEnableAfterTargetSdk
                && this.mDisabled == that.mDisabled
                && this.mLoggingOnly == that.mLoggingOnly
                && this.mDescription.equals(that.mDescription);

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
