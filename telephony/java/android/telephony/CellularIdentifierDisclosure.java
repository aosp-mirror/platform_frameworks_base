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
 * A single occurrence of a cellular identifier being disclosed in the clear before a security
 * context is established.
 *
 * @hide
 */
public final class CellularIdentifierDisclosure implements Parcelable {
    private static final String TAG = "CellularIdentifierDisclosure";

    private @NasProtocolMessage int mNasProtocolMessage;
    private @CellularIdentifier int mCellularIdentifier;
    private String mPlmn;
    private boolean mIsEmergency;

    public CellularIdentifierDisclosure(@NasProtocolMessage int nasProtocolMessage,
            @CellularIdentifier int cellularIdentifier, String plmn, boolean isEmergency) {
        mNasProtocolMessage = nasProtocolMessage;
        mCellularIdentifier = cellularIdentifier;
        mPlmn = plmn;
        mIsEmergency = isEmergency;
    }

    private CellularIdentifierDisclosure(Parcel in) {
        readFromParcel(in);
    }

    public @NasProtocolMessage int getNasProtocolMessage() {
        return mNasProtocolMessage;
    }

    public @CellularIdentifier int getCellularIdentifier() {
        return mCellularIdentifier;
    }

    public String getPlmn() {
        return mPlmn;
    }

    public boolean isEmergency() {
        return mIsEmergency;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mNasProtocolMessage);
        out.writeInt(mCellularIdentifier);
        out.writeBoolean(mIsEmergency);
        out.writeString8(mPlmn);
    }

    public static final Parcelable.Creator<CellularIdentifierDisclosure> CREATOR =
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

    public static final int NAS_PROTOCOL_MESSAGE_UNKNOWN = 0;
    public static final int NAS_PROTOCOL_MESSAGE_ATTACH_REQUEST = 1;
    public static final int NAS_PROTOCOL_MESSAGE_IDENTITY_RESPONSE = 2;
    public static final int NAS_PROTOCOL_MESSAGE_DETACH_REQUEST = 3;
    public static final int NAS_PROTOCOL_MESSAGE_TRACKING_AREA_UPDATE_REQUEST = 4;
    public static final int NAS_PROTOCOL_MESSAGE_LOCATION_UPDATE_REQUEST = 5;
    public static final int NAS_PROTOCOL_MESSAGE_AUTHENTICATION_AND_CIPHERING_RESPONSE = 6;
    public static final int NAS_PROTOCOL_MESSAGE_REGISTRATION_REQUEST = 7;
    public static final int NAS_PROTOCOL_MESSAGE_DEREGISTRATION_REQUEST = 8;
    public static final int NAS_PROTOCOL_MESSAGE_CM_REESTABLISHMENT_REQUEST = 9;
    public static final int NAS_PROTOCOL_MESSAGE_CM_SERVICE_REQUEST = 10;
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

    public static final int CELLULAR_IDENTIFIER_UNKNOWN = 0;
    public static final int CELLULAR_IDENTIFIER_IMSI = 1;
    public static final int CELLULAR_IDENTIFIER_IMEI = 2;
    public static final int CELLULAR_IDENTIFIER_SUCI = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CELLULAR_IDENTIFIER_"}, value = {CELLULAR_IDENTIFIER_UNKNOWN,
            CELLULAR_IDENTIFIER_IMSI, CELLULAR_IDENTIFIER_IMEI, CELLULAR_IDENTIFIER_SUCI})
    public @interface CellularIdentifier {
    }
}
