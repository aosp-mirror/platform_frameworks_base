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
    // This network should lose to a wifi that has ever been validated
    // NOTE : temporarily this policy is managed by ConnectivityService, because of legacy. The
    // legacy design has this bit global to the system and tacked on WiFi which means it will affect
    // networks from carriers who don't want it and non-carrier networks, which is bad for users.
    // The S design has this on mobile networks only, so this can be fixed eventually ; as CS
    // doesn't know what carriers need this bit, the initial S implementation will continue to
    // affect other carriers but will at least leave non-mobile networks alone. Eventually Telephony
    // should set this on networks from carriers that require it.
    /** @hide */
    public static final int POLICY_YIELD_TO_BAD_WIFI = 1;
    // This network is primary for this transport.
    /** @hide */
    public static final int POLICY_TRANSPORT_PRIMARY = 2;
    // This network is exiting : it will likely disconnect in a few seconds.
    /** @hide */
    public static final int POLICY_EXITING = 3;

    /** @hide */
    public static final int MIN_AGENT_MANAGED_POLICY = POLICY_YIELD_TO_BAD_WIFI;
    /** @hide */
    public static final int MAX_AGENT_MANAGED_POLICY = POLICY_EXITING;

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

    /**
     * To the exclusive usage of FullScore
     * @hide
     */
    public long getPolicies() {
        return mPolicies;
    }

    /**
     * Whether this network should yield to a previously validated wifi gone bad.
     *
     * If this policy is set, other things being equal, the device will prefer a previously
     * validated WiFi even if this network is validated and the WiFi is not.
     * If this policy is not set, the device prefers the validated network.
     *
     * @hide
     */
    // TODO : Unhide this for telephony and have telephony call it on the relevant carriers.
    // In the mean time this is handled by Connectivity in a backward-compatible manner.
    public boolean shouldYieldToBadWifi() {
        return hasPolicy(POLICY_YIELD_TO_BAD_WIFI);
    }

    /**
     * Whether this network is primary for this transport.
     *
     * When multiple networks of the same transport are active, the device prefers the ones that
     * are primary. This is meant in particular for DS-DA devices with a user setting to choose the
     * default SIM card, or for WiFi STA+STA and make-before-break cases.
     *
     * @hide
     */
    @SystemApi
    public boolean isTransportPrimary() {
        return hasPolicy(POLICY_TRANSPORT_PRIMARY);
    }

    /**
     * Whether this network is exiting.
     *
     * If this policy is set, the device will expect this network to disconnect within seconds.
     * It will try to migrate to some other network if any is available, policy permitting, to
     * avoid service disruption.
     * This is useful in particular when a good cellular network is available and WiFi is getting
     * weak and risks disconnecting soon. The WiFi network should be marked as exiting so that
     * the device will prefer the reliable mobile network over this soon-to-be-lost WiFi.
     *
     * @hide
     */
    @SystemApi
    public boolean isExiting() {
        return hasPolicy(POLICY_EXITING);
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
        private int mPolicies = 0;

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
         * Set for a network that should never be preferred to a wifi that has ever been validated
         *
         * If this policy is set, other things being equal, the device will prefer a previously
         * validated WiFi even if this network is validated and the WiFi is not.
         * If this policy is not set, the device prefers the validated network.
         *
         * @return this builder
         * @hide
         */
        // TODO : Unhide this for telephony and have telephony call it on the relevant carriers.
        // In the mean time this is handled by Connectivity in a backward-compatible manner.
        @NonNull
        public Builder setShouldYieldToBadWifi(final boolean val) {
            if (val) {
                mPolicies |= (1L << POLICY_YIELD_TO_BAD_WIFI);
            } else {
                mPolicies &= ~(1L << POLICY_YIELD_TO_BAD_WIFI);
            }
            return this;
        }

        /**
         * Set for a network that is primary for this transport.
         *
         * When multiple networks of the same transport are active, the device prefers the ones that
         * are primary. This is meant in particular for DS-DA devices with a user setting to choose
         * the default SIM card, or for WiFi STA+STA and make-before-break cases.
         *
         * @return this builder
         * @hide
         */
        @SystemApi
        @NonNull
        public Builder setTransportPrimary(final boolean val) {
            if (val) {
                mPolicies |= (1L << POLICY_TRANSPORT_PRIMARY);
            } else {
                mPolicies &= ~(1L << POLICY_TRANSPORT_PRIMARY);
            }
            return this;
        }

        /**
         * Set for a network that will likely disconnect in a few seconds.
         *
         * If this policy is set, the device will expect this network to disconnect within seconds.
         * It will try to migrate to some other network if any is available, policy permitting, to
         * avoid service disruption.
         * This is useful in particular when a good cellular network is available and WiFi is
         * getting weak and risks disconnecting soon. The WiFi network should be marked as exiting
         * so that the device will prefer the reliable mobile network over this soon-to-be-lost
         * WiFi.
         *
         * @return this builder
         * @hide
         */
        @SystemApi
        @NonNull
        public Builder setExiting(final boolean val) {
            if (val) {
                mPolicies |= (1L << POLICY_EXITING);
            } else {
                mPolicies &= ~(1L << POLICY_EXITING);
            }
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
            return new NetworkScore(mLegacyInt, mPolicies, mKeepConnectedReason);
        }
    }
}
