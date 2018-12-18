/*
 * Copyright (C) 2009 Qualcomm Innovation Center, Inc.  All Rights Reserved.
 * Copyright (C) 2009 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.LinkAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.data.ApnSetting.ProtocolType;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Description of the response of a setup data call connection request.
 *
 * @hide
 */
@SystemApi
public final class DataCallResponse implements Parcelable {
    private final int mStatus;
    private final int mSuggestedRetryTime;
    private final int mCid;
    private final int mActive;
    private final int mProtocolType;
    private final String mIfname;
    private final List<LinkAddress> mAddresses;
    private final List<InetAddress> mDnses;
    private final List<InetAddress> mGateways;
    private final List<String> mPcscfs;
    private final int mMtu;

    /**
     * @param status Data call fail cause. 0 indicates no error.
     * @param suggestedRetryTime The suggested data retry time in milliseconds.
     * @param cid The unique id of the data connection.
     * @param active Data connection active status. 0 = inactive, 1 = dormant, 2 = active.
     * @param protocolType The connection protocol, should be one of the PDP_type values in 3GPP
     *                     TS 27.007 section 10.1.1. For example, "IP", "IPV6", "IPV4V6", or "PPP".
     * @param ifname The network interface name.
     * @param addresses A list of addresses with optional "/" prefix length, e.g.,
     *                  "192.0.1.3" or "192.0.1.11/16 2001:db8::1/64". Typically 1 IPv4 or 1 IPv6 or
     *                  one of each. If the prefix length is absent the addresses are assumed to be
     *                  point to point with IPv4 having a prefix length of 32 and IPv6 128.
     * @param dnses A list of DNS server addresses, e.g., "192.0.1.3" or
     *              "192.0.1.11 2001:db8::1". Null if no dns server addresses returned.
     * @param gateways A list of default gateway addresses, e.g., "192.0.1.3" or
     *                 "192.0.1.11 2001:db8::1". When null, the addresses represent point to point
     *                 connections.
     * @param pcscfs A list of Proxy Call State Control Function address via PCO(Protocol
     *               Configuration Option) for IMS client.
     * @param mtu MTU (Maximum transmission unit) received from network Value <= 0 means network has
     *            either not sent a value or sent an invalid value.
     */
    public DataCallResponse(int status, int suggestedRetryTime, int cid, int active,
                            @ProtocolType int protocolType, @Nullable String ifname,
                            @Nullable List<LinkAddress> addresses,
                            @Nullable List<InetAddress> dnses,
                            @Nullable List<InetAddress> gateways,
                            @Nullable List<String> pcscfs, int mtu) {
        mStatus = status;
        mSuggestedRetryTime = suggestedRetryTime;
        mCid = cid;
        mActive = active;
        mProtocolType = protocolType;
        mIfname = (ifname == null) ? "" : ifname;
        mAddresses = (addresses == null) ? new ArrayList<>() : addresses;
        mDnses = (dnses == null) ? new ArrayList<>() : dnses;
        mGateways = (gateways == null) ? new ArrayList<>() : gateways;
        mPcscfs = (pcscfs == null) ? new ArrayList<>() : pcscfs;
        mMtu = mtu;
    }

    public DataCallResponse(Parcel source) {
        mStatus = source.readInt();
        mSuggestedRetryTime = source.readInt();
        mCid = source.readInt();
        mActive = source.readInt();
        mProtocolType = source.readInt();
        mIfname = source.readString();
        mAddresses = new ArrayList<>();
        source.readList(mAddresses, LinkAddress.class.getClassLoader());
        mDnses = new ArrayList<>();
        source.readList(mDnses, InetAddress.class.getClassLoader());
        mGateways = new ArrayList<>();
        source.readList(mGateways, InetAddress.class.getClassLoader());
        mPcscfs = new ArrayList<>();
        source.readList(mPcscfs, InetAddress.class.getClassLoader());
        mMtu = source.readInt();
    }

    /**
     * @return Data call fail cause. 0 indicates no error.
     */
    public int getStatus() { return mStatus; }

