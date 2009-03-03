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
import android.telephony.TelephonyManager;
import android.telephony.NeighboringCellInfo;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import com.android.internal.telephony.TelephonyProperties;

import java.util.List;
import java.util.ArrayList;

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
    private int mSignalStrength = -1;

    private List<NeighborCell> mNeighbors;

    public CellState() {
        // constructor for invalid cell location
    }

    public CellState(TelephonyManager telephonyManager, CellLocation location, int signalStrength) {
        GsmCellLocation loc = (GsmCellLocation)location;
        mLac = loc.getLac(); // example: 6032
        mCid = loc.getCid(); // example: 31792
        mTime = System.currentTimeMillis();

        // Get radio type
        int radioType = telephonyManager.getNetworkType();
        if (radioType == TelephonyManager.NETWORK_TYPE_GPRS ||
            radioType == TelephonyManager.NETWORK_TYPE_EDGE) {
            mRadioType = RADIO_TYPE_GPRS;
        } else if (radioType == TelephonyManager.NETWORK_TYPE_UMTS) {
            mRadioType = RADIO_TYPE_WCDMA;
        }

        // Get neighboring cells
        mNeighbors = new ArrayList<NeighborCell>();
        List<NeighboringCellInfo> neighboringCells = telephonyManager.getNeighboringCellInfo();
        if (neighboringCells != null) {
            for (NeighboringCellInfo n : neighboringCells) {
                if (n.getCid() == NeighboringCellInfo.UNKNOWN_CID) {
                    continue;
                }

                if (mRadioType == RADIO_TYPE_WCDMA) {
                    mNeighbors.add(new NeighborCell(-1, -1, n.getCid(), n.getRssi()));
                } else if (mRadioType == RADIO_TYPE_GPRS) {
                    try {
                        String hexCidLac = Integer.toHexString(n.getCid());
                        int l = hexCidLac.length();
                        if (l > 8) {
                            Log.w(TAG, "Unable to parse 2G Cell \"" + hexCidLac + "\"");
                            continue;
                        }
                        if (l < 8) {
                            for (int i = 0; i < (8-l); i++) {
                                hexCidLac = "0" + hexCidLac;
                            }
                        }
                        int lac = Integer.valueOf(hexCidLac.substring(0, 4), 16);
                        int cid  = Integer.valueOf(hexCidLac.substring(4), 16);
                        mNeighbors.add(new NeighborCell(cid, lac, -1, n.getRssi()));

                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing 2G Cell \"" + n.getCid() + "\"", e);
                    }
                }
            }
        }

        // Get MCC/MNC
        String operator = telephonyManager.getNetworkOperator();
        if (operator != null && !operator.equals("")) {
            // Use a try/catch block to detect index out of bounds or number format exceptions.
            // If an error occurs, both mMcc and mMnc will be equal to -1.
            try {
                String mcc = operator.substring(0, 3);
                String mnc = operator.substring(3);
                int mccTmp = Integer.parseInt(mcc);
                int mncTmp = Integer.parseInt(mnc);
                
                // Parsing succeeded, update the instance variables together
                mMcc = mccTmp;
                mMnc = mncTmp;
            } catch (Exception e) {
                Log.e(TAG, "Error parsing MCC/MNC from operator \"" + operator + "\"", e);
            }
        }

        // Get Home MCC/MNC
        String homeOperator = telephonyManager.getSimOperator();
        if (homeOperator != null && !homeOperator.equals("")) {
            // Use a try/catch block to detect index out of bounds or number format exceptions.
            // If an error occurs, both mHomeMcc and mHomeMnc will be equal to -1.
            try {
                String mcc = homeOperator.substring(0, 3);
                String mnc = homeOperator.substring(3);
                int mccTmp = Integer.parseInt(mcc);
                int mncTmp = Integer.parseInt(mnc);
                
                // Parsing succeeded, update the instance variables together
                mHomeMcc = mccTmp;
                mHomeMnc = mncTmp;
            } catch (Exception e) {
                Log.e(TAG, "Error parsing MCC/MNC from home operator \"" + homeOperator + "\"", e);
            }
        }

        // Get Carrier
        String carrier = telephonyManager.getNetworkOperatorName();
        if (carrier != null && !carrier.equals("")) {
            mCarrier = carrier;
        }

        // Initial signal strength
        mSignalStrength = signalStrength;

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            String neighbors = "[";
            for (NeighborCell n : mNeighbors) {
                neighbors += n.toString() + ",";
            }
            neighbors += "]";
            Log.d(TAG, "CellState(): " + mLac +"," + mCid + "," + mMnc +"," + mMcc + "," +
                mHomeMcc + "," + mHomeMnc + "," + mCarrier + "," + mRadioType + "," +
                mSignalStrength + "," + neighbors);
        }
    }

    public void updateRadioType(TelephonyManager telephonyManager) {
        // Get radio type
        int radioType = telephonyManager.getNetworkType();
        if (radioType == TelephonyManager.NETWORK_TYPE_GPRS ||
            radioType == TelephonyManager.NETWORK_TYPE_EDGE) {
            mRadioType = RADIO_TYPE_GPRS;
        } else if (radioType == TelephonyManager.NETWORK_TYPE_UMTS) {
            mRadioType = RADIO_TYPE_WCDMA;
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "updateRadioType(): " + mLac +"," + mCid + "," + mMnc +"," + mMcc + "," +
                mHomeMcc + "," + mHomeMnc + "," + mCarrier + "," + mRadioType + "," +
                mSignalStrength);
        }
    }

    public void updateSignalStrength(int signalStrength) {
        mSignalStrength = signalStrength;

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "updateSignal(): " + mLac +"," + mCid + "," + mMnc +"," + mMcc + "," + 
                mHomeMcc + "," + mHomeMnc + "," + mCarrier + "," + mRadioType + "," +
                mSignalStrength);
        }
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

    public int getSignalStrength() {
        return mSignalStrength;
    }

    public List<NeighborCell> getNeighbors() {
        return mNeighbors;
    }

    public boolean equals(CellState other) {
        return (mCid == other.mCid && mLac == other.mLac);
    }

    public boolean isValid() {
        return (mCid != -1 && mLac != -1);
    }

    public static class NeighborCell {
        private int mCid = -1;
        private int mLac = -1;
        private int mPsc = -1;
        private int mRssi = -1;

        NeighborCell(int cid, int lac, int psc, int rssi) {
            mCid = cid;
            mLac = lac;
            mPsc = psc;
            mRssi = rssi;
        }

        public int getCid() {
            return mCid;
        }

        public int getLac() {
            return mLac;
        }

        public int getPsc() {
            return mPsc;
        }

        public int getRssi() {
            return mRssi;
        }

        public String toString() {
            if (mPsc != -1) {
                return String.valueOf(mPsc) + "@" + mRssi;
            } else {
                return mCid + ":" + mLac + "@" + mRssi;
            }
        }
    }
}
