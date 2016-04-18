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
public final class CaptivePortalCheckResultEvent extends IpConnectivityEvent implements Parcelable {
    public final int netId;
    public final int result;

    private CaptivePortalCheckResultEvent(int netId, int result) {
        this.netId = netId;
        this.result = result;
    }

    private CaptivePortalCheckResultEvent(Parcel in) {
        this.netId = in.readInt();
        this.result = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(netId);
        out.writeInt(result);
    }

    public int describeContents() {
        return 0;
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
        logEvent(IPCE_NETMON_CHECK_RESULT, new CaptivePortalCheckResultEvent(netId, result));
    }
};
