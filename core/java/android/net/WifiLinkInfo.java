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
import android.net.LinkInfo;

/**
 *  Class that represents useful attributes of wifi network links
 *  such as the upload/download throughput or error rate etc.
 *  @hide
 */
public final class WifiLinkInfo extends LinkInfo
{
    /**
     * Type enumerations for Wifi Network
     */

    /* Indicates Wifi network type such as b/g etc*/
    public int  mType = UNKNOWN;

    public String mBssid;

    /* Rssi found by scans */
    public int  mRssi = UNKNOWN;

    /* packet statistics */
    public long  mTxGood = UNKNOWN;
    public long  mTxBad = UNKNOWN;

    /**
     * Implement the Parcelable interface.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags, OBJECT_TYPE_WIFI_LINKINFO);

        dest.writeInt(mType);
        dest.writeInt(mRssi);
        dest.writeLong(mTxGood);
        dest.writeLong(mTxBad);

        dest.writeString(mBssid);
    }

    /* Un-parceling helper */
    public static WifiLinkInfo createFromParcelBody(Parcel in) {
        WifiLinkInfo li = new WifiLinkInfo();

        li.initializeFromParcel(in);

        li.mType =  in.readInt();
        li.mRssi =  in.readInt();
        li.mTxGood =  in.readLong();
        li.mTxBad =  in.readLong();

        li.mBssid =  in.readString();

        return li;
    }
}
