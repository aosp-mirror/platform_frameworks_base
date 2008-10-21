/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.location;

import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.gsm.GsmCellLocation;
import com.android.internal.telephony.TelephonyProperties;

import android.os.SystemProperties;

/**
 * Stores the cell tower state
 *
 * {@hide}
 */
public class CellState {
    
    public static String TAG = "CellState";

    public static int RADIO_TYPE_GPRS = 1;
    public static int RADIO_TYPE_CDMA = 2;
    public static int RADIO_TYPE_WCDMA = 3;

    private int mCid = -1;
    private int mLac = -1;
    private int mMcc = -1;
    private int mMnc = -1;
    private int mHomeMcc = -1;
    private int mHomeMnc = -1;
    private String mCarrier = null;
    private int mRadioType = -1;
    private long mTime = 0;

    public CellState() {
        // constructor for invalid cell location
    }

    public CellState(ServiceState service, CellLocation location) {
        GsmCellLocation loc = (GsmCellLocation)location;
        mLac = loc.getLac(); // example: 6032
        mCid = loc.getCid(); // example: 31792
        mTime = System.currentTimeMillis();

        // Get radio type
        String radioType = SystemProperties.get(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE);
        if (radioType != null && (radioType.equals("GPRS") || radioType.equals("EDGE"))) {
            mRadioType = RADIO_TYPE_GPRS;
        } else if (radioType != null && radioType.equals("UMTS")) {
            mRadioType = RADIO_TYPE_WCDMA;
        }

        // Get MCC/MNC
        String operator = service.getOperatorNumeric();
        if (operator != null && !operator.equals("")) {
            String mcc = operator.substring(0, 3);
            String mnc = operator.substring(3);

            mMcc = Integer.parseInt(mcc);
            mMnc = Integer.parseInt(mnc);
        }

        // Get Home MCC/MNC
        String homeOperator = SystemProperties.get(TelephonyProperties.PROPERTY_SIM_OPERATOR_NUMERIC);
        if (homeOperator != null && !homeOperator.equals("")) {
            String mcc = homeOperator.substring(0, 3);
            String mnc = homeOperator.substring(3);

            mHomeMcc = Integer.parseInt(mcc);
            mHomeMnc = Integer.parseInt(mnc);
        }

        // Get Carrier
        String carrier = service.getOperatorAlphaLong();
        if (carrier != null && !carrier.equals("")) {
            mCarrier = carrier;
        }

        //Log.d(TAG, mLac +"," + mCid + "," + mMnc +"," + mMcc + "," + mHomeMcc + "," +
        //    mHomeMnc + "," + mCarrier + "," + mRadioType);
    }

    public int getCid() {
        return mCid;
    }

    public int getLac() {
        return mLac;
    }

    public int getMcc() {
        return mMcc;
    }

    public int getMnc() {
        return mMnc;
    }

    public int getHomeMcc() {
        return mHomeMcc;
    }

    public void setHomeMcc(int homeMcc) {
        this.mHomeMcc = homeMcc;
    }

    public int getHomeMnc() {
        return mHomeMnc;
    }

    public void setHomeMnc(int homeMnc) {
        this.mHomeMnc = homeMnc;
    }

    public String getCarrier() {
        return mCarrier;
    }

    public void setCarrier(String carrier) {
        this.mCarrier = carrier;
    }

    public int getRadioType() {
        return mRadioType;
    }

    public long getTime() {
        return mTime;
    }

    public boolean equals(CellState other) {
        return (mCid == other.mCid && mLac == other.mLac);
    }

    public boolean isValid() {
        return (mCid != -1 && mLac != -1);
    }
}
