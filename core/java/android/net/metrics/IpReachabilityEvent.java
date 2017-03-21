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
import android.util.SparseArray;

import com.android.internal.util.MessageUtils;

/**
 * An event recorded when IpReachabilityMonitor sends a neighbor probe or receives
 * a neighbor probe result.
 * {@hide}
 */
public final class IpReachabilityEvent implements Parcelable {

    // Event types.
    /** A probe forced by IpReachabilityMonitor. */
    public static final int PROBE                     = 1 << 8;
    /** Neighbor unreachable after a forced probe. */
    public static final int NUD_FAILED                = 2 << 8;
    /** Neighbor unreachable after a forced probe, IP provisioning is also lost. */
    public static final int PROVISIONING_LOST         = 3 << 8;
    /** Neighbor unreachable notification from kernel. */
    public static final int NUD_FAILED_ORGANIC        = 4 << 8;
    /** Neighbor unreachable notification from kernel, IP provisioning is also lost. */
    public static final int PROVISIONING_LOST_ORGANIC = 5 << 8;

    // eventType byte format (MSB to LSB):
    // byte 0: unused
    // byte 1: unused
    // byte 2: type of event: PROBE, NUD_FAILED, PROVISIONING_LOST
    // byte 3: when byte 2 == PROBE, errno code from RTNetlink or IpReachabilityMonitor.
    public final int eventType;

    public IpReachabilityEvent(int eventType) {
        this.eventType = eventType;
    }

    private IpReachabilityEvent(Parcel in) {
        this.eventType = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(eventType);
    }

    @Override
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

    /**
     * Returns the NUD failure event type code corresponding to the given conditions.
     */
    public static int nudFailureEventType(boolean isFromProbe, boolean isProvisioningLost) {
        if (isFromProbe) {
            return isProvisioningLost ? PROVISIONING_LOST : NUD_FAILED;
        } else {
            return isProvisioningLost ? PROVISIONING_LOST_ORGANIC : NUD_FAILED_ORGANIC;
        }
    }

    @Override
    public String toString() {
        int hi = eventType & 0xff00;
        int lo = eventType & 0x00ff;
        String eventName = Decoder.constants.get(hi);
        return String.format("IpReachabilityEvent(%s:%02x)", eventName, lo);
    }

    final static class Decoder {
        static final SparseArray<String> constants =
                MessageUtils.findMessageNames(new Class[]{IpReachabilityEvent.class},
                new String[]{"PROBE", "PROVISIONING_", "NUD_"});
    }
}
