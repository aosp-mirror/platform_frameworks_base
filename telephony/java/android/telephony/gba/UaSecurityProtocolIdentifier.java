/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.telephony.gba;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.gba.TlsParams.TlsCipherSuite;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Description of ua security protocol identifier defined in 3GPP TS 33.220 H.2
 * @hide
 */
@SystemApi
public final class UaSecurityProtocolIdentifier implements Parcelable {

    /**
     * Organization code defined in 3GPP TS 33.220 H.3
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ORG_"}, value = {
        ORG_NONE,
        ORG_3GPP,
        ORG_3GPP2,
        ORG_OMA,
        ORG_GSMA,
        ORG_LOCAL})
    public @interface OrganizationCode {}

    /**
     * Organization octet value for default ua security protocol
     */
    public static final int ORG_NONE  = 0;
    /**
     * Organization octet value for 3GPP ua security protocol
     */
    public static final int ORG_3GPP  = 0x01;
    /**
     * Organization octet value for 3GPP2 ua security protocol
     */
    public static final int ORG_3GPP2 = 0x02;
    /**
     * Organization octet value for OMA ua security protocol
     */
    public static final int ORG_OMA   = 0x03;
    /**
     * Organization octet value for GSMA ua security protocol
     */
    public static final int ORG_GSMA  = 0x04;
    /**
     * Internal organization octet value for local/experimental protocols
     */
    public static final int ORG_LOCAL = 0xFF;

    /**
     * 3GPP UA Security Protocol ID defined in 3GPP TS 33.220 H.3
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"UA_SECURITY_PROTOCOL_3GPP_"}, value = {
        UA_SECURITY_PROTOCOL_3GPP_SUBSCRIBER_CERTIFICATE,
        UA_SECURITY_PROTOCOL_3GPP_MBMS,
        UA_SECURITY_PROTOCOL_3GPP_HTTP_DIGEST_AUTHENTICATION,
        UA_SECURITY_PROTOCOL_3GPP_HTTP_BASED_MBMS,
        UA_SECURITY_PROTOCOL_3GPP_SIP_BASED_MBMS,
        UA_SECURITY_PROTOCOL_3GPP_GENERIC_PUSH_LAYER,
        UA_SECURITY_PROTOCOL_3GPP_IMS_MEDIA_PLANE,
        UA_SECURITY_PROTOCOL_3GPP_GENERATION_TMPI,
        UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT,
        UA_SECURITY_PROTOCOL_3GPP_TLS_BROWSER})
    public @interface UaSecurityProtocol3gpp {}

    /**
     * Security protocol param according to TS 33.221 as described in TS
     * 33.220 Annex H. Mapped to byte stream "0x01,0x00,0x00,0x00,0x00".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_SUBSCRIBER_CERTIFICATE = 0;

    /**
     * Security protocol param according to TS 33.246 for Multimedia
     * broadcast/Multimedia services (MBMS) as described in TS
     * 33.220 Annex H. Mapped to byte stream "0x01,0x00,0x00,0x00,0x01".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_MBMS = 1;

    /**
     * Security protocol param based on HTTP digest authentication
     * according to TS 24.109 as described in TS 33.220 Annex H. Mapped to
     * byte stream "0x01,0x00,0x00,0x00,0x02".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_HTTP_DIGEST_AUTHENTICATION = 2;

    /**
     * Security protocol param used with HTTP-based security procedures for
     * Multimedia broadcast/Multimedia services (MBMS) user services
     * according to TS 26.237 as described in TS 33.220 Annex H.
     * Mapped to byte stream "0x01,0x00,0x00,0x00,0x03".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_HTTP_BASED_MBMS = 3;

    /**
     * Security protocol param used with SIP-based security procedures for
     * Multimedia broadcast/Multimedia services (MBMS) user services
     * according to TS 26.237 as described in TS 33.220 Annex H.
     * Mapped to byte stream "0x01,0x00,0x00,0x00,0x04".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_SIP_BASED_MBMS = 4;

    /**
     * Security protocol param used with Generic Push Layer according to TS
     * 33.224  as described in TS 33.220 Annex H. Mapped to byte stream
     * "0x01,0x00,0x00,0x00,0x05".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_GENERIC_PUSH_LAYER = 5;

    /**
     * Security protocol param used for IMS UE to KMS http based message
     * exchanges according to "IMS media plane security", TS 33.328   as
     * described in TS 33.220 Annex H. Mapped to byte stream
     * "0x01,0x00,0x00,0x00,0x06".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_IMS_MEDIA_PLANE = 6;

    /**
     * Security protocol param used for Generation of Temporary IP
     * Multimedia Private Identity (TMPI) according to TS 33.220 Annex B.4
     * Mapped to byte stream "0x01,0x00,0x00,0x01,0x00".
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_GENERATION_TMPI = 0x0100;

    /**
     * Security protocol param used for Shared key-based UE authentication with
     * certificate-based NAF authentication, according to TS 33.222 section 5.3,
     * or Shared key-based mutual authentication between UE and NAF, according to
     * TS 33.222 section 5.4. Mapped to byte stream "0x01,0x00,0x01,yy,zz".
     * "yy, zz" is the TLS CipherSuite code.
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT = 0x010000;

    /**
     * Security protocol param used for Shared key-based UE authentication with
     * certificate-based NAF authentication, according to TS 33.222 Annex D.
     * Mapped to byte stream "0x01,0x00,0x02,yy,zz".
     * "yy, zz" is the TLS CipherSuite code.
     */
    public static final int UA_SECURITY_PROTOCOL_3GPP_TLS_BROWSER = 0x020000;

