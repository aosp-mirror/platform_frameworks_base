/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.annotation.UnsupportedAppUsage;
import android.hardware.radio.V1_4.CellInfo.Info;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Immutable cell information from a point in time.
 */
public abstract class CellInfo implements Parcelable {

    /**
     * This value indicates that the integer field is unreported.
     */
    public static final int UNAVAILABLE = Integer.MAX_VALUE;

    /**
     * This value indicates that the long field is unreported.
     */
    public static final long UNAVAILABLE_LONG = Long.MAX_VALUE;

    /**
     * Cell identity type
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "TYPE_",
            value = {TYPE_GSM, TYPE_CDMA, TYPE_LTE, TYPE_WCDMA, TYPE_TDSCDMA, TYPE_NR})
    public @interface Type {}

    /**
     * Unknown cell identity type
     * @hide
     */
    public static final int TYPE_UNKNOWN = 0;

    /**
     * GSM cell identity type
     * @hide
     */
    public static final int TYPE_GSM = 1;

    /**
     * CDMA cell identity type
     * @hide
     */
    public static final int TYPE_CDMA = 2;

    /**
     * LTE cell identity type
     * @hide
     */
    public static final int TYPE_LTE = 3;

    /**
     * WCDMA cell identity type
     * @hide
     */
    public static final int TYPE_WCDMA = 4;

    /**
     * TD-SCDMA cell identity type
     * @hide
     */
    public static final int TYPE_TDSCDMA = 5;

    /**
     * 5G cell identity type
     * @hide
     */
    public static final int TYPE_NR = 6;

    // Type to distinguish where time stamp gets recorded.

