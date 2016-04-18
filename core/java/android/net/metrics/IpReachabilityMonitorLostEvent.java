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
public final class IpReachabilityMonitorLostEvent extends IpConnectivityEvent
        implements Parcelable {
    public final String ifName;

    private IpReachabilityMonitorLostEvent(String ifName) {
        this.ifName = ifName;
    }

    private IpReachabilityMonitorLostEvent(Parcel in) {
        this.ifName = in.readString();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<IpReachabilityMonitorLostEvent> CREATOR
        = new Parcelable.Creator<IpReachabilityMonitorLostEvent>() {
        public IpReachabilityMonitorLostEvent createFromParcel(Parcel in) {
            return new IpReachabilityMonitorLostEvent(in);
        }

        public IpReachabilityMonitorLostEvent[] newArray(int size) {
            return new IpReachabilityMonitorLostEvent[size];
        }
    };

    public static void logEvent(String ifName) {
        logEvent(IPCE_IPRM_REACHABILITY_LOST, new IpReachabilityMonitorLostEvent(ifName));
    }
};
