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
public class DhcpClientEvent extends IpConnectivityEvent implements Parcelable {
    public static final String TAG = "DhcpClientEvent";

    private String mIfName;
    private String mMsg;

    public DhcpClientEvent(String ifName, String msg) {
        mIfName = ifName;
        mMsg = msg;
    }

    public DhcpClientEvent(Parcel in) {
        mIfName = in.readString();
        mMsg = in.readString();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mIfName);
        out.writeString(mMsg);
    }

    public static final Parcelable.Creator<DhcpClientEvent> CREATOR
        = new Parcelable.Creator<DhcpClientEvent>() {
        public DhcpClientEvent createFromParcel(Parcel in) {
            return new DhcpClientEvent(in);
        }

        public DhcpClientEvent[] newArray(int size) {
            return new DhcpClientEvent[size];
        }
    };

    public static void logStateEvent(String ifName, String state) {
        logEvent(IpConnectivityEvent.IPCE_DHCP_STATE_CHANGE, new DhcpClientEvent(ifName, state));
    }
};
