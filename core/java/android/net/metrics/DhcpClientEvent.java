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
public final class DhcpClientEvent extends IpConnectivityEvent implements Parcelable {
    public final String ifName;
    public final String msg;

    private DhcpClientEvent(String ifName, String msg) {
        this.ifName = ifName;
        this.msg = msg;
    }

    private DhcpClientEvent(Parcel in) {
        this.ifName = in.readString();
        this.msg = in.readString();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
        out.writeString(msg);
    }

    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return String.format("DhcpClientEvent(%s, %s)", ifName, msg);
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
        logEvent(new DhcpClientEvent(ifName, state));
    }
};
