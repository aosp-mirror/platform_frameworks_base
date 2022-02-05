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

    private WifiSsidPolicy(@WifiSsidPolicyType int policyType, @NonNull Set<WifiSsid> ssids) {
        mPolicyType = policyType;
        mSsids = new ArraySet<>(ssids);
    }

    private WifiSsidPolicy(Parcel in) {
        mPolicyType = in.readInt();
        mSsids = (ArraySet<WifiSsid>) in.readArraySet(null);
    }
    /**
     * Create the allowlist Wi-Fi SSID Policy.
     *
     * @param ssids allowlist of {@link WifiSsid}
     * @throws IllegalArgumentException if the input ssids list is empty
     */
    @NonNull
    public static WifiSsidPolicy createAllowlistPolicy(@NonNull Set<WifiSsid> ssids) {
        if (ssids.isEmpty()) {
            throw new IllegalArgumentException("SSID list cannot be empty");
        }
        return new WifiSsidPolicy(WIFI_SSID_POLICY_TYPE_ALLOWLIST, ssids);
    }

    /**
     * Create the denylist Wi-Fi SSID Policy.
     *
     * @param ssids denylist of {@link WifiSsid}
     * @throws IllegalArgumentException if the input ssids list is empty
     */
    @NonNull
    public static WifiSsidPolicy createDenylistPolicy(@NonNull Set<WifiSsid> ssids) {
        if (ssids.isEmpty()) {
            throw new IllegalArgumentException("SSID list cannot be empty");
        }
        return new WifiSsidPolicy(WIFI_SSID_POLICY_TYPE_DENYLIST, ssids);
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
    public int describeContents() {
        return 0;
    }
}
