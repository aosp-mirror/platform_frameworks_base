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
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * CellIdentity represents the identity of a unique cell. This is the base class for
 * CellIdentityXxx which represents cell identity for specific network access technology.
 */
public abstract class CellIdentity implements Parcelable {
    /**
     * Cell identity type
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "TYPE_", value = {TYPE_GSM, TYPE_CDMA, TYPE_LTE, TYPE_WCDMA, TYPE_TDSCDMA})
    public @interface Type {}

    /**
     * Unknown cell identity type
     * @hide
     */
    public static final int TYPE_UNKNOWN        = 0;
    /**
     * GSM cell identity type
     * @hide
     */
    public static final int TYPE_GSM            = 1;
    /**
     * CDMA cell identity type
     * @hide
     */
    public static final int TYPE_CDMA           = 2;
    /**
     * LTE cell identity type
     * @hide
     */
    public static final int TYPE_LTE            = 3;
    /**
     * WCDMA cell identity type
     * @hide
     */
    public static final int TYPE_WCDMA          = 4;
    /**
     * TDS-CDMA cell identity type
     * @hide
     */
    public static final int TYPE_TDSCDMA        = 5;

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
    public @Type int getType() { return mType; }

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

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CellIdentity)) {
            return false;
        }

        CellIdentity o = (CellIdentity) other;
        return TextUtils.equals(mAlphaLong, o.mAlphaLong)
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
    public static final Creator<CellIdentity> CREATOR =
            new Creator<CellIdentity>() {
                @Override
                public CellIdentity createFromParcel(Parcel in) {
                    int type = in.readInt();
                    switch (type) {
                        case TYPE_GSM: return CellIdentityGsm.createFromParcelBody(in);
                        case TYPE_WCDMA: return CellIdentityWcdma.createFromParcelBody(in);
                        case TYPE_CDMA: return CellIdentityCdma.createFromParcelBody(in);
                        case TYPE_LTE: return CellIdentityLte.createFromParcelBody(in);
                        case TYPE_TDSCDMA: return CellIdentityTdscdma.createFromParcelBody(in);
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