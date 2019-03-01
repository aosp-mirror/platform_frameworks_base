/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.internal.util.BitUtils;

/**
 * Represents a core networking event defined in package android.net.metrics.
 * Logged by IpConnectivityLog and managed by ConnectivityMetrics service.
 * {@hide}
 * */
public final class ConnectivityMetricsEvent implements Parcelable {

    /** Time when this event was collected, as returned by System.currentTimeMillis(). */
    public long timestamp;
    /** Transports of the network associated with the event, as defined in NetworkCapabilities. */
    public long transports;
    /** Network id of the network associated with the event, or 0 if unspecified. */
    public int netId;
    /** Name of the network interface associated with the event, or null if unspecified. */
    public String ifname;
    /** Opaque event-specific data. */
    public Parcelable data;

    public ConnectivityMetricsEvent() {
    }

    private ConnectivityMetricsEvent(Parcel in) {
        timestamp = in.readLong();
        transports = in.readLong();
        netId = in.readInt();
        ifname = in.readString();
        data = in.readParcelable(null);
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Parcelable.Creator<ConnectivityMetricsEvent> CREATOR
            = new Parcelable.Creator<ConnectivityMetricsEvent> (){
        public ConnectivityMetricsEvent createFromParcel(Parcel source) {
            return new ConnectivityMetricsEvent(source);
        }

        public ConnectivityMetricsEvent[] newArray(int size) {
            return new ConnectivityMetricsEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timestamp);
        dest.writeLong(transports);
        dest.writeInt(netId);
        dest.writeString(ifname);
        dest.writeParcelable(data, 0);
    }

    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder("ConnectivityMetricsEvent(");
        buffer.append(String.format("%tT.%tL", timestamp, timestamp));
        if (netId != 0) {
            buffer.append(", ").append("netId=").append(netId);
        }
        if (ifname != null) {
            buffer.append(", ").append(ifname);
        }
        for (int t : BitUtils.unpackBits(transports)) {
            buffer.append(", ").append(NetworkCapabilities.transportNameOf(t));
        }
        buffer.append("): ").append(data.toString());
        return buffer.toString();
    }
}
