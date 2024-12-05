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
 * A single occurrence of a cellular identifier being disclosed in the clear before a security
 * context is established.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CELLULAR_IDENTIFIER_DISCLOSURE_INDICATIONS)
public final class CellularIdentifierDisclosure implements Parcelable {
    private static final String TAG = "CellularIdentifierDisclosure";

    /* Non-access stratum protocol messages */
    /** Unknown */
    public static final int NAS_PROTOCOL_MESSAGE_UNKNOWN = 0;
    /** ATTACH REQUESTS. Sample reference: TS 24.301 8.2.4 Applies to 2g, 3g, and 4g networks */
    public static final int NAS_PROTOCOL_MESSAGE_ATTACH_REQUEST = 1;
    /** IDENTITY RESPONSE. Sample Reference: TS 24.301 8.2.19.
     * Applies to 2g, 3g, 4g, and 5g networks */
    public static final int NAS_PROTOCOL_MESSAGE_IDENTITY_RESPONSE = 2;
    /** DETACH_REQUEST. Sample Reference: TS 24.301 8.2.11. Applies to 2g, 3g, and 4g networks */
    public static final int NAS_PROTOCOL_MESSAGE_DETACH_REQUEST = 3;
    /** TRACKING AREA UPDATE (TAU) REQUEST. Sample Reference: 3GPP TS 24.301 8.2.29.
     * Note: that per the spec, only temporary IDs should be sent in the TAU Request, but since the
     * EPS Mobile Identity field supports IMSIs, this is included as an extra safety measure to
     * combat implementation bugs. Applies to 4g and 5g networks. */
    public static final int NAS_PROTOCOL_MESSAGE_TRACKING_AREA_UPDATE_REQUEST = 4;
    /** LOCATION UPDATE REQUEST. Sample Reference: TS 24.008 4.4.3. Applies to 2g and 3g networks */
    public static final int NAS_PROTOCOL_MESSAGE_LOCATION_UPDATE_REQUEST = 5;
    /** AUTHENTICATION AND CIPHERING RESPONSE. Reference: 3GPP TS 24.008 4.7.7.1.
     * Applies to 2g and 3g networks */
    public static final int NAS_PROTOCOL_MESSAGE_AUTHENTICATION_AND_CIPHERING_RESPONSE = 6;
    /** REGISTRATION REQUEST. Reference: 3GPP TS 24.501 8.2.6. Applies to 5g networks */
    public static final int NAS_PROTOCOL_MESSAGE_REGISTRATION_REQUEST = 7;
    /** DEREGISTRATION REQUEST. Reference: 3GPP TS 24.501 8.2.12. Applies to 5g networks */
    public static final int NAS_PROTOCOL_MESSAGE_DEREGISTRATION_REQUEST = 8;
    /** CONNECTION MANAGEMENT REESTABLISHMENT REQUEST. Reference: 3GPP TS 24.008 9.2.4.
     * Applies to 2g and 3g networks */
    public static final int NAS_PROTOCOL_MESSAGE_CM_REESTABLISHMENT_REQUEST = 9;
    /** CONNECTION MANAGEMENT SERVICE REQUEST. Reference: 3GPP TS 24.008 9.2.9.
     * Applies to 2g and 3g networks */
    public static final int NAS_PROTOCOL_MESSAGE_CM_SERVICE_REQUEST = 10;
    /** IMEI DETATCH INDICATION. Reference: 3GPP TS 24.008 9.2.14.
     * Applies to 2g and 3g networks. Used for circuit-switched detach. */
    public static final int NAS_PROTOCOL_MESSAGE_IMSI_DETACH_INDICATION = 11;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NAS_PROTOCOL_MESSAGE_"}, value = {NAS_PROTOCOL_MESSAGE_UNKNOWN,
            NAS_PROTOCOL_MESSAGE_ATTACH_REQUEST, NAS_PROTOCOL_MESSAGE_IDENTITY_RESPONSE,
            NAS_PROTOCOL_MESSAGE_DETACH_REQUEST, NAS_PROTOCOL_MESSAGE_TRACKING_AREA_UPDATE_REQUEST,
            NAS_PROTOCOL_MESSAGE_LOCATION_UPDATE_REQUEST,
            NAS_PROTOCOL_MESSAGE_AUTHENTICATION_AND_CIPHERING_RESPONSE,
            NAS_PROTOCOL_MESSAGE_REGISTRATION_REQUEST, NAS_PROTOCOL_MESSAGE_DEREGISTRATION_REQUEST,
            NAS_PROTOCOL_MESSAGE_CM_REESTABLISHMENT_REQUEST,
            NAS_PROTOCOL_MESSAGE_CM_SERVICE_REQUEST, NAS_PROTOCOL_MESSAGE_IMSI_DETACH_INDICATION})
    public @interface NasProtocolMessage {
    }

    /* Cellular identifiers */
    /** Unknown */
    public static final int CELLULAR_IDENTIFIER_UNKNOWN = 0;
    /** IMSI (International Mobile Subscriber Identity) */
    public static final int CELLULAR_IDENTIFIER_IMSI = 1;
    /** IMEI (International Mobile Equipment Identity) */
    public static final int CELLULAR_IDENTIFIER_IMEI = 2;
    /** 5G-specific SUCI (Subscription Concealed Identifier) */
    public static final int CELLULAR_IDENTIFIER_SUCI = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CELLULAR_IDENTIFIER_"}, value = {CELLULAR_IDENTIFIER_UNKNOWN,
            CELLULAR_IDENTIFIER_IMSI, CELLULAR_IDENTIFIER_IMEI, CELLULAR_IDENTIFIER_SUCI})
    public @interface CellularIdentifier {
    }

    private @NasProtocolMessage int mNasProtocolMessage;
    private @CellularIdentifier int mCellularIdentifier;
    private String mPlmn;
    private boolean mIsEmergency;

    /**
     * Constructor for new CellularIdentifierDisclosure instances.
     *
     * @hide
     */
    @TestApi
    public CellularIdentifierDisclosure(@NasProtocolMessage int nasProtocolMessage,
            @CellularIdentifier int cellularIdentifier, @NonNull String plmn, boolean isEmergency) {
        mNasProtocolMessage = nasProtocolMessage;
        mCellularIdentifier = cellularIdentifier;
        mPlmn = plmn;
        mIsEmergency = isEmergency;
    }

    private CellularIdentifierDisclosure(Parcel in) {
        readFromParcel(in);
    }

    /**
     * @return the NAS protocol message associated with the disclosed identifier.
     */
    public @NasProtocolMessage int getNasProtocolMessage() {
        return mNasProtocolMessage;
    }

    /**
     * @return the identifier disclosed.
     */
    public @CellularIdentifier int getCellularIdentifier() {
        return mCellularIdentifier;
    }

    /**
     * @return the PLMN associated with the disclosure.
     */
    @NonNull public String getPlmn() {
        return mPlmn;
    }

    /**
     * @return if the disclosure is associated with an emergency call.
     */
    public boolean isEmergency() {
        return mIsEmergency;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mNasProtocolMessage);
        out.writeInt(mCellularIdentifier);
        out.writeBoolean(mIsEmergency);
        out.writeString8(mPlmn);
    }

    public static final @NonNull Parcelable.Creator<CellularIdentifierDisclosure> CREATOR =
            new Parcelable.Creator<CellularIdentifierDisclosure>() {
                public CellularIdentifierDisclosure createFromParcel(Parcel in) {
                    return new CellularIdentifierDisclosure(in);
                }

                public CellularIdentifierDisclosure[] newArray(int size) {
                    return new CellularIdentifierDisclosure[size];
                }
            };

    @Override
    public String toString() {
        return TAG + ":{ mNasProtocolMessage = " + mNasProtocolMessage
                + " mCellularIdentifier = " + mCellularIdentifier + " mIsEmergency = "
                + mIsEmergency + " mPlmn = " + mPlmn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CellularIdentifierDisclosure)) return false;
        CellularIdentifierDisclosure that = (CellularIdentifierDisclosure) o;
        return mNasProtocolMessage == that.mNasProtocolMessage
                && mCellularIdentifier == that.mCellularIdentifier
                && mIsEmergency == that.mIsEmergency && mPlmn.equals(that.mPlmn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNasProtocolMessage, mCellularIdentifier, mIsEmergency,
                mPlmn);
    }

    private void readFromParcel(@NonNull Parcel in) {
        mNasProtocolMessage = in.readInt();
        mCellularIdentifier = in.readInt();
        mIsEmergency = in.readBoolean();
        mPlmn = in.readString8();
    }
}
