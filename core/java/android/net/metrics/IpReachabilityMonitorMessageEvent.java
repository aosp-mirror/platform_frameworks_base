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
public final class IpReachabilityMonitorMessageEvent extends IpConnectivityEvent
        implements Parcelable {
    public final String ifName;
    public final String destination;
    public final int msgType;
    public final int nudState;

    private IpReachabilityMonitorMessageEvent(String ifName, String destination, int msgType,
            int nudState) {
        this.ifName = ifName;
        this.destination = destination;
        this.msgType = msgType;
        this.nudState = nudState;
    }

    private IpReachabilityMonitorMessageEvent(Parcel in) {
        this.ifName = in.readString();
        this.destination = in.readString();
        this.msgType = in.readInt();
        this.nudState = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
        out.writeString(destination);
        out.writeInt(msgType);
        out.writeInt(nudState);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<IpReachabilityMonitorMessageEvent> CREATOR
        = new Parcelable.Creator<IpReachabilityMonitorMessageEvent>() {
        public IpReachabilityMonitorMessageEvent createFromParcel(Parcel in) {
            return new IpReachabilityMonitorMessageEvent(in);
        }

        public IpReachabilityMonitorMessageEvent[] newArray(int size) {
            return new IpReachabilityMonitorMessageEvent[size];
        }
    };

    public static void logEvent(String ifName, String destination, int msgType, int nudState) {
        logEvent(IPCE_IPRM_MESSAGE_RECEIVED,
                new IpReachabilityMonitorMessageEvent(ifName, destination, msgType, nudState));
    }
};
