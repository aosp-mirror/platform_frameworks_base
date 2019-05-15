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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * An event recorded when a DhcpClient state machine transitions to a new state.
 * {@hide}
 */
@SystemApi
@TestApi
public final class DhcpClientEvent implements IpConnectivityLog.Event {

    // Names for recording DhcpClient pseudo-state transitions.

    /** @hide */
    public final String msg;
    /** @hide */
    public final int durationMs;

    @UnsupportedAppUsage
    private DhcpClientEvent(String msg, int durationMs) {
        this.msg = msg;
        this.durationMs = durationMs;
    }

    private DhcpClientEvent(Parcel in) {
        this.msg = in.readString();
        this.durationMs = in.readInt();
    }

    /**
     * Utility to create an instance of {@link ApfProgramEvent}.
     */
    public static final class Builder {
        private String mMsg;
        private int mDurationMs;

        /**
         * Set the message of the event.
         */
        @NonNull
        public Builder setMsg(String msg) {
            mMsg = msg;
            return this;
        }

        /**
         * Set the duration of the event in milliseconds.
         */
        @NonNull
        public Builder setDurationMs(int durationMs) {
            mDurationMs = durationMs;
            return this;
        }

        /**
         * Create a new {@link DhcpClientEvent}.
         */
        @NonNull
        public DhcpClientEvent build() {
            return new DhcpClientEvent(mMsg, mDurationMs);
        }
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(msg);
        out.writeInt(durationMs);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return String.format("DhcpClientEvent(%s, %dms)", msg, durationMs);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj.getClass().equals(DhcpClientEvent.class))) return false;
        final DhcpClientEvent other = (DhcpClientEvent) obj;
        return TextUtils.equals(msg, other.msg)
                && durationMs == other.durationMs;
    }

    /** @hide */
    public static final @android.annotation.NonNull Parcelable.Creator<DhcpClientEvent> CREATOR
        = new Parcelable.Creator<DhcpClientEvent>() {
        public DhcpClientEvent createFromParcel(Parcel in) {
            return new DhcpClientEvent(in);
        }

        public DhcpClientEvent[] newArray(int size) {
            return new DhcpClientEvent[size];
        }
    };
}
