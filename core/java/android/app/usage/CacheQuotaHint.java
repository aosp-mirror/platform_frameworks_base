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

package android.app.usage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * CacheQuotaHint represents a triplet of a uid, the volume UUID it is stored upon, and
 * its usage stats. When processed, it obtains a cache quota as defined by the system which
 * allows apps to understand how much cache to use.
 * {@hide}
 */
@SystemApi
public final class CacheQuotaHint implements Parcelable {
    public static final long QUOTA_NOT_SET = -1;
    private final String mUuid;
    private final int mUid;
    private final UsageStats mUsageStats;
    private final long mQuota;

    /**
     * Create a new request.
     * @param builder A builder for this object.
     */
    public CacheQuotaHint(Builder builder) {
        this.mUuid = builder.mUuid;
        this.mUid = builder.mUid;
        this.mUsageStats = builder.mUsageStats;
        this.mQuota = builder.mQuota;
    }

    public String getVolumeUuid() {
        return mUuid;
    }

    public int getUid() {
        return mUid;
    }

    public long getQuota() {
        return mQuota;
    }

    public UsageStats getUsageStats() {
        return mUsageStats;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUuid);
        dest.writeInt(mUid);
        dest.writeLong(mQuota);
        dest.writeParcelable(mUsageStats, 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof CacheQuotaHint) {
            final CacheQuotaHint other = (CacheQuotaHint) o;
            return Objects.equals(mUuid, other.mUuid)
                    && Objects.equals(mUsageStats, other.mUsageStats)
                    && mUid == other.mUid && mQuota == other.mQuota;
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.mUuid, this.mUid, this.mUsageStats, this.mQuota);
    }

    public static final class Builder {
        private String mUuid;
        private int mUid;
        private UsageStats mUsageStats;
        private long mQuota;

        public Builder() {
        }

        public Builder(CacheQuotaHint hint) {
            setVolumeUuid(hint.getVolumeUuid());
            setUid(hint.getUid());
            setUsageStats(hint.getUsageStats());
            setQuota(hint.getQuota());
        }

        public @NonNull Builder setVolumeUuid(@Nullable String uuid) {
            mUuid = uuid;
            return this;
        }

        public @NonNull Builder setUid(int uid) {
            Preconditions.checkArgumentNonnegative(uid, "Proposed uid was negative.");
            mUid = uid;
            return this;
        }

        public @NonNull Builder setUsageStats(@Nullable UsageStats stats) {
            mUsageStats = stats;
            return this;
        }

        public @NonNull Builder setQuota(long quota) {
            Preconditions.checkArgument((quota >= QUOTA_NOT_SET));
            mQuota = quota;
            return this;
        }

        public @NonNull CacheQuotaHint build() {
            return new CacheQuotaHint(this);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CacheQuotaHint> CREATOR =
            new Creator<CacheQuotaHint>() {
                @Override
                public CacheQuotaHint createFromParcel(Parcel in) {
                    final Builder builder = new Builder();
                    return builder.setVolumeUuid(in.readString())
                            .setUid(in.readInt())
                            .setQuota(in.readLong())
                            .setUsageStats(in.readParcelable(UsageStats.class.getClassLoader()))
                            .build();
                }

                @Override
                public CacheQuotaHint[] newArray(int size) {
                    return new CacheQuotaHint[size];
                }
            };
}
