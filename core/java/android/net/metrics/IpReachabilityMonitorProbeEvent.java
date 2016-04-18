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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * {@hide}
 */
public final class IpReachabilityMonitorProbeEvent extends IpConnectivityEvent
        implements Parcelable {
    public final String ifName;
    public final String destination;
    public final boolean success;

    private IpReachabilityMonitorProbeEvent(String ifName, String destination, boolean success) {
        this.ifName = ifName;
        this.destination = destination;
        this.success = success;
    }

    private IpReachabilityMonitorProbeEvent(Parcel in) {
        this.ifName = in.readString();
        this.destination = in.readString();
        this.success = in.readByte() > 0 ? true : false;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
        out.writeString(destination);
        out.writeByte((byte)(success ? 1 : 0));
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<IpReachabilityMonitorProbeEvent> CREATOR
        = new Parcelable.Creator<IpReachabilityMonitorProbeEvent>() {
        public IpReachabilityMonitorProbeEvent createFromParcel(Parcel in) {
            return new IpReachabilityMonitorProbeEvent(in);
        }

        public IpReachabilityMonitorProbeEvent[] newArray(int size) {
            return new IpReachabilityMonitorProbeEvent[size];
        }
    };

    public static void logEvent(String ifName, String destination, boolean success) {
        logEvent(IPCE_IPRM_PROBE_RESULT,
                new IpReachabilityMonitorProbeEvent(ifName, destination, success));
    }
};
