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
public class IpReachabilityMonitorProbeEvent extends IpConnectivityEvent
    implements Parcelable {
    public static final String TAG = "IpReachabilityMonitorProbeEvent";

    private String mIfName;
    private String mDestination;
    private boolean mSuccess;

    public IpReachabilityMonitorProbeEvent(String ifName, String destination, boolean success) {
        mIfName = ifName;
        mDestination = destination;
        mSuccess = success;
    }

    public IpReachabilityMonitorProbeEvent(Parcel in) {
        mIfName = in.readString();
        mDestination = in.readString();
        mSuccess = in.readByte() > 0 ? true : false;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mIfName);
        out.writeString(mDestination);
        out.writeByte((byte)(mSuccess ? 1 : 0));
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
        IpConnectivityEvent.logEvent(IpConnectivityEvent.IPCE_IPRM_PROBE_RESULT,
                new IpReachabilityMonitorProbeEvent(ifName, destination, success));
    }
};
