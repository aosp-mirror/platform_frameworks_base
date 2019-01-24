/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.statusbar;

import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.ArrayDeque;
import java.util.Collection;

public class NotificationVisibility implements Parcelable {
    private static final String TAG = "NoViz";
    private static final int MAX_POOL_SIZE = 25;
    private static int sNexrId = 0;

    public String key;
    public int rank;
    public int count;
    public boolean visible = true;
    /** The visible location of the notification, could be e.g. notification shade or HUN. */
    public NotificationLocation location;
    /*package*/ int id;

    /**
     * The UI location of the notification.
     *
     * There is a one-to-one mapping between this enum and
     * MetricsProto.MetricsEvent.NotificationLocation.
     */
    public enum NotificationLocation {
        LOCATION_UNKNOWN(MetricsEvent.LOCATION_UNKNOWN),
        LOCATION_FIRST_HEADS_UP(MetricsEvent.LOCATION_FIRST_HEADS_UP), // visible heads-up
        LOCATION_HIDDEN_TOP(MetricsEvent.LOCATION_HIDDEN_TOP), // hidden/scrolled away on the top
        LOCATION_MAIN_AREA(MetricsEvent.LOCATION_MAIN_AREA), // visible in the shade
        // in the bottom stack, and peeking
        LOCATION_BOTTOM_STACK_PEEKING(MetricsEvent.LOCATION_BOTTOM_STACK_PEEKING),
        // in the bottom stack, and hidden
        LOCATION_BOTTOM_STACK_HIDDEN(MetricsEvent.LOCATION_BOTTOM_STACK_HIDDEN),
        LOCATION_GONE(MetricsEvent.LOCATION_GONE); // the view isn't laid out at all

        private final int mMetricsEventNotificationLocation;

        NotificationLocation(int metricsEventNotificationLocation) {
            mMetricsEventNotificationLocation = metricsEventNotificationLocation;
        }

        /**
         * Returns the field from MetricsEvent.NotificationLocation that corresponds to this object.
         */
        public int toMetricsEventEnum() {
            return mMetricsEventNotificationLocation;
        }
    }

    private NotificationVisibility() {
        id = sNexrId++;
    }

    private NotificationVisibility(String key, int rank, int count, boolean visible,
            NotificationLocation location) {
        this();
        this.key = key;
        this.rank = rank;
        this.count = count;
        this.visible = visible;
        this.location = location;
    }

    @Override
    public String toString() {
        return "NotificationVisibility(id=" + id
                + " key=" + key
                + " rank=" + rank
                + " count=" + count
                + (visible?" visible":"")
                + " location=" + location.name()
                + " )";
    }

    @Override
    public NotificationVisibility clone() {
        return obtain(this.key, this.rank, this.count, this.visible, this.location);
    }

    @Override
    public int hashCode() {
        // allow lookups by key, which _should_ never be null.
        return key == null ? 0 : key.hashCode();
    }

    @Override
    public boolean equals(Object that) {
        // allow lookups by key, which _should_ never be null.
        if (that instanceof NotificationVisibility) {
            NotificationVisibility thatViz = (NotificationVisibility) that;
            return (key == null && thatViz.key == null) || key.equals(thatViz.key);
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(this.key);
        out.writeInt(this.rank);
        out.writeInt(this.count);
        out.writeInt(this.visible ? 1 : 0);
        out.writeString(this.location.name());
    }

    private void readFromParcel(Parcel in) {
        this.key = in.readString();
        this.rank = in.readInt();
        this.count = in.readInt();
        this.visible = in.readInt() != 0;
        this.location = NotificationLocation.valueOf(in.readString());
    }

    /**
     * Create a new NotificationVisibility object.
     */
    public static NotificationVisibility obtain(String key, int rank, int count, boolean visible) {
        return obtain(key, rank, count, visible,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
    }

    /**
     * Create a new NotificationVisibility object.
     */
    public static NotificationVisibility obtain(String key, int rank, int count, boolean visible,
            NotificationLocation location) {
        NotificationVisibility vo = obtain();
        vo.key = key;
        vo.rank = rank;
        vo.count = count;
        vo.visible = visible;
        vo.location = location;
        return vo;
    }

    private static NotificationVisibility obtain(Parcel in) {
        NotificationVisibility vo = obtain();
        vo.readFromParcel(in);
        return vo;
    }

    private static NotificationVisibility obtain() {
        return new NotificationVisibility();
    }

    /**
     * Return a NotificationVisibility instance to the global pool.
     * <p>
     * You MUST NOT touch the NotificationVisibility after calling this function because it has
     * effectively been freed.
     * </p>
     */
    public void recycle() {
        // With a modern GC, this is no longer useful for objects this small.
    }

    /**
     * Parcelable.Creator that instantiates NotificationVisibility objects
     */
    public static final Parcelable.Creator<NotificationVisibility> CREATOR
            = new Parcelable.Creator<NotificationVisibility>()
    {
        public NotificationVisibility createFromParcel(Parcel parcel)
        {
            return obtain(parcel);
        }

        public NotificationVisibility[] newArray(int size)
        {
            return new NotificationVisibility[size];
        }
    };
}

