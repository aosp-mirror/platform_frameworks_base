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

package android.net;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/** {@hide} */
@SystemApi
public final class ConnectivityMetricsEvent implements Parcelable {

    /**  The time when this event was collected, as returned by System.currentTimeMillis(). */
    final public long timestamp;

    /** The subsystem that generated the event. One of the COMPONENT_TAG_xxx constants. */
    final public int componentTag;

    /** The subsystem-specific event ID. */
    final public int eventTag;

    /** Opaque event-specific data. */
    final public Parcelable data;

    public ConnectivityMetricsEvent(long timestamp, int componentTag,
                                    int eventTag, Parcelable data) {
        this.timestamp = timestamp;
        this.componentTag = componentTag;
        this.eventTag = eventTag;
        this.data = data;
    }

    /** Implement the Parcelable interface */
    public static final Parcelable.Creator<ConnectivityMetricsEvent> CREATOR
            = new Parcelable.Creator<ConnectivityMetricsEvent> (){
        public ConnectivityMetricsEvent createFromParcel(Parcel source) {
            final long timestamp = source.readLong();
            final int componentTag = source.readInt();
            final int eventTag = source.readInt();
            final Parcelable data = source.readParcelable(null);
            return new ConnectivityMetricsEvent(timestamp, componentTag,
                    eventTag, data);
        }

        public ConnectivityMetricsEvent[] newArray(int size) {
            return new ConnectivityMetricsEvent[size];
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
        dest.writeInt(componentTag);
        dest.writeInt(eventTag);
        dest.writeParcelable(data, 0);
    }

    public String toString() {
        return String.format("ConnectivityMetricsEvent(%tT.%tL, %d, %d): %s",
                timestamp, timestamp, componentTag, eventTag, data);
    }

    /** {@hide} */
    @SystemApi
    public final static class Reference implements Parcelable {

        private long mValue;

        public Reference(long ref) {
            this.mValue = ref;
        }

        /** Implement the Parcelable interface */
        public static final Parcelable.Creator<Reference> CREATOR
                = new Parcelable.Creator<Reference> (){
            public Reference createFromParcel(Parcel source) {
                return new Reference(source.readLong());
            }

            public Reference[] newArray(int size) {
                return new Reference[size];
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
            dest.writeLong(mValue);
        }

        public void readFromParcel(Parcel in) {
            mValue = in.readLong();
        }

        public long getValue() {
            return mValue;
        }

        public void setValue(long val) {
            mValue = val;
        }
    }
}
