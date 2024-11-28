/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telephony;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A single occurrence capturing a notable change to previously reported
 * cryptography algorithms for a given network and network event.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SECURITY_ALGORITHMS_UPDATE_INDICATIONS)
public final class SecurityAlgorithmUpdate implements Parcelable {
    private static final String TAG = "SecurityAlgorithmUpdate";

    /** 2G GSM circuit switched */
    public static final int CONNECTION_EVENT_CS_SIGNALLING_GSM = 0;
    /** 2G GPRS packet services */
    public static final int CONNECTION_EVENT_PS_SIGNALLING_GPRS = 1;
    /** 3G circuit switched*/
    public static final int CONNECTION_EVENT_CS_SIGNALLING_3G = 2;
    /** 3G packet switched*/
    public static final int CONNECTION_EVENT_PS_SIGNALLING_3G = 3;
    /** 4G Non-access stratum */
    public static final int CONNECTION_EVENT_NAS_SIGNALLING_LTE = 4;
    /** 4G Access-stratum */
    public static final int CONNECTION_EVENT_AS_SIGNALLING_LTE = 5;
    /** VOLTE SIP */
    public static final int CONNECTION_EVENT_VOLTE_SIP = 6;
    /** VOLTE SIP SOS (emergency) */
    public static final int CONNECTION_EVENT_VOLTE_SIP_SOS = 7;
    /** VOLTE RTP */
    public static final int CONNECTION_EVENT_VOLTE_RTP = 8;
    /** VOLTE RTP SOS (emergency) */
    public static final int CONNECTION_EVENT_VOLTE_RTP_SOS = 9;
    /** 5G Non-access stratum */
    public static final int CONNECTION_EVENT_NAS_SIGNALLING_5G = 10;
    /** 5G Access stratum */
    public static final int CONNECTION_EVENT_AS_SIGNALLING_5G = 11;
    /** VoNR SIP */
    public static final int CONNECTION_EVENT_VONR_SIP = 12;
    /** VoNR SIP SOS (emergency) */
    public static final int CONNECTION_EVENT_VONR_SIP_SOS = 13;
    /** VoNR RTP */
    public static final int CONNECTION_EVENT_VONR_RTP = 14;
    /** VoNR RTP SOS (emergency) */
    public static final int CONNECTION_EVENT_VONR_RTP_SOS = 15;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CONNECTION_EVENT_"}, value = {CONNECTION_EVENT_CS_SIGNALLING_GSM,
            CONNECTION_EVENT_PS_SIGNALLING_GPRS, CONNECTION_EVENT_CS_SIGNALLING_3G,
            CONNECTION_EVENT_PS_SIGNALLING_3G, CONNECTION_EVENT_NAS_SIGNALLING_LTE,
            CONNECTION_EVENT_AS_SIGNALLING_LTE, CONNECTION_EVENT_VOLTE_SIP,
            CONNECTION_EVENT_VOLTE_SIP_SOS, CONNECTION_EVENT_VOLTE_RTP,
            CONNECTION_EVENT_VOLTE_RTP_SOS, CONNECTION_EVENT_NAS_SIGNALLING_5G,
            CONNECTION_EVENT_AS_SIGNALLING_5G, CONNECTION_EVENT_VONR_SIP,
            CONNECTION_EVENT_VONR_SIP_SOS, CONNECTION_EVENT_VONR_RTP,
            CONNECTION_EVENT_VONR_RTP_SOS})
    public @interface ConnectionEvent {
    }

    /* GSM CS services, see 3GPP TS 43.020 for details */
    /** A5/0 - the null cipher */
    public static final int SECURITY_ALGORITHM_A50 = 0;
    /** A5/1 cipher */
    public static final int SECURITY_ALGORITHM_A51 = 1;
    /** A5/2 cipher */
    public static final int SECURITY_ALGORITHM_A52 = 2;
    /** A5/3 cipher */
    public static final int SECURITY_ALGORITHM_A53 = 3;
    /** A5/4 cipher */
    public static final int SECURITY_ALGORITHM_A54 = 4;
    /* GPRS PS services (3GPP TS 43.020) */
    /** GEA0 - null cipher */
    public static final int SECURITY_ALGORITHM_GEA0 = 14;
    /** GEA1 cipher */
    public static final int SECURITY_ALGORITHM_GEA1 = 15;
    /** GEA2 cipher */
    public static final int SECURITY_ALGORITHM_GEA2 = 16;
    /** GEA3 cipher */
    public static final int SECURITY_ALGORITHM_GEA3 = 17;
    /** GEA4 cipher */
    public static final int SECURITY_ALGORITHM_GEA4 = 18;
    /** GEA5 cipher */
    public static final int SECURITY_ALGORITHM_GEA5 = 19;
    /* 3G PS/CS services (3GPP TS 33.102) */
    /** UEA0 - null cipher */
    public static final int SECURITY_ALGORITHM_UEA0 = 29;
    /** UEA1 cipher */
    public static final int SECURITY_ALGORITHM_UEA1 = 30;
    /** UEA2 cipher */
    public static final int SECURITY_ALGORITHM_UEA2 = 31;
    /* 4G PS services & 5G NSA (3GPP TS 33.401) */
    /** EEA0 - null cipher */
    public static final int SECURITY_ALGORITHM_EEA0 = 41;
    /** EEA1 */
    public static final int SECURITY_ALGORITHM_EEA1 = 42;
    /** EEA2 */
    public static final int SECURITY_ALGORITHM_EEA2 = 43;
    /** EEA3 */
    public static final int SECURITY_ALGORITHM_EEA3 = 44;
    /* 5G PS services (3GPP TS 33.401 for 5G NSA and 3GPP TS 33.501 for 5G SA) */
    /** NEA0 - the null cipher */
    public static final int SECURITY_ALGORITHM_NEA0 = 55;
    /** NEA1 */
    public static final int SECURITY_ALGORITHM_NEA1 = 56;
    /** NEA2 */
    public static final int SECURITY_ALGORITHM_NEA2 = 57;
    /** NEA3 */
    public static final int SECURITY_ALGORITHM_NEA3 = 58;
    /* IMS and SIP layer security (See 3GPP TS 33.203) */
    /** No IPsec config */
    public static final int SECURITY_ALGORITHM_SIP_NO_IPSEC_CONFIG = 66;
    /** No IMS security, recommended to use SIP_NO_IPSEC_CONFIG and SIP_NULL instead */
    public static final int SECURITY_ALGORITHM_IMS_NULL = 67;
    /* IPSEC is present */
    /** SIP security is not enabled */
    public static final int SECURITY_ALGORITHM_SIP_NULL = 68;
    /** AES GCM mode */
    public static final int SECURITY_ALGORITHM_AES_GCM = 69;
    /** AES GMAC mode */
    public static final int SECURITY_ALGORITHM_AES_GMAC = 70;
    /** AES CBC mode */
    public static final int SECURITY_ALGORITHM_AES_CBC = 71;
    /** DES EDE3 CBC mode */
    public static final int SECURITY_ALGORITHM_DES_EDE3_CBC = 72;
    /** AES EDE3 CBC mode */
    public static final int SECURITY_ALGORITHM_AES_EDE3_CBC = 73;
    /** HMAC SHA1 96 */
    public static final int SECURITY_ALGORITHM_HMAC_SHA1_96 = 74;
    /** HMAC MD5 96 */
    public static final int SECURITY_ALGORITHM_HMAC_MD5_96 = 75;
    /* RTP and SRTP (see 3GPP TS 33.328) */
    /** RTP only, SRTP is not being used */
    public static final int SECURITY_ALGORITHM_RTP = 85;
    /* When SRTP is available and used */
    /** SRTP with null ciphering */
    public static final int SECURITY_ALGORITHM_SRTP_NULL = 86;
    /** SRTP with AES counter mode */
    public static final int SECURITY_ALGORITHM_SRTP_AES_COUNTER = 87;
    /** SRTP with AES F8 mode */
    public static final int SECURITY_ALGORITHM_SRTP_AES_F8 = 88;
    /** SRTP with HMAC SHA1 */
    public static final int SECURITY_ALGORITHM_SRTP_HMAC_SHA1 = 89;
    /* Ciphers for ePDG (3GPP TS 33.402) */
    /** ePDG encryption - AES GCM mode */
    public static final int SECURITY_ALGORITHM_ENCR_AES_GCM_16 = 99;
    /** ePDG encryption - AES GCM CBC mode */
    public static final int SECURITY_ALGORITHM_ENCR_AES_CBC = 100;
    /** ePDG authentication - HMAC SHA1 256 128 */
    public static final int SECURITY_ALGORITHM_AUTH_HMAC_SHA2_256_128 = 101;
    /** Unknown */
    public static final int SECURITY_ALGORITHM_UNKNOWN = 113;
    /** Other */
    public static final int SECURITY_ALGORITHM_OTHER = 114;
    /** Proprietary algorithms */
    public static final int SECURITY_ALGORITHM_ORYX = 124;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CONNECTION_EVENT_"}, value = {SECURITY_ALGORITHM_A50, SECURITY_ALGORITHM_A51,
            SECURITY_ALGORITHM_A52, SECURITY_ALGORITHM_A53,
            SECURITY_ALGORITHM_A54, SECURITY_ALGORITHM_GEA0, SECURITY_ALGORITHM_GEA1,
            SECURITY_ALGORITHM_GEA2, SECURITY_ALGORITHM_GEA3, SECURITY_ALGORITHM_GEA4,
            SECURITY_ALGORITHM_GEA5, SECURITY_ALGORITHM_UEA0, SECURITY_ALGORITHM_UEA1,
            SECURITY_ALGORITHM_UEA2, SECURITY_ALGORITHM_EEA0, SECURITY_ALGORITHM_EEA1,
            SECURITY_ALGORITHM_EEA2, SECURITY_ALGORITHM_EEA3, SECURITY_ALGORITHM_NEA0,
            SECURITY_ALGORITHM_NEA1, SECURITY_ALGORITHM_NEA2, SECURITY_ALGORITHM_NEA3,
            SECURITY_ALGORITHM_SIP_NO_IPSEC_CONFIG, SECURITY_ALGORITHM_IMS_NULL,
            SECURITY_ALGORITHM_SIP_NULL, SECURITY_ALGORITHM_AES_GCM,
            SECURITY_ALGORITHM_AES_GMAC, SECURITY_ALGORITHM_AES_CBC,
            SECURITY_ALGORITHM_DES_EDE3_CBC, SECURITY_ALGORITHM_AES_EDE3_CBC,
            SECURITY_ALGORITHM_HMAC_SHA1_96, SECURITY_ALGORITHM_HMAC_MD5_96,
            SECURITY_ALGORITHM_RTP, SECURITY_ALGORITHM_SRTP_NULL,
            SECURITY_ALGORITHM_SRTP_AES_COUNTER, SECURITY_ALGORITHM_SRTP_AES_F8,
            SECURITY_ALGORITHM_SRTP_HMAC_SHA1, SECURITY_ALGORITHM_ENCR_AES_GCM_16,
            SECURITY_ALGORITHM_ENCR_AES_CBC, SECURITY_ALGORITHM_AUTH_HMAC_SHA2_256_128,
            SECURITY_ALGORITHM_UNKNOWN, SECURITY_ALGORITHM_OTHER, SECURITY_ALGORITHM_ORYX})
    public @interface SecurityAlgorithm {
    }

    private @ConnectionEvent int mConnectionEvent;
    private @SecurityAlgorithm int mEncryption;
    private @SecurityAlgorithm int mIntegrity;
    private boolean mIsUnprotectedEmergency;

    /**
     * Constructor for new SecurityAlgorithmUpdate instances.
     *
     * @hide
     */
    @TestApi
    public SecurityAlgorithmUpdate(@ConnectionEvent int connectionEvent,
            @SecurityAlgorithm int encryption, @SecurityAlgorithm int integrity,
            boolean isUnprotectedEmergency) {
        mConnectionEvent = connectionEvent;
        mEncryption = encryption;
        mIntegrity = integrity;
        mIsUnprotectedEmergency = isUnprotectedEmergency;
    }

    private SecurityAlgorithmUpdate(Parcel in) {
        readFromParcel(in);
    }

    /**
     * @return the connection event.
     */
    public @ConnectionEvent int getConnectionEvent() {
        return mConnectionEvent;
    }

    /**
     * @return the encryption algorithm.
     */
    public @SecurityAlgorithm int getEncryption() {
        return mEncryption;
    }

    /**
     * @return the integrity algorithm.
     */
    public @SecurityAlgorithm int getIntegrity() {
        return mIntegrity;
    }

    /**
     * @return if the security algorithm update is associated with an unprotected emergency call.
     */
    public boolean isUnprotectedEmergency() {
        return mIsUnprotectedEmergency;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mConnectionEvent);
        out.writeInt(mEncryption);
        out.writeInt(mIntegrity);
        out.writeBoolean(mIsUnprotectedEmergency);
    }

    private void readFromParcel(@NonNull Parcel in) {
        mConnectionEvent = in.readInt();
        mEncryption = in.readInt();
        mIntegrity = in.readInt();
        mIsUnprotectedEmergency = in.readBoolean();
    }

    public static final @NonNull Parcelable.Creator<SecurityAlgorithmUpdate> CREATOR =
            new Parcelable.Creator<SecurityAlgorithmUpdate>() {
                public SecurityAlgorithmUpdate createFromParcel(Parcel in) {
                    return new SecurityAlgorithmUpdate(in);
                }

                public SecurityAlgorithmUpdate[] newArray(int size) {
                    return new SecurityAlgorithmUpdate[size];
                }
            };

    @Override
    public String toString() {
        return TAG + ":{ mConnectionEvent = " + mConnectionEvent + " mEncryption = " + mEncryption
                + " mIntegrity = " + mIntegrity + " mIsUnprotectedEmergency = "
                + mIsUnprotectedEmergency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecurityAlgorithmUpdate)) return false;
        SecurityAlgorithmUpdate that = (SecurityAlgorithmUpdate) o;
        return mConnectionEvent == that.mConnectionEvent
                && mEncryption == that.mEncryption
                && mIntegrity == that.mIntegrity
                && mIsUnprotectedEmergency == that.mIsUnprotectedEmergency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mConnectionEvent, mEncryption, mIntegrity, mIsUnprotectedEmergency);
    }
}
