/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;
import java.util.UUID;

/**
 * CellIdentity represents the identity of a unique cell. This is the base class for
 * CellIdentityXxx which represents cell identity for specific network access technology.
 */
public abstract class CellIdentity implements Parcelable {

    /** @hide */
    public static final int INVALID_CHANNEL_NUMBER = -1;

    // Log tag
    /** @hide */
    protected final String mTag;
    // Cell identity type
    /** @hide */
    protected final int mType;
    // 3-digit Mobile Country Code in string format. Null for CDMA cell identity.
    /** @hide */
    protected final String mMccStr;
    // 2 or 3-digit Mobile Network Code in string format. Null for CDMA cell identity.
    /** @hide */
    protected final String mMncStr;

    // long alpha Operator Name String or Enhanced Operator Name String
    /** @hide */
    protected final String mAlphaLong;
    // short alpha Operator Name String or Enhanced Operator Name String
    /** @hide */
    protected final String mAlphaShort;

    /** @hide */
    protected CellIdentity(String tag, int type, String mcc, String mnc, String alphal,
                           String alphas) {
        mTag = tag;
        mType = type;

        // Only allow INT_MAX if unknown string mcc/mnc
        if (mcc == null || mcc.matches("^[0-9]{3}$")) {
            mMccStr = mcc;
        } else if (mcc.isEmpty() || mcc.equals(String.valueOf(Integer.MAX_VALUE))) {
            // If the mccStr is empty or unknown, set it as null.
            mMccStr = null;
        } else {
            // TODO: b/69384059 Should throw IllegalArgumentException for the invalid MCC format
            // after the bug got fixed.
            mMccStr = null;
            log("invalid MCC format: " + mcc);
        }

        if (mnc == null || mnc.matches("^[0-9]{2,3}$")) {
            mMncStr = mnc;
        } else if (mnc.isEmpty() || mnc.equals(String.valueOf(Integer.MAX_VALUE))) {
            // If the mncStr is empty or unknown, set it as null.
            mMncStr = null;
        } else {
            // TODO: b/69384059 Should throw IllegalArgumentException for the invalid MNC format
            // after the bug got fixed.
            mMncStr = null;
            log("invalid MNC format: " + mnc);
        }

        if ((mMccStr != null && mMncStr == null) || (mMccStr == null && mMncStr != null)) {
            AnomalyReporter.reportAnomaly(
                    UUID.fromString("a3ab0b9d-f2aa-4baf-911d-7096c0d4645a"),
                    "CellIdentity Missing Half of PLMN ID");
        }

        mAlphaLong = alphal;
        mAlphaShort = alphas;
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     * @return The type of the cell identity
     */
    public @CellInfo.Type int getType() {
        return mType;
    }

    /**
     * Returns the channel number of the cell identity.
     *
     * @hide
     * @return The channel number, or {@link #INVALID_CHANNEL_NUMBER} if not implemented
     */
    public int getChannelNumber() {
        return INVALID_CHANNEL_NUMBER;
    }

    /**
     * @return The long alpha tag associated with the current scan result (may be the operator
     * name string or extended operator name string). May be null if unknown.
     */
    @Nullable
    public CharSequence getOperatorAlphaLong() {
        return mAlphaLong;
    }

    /**
     * @return The short alpha tag associated with the current scan result (may be the operator
     * name string or extended operator name string).  May be null if unknown.
     */
    @Nullable
    public CharSequence getOperatorAlphaShort() {
        return mAlphaShort;
    }

    /**
     * @return a CellLocation object for this CellIdentity
     * @hide
     */
    public abstract CellLocation asCellLocation();

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CellIdentity)) {
            return false;
        }

        CellIdentity o = (CellIdentity) other;
        return mType == o.mType
                && TextUtils.equals(mMccStr, o.mMccStr)
                && TextUtils.equals(mMncStr, o.mMncStr)
                && TextUtils.equals(mAlphaLong, o.mAlphaLong)
                && TextUtils.equals(mAlphaShort, o.mAlphaShort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAlphaLong, mAlphaShort, mMccStr, mMncStr, mType);
    }

    /**
     * Used by child classes for parceling.
     *
     * @hide
     */
    @CallSuper
    public void writeToParcel(Parcel dest, int type) {
        dest.writeInt(type);
        dest.writeString(mMccStr);
        dest.writeString(mMncStr);
        dest.writeString(mAlphaLong);
        dest.writeString(mAlphaShort);
    }

    /**
     * Construct from Parcel
     * @hide
     */
    protected CellIdentity(String tag, int type, Parcel source) {
        this(tag, type, source.readString(), source.readString(),
                source.readString(), source.readString());
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<CellIdentity> CREATOR =
            new Creator<CellIdentity>() {
                @Override
                public CellIdentity createFromParcel(Parcel in) {
                    int type = in.readInt();
                    switch (type) {
                        case CellInfo.TYPE_GSM: return CellIdentityGsm.createFromParcelBody(in);
                        case CellInfo.TYPE_WCDMA: return CellIdentityWcdma.createFromParcelBody(in);
                        case CellInfo.TYPE_CDMA: return CellIdentityCdma.createFromParcelBody(in);
                        case CellInfo.TYPE_LTE: return CellIdentityLte.createFromParcelBody(in);
                        case CellInfo.TYPE_TDSCDMA:
                            return CellIdentityTdscdma.createFromParcelBody(in);
                        case CellInfo.TYPE_NR: return CellIdentityNr.createFromParcelBody(in);
                        default: throw new IllegalArgumentException("Bad Cell identity Parcel");
                    }
                }

                @Override
                public CellIdentity[] newArray(int size) {
                    return new CellIdentity[size];
                }
            };

    /** @hide */
    protected void log(String s) {
        Rlog.w(mTag, s);
    }
}
