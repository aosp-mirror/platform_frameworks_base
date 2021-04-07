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
import android.net.LinkProperties;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.DataFailureCause;
import android.telephony.DataFailCause;
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

    /** {@hide} */
    @IntDef(prefix = "HANDOVER_FAILURE_MODE_", value = {
            HANDOVER_FAILURE_MODE_UNKNOWN,
            HANDOVER_FAILURE_MODE_LEGACY,
            HANDOVER_FAILURE_MODE_DO_FALLBACK,
            HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_HANDOVER,
            HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_SETUP_NORMAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HandoverFailureMode {}

    /**
     * Data handover failure mode is unknown.
     */
    public static final int HANDOVER_FAILURE_MODE_UNKNOWN = -1;

    /**
     * Perform fallback to the source data transport on data handover failure using
     * the legacy logic, which is fallback if the fail cause is
     * {@link DataFailCause#HANDOFF_PREFERENCE_CHANGED}.
     */
    public static final int HANDOVER_FAILURE_MODE_LEGACY = 0;

    /**
     * Perform fallback to the source data transport on data handover failure.
     */
    public static final int HANDOVER_FAILURE_MODE_DO_FALLBACK = 1;

    /**
     * Do not perform fallback to the source data transport on data handover failure.
     * Framework will retry setting up a new data connection by sending
     * {@link DataService#REQUEST_REASON_HANDOVER} request to the underlying {@link DataService}.
     */
    public static final int HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_HANDOVER = 2;

    /**
     * Do not perform fallback to the source transport on data handover failure.
     * Framework will retry setting up a new data connection by sending
     * {@link DataService#REQUEST_REASON_NORMAL} request to the underlying {@link DataService}.
     */
    public static final int HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_SETUP_NORMAL = 3;

    /**
     * Indicates that data retry duration is not specified. Platform can determine when to
     * perform data setup appropriately.
     */
    public static final int RETRY_DURATION_UNDEFINED = -1;

    /**
     * Indicates that the pdu session id is not set.
     */
    public static final int PDU_SESSION_ID_NOT_SET = 0;

    private final @DataFailureCause int mCause;
    private final long mSuggestedRetryTime;
    private final int mId;
    private final @LinkStatus int mLinkStatus;
    private final @ProtocolType int mProtocolType;
    private final String mInterfaceName;
    private final List<LinkAddress> mAddresses;
    private final List<InetAddress> mDnsAddresses;
    private final List<InetAddress> mGatewayAddresses;
    private final List<InetAddress> mPcscfAddresses;
    private final int mMtu;
    private final int mMtuV4;
    private final int mMtuV6;
    private final @HandoverFailureMode int mHandoverFailureMode;
    private final int mPduSessionId;
    private final Qos mDefaultQos;
    private final List<QosBearerSession> mQosBearerSessions;
    private final SliceInfo mSliceInfo;
    private final List<TrafficDescriptor> mTrafficDescriptors;

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
     * @param mtu MTU (maximum transmission unit) in bytes received from network.
     * Zero or negative values means network has either not sent a value or sent an invalid value.
     *
     * @removed Use the {@link Builder()} instead.
     */
    public DataCallResponse(@DataFailureCause int cause, int suggestedRetryTime, int id,
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
        mMtu = mMtuV4 = mMtuV6 = mtu;
        mHandoverFailureMode = HANDOVER_FAILURE_MODE_LEGACY;
        mPduSessionId = PDU_SESSION_ID_NOT_SET;
        mDefaultQos = null;
        mQosBearerSessions = new ArrayList<>();
        mSliceInfo = null;
        mTrafficDescriptors = new ArrayList<>();
    }

    private DataCallResponse(@DataFailureCause int cause, long suggestedRetryTime, int id,
            @LinkStatus int linkStatus, @ProtocolType int protocolType,
            @Nullable String interfaceName, @Nullable List<LinkAddress> addresses,
            @Nullable List<InetAddress> dnsAddresses, @Nullable List<InetAddress> gatewayAddresses,
            @Nullable List<InetAddress> pcscfAddresses, int mtu, int mtuV4, int mtuV6,
            @HandoverFailureMode int handoverFailureMode, int pduSessionId,
            @Nullable Qos defaultQos, @Nullable List<QosBearerSession> qosBearerSessions,
            @Nullable SliceInfo sliceInfo, @Nullable List<TrafficDescriptor> trafficDescriptors) {
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
        mMtuV4 = mtuV4;
        mMtuV6 = mtuV6;
        mHandoverFailureMode = handoverFailureMode;
        mPduSessionId = pduSessionId;
        mDefaultQos = defaultQos;
        mQosBearerSessions = (qosBearerSessions == null)
                ? new ArrayList<>() : new ArrayList<>(qosBearerSessions);
        mSliceInfo = sliceInfo;
        mTrafficDescriptors = (trafficDescriptors == null)
                ? new ArrayList<>() : new ArrayList<>(trafficDescriptors);
    }

    /** @hide */
    @VisibleForTesting
    public DataCallResponse(Parcel source) {
        mCause = source.readInt();
        mSuggestedRetryTime = source.readLong();
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
        mMtuV4 = source.readInt();
        mMtuV6 = source.readInt();
        mHandoverFailureMode = source.readInt();
        mPduSessionId = source.readInt();
        mDefaultQos = source.readParcelable(Qos.class.getClassLoader());
        mQosBearerSessions = new ArrayList<>();
        source.readList(mQosBearerSessions, QosBearerSession.class.getClassLoader());
        mSliceInfo = source.readParcelable(SliceInfo.class.getClassLoader());
        mTrafficDescriptors = new ArrayList<>();
        source.readList(mTrafficDescriptors, TrafficDescriptor.class.getClassLoader());
    }

    /**
     * @return Data call fail cause. {@link DataFailCause#NONE} indicates no error.
     */
    @DataFailureCause
    public int getCause() { return mCause; }

    /**
     * @return The suggested data retry time in milliseconds.
     *
     * @deprecated Use {@link #getRetryDurationMillis()} instead.
     */
    @Deprecated
    public int getSuggestedRetryTime() {

        // To match the pre-deprecated getSuggestedRetryTime() behavior.
        if (mSuggestedRetryTime == RETRY_DURATION_UNDEFINED) {
            return 0;
        } else if (mSuggestedRetryTime > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) mSuggestedRetryTime;
    }

    /**
     * @return The network suggested data retry duration in milliseconds as specified in
     * 3GPP TS 24.302 section 8.2.9.1.  The APN associated to this data call will be throttled for
     * the specified duration unless {@link DataServiceCallback#onApnUnthrottled} is called.
     * {@code Long.MAX_VALUE} indicates data retry should not occur.
     * {@link #RETRY_DURATION_UNDEFINED} indicates network did not suggest any retry duration.
     */
    public long getRetryDurationMillis() {
        return mSuggestedRetryTime;
    }

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
     * @deprecated For IRadio 1.5 and up, use {@link #getMtuV4} or {@link #getMtuV6} instead.
     */
    @Deprecated
    public int getMtu() {
        return mMtu;
    }

    /**
     * This replaces the deprecated method getMtu.
     * @return MTU (maximum transmission unit) in bytes received from network, for IPv4.
     * Zero or negative values means network has either not sent a value or sent an invalid value.
     */
    public int getMtuV4() {
        return mMtuV4;
    }

    /**
     * @return MTU (maximum transmission unit) in bytes received from network, for IPv6.
     * Zero or negative values means network has either not sent a value or sent an invalid value.
     */
    public int getMtuV6() {
        return mMtuV6;
    }

    /**
     * @return The data handover failure mode.
     */
    public @HandoverFailureMode int getHandoverFailureMode() {
        return mHandoverFailureMode;
    }

    /**
     * @return The pdu session id
     */
    public int getPduSessionId() {
        return mPduSessionId;
    }

    /**
     * @return default QOS of the data connection received from the network
     *
     * @hide
     */
    @Nullable
    public Qos getDefaultQos() {
        return mDefaultQos;
    }

    /**
     * @return All the dedicated bearer QOS sessions of the data connection received from the
     * network.
     *
     * @hide
     */
    @NonNull
    public List<QosBearerSession> getQosBearerSessions() {
        return mQosBearerSessions;
    }

    /**
     * @return The slice info related to this data connection.
     */
    @Nullable
    public SliceInfo getSliceInfo() {
        return mSliceInfo;
    }

    /**
     * @return The traffic descriptors related to this data connection.
     */
    @NonNull
    public List<TrafficDescriptor> getTrafficDescriptors() {
        return mTrafficDescriptors;
    }

    @NonNull
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
           .append(" mtu=").append(getMtu())
           .append(" mtuV4=").append(getMtuV4())
           .append(" mtuV6=").append(getMtuV6())
           .append(" handoverFailureMode=").append(getHandoverFailureMode())
           .append(" pduSessionId=").append(getPduSessionId())
           .append(" defaultQos=").append(mDefaultQos)
           .append(" qosBearerSessions=").append(mQosBearerSessions)
           .append(" sliceInfo=").append(mSliceInfo)
           .append(" trafficDescriptors=").append(mTrafficDescriptors)
           .append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;

        if (!(o instanceof DataCallResponse)) {
            return false;
        }

        DataCallResponse other = (DataCallResponse) o;

        final boolean isQosSame = (mDefaultQos == null || other.mDefaultQos == null)
                ? mDefaultQos == other.mDefaultQos
                : mDefaultQos.equals(other.mDefaultQos);

        final boolean isQosBearerSessionsSame =
                (mQosBearerSessions == null || other.mQosBearerSessions == null)
                ? mQosBearerSessions == other.mQosBearerSessions
                : mQosBearerSessions.size() == other.mQosBearerSessions.size()
                && mQosBearerSessions.containsAll(other.mQosBearerSessions);

        final boolean isTrafficDescriptorsSame =
                (mTrafficDescriptors == null || other.mTrafficDescriptors == null)
                ? mTrafficDescriptors == other.mTrafficDescriptors
                : mTrafficDescriptors.size() == other.mTrafficDescriptors.size()
                && mTrafficDescriptors.containsAll(other.mTrafficDescriptors);

        return mCause == other.mCause
                && mSuggestedRetryTime == other.mSuggestedRetryTime
                && mId == other.mId
                && mLinkStatus == other.mLinkStatus
                && mProtocolType == other.mProtocolType
                && mInterfaceName.equals(other.mInterfaceName)
                && mAddresses.size() == other.mAddresses.size()
                && mAddresses.containsAll(other.mAddresses)
                && mDnsAddresses.size() == other.mDnsAddresses.size()
                && mDnsAddresses.containsAll(other.mDnsAddresses)
                && mGatewayAddresses.size() == other.mGatewayAddresses.size()
                && mGatewayAddresses.containsAll(other.mGatewayAddresses)
                && mPcscfAddresses.size() == other.mPcscfAddresses.size()
                && mPcscfAddresses.containsAll(other.mPcscfAddresses)
                && mMtu == other.mMtu
                && mMtuV4 == other.mMtuV4
                && mMtuV6 == other.mMtuV6
                && mHandoverFailureMode == other.mHandoverFailureMode
                && mPduSessionId == other.mPduSessionId
                && isQosSame
                && isQosBearerSessionsSame
                && Objects.equals(mSliceInfo, other.mSliceInfo)
                && isTrafficDescriptorsSame;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCause, mSuggestedRetryTime, mId, mLinkStatus, mProtocolType,
                mInterfaceName, mAddresses, mDnsAddresses, mGatewayAddresses, mPcscfAddresses,
                mMtu, mMtuV4, mMtuV6, mHandoverFailureMode, mPduSessionId, mDefaultQos,
                mQosBearerSessions, mSliceInfo, mTrafficDescriptors);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCause);
        dest.writeLong(mSuggestedRetryTime);
        dest.writeInt(mId);
        dest.writeInt(mLinkStatus);
        dest.writeInt(mProtocolType);
        dest.writeString(mInterfaceName);
        dest.writeList(mAddresses);
        dest.writeList(mDnsAddresses);
        dest.writeList(mGatewayAddresses);
        dest.writeList(mPcscfAddresses);
        dest.writeInt(mMtu);
        dest.writeInt(mMtuV4);
        dest.writeInt(mMtuV6);
        dest.writeInt(mHandoverFailureMode);
        dest.writeInt(mPduSessionId);
        if (mDefaultQos.getType() == Qos.QOS_TYPE_EPS) {
            dest.writeParcelable((EpsQos)mDefaultQos, flags);
        } else {
            dest.writeParcelable((NrQos)mDefaultQos, flags);
        }
        dest.writeList(mQosBearerSessions);
        dest.writeParcelable(mSliceInfo, flags);
        dest.writeList(mTrafficDescriptors);
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
     * Convert handover failure mode to string.
     *
     * @param handoverFailureMode Handover failure mode
     * @return Handover failure mode in string
     *
     * @hide
     */
    public static String failureModeToString(@HandoverFailureMode int handoverFailureMode) {
        switch (handoverFailureMode) {
            case HANDOVER_FAILURE_MODE_UNKNOWN: return "unknown";
            case HANDOVER_FAILURE_MODE_LEGACY: return "legacy";
            case HANDOVER_FAILURE_MODE_DO_FALLBACK: return "fallback";
            case HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_HANDOVER: return "retry handover";
            case HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_SETUP_NORMAL: return "retry setup new one";
            default: return Integer.toString(handoverFailureMode);
        }
    }

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
        private @DataFailureCause int mCause;

        private long mSuggestedRetryTime = RETRY_DURATION_UNDEFINED;

        private int mId;

        private @LinkStatus int mLinkStatus;

        private @ProtocolType int mProtocolType;

        private String mInterfaceName;

        private List<LinkAddress> mAddresses;

        private List<InetAddress> mDnsAddresses;

        private List<InetAddress> mGatewayAddresses;

        private List<InetAddress> mPcscfAddresses;

        private int mMtu;

        private int mMtuV4;

        private int mMtuV6;

        private @HandoverFailureMode int mHandoverFailureMode = HANDOVER_FAILURE_MODE_LEGACY;

        private int mPduSessionId = PDU_SESSION_ID_NOT_SET;

        private Qos mDefaultQos;

        private List<QosBearerSession> mQosBearerSessions = new ArrayList<>();

        private SliceInfo mSliceInfo;

        private List<TrafficDescriptor> mTrafficDescriptors = new ArrayList<>();

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
        public @NonNull Builder setCause(@DataFailureCause int cause) {
            mCause = cause;
            return this;
        }

        /**
         * Set the suggested data retry time.
         *
         * @param suggestedRetryTime The suggested data retry time in milliseconds.
         * @return The same instance of the builder.
         *
         * @deprecated Use {@link #setRetryDurationMillis(long)} instead.
         */
        @Deprecated
        public @NonNull Builder setSuggestedRetryTime(int suggestedRetryTime) {
            mSuggestedRetryTime = (long) suggestedRetryTime;
            return this;
        }

        /**
         * Set the network suggested data retry duration.
         *
         * @param retryDurationMillis The suggested data retry duration in milliseconds.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setRetryDurationMillis(long retryDurationMillis) {
            mSuggestedRetryTime = retryDurationMillis;
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
         * @deprecated For IRadio 1.5 and up, use {@link #setMtuV4} or {@link #setMtuV6} instead.
         */
        public @NonNull Builder setMtu(int mtu) {
            mMtu = mtu;
            return this;
        }

        /**
         * Set maximum transmission unit of the data connection, for IPv4.
         *
         * @param mtu MTU (maximum transmission unit) in bytes received from network. Zero or
         * negative values means network has either not sent a value or sent an invalid value.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMtuV4(int mtu) {
            mMtuV4 = mtu;
            return this;
        }

        /**
         * Set maximum transmission unit of the data connection, for IPv6.
         *
         * @param mtu MTU (maximum transmission unit) in bytes received from network. Zero or
         * negative values means network has either not sent a value or sent an invalid value.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMtuV6(int mtu) {
            mMtuV6 = mtu;
            return this;
        }

        /**
         * Set data handover failure mode for the data call response.
         *
         * @param failureMode Handover failure mode.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setHandoverFailureMode(@HandoverFailureMode int failureMode) {
            mHandoverFailureMode = failureMode;
            return this;
        }

        /**
         * Set pdu session id.
         *
         * @param pduSessionId Pdu Session Id of the data call.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setPduSessionId(int pduSessionId) {
            mPduSessionId = pduSessionId;
            return this;
        }

        /**
         * Set the default QOS for this data connection.
         *
         * @param defaultQos QOS (Quality Of Service) received from network.
         *
         * @return The same instance of the builder.
         *
         * @hide
         */
        public @NonNull Builder setDefaultQos(@Nullable Qos defaultQos) {
            mDefaultQos = defaultQos;
            return this;
        }

        /**
         * Set the dedicated bearer QOS sessions for this data connection.
         *
         * @param qosBearerSessions Dedicated bearer QOS (Quality Of Service) sessions received
         * from network.
         *
         * @return The same instance of the builder.
         *
         * @hide
         */
        public @NonNull Builder setQosBearerSessions(
                @NonNull List<QosBearerSession> qosBearerSessions) {
            mQosBearerSessions = qosBearerSessions;
            return this;
        }

        /**
         * The Slice used for this data connection.
         * <p/>
         * If a handover occurs from EPDG to 5G,
         * this is the {@link SliceInfo} used in {@link DataService#setupDataCall}.
         *
         * @param sliceInfo the slice info for the data call
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setSliceInfo(@Nullable SliceInfo sliceInfo) {
            mSliceInfo = sliceInfo;
            return this;
        }

        /**
         * The traffic descriptors for this data connection, as defined in 3GPP TS 24.526
         * Section 5.2. They are used for URSP traffic matching as described in 3GPP TS 24.526
         * Section 4.2.2. They includes an optional DNN, which, if present, must be used for traffic
         * matching; it does not specify the end point to be used for the data call. The end point
         * is specified by {@link DataProfile}, which must be used as the end point if one is not
         * specified through URSP rules.
         *
         * @param trafficDescriptors the traffic descriptors for the data call.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setTrafficDescriptors(
                @NonNull List<TrafficDescriptor> trafficDescriptors) {
            mTrafficDescriptors = trafficDescriptors;
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
                    mPcscfAddresses, mMtu, mMtuV4, mMtuV6, mHandoverFailureMode, mPduSessionId,
                    mDefaultQos, mQosBearerSessions, mSliceInfo, mTrafficDescriptors);
        }
    }
}
