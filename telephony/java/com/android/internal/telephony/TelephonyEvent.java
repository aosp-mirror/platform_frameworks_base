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

package com.android.internal.telephony;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 *  A parcelable used in ITelephonyDebugSubscriber.aidl
 */
public class TelephonyEvent implements Parcelable {

    final public long timestamp;
    final public int phoneId;
    final public int tag;
    final public int param1;
    final public int param2;
    final public Bundle data;

    public TelephonyEvent(long timestamp, int phoneId, int tag,
            int param1, int param2, Bundle data) {
        this.timestamp = timestamp;
        this.phoneId = phoneId;
        this.tag = tag;
        this.param1 = param1;
        this.param2 = param2;
        this.data = data;
    }

    /** Implement the Parcelable interface */
    public static final Parcelable.Creator<TelephonyEvent> CREATOR
            = new Parcelable.Creator<TelephonyEvent> (){
        public TelephonyEvent createFromParcel(Parcel source) {
            final long timestamp = source.readLong();
            final int phoneId = source.readInt();
            final int tag = source.readInt();
            final int param1 = source.readInt();
            final int param2 = source.readInt();
            final Bundle data = source.readBundle();
            return new TelephonyEvent(timestamp, phoneId, tag, param1, param2, data);
        }

        public TelephonyEvent[] newArray(int size) {
            return new TelephonyEvent[size];
        }
    };

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timestamp);
        dest.writeInt(phoneId);
        dest.writeInt(tag);
        dest.writeInt(param1);
        dest.writeInt(param2);
        dest.writeBundle(data);
    }

    public String toString() {
        return String.format("%d,%d,%d,%d,%d,%s",
                timestamp, phoneId, tag, param1, param2, data);
    }
}
