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
import android.util.SparseArray;

import com.android.internal.util.MessageUtils;

/**
 * {@hide}
 */
@SystemApi
public final class IpReachabilityEvent extends IpConnectivityEvent implements Parcelable {

    public static final int PROBE             = 1 << 8;
    public static final int NUD_FAILED        = 2 << 8;
    public static final int PROVISIONING_LOST = 3 << 8;

    public final String ifName;
    // eventType byte format (MSB to LSB):
    // byte 0: unused
    // byte 1: unused
    // byte 2: type of event: PROBE, NUD_FAILED, PROVISIONING_LOST
    // byte 3: kernel errno from RTNetlink or IpReachabilityMonitor
    public final int eventType;

    private IpReachabilityEvent(String ifName, int eventType) {
        this.ifName = ifName;
        this.eventType = eventType;
    }

    private IpReachabilityEvent(Parcel in) {
        this.ifName = in.readString();
        this.eventType = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
        out.writeInt(eventType);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<IpReachabilityEvent> CREATOR
        = new Parcelable.Creator<IpReachabilityEvent>() {
        public IpReachabilityEvent createFromParcel(Parcel in) {
            return new IpReachabilityEvent(in);
        }

        public IpReachabilityEvent[] newArray(int size) {
            return new IpReachabilityEvent[size];
        }
    };

    public static void logProbeEvent(String ifName, int nlErrorCode) {
        logEvent(new IpReachabilityEvent(ifName, PROBE | (nlErrorCode & 0xFF)));
    }

    public static void logNudFailed(String ifName) {
        logEvent(new IpReachabilityEvent(ifName, NUD_FAILED));
    }

    public static void logProvisioningLost(String ifName) {
        logEvent(new IpReachabilityEvent(ifName, PROVISIONING_LOST));
    }

    @Override
    public String toString() {
        return String.format("IpReachabilityEvent(%s, %s)", ifName,
                Decoder.constants.get(eventType));
    }

    final static class Decoder {
        static final SparseArray<String> constants =
                MessageUtils.findMessageNames(new Class[]{IpReachabilityEvent.class},
                new String[]{"PROBE", "PROVISIONING_", "NUD_"});
    }
};
