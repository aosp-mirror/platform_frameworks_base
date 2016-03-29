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
public class IpReachabilityMonitorMessageEvent extends IpConnectivityEvent
    implements Parcelable {
    public static final String TAG = "IpReachabilityMonitorMessageEvent";

    private String mIfName;
    private String mDestination;
    private int mMsgType;
    private int mNudState;

    public IpReachabilityMonitorMessageEvent(String ifName, String destination, int msgType,
            int nudState) {
        mIfName = ifName;
        mDestination = destination;
        mMsgType = msgType;
        mNudState = nudState;
    }

    public IpReachabilityMonitorMessageEvent(Parcel in) {
        mIfName = in.readString();
        mDestination = in.readString();
        mMsgType = in.readInt();
        mNudState = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mIfName);
        out.writeString(mDestination);
        out.writeInt(mMsgType);
        out.writeInt(mNudState);
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
        IpConnectivityEvent.logEvent(IpConnectivityEvent.IPCE_IPRM_MESSAGE_RECEIVED,
                new IpReachabilityMonitorMessageEvent(ifName, destination, msgType, nudState));
    }
};
