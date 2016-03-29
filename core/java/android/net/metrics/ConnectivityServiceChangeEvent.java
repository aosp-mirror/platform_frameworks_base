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
public class ConnectivityServiceChangeEvent extends IpConnectivityEvent implements Parcelable {
    public static final String TAG = "ConnectivityServiceChangeEvent";

    private int mNetId;

    public ConnectivityServiceChangeEvent(int netId) {
        mNetId = netId;
    }

    public ConnectivityServiceChangeEvent(Parcel in) {
        mNetId = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mNetId);
    }

    public static final Parcelable.Creator<ConnectivityServiceChangeEvent> CREATOR
        = new Parcelable.Creator<ConnectivityServiceChangeEvent>() {
        public ConnectivityServiceChangeEvent createFromParcel(Parcel in) {
            return new ConnectivityServiceChangeEvent(in);
        }

        public ConnectivityServiceChangeEvent[] newArray(int size) {
            return new ConnectivityServiceChangeEvent[size];
        }
    };

    public static void logEvent(int netId) {
        IpConnectivityEvent.logEvent(IpConnectivityEvent.IPCE_CONSRV_DEFAULT_NET_CHANGE,
                new ConnectivityServiceChangeEvent(netId));
    }
};
