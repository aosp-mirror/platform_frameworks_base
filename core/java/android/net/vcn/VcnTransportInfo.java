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

package android.net.vcn;

import static android.net.NetworkCapabilities.REDACT_ALL;
import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.SubscriptionManager;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * VcnTransportInfo contains information about the VCN's underlying transports for SysUi.
 *
 * <p>Presence of this class in the NetworkCapabilities.TransportInfo implies that the network is a
 * VCN.
 *
 * <p>VcnTransportInfo must exist on top of either an underlying Wifi or Cellular Network. If the
 * underlying Network is WiFi, the subId will be {@link
 * SubscriptionManager#INVALID_SUBSCRIPTION_ID}. If the underlying Network is Cellular, the WifiInfo
 * will be {@code null}.
 *
 * <p>Receipt of a VcnTransportInfo requires the NETWORK_SETTINGS permission; else the entire
 * VcnTransportInfo instance will be redacted.
 *
 * @hide
 */
public class VcnTransportInfo implements TransportInfo, Parcelable {
    @Nullable private final WifiInfo mWifiInfo;
    private final int mSubId;

    /**
     * The redaction scheme to use when parcelling.
     *
     * <p>The TransportInfo/NetworkCapabilities redaction mechanisms rely on redaction being
     * performed at parcelling time. This means that the redaction scheme must be stored for later
     * use.
     *
     * <p>Since the redaction scheme itself is not parcelled, this field is listed as a transient.
     *
     * <p>Defaults to REDACT_ALL when constructed using public constructors, or creating from
     * parcels.
     */
    private final transient long mRedactions;

    public VcnTransportInfo(@NonNull WifiInfo wifiInfo) {
        this(wifiInfo, INVALID_SUBSCRIPTION_ID, REDACT_ALL);
    }

    public VcnTransportInfo(int subId) {
        this(null /* wifiInfo */, subId, REDACT_ALL);
    }

    private VcnTransportInfo(@Nullable WifiInfo wifiInfo, int subId, long redactions) {
        mWifiInfo = wifiInfo;
        mSubId = subId;
        mRedactions = redactions;
    }

    /**
     * Get the {@link WifiInfo} for this VcnTransportInfo.
     *
     * <p>If the underlying Network for the associated VCN is Cellular, returns null.
     *
     * @return the WifiInfo if there is an underlying WiFi connection, else null.
     */
    @Nullable
    public WifiInfo getWifiInfo() {
        return mWifiInfo;
    }

    /**
     * Get the subId for the VCN Network associated with this VcnTransportInfo.
     *
     * <p>If the underlying Network for the associated VCN is WiFi, returns {@link
     * SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     *
     * @return the Subscription ID if a cellular underlying Network is present, else {@link
     *     android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID}.
     */
    public int getSubId() {
        return mSubId;
    }

    /**
     * Gets the redaction scheme
     *
     * @hide
     */
    @VisibleForTesting(visibility = PRIVATE)
    public long getRedaction() {
        return mRedactions;
    }

    @Override
    public int hashCode() {
        // mRedactions not hashed, as it is a transient, for control of parcelling
        return Objects.hash(mWifiInfo, mSubId);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VcnTransportInfo)) return false;
        final VcnTransportInfo that = (VcnTransportInfo) o;

        // mRedactions not compared, as it is a transient, for control of parcelling
        return Objects.equals(mWifiInfo, that.mWifiInfo) && mSubId == that.mSubId;
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    @NonNull
    public TransportInfo makeCopy(long redactions) {
        return new VcnTransportInfo(
                mWifiInfo == null ? null : mWifiInfo.makeCopy(redactions), mSubId, redactions);
    }

    @Override
    public long getApplicableRedactions() {
        long redactions = REDACT_FOR_NETWORK_SETTINGS;

        // Add additional wifi redactions if necessary
        if (mWifiInfo != null) {
            redactions |= mWifiInfo.getApplicableRedactions();
        }

        return redactions;
    }

    private boolean shouldParcelNetworkSettingsFields() {
        return (mRedactions & NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS) == 0;
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(shouldParcelNetworkSettingsFields() ? mSubId : INVALID_SUBSCRIPTION_ID);
        dest.writeParcelable(
                shouldParcelNetworkSettingsFields() ? (Parcelable) mWifiInfo : null, flags);
    }

    @Override
    public String toString() {
        return "VcnTransportInfo { mWifiInfo = " + mWifiInfo + ", mSubId = " + mSubId + " }";
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<VcnTransportInfo> CREATOR =
            new Creator<VcnTransportInfo>() {
                public VcnTransportInfo createFromParcel(Parcel in) {
                    final int subId = in.readInt();
                    final WifiInfo wifiInfo = in.readParcelable(null);

                    // If all fields are their null values, return null TransportInfo to avoid
                    // leaking information about this being a VCN Network (instead of macro
                    // cellular, etc)
                    if (wifiInfo == null && subId == INVALID_SUBSCRIPTION_ID) {
                        return null;
                    }

                    // Prevent further forwarding by redacting everything in future parcels from
                    // this VcnTransportInfo
                    return new VcnTransportInfo(wifiInfo, subId, REDACT_ALL);
                }

                public VcnTransportInfo[] newArray(int size) {
                    return new VcnTransportInfo[size];
                }
            };
}