    private static final int PROTOCOL_SIZE = 5;
    private static final int[] sUaSp3gppIds = new int[] {
            UA_SECURITY_PROTOCOL_3GPP_SUBSCRIBER_CERTIFICATE,
            UA_SECURITY_PROTOCOL_3GPP_MBMS,
            UA_SECURITY_PROTOCOL_3GPP_HTTP_DIGEST_AUTHENTICATION,
            UA_SECURITY_PROTOCOL_3GPP_HTTP_BASED_MBMS,
            UA_SECURITY_PROTOCOL_3GPP_SIP_BASED_MBMS,
            UA_SECURITY_PROTOCOL_3GPP_GENERIC_PUSH_LAYER,
            UA_SECURITY_PROTOCOL_3GPP_IMS_MEDIA_PLANE,
            UA_SECURITY_PROTOCOL_3GPP_GENERATION_TMPI,
            UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT,
            UA_SECURITY_PROTOCOL_3GPP_TLS_BROWSER};

    private int mOrg;
    private int mProtocol;
    private int mTlsCipherSuite;

    private UaSecurityProtocolIdentifier() {}

    private UaSecurityProtocolIdentifier(UaSecurityProtocolIdentifier sp) {
        mOrg = sp.mOrg;
        mProtocol = sp.mProtocol;
        mTlsCipherSuite = sp.mTlsCipherSuite;
    }

    /**
     * Returns the byte array representing the ua security protocol
     */
    @NonNull
    public byte[] toByteArray() {
        byte[] data = new byte[PROTOCOL_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.put((byte) mOrg);
        buf.putInt(mProtocol | mTlsCipherSuite);
        return data;
    }

    /**
     * Returns the organization code
     */
    public @OrganizationCode int getOrg() {
        return mOrg;
    }

    /**
     * Returns the security procotol id
     *
     * <p>Note that only 3GPP UA Security Protocols are supported for now
     */
    public @UaSecurityProtocol3gpp int getProtocol() {
        return mProtocol;
    }

    /**
     * Returns the TLS cipher suite
     */
    public @TlsCipherSuite int getTlsCipherSuite() {
        return mTlsCipherSuite;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mOrg);
        out.writeInt(mProtocol);
        out.writeInt(mTlsCipherSuite);
    }