    /** @hide */
    @UnsupportedAppUsage
    public static final int TIMESTAMP_TYPE_UNKNOWN = 0;
    /** @hide */
    @UnsupportedAppUsage
    public static final int TIMESTAMP_TYPE_ANTENNA = 1;
    /** @hide */
    @UnsupportedAppUsage
    public static final int TIMESTAMP_TYPE_MODEM = 2;
    /** @hide */
    @UnsupportedAppUsage
    public static final int TIMESTAMP_TYPE_OEM_RIL = 3;
    /** @hide */
    @UnsupportedAppUsage
    public static final int TIMESTAMP_TYPE_JAVA_RIL = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        CONNECTION_NONE,
        CONNECTION_PRIMARY_SERVING,
        CONNECTION_SECONDARY_SERVING,
        CONNECTION_UNKNOWN
    })
    public @interface CellConnectionStatus {}

    /**
     * Cell is not a serving cell.
     *
     * <p>The cell has been measured but is neither a camped nor serving cell (3GPP 36.304).
     */
    public static final int CONNECTION_NONE = 0;

    /** UE is connected to cell for signalling and possibly data (3GPP 36.331, 25.331). */
    public static final int CONNECTION_PRIMARY_SERVING = 1;

    /** UE is connected to cell for data (3GPP 36.331, 25.331). */
    public static final int CONNECTION_SECONDARY_SERVING = 2;

    /** Connection status is unknown. */
    public static final int CONNECTION_UNKNOWN = Integer.MAX_VALUE;

    /** A cell connection status */
    private int mCellConnectionStatus;

    // True if device is mRegistered to the mobile network
    private boolean mRegistered;

    // Observation time stamped as type in nanoseconds since boot
    private long mTimeStamp;

    /** @hide */
    protected CellInfo() {
        this.mRegistered = false;
        this.mTimeStamp = Long.MAX_VALUE;
        mCellConnectionStatus = CONNECTION_NONE;
    }

    /** @hide */
    protected CellInfo(CellInfo ci) {
        this.mRegistered = ci.mRegistered;
        this.mTimeStamp = ci.mTimeStamp;
        this.mCellConnectionStatus = ci.mCellConnectionStatus;
    }

    /**
     * True if the phone is registered to a mobile network that provides service on this cell
     * and this cell is being used or would be used for network signaling.
     */
    public boolean isRegistered() {
        return mRegistered;
    }

    /** @hide */
    public void setRegistered(boolean registered) {
        mRegistered = registered;
    }

    /**
     * Approximate time this cell information was received from the modem.
     *
     * @return a time stamp in nanos since boot.
     */
    public long getTimeStamp() {
        return mTimeStamp;
    }

    /** @hide */
    @VisibleForTesting
    public void setTimeStamp(long ts) {
        mTimeStamp = ts;
    }

    /** @hide */
    @NonNull
    public abstract CellIdentity getCellIdentity();

    /** @hide */
    @NonNull
    public abstract CellSignalStrength getCellSignalStrength();

    /** @hide */
    public CellInfo sanitizeLocationInfo() {
        return null;
    }

    /**
     * Gets the connection status of this cell.
     *
     * @see #CONNECTION_NONE
     * @see #CONNECTION_PRIMARY_SERVING
     * @see #CONNECTION_SECONDARY_SERVING
     * @see #CONNECTION_UNKNOWN
     *
     * @return The connection status of the cell.
     */
    @CellConnectionStatus
    public int getCellConnectionStatus() {
        return mCellConnectionStatus;
    }
    /** @hide */
    public void setCellConnectionStatus(@CellConnectionStatus int cellConnectionStatus) {
        mCellConnectionStatus = cellConnectionStatus;
    }

    @Override
    public int hashCode() {
        int primeNum = 31;
        return ((mRegistered ? 0 : 1) * primeNum) + ((int)(mTimeStamp / 1000) * primeNum)
                + (mCellConnectionStatus * primeNum);
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (this == other) {
            return true;
        }
        try {
            CellInfo o = (CellInfo) other;
            return mRegistered == o.mRegistered
                    && mTimeStamp == o.mTimeStamp
                    && mCellConnectionStatus == o.mCellConnectionStatus;
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("mRegistered=").append(mRegistered ? "YES" : "NO");
        sb.append(" mTimeStamp=").append(mTimeStamp).append("ns");
        sb.append(" mCellConnectionStatus=").append(mCellConnectionStatus);

        return sb.toString();
    }

    /**
     * Implement the Parcelable interface
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public abstract void writeToParcel(Parcel dest, int flags);

    /**
     * Used by child classes for parceling.
     *
     * @hide
     */
    protected void writeToParcel(Parcel dest, int flags, int type) {
        dest.writeInt(type);
        dest.writeInt(mRegistered ? 1 : 0);
        dest.writeLong(mTimeStamp);
        dest.writeInt(mCellConnectionStatus);
    }

    /**
     * Used by child classes for parceling
     *
     * @hide
     */
    protected CellInfo(Parcel in) {
        mRegistered = (in.readInt() == 1) ? true : false;
        mTimeStamp = in.readLong();
        mCellConnectionStatus = in.readInt();
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<CellInfo> CREATOR = new Creator<CellInfo>() {
        @Override
        public CellInfo createFromParcel(Parcel in) {
                int type = in.readInt();
                switch (type) {
                    case TYPE_GSM: return CellInfoGsm.createFromParcelBody(in);
                    case TYPE_CDMA: return CellInfoCdma.createFromParcelBody(in);
                    case TYPE_LTE: return CellInfoLte.createFromParcelBody(in);
                    case TYPE_WCDMA: return CellInfoWcdma.createFromParcelBody(in);
                    case TYPE_TDSCDMA: return CellInfoTdscdma.createFromParcelBody(in);
                    case TYPE_NR: return CellInfoNr.createFromParcelBody(in);
                    default: throw new RuntimeException("Bad CellInfo Parcel");
                }
        }

        @Override
        public CellInfo[] newArray(int size) {
            return new CellInfo[size];
        }
    };

    /** @hide */
    protected CellInfo(android.hardware.radio.V1_0.CellInfo ci) {
        this.mRegistered = ci.registered;
        this.mTimeStamp = ci.timeStamp;
        this.mCellConnectionStatus = CONNECTION_UNKNOWN;
    }

    /** @hide */
    protected CellInfo(android.hardware.radio.V1_2.CellInfo ci) {
        this.mRegistered = ci.registered;
        this.mTimeStamp = ci.timeStamp;
        this.mCellConnectionStatus = ci.connectionStatus;
    }

    /** @hide */
    protected CellInfo(android.hardware.radio.V1_4.CellInfo ci, long timeStamp) {
        this.mRegistered = ci.isRegistered;
        this.mTimeStamp = timeStamp;
        this.mCellConnectionStatus = ci.connectionStatus;
    }

    /** @hide */
    public static CellInfo create(android.hardware.radio.V1_0.CellInfo ci) {
        if (ci == null) return null;
        switch(ci.cellInfoType) {
            case android.hardware.radio.V1_0.CellInfoType.GSM: return new CellInfoGsm(ci);
            case android.hardware.radio.V1_0.CellInfoType.CDMA: return new CellInfoCdma(ci);
            case android.hardware.radio.V1_0.CellInfoType.LTE: return new CellInfoLte(ci);
            case android.hardware.radio.V1_0.CellInfoType.WCDMA: return new CellInfoWcdma(ci);
            case android.hardware.radio.V1_0.CellInfoType.TD_SCDMA: return new CellInfoTdscdma(ci);
            default: return null;
        }
    }

    /** @hide */
    public static CellInfo create(android.hardware.radio.V1_2.CellInfo ci) {
        if (ci == null) return null;
        switch(ci.cellInfoType) {
            case android.hardware.radio.V1_0.CellInfoType.GSM: return new CellInfoGsm(ci);
            case android.hardware.radio.V1_0.CellInfoType.CDMA: return new CellInfoCdma(ci);
            case android.hardware.radio.V1_0.CellInfoType.LTE: return new CellInfoLte(ci);
            case android.hardware.radio.V1_0.CellInfoType.WCDMA: return new CellInfoWcdma(ci);
            case android.hardware.radio.V1_0.CellInfoType.TD_SCDMA: return new CellInfoTdscdma(ci);
            default: return null;
        }
    }

    /** @hide */
    public static CellInfo create(android.hardware.radio.V1_4.CellInfo ci, long timeStamp) {
        if (ci == null) return null;
        switch (ci.info.getDiscriminator()) {
            case Info.hidl_discriminator.gsm: return new CellInfoGsm(ci, timeStamp);
            case Info.hidl_discriminator.cdma: return new CellInfoCdma(ci, timeStamp);
            case Info.hidl_discriminator.lte: return new CellInfoLte(ci, timeStamp);
            case Info.hidl_discriminator.wcdma: return new CellInfoWcdma(ci, timeStamp);
            case Info.hidl_discriminator.tdscdma: return new CellInfoTdscdma(ci, timeStamp);
            case Info.hidl_discriminator.nr: return new CellInfoNr(ci, timeStamp);
            default: return null;
        }
    }
}
