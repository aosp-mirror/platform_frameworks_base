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
public class CaptivePortalCheckResultEvent extends IpConnectivityEvent implements Parcelable {
    public static final String TAG = "CaptivePortalCheckResultEvent";

    private int mNetId;
    private int mResult;

    public CaptivePortalCheckResultEvent(int netId, int result) {
        mNetId = netId;
        mResult = result;
    }

    public CaptivePortalCheckResultEvent(Parcel in) {
        mNetId = in.readInt();
        mResult = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mNetId);
        out.writeInt(mResult);
    }

    public static final Parcelable.Creator<CaptivePortalCheckResultEvent> CREATOR
        = new Parcelable.Creator<CaptivePortalCheckResultEvent>() {
            public CaptivePortalCheckResultEvent createFromParcel(Parcel in) {
                return new CaptivePortalCheckResultEvent(in);
            }

            public CaptivePortalCheckResultEvent[] newArray(int size) {
                return new CaptivePortalCheckResultEvent[size];
            }
        };

    public static void logEvent(int netId, int result) {
        IpConnectivityEvent.logEvent(IpConnectivityEvent.IPCE_NETMON_CHECK_RESULT,
                new CaptivePortalCheckResultEvent(netId, result));
    }
};