    /**
     * {@link Parcelable.Creator}
     *
     */
    public static final @NonNull Parcelable.Creator<
            UaSecurityProtocolIdentifier> CREATOR = new Creator<UaSecurityProtocolIdentifier>() {
                @Nullable
                @Override
                public UaSecurityProtocolIdentifier createFromParcel(Parcel in) {
                    int org = in.readInt();
                    int protocol = in.readInt();
                    int cs = in.readInt();
                    if (org < 0 || protocol < 0 || cs < 0) {
                        return null;
                    }
                    Builder builder = new Builder();
                    try {
                        if (org > 0) {
                            builder.setOrg(org);
                        }
                        if (protocol > 0) {
                            builder.setProtocol(protocol);
                        }
                        if (cs > 0) {
                            builder.setTlsCipherSuite(cs);
                        }
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                    return builder.build();
                }

                @NonNull
                @Override
                public UaSecurityProtocolIdentifier[] newArray(int size) {
                    return new UaSecurityProtocolIdentifier[size];
                }
            };

    /**
     * {@link Parcelable#describeContents}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "UaSecurityProtocolIdentifier[" + mOrg + " , " + (mProtocol | mTlsCipherSuite) + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof UaSecurityProtocolIdentifier)) {
            return false;
        }

        UaSecurityProtocolIdentifier other = (UaSecurityProtocolIdentifier) obj;

        return mOrg == other.mOrg && mProtocol == other.mProtocol
                && mTlsCipherSuite == other.mTlsCipherSuite;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mOrg, mProtocol, mTlsCipherSuite);
    }

    private boolean isTlsSupported() {
        //TODO May update to support non 3gpp protocol in the future
        if (mOrg == ORG_3GPP && (mProtocol == UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT
                    || mProtocol == UA_SECURITY_PROTOCOL_3GPP_TLS_BROWSER)) {
            return true;
        }

        return false;
    }

    /**
     * Builder class for UaSecurityProtocolIdentifier
     */
    public static final class Builder {
        private final UaSecurityProtocolIdentifier mSp;

        /**
         * Creates a Builder with default UaSecurityProtocolIdentifier, a.k.a 0x00 00 00 00 00
         */
        public Builder() {
            mSp = new UaSecurityProtocolIdentifier();
        }

        /**
         * Creates a Builder from a UaSecurityProtocolIdentifier
         */
        public Builder(@NonNull final UaSecurityProtocolIdentifier sp) {
            Objects.requireNonNull(sp);
            mSp = new UaSecurityProtocolIdentifier(sp);
        }

        /**
         * Sets the organization code
         *
         * @param orgCode the organization code with the following value
         * <ol>
         * <li>{@link #ORG_NONE} </li>
         * <li>{@link #ORG_3GPP} </li>
         * <li>{@link #ORG_3GPP2} </li>
         * <li>{@link #ORG_OMA} </li>
         * <li>{@link #ORG_GSMA} </li>
         * <li>{@link #ORG_LOCAL} </li>
         * </ol>
         * @throws IllegalArgumentException if it is not one of the value above.
         *
         * <p>Note that this method will reset the security protocol and TLS cipher suite
         * if they have been set.
         */
        @NonNull
        public Builder setOrg(@OrganizationCode int orgCode) {
            if (orgCode < ORG_NONE || orgCode > ORG_LOCAL) {
                throw new IllegalArgumentException("illegal organization code");
            }
            mSp.mOrg = orgCode;
            mSp.mProtocol = 0;
            mSp.mTlsCipherSuite = 0;
            return this;
        }

        /**
         * Sets the UA security protocol for 3GPP
         *
         * @param protocol only 3GPP ua security protocol ID is supported for now, which
         * is one of the following value
         * <ol>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_SUBSCRIBER_CERTIFICATE} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_MBMS} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_HTTP_DIGEST_AUTHENTICATION} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_HTTP_BASED_MBMS} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_SIP_BASED_MBMS} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_GENERIC_PUSH_LAYER} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_IMS_MEDIA_PLANE} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_GENERATION_TMPI} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT} </li>
         * <li>{@link #UA_SECURITY_PROTOCOL_3GPP_TLS_BROWSER} </li>
         * </ol>
         * @throws IllegalArgumentException if the protocol is not one of the value above.
         *
         * <p>Note that this method will reset TLS cipher suite if it has been set.
         */
        @NonNull
        public Builder setProtocol(@UaSecurityProtocol3gpp int protocol) {
            //TODO May update to support non 3gpp protocol in the future
            if (protocol < UA_SECURITY_PROTOCOL_3GPP_SUBSCRIBER_CERTIFICATE
                    || (protocol > UA_SECURITY_PROTOCOL_3GPP_IMS_MEDIA_PLANE
                    && protocol != UA_SECURITY_PROTOCOL_3GPP_GENERATION_TMPI
                    && protocol != UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT
                    && protocol != UA_SECURITY_PROTOCOL_3GPP_TLS_BROWSER)
                    || mSp.mOrg != ORG_3GPP) {
                throw new IllegalArgumentException("illegal protocol code");
            }
            mSp.mProtocol = protocol;
            mSp.mTlsCipherSuite = 0;
            return this;
        }

        /**
         * Sets the UA security protocol for 3GPP
         *
         * @param cs TLS cipher suite value defined by {@link TlsParams#TlsCipherSuite}
         * @throws IllegalArgumentException if it is not a 3GPP ua security protocol,
         * the protocol does not support TLS, or does not support the cipher suite.
         */
        @NonNull
        public Builder setTlsCipherSuite(@TlsCipherSuite int cs) {
            if (!mSp.isTlsSupported()) {
                throw new IllegalArgumentException("The protocol does not support TLS");
            }
            if (!TlsParams.isTlsCipherSuiteSupported(cs)) {
                throw new IllegalArgumentException("TLS cipher suite is not supported");
            }
            mSp.mTlsCipherSuite = cs;
            return this;
        }

        /**
         * Builds the instance of UaSecurityProtocolIdentifier
         *
         * @return the built instance of UaSecurityProtocolIdentifier
         */
        @NonNull
        public UaSecurityProtocolIdentifier build() {
            return new UaSecurityProtocolIdentifier(mSp);
        }
    }
}
