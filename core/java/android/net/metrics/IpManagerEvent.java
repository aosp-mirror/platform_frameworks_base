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
public final class IpManagerEvent extends IpConnectivityEvent implements Parcelable {

    public static final int PROVISIONING_OK    = 1;
    public static final int PROVISIONING_FAIL  = 2;
    public static final int COMPLETE_LIFECYCLE = 3;

    public final String ifName;
    public final int eventType;
    public final long durationMs;

    private IpManagerEvent(String ifName, int eventType, long duration) {
        this.ifName = ifName;
        this.eventType = eventType;
        this.durationMs = duration;
    }

    private IpManagerEvent(Parcel in) {
        this.ifName = in.readString();
        this.eventType = in.readInt();
        this.durationMs = in.readLong();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
        out.writeInt(eventType);
        out.writeLong(durationMs);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<IpManagerEvent> CREATOR
        = new Parcelable.Creator<IpManagerEvent>() {
        public IpManagerEvent createFromParcel(Parcel in) {
            return new IpManagerEvent(in);
        }

        public IpManagerEvent[] newArray(int size) {
            return new IpManagerEvent[size];
        }
    };

    public static void logEvent(int eventType, String ifName, long durationMs) {
        logEvent(new IpManagerEvent(ifName, eventType, durationMs));
    }

    @Override
    public String toString() {
        return String.format("IpManagerEvent(%s, %s, %dms)",
                ifName, Decoder.constants.get(eventType), durationMs);
    }

    final static class Decoder {
        static final SparseArray<String> constants = MessageUtils.findMessageNames(
                new Class[]{IpManagerEvent.class}, new String[]{"PROVISIONING_", "COMPLETE_"});
    }
};
