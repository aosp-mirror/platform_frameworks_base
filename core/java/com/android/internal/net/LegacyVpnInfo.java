/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.net;

import android.annotation.UnsupportedAppUsage;
import android.app.PendingIntent;
import android.net.NetworkInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * A simple container used to carry information of the ongoing legacy VPN.
 * Internal use only.
 *
 * @hide
 */
public class LegacyVpnInfo implements Parcelable {
    private static final String TAG = "LegacyVpnInfo";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_INITIALIZING = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;
    public static final int STATE_TIMEOUT = 4;
    public static final int STATE_FAILED = 5;

    @UnsupportedAppUsage
    public String key;
    @UnsupportedAppUsage
    public int state = -1;
    public PendingIntent intent;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(key);
        out.writeInt(state);
        out.writeParcelable(intent, flags);
    }

    @UnsupportedAppUsage
    public static final Parcelable.Creator<LegacyVpnInfo> CREATOR =
            new Parcelable.Creator<LegacyVpnInfo>() {
        @Override
        public LegacyVpnInfo createFromParcel(Parcel in) {
            LegacyVpnInfo info = new LegacyVpnInfo();
            info.key = in.readString();
            info.state = in.readInt();
            info.intent = in.readParcelable(null);
            return info;
        }

        @Override
        public LegacyVpnInfo[] newArray(int size) {
            return new LegacyVpnInfo[size];
        }
    };

    /**
     * Return best matching {@link LegacyVpnInfo} state based on given
     * {@link NetworkInfo}.
     */
    public static int stateFromNetworkInfo(NetworkInfo info) {
        switch (info.getDetailedState()) {
            case CONNECTING:
                return STATE_CONNECTING;
            case CONNECTED:
                return STATE_CONNECTED;
            case DISCONNECTED:
                return STATE_DISCONNECTED;
            case FAILED:
                return STATE_FAILED;
            default:
                Log.w(TAG, "Unhandled state " + info.getDetailedState()
                        + " ; treating as disconnected");
                return STATE_DISCONNECTED;
        }
    }
}
