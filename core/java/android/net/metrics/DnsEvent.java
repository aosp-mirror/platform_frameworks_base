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
 * A DNS event recorded by NetdEventListenerService.
 * {@hide}
 */
@SystemApi
final public class DnsEvent implements Parcelable {
    public final int netId;

    // The event type is currently only 1 or 2, so we store it as a byte.
    public final byte[] eventTypes;
    // Current getaddrinfo codes go from 1 to EAI_MAX = 15. gethostbyname returns errno, but there
    // are fewer than 255 errno values. So we store the result code in a byte as well.
    public final byte[] returnCodes;
    // The latency is an integer because a) short arrays aren't parcelable and b) a short can only
    // store a maximum latency of 32757 or 65535 ms, which is too short for pathologically slow
    // queries.
    public final int[] latenciesMs;

    /** {@hide} */
    public DnsEvent(int netId, byte[] eventTypes, byte[] returnCodes, int[] latenciesMs) {
        this.netId = netId;
        this.eventTypes = eventTypes;
        this.returnCodes = returnCodes;
        this.latenciesMs = latenciesMs;
    }

    private DnsEvent(Parcel in) {
        this.netId = in.readInt();
        this.eventTypes = in.createByteArray();
        this.returnCodes = in.createByteArray();
        this.latenciesMs = in.createIntArray();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(netId);
        out.writeByteArray(eventTypes);
        out.writeByteArray(returnCodes);
        out.writeIntArray(latenciesMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return String.format("DnsEvent(%d, %d events)", netId, eventTypes.length);
    }

    public static final Parcelable.Creator<DnsEvent> CREATOR = new Parcelable.Creator<DnsEvent>() {
        @Override
        public DnsEvent createFromParcel(Parcel in) {
            return new DnsEvent(in);
        }

        @Override
        public DnsEvent[] newArray(int size) {
            return new DnsEvent[size];
        }
    };

    public static void logEvent(
            int netId, byte[] eventTypes, byte[] returnCodes, int[] latenciesMs) {
    }
}
