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
public final class IpManagerEvent extends IpConnectivityEvent implements Parcelable {
    public final String ifName;
    public final long durationMs;

    private IpManagerEvent(String ifName, long duration) {
        this.ifName = ifName;
        this.durationMs = duration;
    }

    private IpManagerEvent(Parcel in) {
        this.ifName = in.readString();
        this.durationMs = in.readLong();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
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
        logEvent(eventType, new IpManagerEvent(ifName, durationMs));
    }
};
