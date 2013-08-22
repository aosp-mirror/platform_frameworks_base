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
import android.os.Parcelable;

/**
 *  Class that represents useful attributes of generic network links
 *  such as the upload/download throughput or packet error rate.
 *  Generally speaking, you should be dealing with instances of
 *  LinkInfo subclasses, such as {@link android.net.#WifiLinkInfo}
 *  or {@link android.net.#MobileLinkInfo} which provide additional
 *  information.
 *  @hide
 */
public class LinkInfo implements Parcelable
{
    public static final int UNKNOWN = Integer.MAX_VALUE;

    public static final int NORMALIZED_MIN_SIGNAL_STRENGTH = 0;

    public static final int NORMALIZED_MAX_SIGNAL_STRENGTH = 99;

    public static final int NORMALIZED_SIGNAL_STRENGTH_RANGE = NORMALIZED_MAX_SIGNAL_STRENGTH + 1;

    /* Network type as defined by ConnectivityManager */
    public int mNetworkType = ConnectivityManager.TYPE_NONE;

    public int mNormalizedSignalStrength = UNKNOWN;

    public int mPacketCount = UNKNOWN;
    public int mPacketErrorCount = UNKNOWN;
    public int mTheoreticalTxBandwidth = UNKNOWN;
    public int mTheoreticalRxBandwidth = UNKNOWN;
    public int mTheoreticalLatency = UNKNOWN;

    /* Timestamp when last sample was made available */
    public long mLastDataSampleTime = UNKNOWN;

    /* Sample duration in millisecond */
    public int mDataSampleDuration = UNKNOWN;

    public LinkInfo() {

    }

    /**
     * Implement the Parcelable interface
     * @hide
     */
    public int describeContents() {
        return 0;
    }
    /**
     * Implement the Parcelable interface.
     */

    protected static final int OBJECT_TYPE_LINKINFO = 1;
    protected static final int OBJECT_TYPE_WIFI_LINKINFO = 2;
    protected static final int OBJECT_TYPE_MOBILE_LINKINFO = 3;

    public void writeToParcel(Parcel dest, int flags) {
        writeToParcel(dest, flags, OBJECT_TYPE_LINKINFO);
    }

    public void writeToParcel(Parcel dest, int flags, int objectType) {
        dest.writeInt(objectType);
        dest.writeInt(mNetworkType);
        dest.writeInt(mNormalizedSignalStrength);
        dest.writeInt(mPacketCount);
        dest.writeInt(mPacketErrorCount);
        dest.writeInt(mTheoreticalTxBandwidth);
        dest.writeInt(mTheoreticalRxBandwidth);
        dest.writeInt(mTheoreticalLatency);
        dest.writeLong(mLastDataSampleTime);
        dest.writeInt(mDataSampleDuration);
    }

    public static final Creator<LinkInfo> CREATOR =
            new Creator<LinkInfo>() {
                public LinkInfo createFromParcel(Parcel in) {
                    int objectType = in.readInt();
                    if (objectType == OBJECT_TYPE_LINKINFO) {
                        LinkInfo li = new LinkInfo();
                        li.initializeFromParcel(in);
                        return li;
                    } else if (objectType == OBJECT_TYPE_WIFI_LINKINFO) {
                        return WifiLinkInfo.createFromParcelBody(in);
                    } else if (objectType == OBJECT_TYPE_MOBILE_LINKINFO) {
                        return MobileLinkInfo.createFromParcelBody(in);
                    } else {
                        return null;
                    }
                }

                public LinkInfo[] newArray(int size) {
                    return new LinkInfo[size];
                }
            };

    protected void initializeFromParcel(Parcel in) {
        mNetworkType = in.readInt();
        mNormalizedSignalStrength = in.readInt();
        mPacketCount = in.readInt();
        mPacketErrorCount = in.readInt();
        mTheoreticalTxBandwidth = in.readInt();
        mTheoreticalRxBandwidth = in.readInt();
        mTheoreticalLatency = in.readInt();
        mLastDataSampleTime = in.readLong();
        mDataSampleDuration = in.readInt();
    }

}
