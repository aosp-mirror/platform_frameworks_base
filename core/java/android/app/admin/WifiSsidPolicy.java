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
import android.net.wifi.WifiSsid;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;

/**
 * Used to indicate the Wi-Fi SSID restriction policy the network must satisfy
 * in order to be eligible for a connection.
 *
 * If the policy type is a denylist, the device may not connect to networks on the denylist.
 * If the policy type is an allowlist, the device may only connect to networks on the allowlist.
 * Admin configured networks are not exempt from this restriction.
 * This policy only prohibits connecting to a restricted network and
 * does not affect adding a restricted network.
 * If the current network is present in the denylist or not present in the allowlist,
 * it will be disconnected.
 */
public final class WifiSsidPolicy implements Parcelable {
    /**
     * SSID policy type indicator for {@link WifiSsidPolicy}.
     *
     * <p> When returned from {@link WifiSsidPolicy#getPolicyType()}, the constant
     * indicates that the SSID policy type is an allowlist.
     *
     * @see #WIFI_SSID_POLICY_TYPE_DENYLIST
     */
    public static final int WIFI_SSID_POLICY_TYPE_ALLOWLIST = 0;

    /**
     * SSID policy type indicator for {@link WifiSsidPolicy}.
     *
     * <p> When returned from {@link WifiSsidPolicy#getPolicyType()}, the constant
     * indicates that the SSID policy type is a denylist.
     *
     * @see #WIFI_SSID_POLICY_TYPE_ALLOWLIST
     */
    public static final int WIFI_SSID_POLICY_TYPE_DENYLIST = 1;

    /**
     * Possible SSID policy types
     *
     * @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"WIFI_SSID_POLICY_TYPE_"}, value = {
            WIFI_SSID_POLICY_TYPE_ALLOWLIST,
            WIFI_SSID_POLICY_TYPE_DENYLIST})
    public @interface WifiSsidPolicyType {}

    private @WifiSsidPolicyType int mPolicyType;
    private ArraySet<WifiSsid> mSsids;

    /**
     * Create the Wi-Fi SSID Policy.
     *
     * @param policyType indicate whether the policy is an allowlist or a denylist
     * @param ssids set of {@link WifiSsid}
     * @throws IllegalArgumentException if the input ssids set is empty or the policyType is invalid
     */
    public WifiSsidPolicy(@WifiSsidPolicyType int policyType, @NonNull Set<WifiSsid> ssids) {
        if (ssids.isEmpty()) {
            throw new IllegalArgumentException("SSID list cannot be empty");
        }
        if (policyType != WIFI_SSID_POLICY_TYPE_ALLOWLIST
                && policyType != WIFI_SSID_POLICY_TYPE_DENYLIST) {
            throw new IllegalArgumentException("Invalid policy type");
        }
        mPolicyType = policyType;
        mSsids = new ArraySet<>(ssids);
    }

    private WifiSsidPolicy(Parcel in) {
        mPolicyType = in.readInt();
        mSsids = (ArraySet<WifiSsid>) in.readArraySet(null);
    }

    /**
     * Returns the set of {@link WifiSsid}
     */
    @NonNull
    public Set<WifiSsid> getSsids() {
        return mSsids;
    }

    /**
     * Returns the policy type.
     */
    public @WifiSsidPolicyType int getPolicyType() {
        return mPolicyType;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<WifiSsidPolicy> CREATOR = new Creator<WifiSsidPolicy>() {
        @Override
        public WifiSsidPolicy createFromParcel(Parcel source) {
            return new WifiSsidPolicy(source);
        }

        @Override
        public WifiSsidPolicy[] newArray(int size) {
            return new WifiSsidPolicy[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPolicyType);
        dest.writeArraySet(mSsids);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof WifiSsidPolicy)) {
            return false;
        }
        WifiSsidPolicy that = (WifiSsidPolicy) thatObject;
        return mPolicyType == that.mPolicyType && Objects.equals(mSsids, that.mSsids);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPolicyType, mSsids);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
