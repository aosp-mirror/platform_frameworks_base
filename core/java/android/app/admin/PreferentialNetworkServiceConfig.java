/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.admin;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Network configuration to be set for the user profile
 * {@see DevicePolicyManager#setPreferentialNetworkServiceConfigs}.
 */
public final class PreferentialNetworkServiceConfig implements Parcelable {
    final boolean mIsEnabled;
    final int mNetworkId;
    final boolean mAllowFallbackToDefaultConnection;
    final int[] mIncludedUids;
    final int[] mExcludedUids;

    /** @hide */
    public static final PreferentialNetworkServiceConfig DEFAULT =
            (new PreferentialNetworkServiceConfig.Builder()).build();

    /**
     * Preferential network identifier 1.
     */
    public static final int PREFERENTIAL_NETWORK_ID_1 = 1;

    /**
     * Preferential network identifier 2.
     */
    public static final int PREFERENTIAL_NETWORK_ID_2 = 2;

    /**
     * Preferential network identifier 3.
     */
    public static final int PREFERENTIAL_NETWORK_ID_3 = 3;

    /**
     * Preferential network identifier 4.
     */
    public static final int PREFERENTIAL_NETWORK_ID_4 = 4;

    /**
     * Preferential network identifier 5.
     */
    public static final int PREFERENTIAL_NETWORK_ID_5 = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "PREFERENTIAL_NETWORK_ID_" }, value = {
            PREFERENTIAL_NETWORK_ID_1,
            PREFERENTIAL_NETWORK_ID_2,
            PREFERENTIAL_NETWORK_ID_3,
            PREFERENTIAL_NETWORK_ID_4,
            PREFERENTIAL_NETWORK_ID_5,
    })

    public @interface PreferentialNetworkPreferenceId {
    }

    private PreferentialNetworkServiceConfig(boolean isEnabled,
            boolean allowFallbackToDefaultConnection, int[] includedUids,
            int[] excludedUids, @PreferentialNetworkPreferenceId int networkId) {
        mIsEnabled = isEnabled;
        mAllowFallbackToDefaultConnection = allowFallbackToDefaultConnection;
        mIncludedUids = includedUids;
        mExcludedUids = excludedUids;
        mNetworkId = networkId;
    }

    private PreferentialNetworkServiceConfig(Parcel in) {
        mIsEnabled = in.readBoolean();
        mAllowFallbackToDefaultConnection = in.readBoolean();
        mNetworkId = in.readInt();
        mIncludedUids = in.createIntArray();
        mExcludedUids = in.createIntArray();
    }

    /**
     * Is the preferential network enabled.
     * @return true if enabled else false
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * is fallback to default network allowed. This boolean configures whether default connection
     * (default internet or wifi) should be used or not if a preferential network service
     * connection is not available.
     * @return true if fallback is allowed, else false.
     */
    public boolean isFallbackToDefaultConnectionAllowed() {
        return mAllowFallbackToDefaultConnection;
    }

    /**
     * Get the array of uids that are applicable for the profile preference.
     *
     * {@see #getExcludedUids()}
     * Included UIDs and Excluded UIDs can't both be non-empty.
     * if both are empty, it means this request applies to all uids in the user profile.
     * if included is not empty, then only included UIDs are applied.
     * if excluded is not empty, then it is all uids in the user profile except these UIDs.
     * @return Array of uids applicable for the profile preference.
     *      Empty array would mean that this request applies to all uids in the profile.
     */
    public @NonNull int[] getIncludedUids() {
        return mIncludedUids;
    }

    /**
     * Get the array of uids that are excluded for the profile preference.
     *
     * {@see #getIncludedUids()}
     * Included UIDs and Excluded UIDs can't both be non-empty.
     * if both are empty, it means this request applies to all uids in the user profile.
     * if included is not empty, then only included UIDs are applied.
     * if excluded is not empty, then it is all uids in the user profile except these UIDs.
     * @return Array of uids that are excluded for the profile preference.
     *      Empty array would mean that this request applies to all uids in the profile.
     */
    public @NonNull int[] getExcludedUids() {
        return mExcludedUids;
    }

    /**
     * @return preference enterprise identifier.
     * preference identifier is applicable only if preference network service is enabled
     *
     */
    public @PreferentialNetworkPreferenceId int getNetworkId() {
        return mNetworkId;
    }

    @Override
    public String toString() {
        return "PreferentialNetworkServiceConfig{"
                + "mIsEnabled=" + isEnabled()
                + "mAllowFallbackToDefaultConnection=" + isFallbackToDefaultConnectionAllowed()
                + "mIncludedUids=" + mIncludedUids.toString()
                + "mExcludedUids=" + mExcludedUids.toString()
                + "mNetworkId=" + mNetworkId
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final PreferentialNetworkServiceConfig that = (PreferentialNetworkServiceConfig) o;
        return mIsEnabled == that.mIsEnabled
                && mAllowFallbackToDefaultConnection == that.mAllowFallbackToDefaultConnection
                && mNetworkId == that.mNetworkId
                && Objects.equals(mIncludedUids, that.mIncludedUids)
                && Objects.equals(mExcludedUids, that.mExcludedUids);
    }

    @Override
    public int hashCode() {
        return ((Objects.hashCode(mIsEnabled) * 17)
                + (Objects.hashCode(mAllowFallbackToDefaultConnection) * 19)
                + (Objects.hashCode(mIncludedUids) * 23)
                + (Objects.hashCode(mExcludedUids) * 29)
                + mNetworkId * 31);
    }

    /**
     * Builder used to create {@link PreferentialNetworkServiceConfig} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        boolean mIsEnabled = false;
        int mNetworkId = 0;
        boolean mAllowFallbackToDefaultConnection = true;
        int[] mIncludedUids = new int[0];
        int[] mExcludedUids = new int[0];

        /**
         * Constructs an empty Builder with preferential network disabled by default.
         */
        public Builder() {}

        /**
         * Set the preferential network service enabled state.
         * Default value is false.
         * @param isEnabled  the desired network preference to use, true to enable else false
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public PreferentialNetworkServiceConfig.Builder setEnabled(boolean isEnabled) {
            mIsEnabled = isEnabled;
            return this;
        }

        /**
         * Set whether the default connection should be used as fallback.
         * This boolean configures whether the default connection (default internet or wifi)
         * should be used if a preferential network service connection is not available.
         * Default value is true
         * @param allowFallbackToDefaultConnection  true if fallback is allowed else false
         * @return The builder to facilitate chaining.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public PreferentialNetworkServiceConfig.Builder setFallbackToDefaultConnectionAllowed(
                boolean allowFallbackToDefaultConnection) {
            mAllowFallbackToDefaultConnection = allowFallbackToDefaultConnection;
            return this;
        }

        /**
         * Set the array of uids whose network access will go through this preferential
         * network service.
         * {@see #setExcludedUids(int[])}
         * Included UIDs and Excluded UIDs can't both be non-empty.
         * if both are empty, it means this request applies to all uids in the user profile.
         * if included is not empty, then only included UIDs are applied.
         * if excluded is not empty, then it is all uids in the user profile except these UIDs.
         * @param uids  array of included uids
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public PreferentialNetworkServiceConfig.Builder setIncludedUids(
                @NonNull int[] uids) {
            Objects.requireNonNull(uids);
            mIncludedUids = uids;
            return this;
        }

        /**
         * Set the array of uids who are not allowed through this preferential
         * network service.
         * {@see #setIncludedUids(int[])}
         * Included UIDs and Excluded UIDs can't both be non-empty.
         * if both are empty, it means this request applies to all uids in the user profile.
         * if included is not empty, then only included UIDs are applied.
         * if excluded is not empty, then it is all uids in the user profile except these UIDs.
         * @param uids  array of excluded uids
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public PreferentialNetworkServiceConfig.Builder setExcludedUids(
                @NonNull int[] uids) {
            Objects.requireNonNull(uids);
            mExcludedUids = uids;
            return this;
        }

        /**
         * Returns an instance of {@link PreferentialNetworkServiceConfig} created from the
         * fields set on this builder.
         */
        @NonNull
        public PreferentialNetworkServiceConfig build() {
            if (mIncludedUids.length > 0 && mExcludedUids.length > 0) {
                throw new IllegalStateException("Both includedUids and excludedUids "
                        + "cannot be nonempty");
            }
            return new PreferentialNetworkServiceConfig(mIsEnabled,
                    mAllowFallbackToDefaultConnection, mIncludedUids, mExcludedUids, mNetworkId);
        }

        /**
         * Set the preferential network identifier.
         * preference identifier is applicable only if preferential network service is enabled.
         * @param preferenceId  preference Id
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public PreferentialNetworkServiceConfig.Builder setNetworkId(
                @PreferentialNetworkPreferenceId int preferenceId) {
            if ((preferenceId < PREFERENTIAL_NETWORK_ID_1)
                    || (preferenceId > PREFERENTIAL_NETWORK_ID_5)) {
                throw new IllegalArgumentException("Invalid preference identifier");
            }
            mNetworkId = preferenceId;
            return this;
        }
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeBoolean(mIsEnabled);
        dest.writeBoolean(mAllowFallbackToDefaultConnection);
        dest.writeInt(mNetworkId);
        dest.writeIntArray(mIncludedUids);
        dest.writeIntArray(mExcludedUids);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<PreferentialNetworkServiceConfig> CREATOR =
            new Creator<PreferentialNetworkServiceConfig>() {
                @Override
                public PreferentialNetworkServiceConfig[] newArray(int size) {
                    return new PreferentialNetworkServiceConfig[size];
                }

                @Override
                public PreferentialNetworkServiceConfig createFromParcel(
                        @NonNull android.os.Parcel in) {
                    return new PreferentialNetworkServiceConfig(in);
                }
            };
}
