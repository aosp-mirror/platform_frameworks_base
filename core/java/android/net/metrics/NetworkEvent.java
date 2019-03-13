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
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.internal.util.MessageUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * {@hide}
 */
@SystemApi
@TestApi
public final class NetworkEvent implements IpConnectivityLog.Event {

    public static final int NETWORK_CONNECTED            = 1;
    public static final int NETWORK_VALIDATED            = 2;
    public static final int NETWORK_VALIDATION_FAILED    = 3;
    public static final int NETWORK_CAPTIVE_PORTAL_FOUND = 4;
    public static final int NETWORK_LINGER               = 5;
    public static final int NETWORK_UNLINGER             = 6;
    public static final int NETWORK_DISCONNECTED         = 7;

    public static final int NETWORK_FIRST_VALIDATION_SUCCESS      = 8;
    public static final int NETWORK_REVALIDATION_SUCCESS          = 9;
    public static final int NETWORK_FIRST_VALIDATION_PORTAL_FOUND = 10;
    public static final int NETWORK_REVALIDATION_PORTAL_FOUND     = 11;

    public static final int NETWORK_CONSECUTIVE_DNS_TIMEOUT_FOUND = 12;

    public static final int NETWORK_PARTIAL_CONNECTIVITY = 13;

    /** @hide */
    @IntDef(value = {
            NETWORK_CONNECTED,
            NETWORK_VALIDATED,
            NETWORK_VALIDATION_FAILED,
            NETWORK_CAPTIVE_PORTAL_FOUND,
            NETWORK_LINGER,
            NETWORK_UNLINGER,
            NETWORK_DISCONNECTED,
            NETWORK_FIRST_VALIDATION_SUCCESS,
            NETWORK_REVALIDATION_SUCCESS,
            NETWORK_FIRST_VALIDATION_PORTAL_FOUND,
            NETWORK_REVALIDATION_PORTAL_FOUND,
            NETWORK_CONSECUTIVE_DNS_TIMEOUT_FOUND,
            NETWORK_PARTIAL_CONNECTIVITY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}

    /** @hide */
    public final @EventType int eventType;
    /** @hide */
    public final long durationMs;

    public NetworkEvent(@EventType int eventType, long durationMs) {
        this.eventType = eventType;
        this.durationMs = durationMs;
    }

    public NetworkEvent(@EventType int eventType) {
        this(eventType, 0);
    }

    private NetworkEvent(Parcel in) {
        eventType = in.readInt();
        durationMs = in.readLong();
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(eventType);
        out.writeLong(durationMs);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public static final @android.annotation.NonNull Parcelable.Creator<NetworkEvent> CREATOR
        = new Parcelable.Creator<NetworkEvent>() {
        public NetworkEvent createFromParcel(Parcel in) {
            return new NetworkEvent(in);
        }

        public NetworkEvent[] newArray(int size) {
            return new NetworkEvent[size];
        }
    };

    @Override
    public String toString() {
        return String.format("NetworkEvent(%s, %dms)",
                Decoder.constants.get(eventType), durationMs);
    }

    final static class Decoder {
        static final SparseArray<String> constants = MessageUtils.findMessageNames(
                new Class[]{NetworkEvent.class}, new String[]{"NETWORK_"});
    }
}
