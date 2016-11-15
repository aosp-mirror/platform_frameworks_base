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

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.internal.util.MessageUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event recorded by NetworkMonitor when sending a probe for finding captive portals.
 * {@hide}
 */
@SystemApi
public final class ValidationProbeEvent implements Parcelable {

    public static final int PROBE_DNS       = 0;
    public static final int PROBE_HTTP      = 1;
    public static final int PROBE_HTTPS     = 2;
    public static final int PROBE_PAC       = 3;
    /** {@hide} */
    public static final int PROBE_FALLBACK  = 4;

    public static final int DNS_FAILURE = 0;
    public static final int DNS_SUCCESS = 1;

    private static final int FIRST_VALIDATION  = 1 << 8;
    private static final int REVALIDATION      = 2 << 8;

    /** {@hide} */
    @IntDef(value = {DNS_FAILURE, DNS_SUCCESS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReturnCode {}

    public final int netId;
    public final long durationMs;
    // probeType byte format (MSB to LSB):
    // byte 0: unused
    // byte 1: unused
    // byte 2: 0 = UNKNOWN, 1 = FIRST_VALIDATION, 2 = REVALIDATION
    // byte 3: PROBE_* constant
    public final int probeType;
    public final @ReturnCode int returnCode;

    /** {@hide} */
    public ValidationProbeEvent(
            int netId, long durationMs, int probeType, @ReturnCode int returnCode) {
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

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(netId);
        out.writeLong(durationMs);
        out.writeInt(probeType);
        out.writeInt(returnCode);
    }

    @Override
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
    public static int makeProbeType(int probeType, boolean firstValidation) {
        return (probeType & 0xff) | (firstValidation ? FIRST_VALIDATION : REVALIDATION);
    }

    /** @hide */
    public static String getProbeName(int probeType) {
        return Decoder.constants.get(probeType & 0xff, "PROBE_???");
    }

    /** @hide */
    public static String getValidationStage(int probeType) {
        return Decoder.constants.get(probeType & 0xff00, "UNKNOWN");
    }

    public static void logEvent(int netId, long durationMs, int probeType, int returnCode) {
    }

    @Override
    public String toString() {
        return String.format("ValidationProbeEvent(%d, %s:%d %s, %dms)", netId,
                getProbeName(probeType), returnCode, getValidationStage(probeType), durationMs);
    }

    final static class Decoder {
        static final SparseArray<String> constants = MessageUtils.findMessageNames(
                new Class[]{ValidationProbeEvent.class},
                new String[]{"PROBE_", "FIRST_", "REVALIDATION"});
    }
}
