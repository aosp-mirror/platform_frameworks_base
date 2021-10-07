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
    private final int mEnableSinceTargetSdk;
    private final boolean mDisabled;
    private final boolean mLoggingOnly;
    private final @Nullable String mDescription;
    private final boolean mOverridable;

    public long getId() {
        return mChangeId;
    }

    @Nullable
    public String getName() {
        return mName;
    }

    public int getEnableSinceTargetSdk() {
        return mEnableSinceTargetSdk;
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

    public boolean getOverridable() {
        return mOverridable;
    }

    public CompatibilityChangeInfo(
            Long changeId, String name, int enableAfterTargetSdk, int enableSinceTargetSdk,
            boolean disabled, boolean loggingOnly, String description, boolean overridable) {
        this.mChangeId = changeId;
        this.mName = name;
        if (enableAfterTargetSdk > 0) {
            // Need to maintain support for @EnabledAfter(X), but make it equivalent to
            // @EnabledSince(X+1)
            this.mEnableSinceTargetSdk = enableAfterTargetSdk + 1;
        } else if (enableSinceTargetSdk > 0) {
            this.mEnableSinceTargetSdk = enableSinceTargetSdk;
        } else {
            this.mEnableSinceTargetSdk = -1;
        }
        this.mDisabled = disabled;
        this.mLoggingOnly = loggingOnly;
        this.mDescription = description;
        this.mOverridable = overridable;
    }

    public CompatibilityChangeInfo(CompatibilityChangeInfo other) {
        this.mChangeId = other.mChangeId;
        this.mName = other.mName;
        this.mEnableSinceTargetSdk = other.mEnableSinceTargetSdk;
        this.mDisabled = other.mDisabled;
        this.mLoggingOnly = other.mLoggingOnly;
        this.mDescription = other.mDescription;
        this.mOverridable = other.mOverridable;
    }

    private CompatibilityChangeInfo(Parcel in) {
        mChangeId = in.readLong();
        mName = in.readString();
        mEnableSinceTargetSdk = in.readInt();
        mDisabled = in.readBoolean();
        mLoggingOnly = in.readBoolean();
        mDescription = in.readString();
        mOverridable = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mChangeId);
        dest.writeString(mName);
        dest.writeInt(mEnableSinceTargetSdk);
        dest.writeBoolean(mDisabled);
        dest.writeBoolean(mLoggingOnly);
        dest.writeString(mDescription);
        dest.writeBoolean(mOverridable);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CompatibilityChangeInfo(")
                .append(getId());
        if (getName() != null) {
            sb.append("; name=").append(getName());
        }
        if (getEnableSinceTargetSdk() != -1) {
            sb.append("; enableSinceTargetSdk=").append(getEnableSinceTargetSdk());
        }
        if (getDisabled()) {
            sb.append("; disabled");
        }
        if (getLoggingOnly()) {
            sb.append("; loggingOnly");
        }
        if (getOverridable()) {
            sb.append("; overridable");
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
                && this.mEnableSinceTargetSdk == that.mEnableSinceTargetSdk
                && this.mDisabled == that.mDisabled
                && this.mLoggingOnly == that.mLoggingOnly
                && this.mDescription.equals(that.mDescription)
                && this.mOverridable == that.mOverridable;
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
