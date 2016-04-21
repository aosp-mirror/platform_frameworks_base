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

package android.net.metrics;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * {@hide}
 */
@SystemApi
public final class NetworkMonitorEvent extends IpConnectivityEvent implements Parcelable {
    public final int netId;
    public final long durationMs;
    public final int returnCode;

    private NetworkMonitorEvent(int netId, long durationMs, int returnCode) {
        this.netId = netId;
        this.durationMs = durationMs;
        this.returnCode = returnCode;
    }

    public NetworkMonitorEvent(Parcel in) {
        netId = in.readInt();
        durationMs = in.readLong();
        returnCode = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(netId);
        out.writeLong(durationMs);
        out.writeInt(returnCode);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<NetworkMonitorEvent> CREATOR
        = new Parcelable.Creator<NetworkMonitorEvent>() {
        public NetworkMonitorEvent createFromParcel(Parcel in) {
            return new NetworkMonitorEvent(in);
        }

        public NetworkMonitorEvent[] newArray(int size) {
            return new NetworkMonitorEvent[size];
        }
    };

    private static void logEvent(int eventType, int netId, long durationMs, int returnCode) {
        logEvent(eventType, new NetworkMonitorEvent(netId, durationMs, returnCode));
    }

    public static void logValidated(int netId, long durationMs) {
        logEvent(IPCE_NETMON_VALIDATED, netId, durationMs, 0);
    }

    public static void logPortalProbeEvent(int netId, long durationMs, int returnCode) {
        logEvent(IPCE_NETMON_PORTAL_PROBE, netId, durationMs, returnCode);
    }

    public static void logCaptivePortalFound(int netId, long durationMs) {
        logEvent(IPCE_NETMON_CAPPORT_FOUND, netId, durationMs, 0);
    }
};
