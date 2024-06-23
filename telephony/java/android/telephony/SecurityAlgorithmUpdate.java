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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A single occurrence capturing a notable change to previously reported
 * cryptography algorithms for a given network and network event.
 *
 * @hide
 */
public final class SecurityAlgorithmUpdate implements Parcelable {
    private static final String TAG = "SecurityAlgorithmUpdate";

    private @ConnectionEvent int mConnectionEvent;
    private @SecurityAlgorithm int mEncryption;
    private @SecurityAlgorithm int mIntegrity;
    private boolean mIsUnprotectedEmergency;

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

    public @ConnectionEvent int getConnectionEvent() {
        return mConnectionEvent;
    }

    public @SecurityAlgorithm int getEncryption() {
        return mEncryption;
    }

    public @SecurityAlgorithm int getIntegrity() {
        return mIntegrity;
    }

    public boolean isUnprotectedEmergency() {
        return mIsUnprotectedEmergency;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
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

    public static final Parcelable.Creator<SecurityAlgorithmUpdate> CREATOR =
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

    public static final int CONNECTION_EVENT_CS_SIGNALLING_GSM = 0;
    public static final int CONNECTION_EVENT_PS_SIGNALLING_GPRS = 1;
    public static final int CONNECTION_EVENT_CS_SIGNALLING_3G = 2;
    public static final int CONNECTION_EVENT_PS_SIGNALLING_3G = 3;
    public static final int CONNECTION_EVENT_NAS_SIGNALLING_LTE = 4;
    public static final int CONNECTION_EVENT_AS_SIGNALLING_LTE = 5;
    public static final int CONNECTION_EVENT_VOLTE_SIP = 6;
    public static final int CONNECTION_EVENT_VOLTE_SIP_SOS = 7;
    public static final int CONNECTION_EVENT_VOLTE_RTP = 8;
    public static final int CONNECTION_EVENT_VOLTE_RTP_SOS = 9;
    public static final int CONNECTION_EVENT_NAS_SIGNALLING_5G = 10;
    public static final int CONNECTION_EVENT_AS_SIGNALLING_5G = 11;
    public static final int CONNECTION_EVENT_VONR_SIP = 12;
    public static final int CONNECTION_EVENT_VONR_SIP_SOS = 13;
    public static final int CONNECTION_EVENT_VONR_RTP = 14;
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

    public static final int SECURITY_ALGORITHM_A50 = 0;
    public static final int SECURITY_ALGORITHM_A51 = 1;
    public static final int SECURITY_ALGORITHM_A52 = 2;
    public static final int SECURITY_ALGORITHM_A53 = 3;
    public static final int SECURITY_ALGORITHM_A54 = 4;
    public static final int SECURITY_ALGORITHM_GEA0 = 14;
    public static final int SECURITY_ALGORITHM_GEA1 = 15;
    public static final int SECURITY_ALGORITHM_GEA2 = 16;
    public static final int SECURITY_ALGORITHM_GEA3 = 17;
    public static final int SECURITY_ALGORITHM_GEA4 = 18;
    public static final int SECURITY_ALGORITHM_GEA5 = 19;
    public static final int SECURITY_ALGORITHM_UEA0 = 29;
    public static final int SECURITY_ALGORITHM_UEA1 = 30;
    public static final int SECURITY_ALGORITHM_UEA2 = 31;
    public static final int SECURITY_ALGORITHM_EEA0 = 41;
    public static final int SECURITY_ALGORITHM_EEA1 = 42;
    public static final int SECURITY_ALGORITHM_EEA2 = 43;
    public static final int SECURITY_ALGORITHM_EEA3 = 44;
    public static final int SECURITY_ALGORITHM_NEA0 = 55;
    public static final int SECURITY_ALGORITHM_NEA1 = 56;
    public static final int SECURITY_ALGORITHM_NEA2 = 57;
    public static final int SECURITY_ALGORITHM_NEA3 = 58;
    public static final int SECURITY_ALGORITHM_SIP_NO_IPSEC_CONFIG = 66;
    public static final int SECURITY_ALGORITHM_IMS_NULL = 67;
    public static final int SECURITY_ALGORITHM_SIP_NULL = 68;
    public static final int SECURITY_ALGORITHM_AES_GCM = 69;
    public static final int SECURITY_ALGORITHM_AES_GMAC = 70;
    public static final int SECURITY_ALGORITHM_AES_CBC = 71;
    public static final int SECURITY_ALGORITHM_DES_EDE3_CBC = 72;
    public static final int SECURITY_ALGORITHM_AES_EDE3_CBC = 73;
    public static final int SECURITY_ALGORITHM_HMAC_SHA1_96 = 74;
    public static final int SECURITY_ALGORITHM_HMAC_MD5_96 = 75;
    public static final int SECURITY_ALGORITHM_RTP = 85;
    public static final int SECURITY_ALGORITHM_SRTP_NULL = 86;
    public static final int SECURITY_ALGORITHM_SRTP_AES_COUNTER = 87;
    public static final int SECURITY_ALGORITHM_SRTP_AES_F8 = 88;
    public static final int SECURITY_ALGORITHM_SRTP_HMAC_SHA1 = 89;
    public static final int SECURITY_ALGORITHM_ENCR_AES_GCM_16 = 99;
    public static final int SECURITY_ALGORITHM_ENCR_AES_CBC = 100;
    public static final int SECURITY_ALGORITHM_AUTH_HMAC_SHA2_256_128 = 101;
    public static final int SECURITY_ALGORITHM_UNKNOWN = 113;
    public static final int SECURITY_ALGORITHM_OTHER = 114;
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

}
