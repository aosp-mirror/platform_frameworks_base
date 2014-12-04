/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.net.wifi;

import android.os.Parcelable;
import android.os.Parcel;

import java.util.ArrayList;
import android.util.Slog;
import android.util.Log;

/**
 * Describes information about EAP sim information.
 * {@hide}
 */
public class WifiEapSimInfo implements Parcelable {

    private static final String TAG = "WifiEapSimInfo";
    /**
     * No of sims device support
     */
    public int  mNumOfSims;

    /**
     * SIM info
     */
    public ArrayList <Integer> mSimTypes = new ArrayList <Integer>();

    /**
     * These definitions are for deviceState
     */
    public static final int SIM_UNSUPPORTED = 0;
    public static final int SIM_2G = 1;
    public static final int SIM_3G = 3;
    public static final int MAX_NUM_OF_SIMS_SUPPORTED = 4;

    /**
     * Sim supported EAP types
     */
     public static final String[]  m2GSupportedTypes={ "SIM" };
     public static final String[]  m3GSupportedTypes={ "SIM", "AKA" };

     /**
     *  Strings constants to parse
     */
    private static final String NUM_OF_SIMS_STR = "no_of_sims=";
    private static final String SIM_ONE_TYPE_STR = "sim1=";
    private static final String SIM_TWO_TYPE_STR = "sim2=";
    private static final String SIM_THREE_TYPE_STR = "sim3=";
    private static final String SIM_FOUR_TYPE_STR = "sim4=";
    private static final boolean DBG = false;

    /** {@hide} */
    public WifiEapSimInfo() {}

    /** copy constructor {@hide} */
    public WifiEapSimInfo(WifiEapSimInfo source) {
        if (source != null) {
            mNumOfSims = source.mNumOfSims;
            mSimTypes= source.mSimTypes;
        }
    }

    /**
     * @param string formats supported include
     *  no_of_sims=2 sim1=3 sim2=0
     *
     * @hide
     */
    public WifiEapSimInfo(String dataString) throws IllegalArgumentException {
        String[] sims = dataString.split(" ");
        int mSimInfo = -1;

        if ((sims.length < 1)||(sims.length > MAX_NUM_OF_SIMS_SUPPORTED )) {
            throw new IllegalArgumentException();
        }
        for (String sim :sims) {
            if (sim.startsWith(NUM_OF_SIMS_STR)) {
                try {
                    mNumOfSims = Integer.parseInt(sim.substring(NUM_OF_SIMS_STR.length()));
                    if (DBG) Log.d(TAG,"mNumOfSims =" + mNumOfSims);
                } catch (NumberFormatException e) {
                    mNumOfSims = 0;
                }
            } else if (sim.startsWith(SIM_ONE_TYPE_STR)) {
                try {
                    mSimInfo = Integer.parseInt(sim.substring(SIM_ONE_TYPE_STR.length()));
                    if (DBG) Log.d(TAG,"SIM_ONE_TYPE mSimInfo =" + mSimInfo);
                } catch (NumberFormatException e) {
                    mSimInfo = 0;
                }
                mSimTypes.add(mSimInfo);
            } else if (sim.startsWith(SIM_TWO_TYPE_STR)) {
                try {
                    mSimInfo = Integer.parseInt(sim.substring(SIM_TWO_TYPE_STR.length()));
                    if (DBG) Log.d(TAG,"SIM_TWO_TYPE mSimInfo =" + mSimInfo);
                } catch (NumberFormatException e) {
                    mSimInfo = 0;
                }
                mSimTypes.add(mSimInfo);
            } else if (sim.startsWith(SIM_THREE_TYPE_STR)) {
                try {
                    mSimInfo = Integer.parseInt(sim.substring(SIM_THREE_TYPE_STR.length()));
                    if (DBG) Log.d(TAG,"SIM_THREE_TYPE mSimInfo =" + mSimInfo);
                } catch (NumberFormatException e) {
                    mSimInfo = 0;
                }
                mSimTypes.add(mSimInfo);
            } else if (sim.startsWith(SIM_FOUR_TYPE_STR)) {
                try {
                    mSimInfo = Integer.parseInt(sim.substring(SIM_FOUR_TYPE_STR.length()));
                    if (DBG) Log.d(TAG,"SIM_FOUR_TYPE mSimInfo =" + mSimInfo);
                } catch (NumberFormatException e) {
                    mSimInfo = 0;
                }
                mSimTypes.add(mSimInfo);
            } else {
                throw new IllegalArgumentException();
            }
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mNumOfSims);
        dest.writeInt(mSimTypes.size());
        for(Integer mInteger : mSimTypes) {
           dest.writeInt(mInteger.intValue());
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiEapSimInfo> CREATOR =
        new Creator<WifiEapSimInfo>() {
            public WifiEapSimInfo createFromParcel(Parcel in) {
                WifiEapSimInfo mWifiEapSimInfo = new WifiEapSimInfo();
                mWifiEapSimInfo.mNumOfSims = in.readInt();
                int count = in.readInt();
                if (DBG) Log.d(TAG,"Creator mNumOfSims =" + mWifiEapSimInfo.mNumOfSims);
                while(count-- > 0) {
                  mWifiEapSimInfo.mSimTypes.add(new Integer(in.readInt()));
                }
                return mWifiEapSimInfo;
            }

            public WifiEapSimInfo[] newArray(int size) {
                return new WifiEapSimInfo[size];
            }
        };
}
