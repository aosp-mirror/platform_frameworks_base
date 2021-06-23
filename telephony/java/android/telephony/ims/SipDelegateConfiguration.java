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

package android.telephony.ims;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.InetAddresses;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.stub.SipDelegate;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * The IMS registration and other attributes that the {@link SipDelegateConnection} used by the
 * IMS application will need to be aware of to correctly generate outgoing {@link SipMessage}s.
 * <p>
 * The IMS service must generate new instances of this configuration as the IMS configuration
 * managed by the IMS service changes. Along with each {@link SipDelegateConfiguration} instance
 * containing the configuration is the "version", which should be incremented every time a new
 * {@link SipDelegateConfiguration} instance is created. The {@link SipDelegateConnection} will
 * include the version of the {@link SipDelegateConfiguration} instance that it used in order for
 * the {@link SipDelegate} to easily identify if the IMS application used a now stale configuration
 * to generate the {@link SipMessage} and return
 * {@link SipDelegateManager#MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION} in
 * {@link DelegateMessageCallback#onMessageSendFailure(String, int)} so that the IMS application can
 * regenerate that {@link SipMessage} using the correct {@link SipDelegateConfiguration}
 * instance.
 * <p>
 * Every time the IMS configuration state changes in the IMS service, a full configuration should
 * be generated. The new  {@link SipDelegateConfiguration} instance should not be an incremental
 * update.
 * @see Builder
 * @hide
 */
@SystemApi
public final class SipDelegateConfiguration implements Parcelable {

    /**
     * The SIP transport uses UDP.
     */
    public static final int SIP_TRANSPORT_UDP = 0;

    /**
     * The SIP transport uses TCP.
     */
    public static final int SIP_TRANSPORT_TCP = 1;

    /**@hide*/
    @IntDef(prefix = "SIP_TRANSPORT_", value = {
            SIP_TRANSPORT_UDP,
            SIP_TRANSPORT_TCP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransportType {}

    /**
     * The value returned by {@link #getMaxUdpPayloadSizeBytes()} when it is not defined.
     */
    public static final int UDP_PAYLOAD_SIZE_UNDEFINED = -1;

    /**
     * SIP over IPSec configuration
     */
    public static final class IpSecConfiguration {
        private final int mLocalTxPort;
        private final int mLocalRxPort;
        private final int mLastLocalTxPort;
        private final int mRemoteTxPort;
        private final int mRemoteRxPort;
        private final int mLastRemoteTxPort;
        private final String mSecurityHeader;

        /**
         * Describes the SIP over IPSec configuration the SipDelegate will need to use.
         *
         * @param localTxPort  Local SIP port number used to send traffic.
         * @param localRxPort Local SIP port number used to receive traffic.
         * @param lastLocalTxPort Local SIP port number used for the previous IPsec security
         *                        association.
         * @param remoteTxPort Remote port number used by the SIP server to send SIP traffic.
         * @param remoteRxPort Remote port number used by the SIP server to receive incoming SIP
         *                     traffic.
         * @param lastRemoteTxPort Remote port number used by the SIP server to send SIP traffic on
         *                         the previous IPSec security association.
         * @param securityHeader The value of the SIP security verify header.
         */
        public IpSecConfiguration(int localTxPort, int localRxPort, int lastLocalTxPort,
                int remoteTxPort, int remoteRxPort, int lastRemoteTxPort,
                @NonNull String securityHeader) {
            mLocalTxPort = localTxPort;
            mLocalRxPort = localRxPort;
            mLastLocalTxPort = lastLocalTxPort;
            mRemoteTxPort = remoteTxPort;
            mRemoteRxPort = remoteRxPort;
            mLastRemoteTxPort = lastRemoteTxPort;
            mSecurityHeader = securityHeader;
        }

        /**
         * @return The local SIP port number used to send traffic.
         */
        public int getLocalTxPort() {
            return mLocalTxPort;
        }

        /**
         * @return The Local SIP port number used to receive traffic.
         */
        public int getLocalRxPort() {
            return mLocalRxPort;
        }

        /**
         * @return The last local SIP port number used for the previous IPsec security association.
         */
        public int getLastLocalTxPort() {
            return mLastLocalTxPort;
        }

        /**
         * @return The remote port number used by the SIP server to send SIP traffic.
         */
        public int getRemoteTxPort() {
            return mRemoteTxPort;
        }

        /**
         * @return the remote port number used by the SIP server to receive incoming SIP traffic.
         */
        public int getRemoteRxPort() {
            return mRemoteRxPort;
        }

        /**
         * @return the remote port number used by the SIP server to send SIP traffic on the previous
         * IPSec security association.
         */
        public int getLastRemoteTxPort() {
            return mLastRemoteTxPort;
        }

        /**
         * @return The value of the SIP security verify header.
         */
        public @NonNull String getSipSecurityVerifyHeader() {
            return mSecurityHeader;
        }

        /**
         * Helper for parcelling this object.
         * @hide
         */
        public void addToParcel(Parcel dest) {
            dest.writeInt(mLocalTxPort);
            dest.writeInt(mLocalRxPort);
            dest.writeInt(mLastLocalTxPort);
            dest.writeInt(mRemoteTxPort);
            dest.writeInt(mRemoteRxPort);
            dest.writeInt(mLastRemoteTxPort);
            dest.writeString(mSecurityHeader);
        }

        /**
         * Helper for unparcelling this object.
         * @hide
         */
        public static IpSecConfiguration fromParcel(Parcel source) {
            return new IpSecConfiguration(source.readInt(), source.readInt(), source.readInt(),
                    source.readInt(), source.readInt(), source.readInt(), source.readString());
        }

        @Override
        public String toString() {
            return "IpSecConfiguration{" + "localTx=" + mLocalTxPort + ", localRx=" + mLocalRxPort
                    + ", lastLocalTx=" + mLastLocalTxPort + ", remoteTx=" + mRemoteTxPort
                    + ", remoteRx=" + mRemoteRxPort + ", lastRemoteTx=" + mLastRemoteTxPort
                    + ", securityHeader=" + mSecurityHeader + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IpSecConfiguration that = (IpSecConfiguration) o;
            return mLocalTxPort == that.mLocalTxPort
                    && mLocalRxPort == that.mLocalRxPort
                    && mLastLocalTxPort == that.mLastLocalTxPort
                    && mRemoteTxPort == that.mRemoteTxPort
                    && mRemoteRxPort == that.mRemoteRxPort
                    && mLastRemoteTxPort == that.mLastRemoteTxPort
                    && Objects.equals(mSecurityHeader, that.mSecurityHeader);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mLocalTxPort, mLocalRxPort, mLastLocalTxPort, mRemoteTxPort,
                    mRemoteRxPort, mLastRemoteTxPort, mSecurityHeader);
        }
    }

    /**
     *  Creates a new instance of {@link SipDelegateConfiguration} composed from optional
     *  configuration items.
     */
    public static final class Builder {
        private final SipDelegateConfiguration mConfig;

        /**
         *
         * @param version The version associated with the {@link SipDelegateConfiguration} instance
         *                being built. See {@link #getVersion()} for more information.
         * @param transportType The transport type to use for SIP signalling.
         * @param localAddr The local socket address used for SIP traffic.
         * @param serverAddr The SIP server or P-CSCF default IP address for sip traffic.
         * @see InetAddresses#parseNumericAddress(String) for how to create an
         * {@link InetAddress} without requiring a DNS lookup.
         */
        public Builder(@IntRange(from = 0) long version, @TransportType int transportType,
                @NonNull InetSocketAddress localAddr, @NonNull InetSocketAddress serverAddr) {
            mConfig = new SipDelegateConfiguration(version, transportType, localAddr,
                    serverAddr);
        }

        /**
         * Create a new {@link SipDelegateConfiguration} instance with the same exact configuration
         * as the passed in instance, except for the version parameter, which will be incremented
         * by 1.
         * <p>
         * This method is useful for cases where only a small subset of configurations have changed
         * and the new configuration is based off of the old configuration.
         * @param c The older {@link SipDelegateConfiguration} instance to base this instance's
         *          configuration off of.
         */
        public Builder(@NonNull SipDelegateConfiguration c) {
            mConfig = c.copyAndIncrementVersion();
        }

        /**
         * Sets whether or not SIP compact form is enabled for the associated SIP delegate.
         * <p>
         * If unset, this configuration defaults to {@code false}.
         * @param isEnabled {@code true} if SIP compact form is enabled for the associated SIP
         *     Delegate, {@code false} if it is not.
         * @return this Builder instance with the compact form configuration set.
         */
        public @NonNull Builder setSipCompactFormEnabled(boolean isEnabled) {
            mConfig.mIsSipCompactFormEnabled = isEnabled;
            return this;
        }

        /**
         * Sets whether or not underlying SIP keepalives are enabled for the associated SIP
         * delegate.
         * <p>
         * If unset, this configuration defaults to {@code false}.
         * @param isEnabled {@code true} if SIP keepalives are enabled for the associated SIP
         *     Delegate, {@code false} if it is not.
         * @return this Builder instance with the new configuration set.
         */
        public @NonNull Builder setSipKeepaliveEnabled(boolean isEnabled) {
            mConfig.mIsSipKeepaliveEnabled = isEnabled;
            return this;
        }

        /**
         * Sets the max SIP payload size in bytes to be sent on UDP. If the SIP message payload is
         * greater than the max UDP payload size, then TCP must be used.
         * <p>
         * If unset, this configuration defaults to {@link #UDP_PAYLOAD_SIZE_UNDEFINED}, or no
         * size specified.
         * @param size The maximum SIP payload size in bytes for UDP.
         * @return this Builder instance with the new configuration set.
         */
        public @NonNull Builder setMaxUdpPayloadSizeBytes(@IntRange(from = 1) int size) {
            mConfig.mMaxUdpPayloadSize = size;
            return this;
        }

        /**
         * Sets the IMS public user identifier.
         * <p>
         * If unset, this configuration defaults to {@code null}, or no identifier specified.
         * @param id The IMS public user identifier.
         * @return this Builder instance with the new configuration set.
         */
        public @NonNull Builder setPublicUserIdentifier(@Nullable String id) {
            mConfig.mPublicUserIdentifier = id;
            return this;
        }

        /**
         * Sets the IMS private user identifier.
         * <p>
         * If unset, this configuration defaults to {@code null}, or no identifier specified.
         * @param id The IMS private user identifier.
         * @return this Builder instance with the new configuration set.
         */
        public @NonNull Builder setPrivateUserIdentifier(@Nullable String id) {
            mConfig.mPrivateUserIdentifier = id;
            return this;
        }

        /**
         * Sets the IMS home domain.
         * <p>
         * If unset, this configuration defaults to {@code null}, or no domain specified.
         * @param domain The IMS home domain.
         * @return this Builder instance with the new configuration set.
         */
        public @NonNull Builder setHomeDomain(@Nullable String domain) {
            mConfig.mHomeDomain = domain;
            return this;
        }

        /**
         * Sets the IMEI of the associated device.
         * <p>
         * Application can include the Instance-ID feature tag {@code "+sip.instance"} in the
         * Contact header with a value of the device IMEI in the form
         * {@code "urn:gsma:imei:<device IMEI>"}.
         * <p>
         * If unset, this configuration defaults to {@code null}, or no IMEI string specified.
         * @param imei The IMEI of the device.
         * @return this Builder instance with the new configuration set.
         */
        public @NonNull Builder setImei(@Nullable String imei) {
            mConfig.mImei = imei;
            return this;
        }

        /**
         * Set the optional {@link IpSecConfiguration} instance used if the associated SipDelegate
         * is communicating over IPSec.
         * <p>
         * If unset, this configuration defaults to {@code null}
         * @param c The IpSecConfiguration instance to set.
         * @return this Builder instance with IPSec configuration set.
         */
        public @NonNull Builder setIpSecConfiguration(@Nullable IpSecConfiguration c) {
            mConfig.mIpSecConfiguration = c;
            return this;
        }

        /**
         * Describes the Device's Public IP Address and port that is set when Network Address
         * Translation is enabled and the device is behind a NAT.
         * <p>
         * If unset, this configuration defaults to {@code null}
         * @param addr The {@link InetAddress} representing the device's public IP address and port
         *          when behind a NAT.
         * @return this Builder instance with the new configuration set.
         * @see InetAddresses#parseNumericAddress(String) For an example of how to create an
         * instance of {@link InetAddress} without causing a DNS lookup.
         */
        public @NonNull Builder setNatSocketAddress(@Nullable InetSocketAddress addr) {
            mConfig.mNatAddress = addr;
            return this;
        }

        /**
         * Sets the optional URI of the device's Globally routable user-agent URI (GRUU) if this
         * feature is enabled for the SIP delegate.
         * <p>
         * If unset, this configuration defaults to {@code null}
         * @param uri The GRUU to set.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setPublicGruuUri(@Nullable Uri uri) {
            mConfig.mGruu = uri;
            return this;
        }

        /**
         * Sets the SIP authentication header value.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP authentication header's value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipAuthenticationHeader(@Nullable String header) {
            mConfig.mSipAuthHeader = header;
            return this;
        }

        /**
         * Sets the SIP authentication nonce.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param nonce The SIP authentication nonce.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipAuthenticationNonce(@Nullable String nonce) {
            mConfig.mSipAuthNonce = nonce;
            return this;
        }

        /**
         * Sets the SIP service route header value.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP service route header value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipServiceRouteHeader(@Nullable String header) {
            mConfig.mServiceRouteHeader = header;
            return this;
        }

        /**
         * Sets the SIP path header value.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP path header value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipPathHeader(@Nullable String header) {
            mConfig.mPathHeader = header;
            return this;
        }

        /**
         * Sets the SIP User-Agent header value.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP User-Agent header value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipUserAgentHeader(@Nullable String header) {
            mConfig.mUserAgentHeader = header;
            return this;
        }

        /**
         * Sets the SIP Contact header's User parameter value.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param param The SIP Contact header's User parameter value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipContactUserParameter(@Nullable String param) {
            mConfig.mContactUserParam = param;
            return this;
        }

        /**
         * Sets the SIP P-Access-Network-Info (P-ANI) header value. Populated for networks that
         * require this information to be provided as part of outgoing SIP messages.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP P-ANI header value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipPaniHeader(@Nullable String header) {
            mConfig.mPaniHeader = header;
            return this;
        }

        /**
         * Sets the SIP P-Last-Access-Network-Info (P-LANI) header value. Populated for
         * networks that require this information to be provided as part of outgoing SIP messages.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP P-LANI header value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipPlaniHeader(@Nullable String header) {
            mConfig.mPlaniHeader = header;
            return this;
        }

        /**
         * Sets the SIP Cellular-Network-Info (CNI) header value (See 3GPP 24.229, section 7.2.15),
         * populated for networks that require this information to be provided as part of outgoing
         * SIP messages.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP P-LANI header value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipCniHeader(@Nullable String header) {
            mConfig.mCniHeader = header;
            return this;
        }

        /**
         * Sets the SIP P-associated-uri header value.
         * <p>
         * If unset, this configuration defaults to {@code null}.
         * @param header The SIP P-associated-uri header value.
         * @return this builder instance with the new configuration set.
         */
        public @NonNull Builder setSipAssociatedUriHeader(@Nullable String header) {
            mConfig.mAssociatedUriHeader = header;
            return this;
        }

        /**
         * @return A {@link SipDelegateConfiguration} instance with the optional configurations set.
         */
        public @NonNull SipDelegateConfiguration build() {
            return mConfig;
        }
    }

    private final long mVersion;
    private final int mTransportType;
    private final InetSocketAddress mLocalAddress;
    private final InetSocketAddress mSipServerAddress;
    private boolean mIsSipCompactFormEnabled = false;
    private boolean mIsSipKeepaliveEnabled = false;
    private int mMaxUdpPayloadSize = -1;
    private String mPublicUserIdentifier = null;
    private String mPrivateUserIdentifier = null;
    private String mHomeDomain = null;
    private String mImei = null;
    private Uri mGruu = null;
    private String mSipAuthHeader = null;
    private String mSipAuthNonce = null;
    private String mServiceRouteHeader = null;
    private String mPathHeader = null;
    private String mUserAgentHeader = null;
    private String mContactUserParam = null;
    private String mPaniHeader = null;
    private String mPlaniHeader = null;
    private String mCniHeader = null;
    private String mAssociatedUriHeader = null;
    private IpSecConfiguration mIpSecConfiguration = null;
    private InetSocketAddress mNatAddress = null;


    private SipDelegateConfiguration(long version, int transportType,
            InetSocketAddress localAddress, InetSocketAddress sipServerAddress) {
        mVersion = version;
        mTransportType = transportType;
        mLocalAddress = localAddress;
        mSipServerAddress = sipServerAddress;
    }

    private SipDelegateConfiguration(Parcel source) {
        mVersion = source.readLong();
        mTransportType = source.readInt();
        mLocalAddress = readAddressFromParcel(source);
        mSipServerAddress = readAddressFromParcel(source);
        mIsSipCompactFormEnabled = source.readBoolean();
        mIsSipKeepaliveEnabled = source.readBoolean();
        mMaxUdpPayloadSize = source.readInt();
        mPublicUserIdentifier = source.readString();
        mPrivateUserIdentifier = source.readString();
        mHomeDomain = source.readString();
        mImei = source.readString();
        mGruu = source.readParcelable(null);
        mSipAuthHeader = source.readString();
        mSipAuthNonce = source.readString();
        mServiceRouteHeader = source.readString();
        mPathHeader = source.readString();
        mUserAgentHeader = source.readString();
        mContactUserParam = source.readString();
        mPaniHeader = source.readString();
        mPlaniHeader = source.readString();
        mCniHeader = source.readString();
        mAssociatedUriHeader = source.readString();
        boolean isIpsecConfigAvailable = source.readBoolean();
        if (isIpsecConfigAvailable) {
            mIpSecConfiguration = IpSecConfiguration.fromParcel(source);
        }
        boolean isNatConfigAvailable = source.readBoolean();
        if (isNatConfigAvailable) {
            mNatAddress = readAddressFromParcel(source);
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mVersion);
        dest.writeInt(mTransportType);
        writeAddressToParcel(mLocalAddress, dest);
        writeAddressToParcel(mSipServerAddress, dest);
        dest.writeBoolean(mIsSipCompactFormEnabled);
        dest.writeBoolean(mIsSipKeepaliveEnabled);
        dest.writeInt(mMaxUdpPayloadSize);
        dest.writeString(mPublicUserIdentifier);
        dest.writeString(mPrivateUserIdentifier);
        dest.writeString(mHomeDomain);
        dest.writeString(mImei);
        dest.writeParcelable(mGruu, flags);
        dest.writeString(mSipAuthHeader);
        dest.writeString(mSipAuthNonce);
        dest.writeString(mServiceRouteHeader);
        dest.writeString(mPathHeader);
        dest.writeString(mUserAgentHeader);
        dest.writeString(mContactUserParam);
        dest.writeString(mPaniHeader);
        dest.writeString(mPlaniHeader);
        dest.writeString(mCniHeader);
        dest.writeString(mAssociatedUriHeader);
        dest.writeBoolean(mIpSecConfiguration != null);
        if (mIpSecConfiguration != null) {
            mIpSecConfiguration.addToParcel(dest);
        }
        dest.writeBoolean(mNatAddress != null);
        if (mNatAddress != null) {
            writeAddressToParcel(mNatAddress, dest);
        }
    }

    /**
     * @return A copy of this instance with an incremented version.
     * @hide
     */
    public SipDelegateConfiguration copyAndIncrementVersion() {
        SipDelegateConfiguration c = new SipDelegateConfiguration(getVersion() + 1, mTransportType,
                mLocalAddress, mSipServerAddress);
        c.mIsSipCompactFormEnabled = mIsSipCompactFormEnabled;
        c.mIsSipKeepaliveEnabled = mIsSipKeepaliveEnabled;
        c.mMaxUdpPayloadSize = mMaxUdpPayloadSize;
        c.mIpSecConfiguration = mIpSecConfiguration;
        c.mNatAddress = mNatAddress;
        c.mPublicUserIdentifier = mPublicUserIdentifier;
        c.mPrivateUserIdentifier = mPrivateUserIdentifier;
        c.mHomeDomain = mHomeDomain;
        c.mImei = mImei;
        c.mGruu = mGruu;
        c.mSipAuthHeader = mSipAuthHeader;
        c.mSipAuthNonce = mSipAuthNonce;
        c.mServiceRouteHeader = mServiceRouteHeader;
        c.mPathHeader = mPathHeader;
        c.mUserAgentHeader = mUserAgentHeader;
        c.mContactUserParam = mContactUserParam;
        c.mPaniHeader = mPaniHeader;
        c.mPlaniHeader = mPlaniHeader;
        c.mCniHeader = mCniHeader;
        c.mAssociatedUriHeader = mAssociatedUriHeader;
        return c;
    }

    /**
     * An integer representing the version number of this SipDelegateImsConfiguration.
     * {@link SipMessage}s that are created using this configuration will also have a this
     * version number associated with them, which will allow the IMS service to validate that the
     * {@link SipMessage} was using the latest configuration during creation and not a stale
     * configuration due to race conditions between the configuration being updated and the RCS
     * application not receiving the updated configuration before generating a new message.
     * <p>
     * The version number should be a positive number that starts at 0 and increments sequentially
     * as new {@link SipDelegateConfiguration} instances are created to update the IMS
     * configuration state.
     *
     * @return the version number associated with this {@link SipDelegateConfiguration}.
     */
    public @IntRange(from = 0) long getVersion() {
        return mVersion;
    }

    /**
     * @return The Transport type of the SIP delegate.
     */
    public @TransportType int getTransportType() {
        return mTransportType;
    }

    /**
     * @return The local IP address and port used for SIP traffic.
     */
    public @NonNull InetSocketAddress getLocalAddress() {
        return mLocalAddress;
    }

    /**
     * @return The default IP Address and port of the SIP server or P-CSCF used for SIP traffic.
     */
    public @NonNull InetSocketAddress getSipServerAddress() {
        return mSipServerAddress;
    }

    /**
     * @return {@code true} if SIP compact form is enabled for the associated SIP Delegate,
     * {@code false} if it is not.
     */
    public boolean isSipCompactFormEnabled() {
        return mIsSipCompactFormEnabled;
    }

    /**
     * @return {@code true} if SIP keepalives are enabled for the associated SIP Delegate,
     * {@code false} if it is not.
     */
    public boolean isSipKeepaliveEnabled() {
        return mIsSipKeepaliveEnabled;
    }

    /**
     * @return The maximum SIP payload size in bytes for UDP or {code -1} if no value was set.
     */
    public int getMaxUdpPayloadSizeBytes() {
        return mMaxUdpPayloadSize;
    }

    /**
     * @return The IMS public user identifier or {@code null} if it was not set.
     */
    public @Nullable String getPublicUserIdentifier() {
        return mPublicUserIdentifier;
    }

    /**
     * @return The IMS private user identifier or {@code null} if it was not set.
     */
    public @Nullable String getPrivateUserIdentifier() {
        return mPrivateUserIdentifier;
    }

    /**
     * @return The IMS home domain or {@code null} if it was not set.
     */
    public @Nullable String getHomeDomain() {
        return mHomeDomain;
    }

    /**
     * get the IMEI of the associated device.
     * <p>
     * Application can include the Instance-ID feature tag {@code "+sip.instance"} in the Contact
     * header with a value of the device IMEI in the form {@code "urn:gsma:imei:<device IMEI>"}.
     * @return The IMEI of the device or {@code null} if it was not set.
     */
    public @Nullable String getImei() {
        return mImei;
    }

    /**
     * @return The IPSec configuration that must be used because SIP is communicating over IPSec.
     * This returns {@code null} SIP is not communicating over IPSec.
     */
    public @Nullable IpSecConfiguration getIpSecConfiguration() {
        return mIpSecConfiguration;
    }

    /**
     * @return The public IP address and port of the device due to it being behind a NAT.
     * This returns {@code null} if the device is not behind a NAT.
     */
    public @Nullable InetSocketAddress getNatSocketAddress() {
        return mNatAddress;
    }

    /**
     * @return The device's Globally routable user-agent URI (GRUU) or {@code null} if this feature
     * is not enabled for the SIP delegate.
     */
    public @Nullable Uri getPublicGruuUri() {
        return mGruu;
    }

    /**
     * @return The value of the SIP authentication header or {@code null} if there is none set.
     */
    public @Nullable String getSipAuthenticationHeader() {
        return mSipAuthHeader;
    }

    /**
     * @return The value of the SIP authentication nonce or {@code null} if there is none set.
     */
    public @Nullable String getSipAuthenticationNonce() {
        return mSipAuthNonce;
    }

    /**
     * @return The value of the SIP service route header or {@code null} if there is none set.
     */
    public @Nullable String getSipServiceRouteHeader() {
        return mServiceRouteHeader;
    }

    /**
     * @return The value of the SIP path header or {@code null} if there is none set.
     */
    public @Nullable String getSipPathHeader() {
        return mPathHeader;
    }

    /**
     * @return The value of the SIP User-Agent header or {@code null} if there is none set.
     */
    public @Nullable String getSipUserAgentHeader() {
        return mUserAgentHeader;
    }
    /**
     * @return The value of the SIP Contact header's User parameter or {@code null} if there is
     * none set.
     */
    public @Nullable String getSipContactUserParameter() {
        return mContactUserParam;
    }

    /**
     * @return The value of the SIP P-Access-Network-Info (P-ANI) header or {@code null} if there is
     * none set.
     */
    public @Nullable String getSipPaniHeader() {
        return mPaniHeader;
    }
    /**
     * @return The value of the SIP P-Last-Access-Network-Info (P-LANI) header or {@code null} if
     * there is none set.
     */
    public @Nullable String getSipPlaniHeader() {
        return mPlaniHeader;
    }

    /**
     * @return The value of the SIP Cellular-Network-Info (CNI) header or {@code null} if there is
     * none set.
     */
    public @Nullable String getSipCniHeader() {
        return mCniHeader;
    }

    /**
     * @return The value of the SIP P-associated-uri header or {@code null} if there is none set.
     */
    public @Nullable String getSipAssociatedUriHeader() {
        return mAssociatedUriHeader;
    }

    private void writeAddressToParcel(InetSocketAddress addr, Parcel dest) {
        dest.writeByteArray(addr.getAddress().getAddress());
        dest.writeInt(addr.getPort());
    }

    private InetSocketAddress readAddressFromParcel(Parcel source) {
        final byte[] addressBytes = source.createByteArray();
        final int port = source.readInt();
        try {
            return new InetSocketAddress(InetAddress.getByAddress(addressBytes), port);
        } catch (UnknownHostException e) {
            // Should not happen, as length of array was verified before parcelling.
            Log.e("SipDelegateConfiguration", "exception reading address, returning null");
            return null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<SipDelegateConfiguration> CREATOR =
            new Creator<SipDelegateConfiguration>() {
        @Override
        public SipDelegateConfiguration createFromParcel(Parcel source) {
            return new SipDelegateConfiguration(source);
        }

        @Override
        public SipDelegateConfiguration[] newArray(int size) {
            return new SipDelegateConfiguration[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SipDelegateConfiguration that = (SipDelegateConfiguration) o;
        return mVersion == that.mVersion
                && mTransportType == that.mTransportType
                && mIsSipCompactFormEnabled == that.mIsSipCompactFormEnabled
                && mIsSipKeepaliveEnabled == that.mIsSipKeepaliveEnabled
                && mMaxUdpPayloadSize == that.mMaxUdpPayloadSize
                && Objects.equals(mLocalAddress, that.mLocalAddress)
                && Objects.equals(mSipServerAddress, that.mSipServerAddress)
                && Objects.equals(mPublicUserIdentifier, that.mPublicUserIdentifier)
                && Objects.equals(mPrivateUserIdentifier, that.mPrivateUserIdentifier)
                && Objects.equals(mHomeDomain, that.mHomeDomain)
                && Objects.equals(mImei, that.mImei)
                && Objects.equals(mGruu, that.mGruu)
                && Objects.equals(mSipAuthHeader, that.mSipAuthHeader)
                && Objects.equals(mSipAuthNonce, that.mSipAuthNonce)
                && Objects.equals(mServiceRouteHeader, that.mServiceRouteHeader)
                && Objects.equals(mPathHeader, that.mPathHeader)
                && Objects.equals(mUserAgentHeader, that.mUserAgentHeader)
                && Objects.equals(mContactUserParam, that.mContactUserParam)
                && Objects.equals(mPaniHeader, that.mPaniHeader)
                && Objects.equals(mPlaniHeader, that.mPlaniHeader)
                && Objects.equals(mCniHeader, that.mCniHeader)
                && Objects.equals(mAssociatedUriHeader, that.mAssociatedUriHeader)
                && Objects.equals(mIpSecConfiguration, that.mIpSecConfiguration)
                && Objects.equals(mNatAddress, that.mNatAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mVersion, mTransportType, mLocalAddress, mSipServerAddress,
                mIsSipCompactFormEnabled, mIsSipKeepaliveEnabled, mMaxUdpPayloadSize,
                mPublicUserIdentifier, mPrivateUserIdentifier, mHomeDomain, mImei, mGruu,
                mSipAuthHeader, mSipAuthNonce, mServiceRouteHeader, mPathHeader, mUserAgentHeader,
                mContactUserParam, mPaniHeader, mPlaniHeader, mCniHeader, mAssociatedUriHeader,
                mIpSecConfiguration, mNatAddress);
    }

    @Override
    public String toString() {
        return "SipDelegateConfiguration{ mVersion=" + mVersion + ", mTransportType="
                + mTransportType + '}';
    }
}
