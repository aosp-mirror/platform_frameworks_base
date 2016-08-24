/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.net.wifi;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class representing wifi wake reason accounting.
 */

/** @hide */
public class WifiWakeReasonAndCounts implements Parcelable {
    private static final String TAG = "WifiWakeReasonAndCounts";
    /**
     * Wlan can wake host, only when it is cmd/event, local driver-fw
     * functions(non-data, non cmd/event) and rx data.The first packet
     * from wlan that woke up a sleep host is what is accounted here.
     * Total wlan wake to application processor would be:
     * [cmdEventWake + driverFwLocalWake + totalRxDataWake]
     * A further classification is provided for identifying the reasons
     * for wakeup.
     */
    public int totalCmdEventWake;
    public int totalDriverFwLocalWake;
    public int totalRxDataWake;

    public int rxUnicast;
    public int rxMulticast;
    public int rxBroadcast;

    public int icmp;
    public int icmp6;
    public int icmp6Ra;
    public int icmp6Na;
    public int icmp6Ns;

    public int ipv4RxMulticast;
    public int ipv6Multicast;
    public int otherRxMulticast;
    public int[] cmdEventWakeCntArray;
    public int[] driverFWLocalWakeCntArray;

    /* {@hide} */
    public WifiWakeReasonAndCounts () {
    }

    @Override
    /* {@hide} */
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(" totalCmdEventWake ").append(totalCmdEventWake);
        sb.append(" totalDriverFwLocalWake ").append(totalDriverFwLocalWake);
        sb.append(" totalRxDataWake ").append(totalRxDataWake);

        sb.append(" rxUnicast ").append(rxUnicast);
        sb.append(" rxMulticast ").append(rxMulticast);
        sb.append(" rxBroadcast ").append(rxBroadcast);

        sb.append(" icmp ").append(icmp);
        sb.append(" icmp6 ").append(icmp6);
        sb.append(" icmp6Ra ").append(icmp6Ra);
        sb.append(" icmp6Na ").append(icmp6Na);
        sb.append(" icmp6Ns ").append(icmp6Ns);

        sb.append(" ipv4RxMulticast ").append(ipv4RxMulticast);
        sb.append(" ipv6Multicast ").append(ipv6Multicast);
        sb.append(" otherRxMulticast ").append(otherRxMulticast);
        for (int i = 0; i < cmdEventWakeCntArray.length; i++) {
            sb.append(" cmdEventWakeCntArray[" + i + "] " + cmdEventWakeCntArray[i]);
        }
        for (int i = 0; i < driverFWLocalWakeCntArray.length; i++) {
            sb.append(" driverFWLocalWakeCntArray[" + i + "] " + driverFWLocalWakeCntArray[i]);
        }

        return sb.toString();
    }

    /* Implement the Parcelable interface
     * {@hide}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /* Implement the Parcelable interface
     * {@hide}
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(totalCmdEventWake);
        dest.writeInt(totalDriverFwLocalWake);
        dest.writeInt(totalRxDataWake);

        dest.writeInt(rxUnicast);
        dest.writeInt(rxMulticast);
        dest.writeInt(rxBroadcast);

        dest.writeInt(icmp);
        dest.writeInt(icmp6);
        dest.writeInt(icmp6Ra);
        dest.writeInt(icmp6Na);
        dest.writeInt(icmp6Ns);

        dest.writeInt(ipv4RxMulticast);
        dest.writeInt(ipv6Multicast);
        dest.writeInt(otherRxMulticast);
        dest.writeIntArray(cmdEventWakeCntArray);
        dest.writeIntArray(driverFWLocalWakeCntArray);
    }

    /* Implement the Parcelable interface
     * {@hide}
     */
    public static final Creator<WifiWakeReasonAndCounts> CREATOR =
        new Creator<WifiWakeReasonAndCounts>() {
            public WifiWakeReasonAndCounts createFromParcel(Parcel in) {
                WifiWakeReasonAndCounts counts = new WifiWakeReasonAndCounts();
                counts.totalCmdEventWake = in.readInt();
                counts.totalDriverFwLocalWake = in.readInt();
                counts.totalRxDataWake = in.readInt();

                counts.rxUnicast = in.readInt();
                counts.rxMulticast = in.readInt();
                counts.rxBroadcast = in.readInt();

                counts.icmp = in.readInt();
                counts.icmp6 = in.readInt();
                counts.icmp6Ra = in.readInt();
                counts.icmp6Na = in.readInt();
                counts.icmp6Ns = in.readInt();

                counts.ipv4RxMulticast = in.readInt();
                counts.ipv6Multicast = in.readInt();
                counts.otherRxMulticast = in.readInt();
                in.readIntArray(counts.cmdEventWakeCntArray);
                in.readIntArray(counts.driverFWLocalWakeCntArray);
                return counts;
            }
            /* Implement the Parcelable interface
             * {@hide}
             */
            @Override
            public WifiWakeReasonAndCounts[] newArray(int size) {
                return new WifiWakeReasonAndCounts[size];
            }
        };
}
