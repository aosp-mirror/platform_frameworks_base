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
public final class ValidationProbeEvent extends IpConnectivityEvent implements Parcelable {

    public static final int PROBE_DNS   = 0;
    public static final int PROBE_HTTP  = 1;
    public static final int PROBE_HTTPS = 2;
    public static final int PROBE_PAC   = 3;

    public static final int DNS_FAILURE = 0;
    public static final int DNS_SUCCESS = 1;

    public final int netId;
    public final long durationMs;
    public final int probeType;
    public final int returnCode;

    private ValidationProbeEvent(int netId, long durationMs, int probeType, int returnCode) {
        this.netId = netId;
        this.durationMs = durationMs;
        this.probeType = probeType;
        this.returnCode = returnCode;
    }

    private ValidationProbeEvent(Parcel in) {
        netId = in.readInt();
        durationMs = in.readLong();
        probeType = in.readInt();
        returnCode = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(netId);
        out.writeLong(durationMs);
        out.writeInt(probeType);
        out.writeInt(returnCode);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ValidationProbeEvent> CREATOR
        = new Parcelable.Creator<ValidationProbeEvent>() {
        public ValidationProbeEvent createFromParcel(Parcel in) {
            return new ValidationProbeEvent(in);
        }

        public ValidationProbeEvent[] newArray(int size) {
            return new ValidationProbeEvent[size];
        }
    };

    /** @hide */
    public static String getProbeName(int probeType) {
        return Decoder.constants.get(probeType, "PROBE_???");
    }

    public static void logEvent(int netId, long durationMs, int probeType, int returnCode) {
        logEvent(new ValidationProbeEvent(netId, durationMs, probeType, returnCode));
    }

    @Override
    public String toString() {
        return String.format("ValidationProbeEvent(%d, %s:%d, %dms)",
                netId, getProbeName(probeType), returnCode, durationMs);
    }

    final static class Decoder {
        static final SparseArray<String> constants = MessageUtils.findMessageNames(
                new Class[]{ValidationProbeEvent.class}, new String[]{"PROBE_"});
    }
};
