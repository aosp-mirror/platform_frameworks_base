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

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Object representing the quality of a network as perceived by the user.
 *
 * A NetworkScore object represents the characteristics of a network that affects how good the
 * network is considered for a particular use.
 * @hide
 */
@SystemApi
public final class NetworkScore implements Parcelable {
    // This will be removed soon. Do *NOT* depend on it for any new code that is not part of
    // a migration.
    private final int mLegacyInt;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            KEEP_CONNECTED_NONE,
            KEEP_CONNECTED_FOR_HANDOVER
    })
    public @interface KeepConnectedReason { }

    public static final int KEEP_CONNECTED_NONE = 0;
    public static final int KEEP_CONNECTED_FOR_HANDOVER = 1;

    // Agent-managed policies
    // TODO : add them here, starting from 1
    /** @hide */
    public static final int MIN_AGENT_MANAGED_POLICY = 0;
    /** @hide */
    public static final int MAX_AGENT_MANAGED_POLICY = -1;

    // Bitmask of all the policies applied to this score.
    private final long mPolicies;

    private final int mKeepConnectedReason;

    /** @hide */
    NetworkScore(final int legacyInt, final long policies,
            @KeepConnectedReason final int keepConnectedReason) {
        mLegacyInt = legacyInt;
        mPolicies = policies;
        mKeepConnectedReason = keepConnectedReason;
    }

    private NetworkScore(@NonNull final Parcel in) {
        mLegacyInt = in.readInt();
        mPolicies = in.readLong();
        mKeepConnectedReason = in.readInt();
    }

    public int getLegacyInt() {
        return mLegacyInt;
    }

    /**
     * Returns the keep-connected reason, or KEEP_CONNECTED_NONE.
     */
    public int getKeepConnectedReason() {
        return mKeepConnectedReason;
    }

    /**
     * @return whether this score has a particular policy.
     *
     * @hide
     */
    @VisibleForTesting
    public boolean hasPolicy(final int policy) {
        return 0 != (mPolicies & (1L << policy));
    }

    @Override
    public String toString() {
        return "Score(" + mLegacyInt + ")";
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeInt(mLegacyInt);
        dest.writeLong(mPolicies);
        dest.writeInt(mKeepConnectedReason);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull public static final Creator<NetworkScore> CREATOR = new Creator<>() {
        @Override
        @NonNull
        public NetworkScore createFromParcel(@NonNull final Parcel in) {
            return new NetworkScore(in);
        }

        @Override
        @NonNull
        public NetworkScore[] newArray(int size) {
            return new NetworkScore[size];
        }
    };

    /**
     * A builder for NetworkScore.
     */
    public static final class Builder {
        private static final long POLICY_NONE = 0L;
        private static final int INVALID_LEGACY_INT = Integer.MIN_VALUE;
        private int mLegacyInt = INVALID_LEGACY_INT;
        private int mKeepConnectedReason = KEEP_CONNECTED_NONE;

        /**
         * Sets the legacy int for this score.
         *
         * Do not rely on this. It will be gone by the time S is released.
         *
         * @param score the legacy int
         * @return this
         */
        @NonNull
        public Builder setLegacyInt(final int score) {
            mLegacyInt = score;
            return this;
        }

        /**
         * Set the keep-connected reason.
         *
         * This can be reset by calling it again with {@link KEEP_CONNECTED_NONE}.
         */
        @NonNull
        public Builder setKeepConnectedReason(@KeepConnectedReason final int reason) {
            mKeepConnectedReason = reason;
            return this;
        }

        /**
         * Builds this NetworkScore.
         * @return The built NetworkScore object.
         */
        @NonNull
        public NetworkScore build() {
            return new NetworkScore(mLegacyInt, POLICY_NONE, mKeepConnectedReason);
        }
    }
}
