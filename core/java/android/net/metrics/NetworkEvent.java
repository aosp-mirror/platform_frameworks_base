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
 * {@hide}
 */
@SystemApi
public final class NetworkEvent implements Parcelable {

    public static final int NETWORK_CONNECTED            = 1;
    public static final int NETWORK_VALIDATED            = 2;
    public static final int NETWORK_VALIDATION_FAILED    = 3;
    public static final int NETWORK_CAPTIVE_PORTAL_FOUND = 4;
    public static final int NETWORK_LINGER               = 5;
    public static final int NETWORK_UNLINGER             = 6;
    public static final int NETWORK_DISCONNECTED         = 7;

    /** {@hide} */
    @IntDef(value = {
            NETWORK_CONNECTED,
            NETWORK_VALIDATED,
            NETWORK_VALIDATION_FAILED,
            NETWORK_CAPTIVE_PORTAL_FOUND,
            NETWORK_LINGER,
            NETWORK_UNLINGER,
            NETWORK_DISCONNECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}

    public final int netId;
    public final @EventType int eventType;
    public final long durationMs;

    /** {@hide} */
    public NetworkEvent(int netId, @EventType int eventType, long durationMs) {
        this.netId = netId;
        this.eventType = eventType;
        this.durationMs = durationMs;
    }

    /** {@hide} */
    public NetworkEvent(int netId, @EventType int eventType) {
        this(netId, eventType, 0);
    }

    private NetworkEvent(Parcel in) {
        netId = in.readInt();
        eventType = in.readInt();
        durationMs = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(netId);
        out.writeInt(eventType);
        out.writeLong(durationMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<NetworkEvent> CREATOR
        = new Parcelable.Creator<NetworkEvent>() {
        public NetworkEvent createFromParcel(Parcel in) {
            return new NetworkEvent(in);
        }

        public NetworkEvent[] newArray(int size) {
            return new NetworkEvent[size];
        }
    };

    public static void logEvent(int netId, int eventType) {
    }

    public static void logValidated(int netId, long durationMs) {
    }

    public static void logCaptivePortalFound(int netId, long durationMs) {
    }

    @Override
    public String toString() {
        return String.format("NetworkEvent(%d, %s, %dms)",
                netId, Decoder.constants.get(eventType), durationMs);
    }

    final static class Decoder {
        static final SparseArray<String> constants = MessageUtils.findMessageNames(
                new Class[]{NetworkEvent.class}, new String[]{"NETWORK_"});
    }
}