    /**
     * @return The suggested data retry time in milliseconds.
     */
    public int getSuggestedRetryTime() { return mSuggestedRetryTime; }

    /**
     * @return The unique id of the data connection.
     */
    public int getCallId() { return mCid; }

    /**
     * @return 0 = inactive, 1 = dormant, 2 = active.
     */
    public int getActive() { return mActive; }

    /**
     * @return The connection protocol type.
     */
    @ProtocolType
    public int getProtocolType() { return mProtocolType; }

    /**
     * @return The network interface name.
     */
    @NonNull
    public String getIfname() { return mIfname; }

    /**
     * @return A list of {@link LinkAddress}
     */
    @NonNull
    public List<LinkAddress> getAddresses() { return mAddresses; }

    /**
     * @return A list of DNS server addresses, e.g., "192.0.1.3" or
     * "192.0.1.11 2001:db8::1". Empty list if no dns server addresses returned.
     */
    @NonNull
    public List<InetAddress> getDnses() { return mDnses; }

    /**
     * @return A list of default gateway addresses, e.g., "192.0.1.3" or
     * "192.0.1.11 2001:db8::1". Empty list if the addresses represent point to point connections.
     */
    @NonNull
    public List<InetAddress> getGateways() { return mGateways; }

    /**
     * @return A list of Proxy Call State Control Function address via PCO(Protocol Configuration
     * Option) for IMS client.
     */
    @NonNull
    public List<String> getPcscfs() { return mPcscfs; }

    /**
     * @return MTU received from network Value <= 0 means network has either not sent a value or
     * sent an invalid value
     */
    public int getMtu() { return mMtu; }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("DataCallResponse: {")
           .append(" status=").append(mStatus)
           .append(" retry=").append(mSuggestedRetryTime)
           .append(" cid=").append(mCid)
           .append(" active=").append(mActive)
           .append(" protocolType=").append(mProtocolType)
           .append(" ifname=").append(mIfname)
           .append(" addresses=").append(mAddresses)
           .append(" dnses=").append(mDnses)
           .append(" gateways=").append(mGateways)
           .append(" pcscf=").append(mPcscfs)
           .append(" mtu=").append(mMtu)
           .append("}");
        return sb.toString();
    }

    @Override
    public boolean equals (Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof DataCallResponse)) {
            return false;
        }

        DataCallResponse other = (DataCallResponse) o;
        return this.mStatus == other.mStatus
                && this.mSuggestedRetryTime == other.mSuggestedRetryTime
                && this.mCid == other.mCid
                && this.mActive == other.mActive
                && this.mProtocolType == other.mProtocolType
                && this.mIfname.equals(other.mIfname)
                && mAddresses.size() == other.mAddresses.size()
                && mAddresses.containsAll(other.mAddresses)
                && mDnses.size() == other.mDnses.size()
                && mDnses.containsAll(other.mDnses)
                && mGateways.size() == other.mGateways.size()
                && mGateways.containsAll(other.mGateways)
                && mPcscfs.size() == other.mPcscfs.size()
                && mPcscfs.containsAll(other.mPcscfs)
                && mMtu == other.mMtu;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, mSuggestedRetryTime, mCid, mActive, mProtocolType, mIfname,
                mAddresses, mDnses, mGateways, mPcscfs, mMtu);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeInt(mSuggestedRetryTime);
        dest.writeInt(mCid);
        dest.writeInt(mActive);
        dest.writeInt(mProtocolType);
        dest.writeString(mIfname);
        dest.writeList(mAddresses);
        dest.writeList(mDnses);
        dest.writeList(mGateways);
        dest.writeList(mPcscfs);
        dest.writeInt(mMtu);
    }

    public static final Parcelable.Creator<DataCallResponse> CREATOR =
            new Parcelable.Creator<DataCallResponse>() {
                @Override
                public DataCallResponse createFromParcel(Parcel source) {
                    return new DataCallResponse(source);
                }

                @Override
                public DataCallResponse[] newArray(int size) {
                    return new DataCallResponse[size];
                }
            };
}