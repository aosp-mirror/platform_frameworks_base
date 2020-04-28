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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.LinkAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.DataFailCause;
import android.telephony.DataFailCause.FailCause;
import android.telephony.data.ApnSetting.ProtocolType;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    /** {@hide} */
    @IntDef(prefix = "LINK_STATUS_", value = {
            LINK_STATUS_UNKNOWN,
            LINK_STATUS_INACTIVE,
            LINK_STATUS_DORMANT,
            LINK_STATUS_ACTIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LinkStatus {}

    /** Unknown status */
    public static final int LINK_STATUS_UNKNOWN = -1;

    /** Indicates the data connection is inactive. */
    public static final int LINK_STATUS_INACTIVE = 0;

    /** Indicates the data connection is active with physical link dormant. */
    public static final int LINK_STATUS_DORMANT = 1;

    /** Indicates the data connection is active with physical link up. */
    public static final int LINK_STATUS_ACTIVE = 2;

    private final @FailCause int mCause;
    private final int mSuggestedRetryTime;
    private final int mId;
    private final @LinkStatus int mLinkStatus;
    private final @ProtocolType int mProtocolType;
    private final String mInterfaceName;
    private final List<LinkAddress> mAddresses;
    private final List<InetAddress> mDnsAddresses;
    private final List<InetAddress> mGatewayAddresses;
    private final List<InetAddress> mPcscfAddresses;
    private final int mMtu;

    /**
     * @param cause Data call fail cause. {@link DataFailCause#NONE} indicates no error.
     * @param suggestedRetryTime The suggested data retry time in milliseconds.
     * @param id The unique id of the data connection.
     * @param linkStatus Data connection link status.
     * @param protocolType The connection protocol, should be one of the PDP_type values in 3GPP
     * TS 27.007 section 10.1.1. For example, "IP", "IPV6", "IPV4V6", or "PPP".
     * @param interfaceName The network interface name.
     * @param addresses A list of addresses with optional "/" prefix length, e.g.,
     * "192.0.1.3" or "192.0.1.11/16 2001:db8::1/64". Typically 1 IPv4 or 1 IPv6 or
     * one of each. If the prefix length is absent the addresses are assumed to be
     * point to point with IPv4 having a prefix length of 32 and IPv6 128.
     * @param dnsAddresses A list of DNS server addresses, e.g., "192.0.1.3" or
     * "192.0.1.11 2001:db8::1". Null if no dns server addresses returned.
     * @param gatewayAddresses A list of default gateway addresses, e.g., "192.0.1.3" or
     * "192.0.1.11 2001:db8::1". When null, the addresses represent point to point connections.
     * @param pcscfAddresses A list of Proxy Call State Control Function address via PCO (Protocol
     * Configuration Option) for IMS client.
     * @param mtu MTU (maximum transmission unit) in bytes received from network. Zero or negative
     * values means network has either not sent a value or sent an invalid value.
     * either not sent a value or sent an invalid value.
     *
     * @removed Use the {@link Builder()} instead.
     */
    public DataCallResponse(@FailCause int cause, int suggestedRetryTime, int id,
                            @LinkStatus int linkStatus,
                            @ProtocolType int protocolType, @Nullable String interfaceName,
                            @Nullable List<LinkAddress> addresses,
                            @Nullable List<InetAddress> dnsAddresses,
                            @Nullable List<InetAddress> gatewayAddresses,
                            @Nullable List<InetAddress> pcscfAddresses, int mtu) {
        mCause = cause;
        mSuggestedRetryTime = suggestedRetryTime;
        mId = id;
        mLinkStatus = linkStatus;
        mProtocolType = protocolType;
        mInterfaceName = (interfaceName == null) ? "" : interfaceName;
        mAddresses = (addresses == null)
                ? new ArrayList<>() : new ArrayList<>(addresses);
        mDnsAddresses = (dnsAddresses == null)
                ? new ArrayList<>() : new ArrayList<>(dnsAddresses);
        mGatewayAddresses = (gatewayAddresses == null)
                ? new ArrayList<>() : new ArrayList<>(gatewayAddresses);
        mPcscfAddresses = (pcscfAddresses == null)
                ? new ArrayList<>() : new ArrayList<>(pcscfAddresses);
        mMtu = mtu;
    }

    /** @hide */
    @VisibleForTesting
    public DataCallResponse(Parcel source) {
        mCause = source.readInt();
        mSuggestedRetryTime = source.readInt();
        mId = source.readInt();
        mLinkStatus = source.readInt();
        mProtocolType = source.readInt();
        mInterfaceName = source.readString();
        mAddresses = new ArrayList<>();
        source.readList(mAddresses, LinkAddress.class.getClassLoader());
        mDnsAddresses = new ArrayList<>();
        source.readList(mDnsAddresses, InetAddress.class.getClassLoader());
        mGatewayAddresses = new ArrayList<>();
        source.readList(mGatewayAddresses, InetAddress.class.getClassLoader());
        mPcscfAddresses = new ArrayList<>();
        source.readList(mPcscfAddresses, InetAddress.class.getClassLoader());
        mMtu = source.readInt();
    }

    /**
     * @return Data call fail cause. {@link DataFailCause#NONE} indicates no error.
     */
    @FailCause
    public int getCause() { return mCause; }

    /**
     * @return The suggested data retry time in milliseconds.
     */
    public int getSuggestedRetryTime() { return mSuggestedRetryTime; }

    /**
     * @return The unique id of the data connection.
     */
    public int getId() { return mId; }

    /**
     * @return The link status
     */
    @LinkStatus public int getLinkStatus() { return mLinkStatus; }

    /**
     * @return The connection protocol type.
     */
    @ProtocolType
    public int getProtocolType() { return mProtocolType; }

    /**
     * @return The network interface name (e.g. "rmnet_data1").
     */
    @NonNull
    public String getInterfaceName() { return mInterfaceName; }

    /**
     * @return A list of addresses of this data connection.
     */
    @NonNull
    public List<LinkAddress> getAddresses() { return mAddresses; }

    /**
     * @return A list of DNS server addresses, e.g., "192.0.1.3" or
     * "192.0.1.11 2001:db8::1". Empty list if no dns server addresses returned.
     */
    @NonNull
    public List<InetAddress> getDnsAddresses() { return mDnsAddresses; }

    /**
     * @return A list of default gateway addresses, e.g., "192.0.1.3" or
     * "192.0.1.11 2001:db8::1". Empty list if the addresses represent point to point connections.
     */
    @NonNull
    public List<InetAddress> getGatewayAddresses() { return mGatewayAddresses; }

    /**
     * @return A list of Proxy Call State Control Function address via PCO (Protocol Configuration
     * Option) for IMS client.
     */
    @NonNull
    public List<InetAddress> getPcscfAddresses() { return mPcscfAddresses; }

    /**
     * @return MTU (maximum transmission unit) in bytes received from network. Zero or negative
     * values means network has either not sent a value or sent an invalid value.
     */
    public int getMtu() { return mMtu; }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("DataCallResponse: {")
           .append(" cause=").append(mCause)
           .append(" retry=").append(mSuggestedRetryTime)
           .append(" cid=").append(mId)
           .append(" linkStatus=").append(mLinkStatus)
           .append(" protocolType=").append(mProtocolType)
           .append(" ifname=").append(mInterfaceName)
           .append(" addresses=").append(mAddresses)
           .append(" dnses=").append(mDnsAddresses)
           .append(" gateways=").append(mGatewayAddresses)
           .append(" pcscf=").append(mPcscfAddresses)
           .append(" mtu=").append(mMtu)
           .append("}");
        return sb.toString();
    }

    @Override
    public boolean equals (Object o) {
        if (this == o) return true;

        if (!(o instanceof DataCallResponse)) {
            return false;
        }

        DataCallResponse other = (DataCallResponse) o;
        return this.mCause == other.mCause
                && this.mSuggestedRetryTime == other.mSuggestedRetryTime
                && this.mId == other.mId
                && this.mLinkStatus == other.mLinkStatus
                && this.mProtocolType == other.mProtocolType
                && this.mInterfaceName.equals(other.mInterfaceName)
                && mAddresses.size() == other.mAddresses.size()
                && mAddresses.containsAll(other.mAddresses)
                && mDnsAddresses.size() == other.mDnsAddresses.size()
                && mDnsAddresses.containsAll(other.mDnsAddresses)
                && mGatewayAddresses.size() == other.mGatewayAddresses.size()
                && mGatewayAddresses.containsAll(other.mGatewayAddresses)
                && mPcscfAddresses.size() == other.mPcscfAddresses.size()
                && mPcscfAddresses.containsAll(other.mPcscfAddresses)
                && mMtu == other.mMtu;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCause, mSuggestedRetryTime, mId, mLinkStatus, mProtocolType,
                mInterfaceName, mAddresses, mDnsAddresses, mGatewayAddresses, mPcscfAddresses,
                mMtu);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCause);
        dest.writeInt(mSuggestedRetryTime);
        dest.writeInt(mId);
        dest.writeInt(mLinkStatus);
        dest.writeInt(mProtocolType);
        dest.writeString(mInterfaceName);
        dest.writeList(mAddresses);
        dest.writeList(mDnsAddresses);
        dest.writeList(mGatewayAddresses);
        dest.writeList(mPcscfAddresses);
        dest.writeInt(mMtu);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<DataCallResponse> CREATOR =
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

    /**
     * Provides a convenient way to set the fields of a {@link DataCallResponse} when creating a new
     * instance.
     *
     * <p>The example below shows how you might create a new {@code DataCallResponse}:
     *
     * <pre><code>
     *
     * DataCallResponse response = new DataCallResponse.Builder()
     *     .setAddresses(Arrays.asList("192.168.1.2"))
     *     .setProtocolType(ApnSetting.PROTOCOL_IPV4V6)
     *     .build();
     * </code></pre>
     */
    public static final class Builder {
        private @FailCause int mCause;

        private int mSuggestedRetryTime;

        private int mId;

        private @LinkStatus int mLinkStatus;

        private @ProtocolType int mProtocolType;

        private String mInterfaceName;

        private List<LinkAddress> mAddresses;

        private List<InetAddress> mDnsAddresses;

        private List<InetAddress> mGatewayAddresses;

        private List<InetAddress> mPcscfAddresses;

        private int mMtu;

        /**
         * Default constructor for Builder.
         */
        public Builder() {
        }

        /**
         * Set data call fail cause.
         *
         * @param cause Data call fail cause. {@link DataFailCause#NONE} indicates no error.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setCause(@FailCause int cause) {
            mCause = cause;
            return this;
        }

        /**
         * Set the suggested data retry time.
         *
         * @param suggestedRetryTime The suggested data retry time in milliseconds.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setSuggestedRetryTime(int suggestedRetryTime) {
            mSuggestedRetryTime = suggestedRetryTime;
            return this;
        }

        /**
         * Set the unique id of the data connection.
         *
         * @param id The unique id of the data connection.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setId(int id) {
            mId = id;
            return this;
        }

        /**
         * Set the link status
         *
         * @param linkStatus The link status
         * @return The same instance of the builder.
         */
        public @NonNull Builder setLinkStatus(@LinkStatus int linkStatus) {
            mLinkStatus = linkStatus;
            return this;
        }

        /**
         * Set the connection protocol type.
         *
         * @param protocolType The connection protocol type.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setProtocolType(@ProtocolType int protocolType) {
            mProtocolType = protocolType;
            return this;
        }

        /**
         * Set the network interface name.
         *
         * @param interfaceName The network interface name (e.g. "rmnet_data1").
         * @return The same instance of the builder.
         */
        public @NonNull Builder setInterfaceName(@NonNull String interfaceName) {
            mInterfaceName = interfaceName;
            return this;
        }

        /**
         * Set the addresses of this data connection.
         *
         * @param addresses The list of address of the data connection.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setAddresses(@NonNull List<LinkAddress> addresses) {
            mAddresses = addresses;
            return this;
        }

        /**
         * Set the DNS addresses of this data connection
         *
         * @param dnsAddresses The list of DNS address of the data connection.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setDnsAddresses(@NonNull List<InetAddress> dnsAddresses) {
            mDnsAddresses = dnsAddresses;
            return this;
        }

        /**
         * Set the gateway addresses of this data connection
         *
         * @param gatewayAddresses The list of gateway address of the data connection.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setGatewayAddresses(@NonNull List<InetAddress> gatewayAddresses) {
            mGatewayAddresses = gatewayAddresses;
            return this;
        }

        /**
         * Set the Proxy Call State Control Function address via PCO(Protocol Configuration
         * Option) for IMS client.
         *
         * @param pcscfAddresses The list of pcscf address of the data connection.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setPcscfAddresses(@NonNull List<InetAddress> pcscfAddresses) {
            mPcscfAddresses = pcscfAddresses;
            return this;
        }

        /**
         * Set maximum transmission unit of the data connection.
         *
         * @param mtu MTU (maximum transmission unit) in bytes received from network. Zero or
         * negative values means network has either not sent a value or sent an invalid value.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMtu(int mtu) {
            mMtu = mtu;
            return this;
        }

        /**
         * Build the DataCallResponse.
         *
         * @return the DataCallResponse object.
         */
        public @NonNull DataCallResponse build() {
            return new DataCallResponse(mCause, mSuggestedRetryTime, mId, mLinkStatus,
                    mProtocolType, mInterfaceName, mAddresses, mDnsAddresses, mGatewayAddresses,
                    mPcscfAddresses, mMtu);
        }
    }
}
