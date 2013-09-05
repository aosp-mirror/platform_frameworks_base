/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.net;

import android.os.Parcel;

/**
 *  Class that represents useful attributes of wifi network links
 *  such as the upload/download throughput or error rate etc.
 *  @hide
 */
public class WifiLinkQualityInfo extends LinkQualityInfo {

    /* Indicates Wifi network type such as b/g etc*/
    private int  mType = UNKNOWN_INT;

    private String mBssid;

    /* Rssi found by scans */
    private int  mRssi = UNKNOWN_INT;

    /* packet statistics */
    private long mTxGood = UNKNOWN_LONG;
    private long mTxBad = UNKNOWN_LONG;

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags, OBJECT_TYPE_WIFI_LINK_QUALITY_INFO);

        dest.writeInt(mType);
        dest.writeInt(mRssi);
        dest.writeLong(mTxGood);
        dest.writeLong(mTxBad);

        dest.writeString(mBssid);
    }

    /* Un-parceling helper */
    /**
     * @hide
     */
    public static WifiLinkQualityInfo createFromParcelBody(Parcel in) {
        WifiLinkQualityInfo li = new WifiLinkQualityInfo();

        li.initializeFromParcel(in);

        li.mType =  in.readInt();
        li.mRssi =  in.readInt();
        li.mTxGood =  in.readLong();
        li.mTxBad =  in.readLong();

        li.mBssid =  in.readString();

        return li;
    }

    /**
     * returns Wifi network type
     * @return network type or {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getType() {
        return mType;
    }

    /**
     * @hide
     */
    public void setType(int type) {
        mType = type;
    }

    /**
     * returns BSSID of the access point
     * @return the BSSID, in the form of a six-byte MAC address: {@code XX:XX:XX:XX:XX:XX} or null
     */
    public String getBssid() {
        return mBssid;
    }

    /**
     * @hide
     */
    public void setBssid(String bssid) {
        mBssid = bssid;
    }

    /**
     * returns RSSI of the network in raw form
     * @return un-normalized RSSI or {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * @hide
     */
    public void setRssi(int rssi) {
        mRssi = rssi;
    }

    /**
     * returns number of packets transmitted without error
     * @return number of packets or {@link android.net.LinkQualityInfo#UNKNOWN_LONG}
     */
    public long getTxGood() {
        return mTxGood;
    }

    /**
     * @hide
     */
    public void setTxGood(long txGood) {
        mTxGood = txGood;
    }

    /**
     * returns number of transmitted packets that encountered errors
     * @return number of packets or {@link android.net.LinkQualityInfo#UNKNOWN_LONG}
     */
    public long getTxBad() {
        return mTxBad;
    }

    /**
     * @hide
     */
    public void setTxBad(long txBad) {
        mTxBad = txBad;
    }
}
