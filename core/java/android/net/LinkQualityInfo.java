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

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 *  Class that represents useful attributes of generic network links
 *  such as the upload/download throughput or packet error rate.
 *  Generally speaking, you should be dealing with instances of
 *  LinkQualityInfo subclasses, such as {@link android.net.#WifiLinkQualityInfo}
 *  or {@link android.net.#MobileLinkQualityInfo} which provide additional
 *  information.
 *  @hide
 */
public class LinkQualityInfo implements Parcelable {

    /**
     * Represents a value that you can use to test if an integer field is set to a good value
     */
    public static final int UNKNOWN_INT = Integer.MAX_VALUE;

    /**
     * Represents a value that you can use to test if a long field is set to a good value
     */
    public static final long UNKNOWN_LONG = Long.MAX_VALUE;

    public static final int NORMALIZED_MIN_SIGNAL_STRENGTH = 0;

    public static final int NORMALIZED_MAX_SIGNAL_STRENGTH = 99;

    public static final int NORMALIZED_SIGNAL_STRENGTH_RANGE =
            NORMALIZED_MAX_SIGNAL_STRENGTH - NORMALIZED_MIN_SIGNAL_STRENGTH + 1;

    /* Network type as defined by ConnectivityManager */
    private int mNetworkType = ConnectivityManager.TYPE_NONE;

    private int mNormalizedSignalStrength = UNKNOWN_INT;

    private long mPacketCount = UNKNOWN_LONG;
    private long mPacketErrorCount = UNKNOWN_LONG;
    private int mTheoreticalTxBandwidth = UNKNOWN_INT;
    private int mTheoreticalRxBandwidth = UNKNOWN_INT;
    private int mTheoreticalLatency = UNKNOWN_INT;

    /* Timestamp when last sample was made available */
    private long mLastDataSampleTime = UNKNOWN_LONG;

    /* Sample duration in millisecond */
    private int mDataSampleDuration = UNKNOWN_INT;

    public LinkQualityInfo() {

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

    protected static final int OBJECT_TYPE_LINK_QUALITY_INFO = 1;
    protected static final int OBJECT_TYPE_WIFI_LINK_QUALITY_INFO = 2;
    protected static final int OBJECT_TYPE_MOBILE_LINK_QUALITY_INFO = 3;

    /**
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcel(dest, flags, OBJECT_TYPE_LINK_QUALITY_INFO);
    }

    /**
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags, int objectType) {
        dest.writeInt(objectType);
        dest.writeInt(mNetworkType);
        dest.writeInt(mNormalizedSignalStrength);
        dest.writeLong(mPacketCount);
        dest.writeLong(mPacketErrorCount);
        dest.writeInt(mTheoreticalTxBandwidth);
        dest.writeInt(mTheoreticalRxBandwidth);
        dest.writeInt(mTheoreticalLatency);
        dest.writeLong(mLastDataSampleTime);
        dest.writeInt(mDataSampleDuration);
    }

    /**
     * @hide
     */
    public static final @android.annotation.NonNull Creator<LinkQualityInfo> CREATOR =
            new Creator<LinkQualityInfo>() {
                public LinkQualityInfo createFromParcel(Parcel in) {
                    int objectType = in.readInt();
                    if (objectType == OBJECT_TYPE_LINK_QUALITY_INFO) {
                        LinkQualityInfo li = new LinkQualityInfo();
                        li.initializeFromParcel(in);
                        return li;
                    } else if (objectType == OBJECT_TYPE_WIFI_LINK_QUALITY_INFO) {
                        return WifiLinkQualityInfo.createFromParcelBody(in);
                    } else if (objectType == OBJECT_TYPE_MOBILE_LINK_QUALITY_INFO) {
                        return MobileLinkQualityInfo.createFromParcelBody(in);
                    } else {
                        return null;
                    }
                }

                public LinkQualityInfo[] newArray(int size) {
                    return new LinkQualityInfo[size];
                }
            };

    /**
     * @hide
     */
    protected void initializeFromParcel(Parcel in) {
        mNetworkType = in.readInt();
        mNormalizedSignalStrength = in.readInt();
        mPacketCount = in.readLong();
        mPacketErrorCount = in.readLong();
        mTheoreticalTxBandwidth = in.readInt();
        mTheoreticalRxBandwidth = in.readInt();
        mTheoreticalLatency = in.readInt();
        mLastDataSampleTime = in.readLong();
        mDataSampleDuration = in.readInt();
    }

    /**
     * returns the type of network this link is connected to
     * @return network type as defined by {@link android.net.ConnectivityManager} or
     * {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getNetworkType() {
        return mNetworkType;
    }

    /**
     * @hide
     */
    public void setNetworkType(int networkType) {
        mNetworkType = networkType;
    }

    /**
     * returns the signal strength normalized across multiple types of networks
     * @return an integer value from 0 - 99 or {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getNormalizedSignalStrength() {
        return mNormalizedSignalStrength;
    }

    /**
     * @hide
     */
    public void setNormalizedSignalStrength(int normalizedSignalStrength) {
        mNormalizedSignalStrength = normalizedSignalStrength;
    }

    /**
     * returns the total number of packets sent or received in sample duration
     * @return number of packets or {@link android.net.LinkQualityInfo#UNKNOWN_LONG}
     */
    public long getPacketCount() {
        return mPacketCount;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setPacketCount(long packetCount) {
        mPacketCount = packetCount;
    }

    /**
     * returns the total number of packets errors encountered in sample duration
     * @return number of errors or {@link android.net.LinkQualityInfo#UNKNOWN_LONG}
     */
    public long getPacketErrorCount() {
        return mPacketErrorCount;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setPacketErrorCount(long packetErrorCount) {
        mPacketErrorCount = packetErrorCount;
    }

    /**
     * returns the theoretical upload bandwidth of this network
     * @return bandwidth in Kbps or {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getTheoreticalTxBandwidth() {
        return mTheoreticalTxBandwidth;
    }

    /**
     * @hide
     */
    public void setTheoreticalTxBandwidth(int theoreticalTxBandwidth) {
        mTheoreticalTxBandwidth = theoreticalTxBandwidth;
    }

    /**
     * returns the theoretical download bandwidth of this network
     * @return bandwidth in Kbps or {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getTheoreticalRxBandwidth() {
        return mTheoreticalRxBandwidth;
    }

    /**
     * @hide
     */
    public void setTheoreticalRxBandwidth(int theoreticalRxBandwidth) {
        mTheoreticalRxBandwidth = theoreticalRxBandwidth;
    }

    /**
     * returns the theoretical latency of this network
     * @return latency in milliseconds or {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getTheoreticalLatency() {
        return mTheoreticalLatency;
    }

    /**
     * @hide
     */
    public void setTheoreticalLatency(int theoreticalLatency) {
        mTheoreticalLatency = theoreticalLatency;
    }

    /**
     * returns the time stamp of the last sample
     * @return milliseconds elapsed since start and sample time or
     * {@link android.net.LinkQualityInfo#UNKNOWN_LONG}
     */
    public long getLastDataSampleTime() {
        return mLastDataSampleTime;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setLastDataSampleTime(long lastDataSampleTime) {
        mLastDataSampleTime = lastDataSampleTime;
    }

    /**
     * returns the sample duration used
     * @return duration in milliseconds or {@link android.net.LinkQualityInfo#UNKNOWN_INT}
     */
    public int getDataSampleDuration() {
        return mDataSampleDuration;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setDataSampleDuration(int dataSampleDuration) {
        mDataSampleDuration = dataSampleDuration;
    }
}
