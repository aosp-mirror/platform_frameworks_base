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

import java.util.ArrayDeque;
import java.util.Collection;

public class NotificationVisibility implements Parcelable {
    private static final String TAG = "NoViz";
    private static final int MAX_POOL_SIZE = 25;
    private static ArrayDeque<NotificationVisibility> sPool = new ArrayDeque<>(MAX_POOL_SIZE);
    private static int sNexrId = 0;

    public String key;
    public int rank;
    public int count;
    public boolean visible = true;
    /*package*/ int id;

    private NotificationVisibility() {
        id = sNexrId++;
    }

    private NotificationVisibility(String key, int rank, int count, boolean visibile) {
        this();
        this.key = key;
        this.rank = rank;
        this.count = count;
        this.visible = visibile;
    }

    @Override
    public String toString() {
        return "NotificationVisibility(id=" + id
                + " key=" + key
                + " rank=" + rank
                + " count=" + count
                + (visible?" visible":"")
                + " )";
    }

    @Override
    public NotificationVisibility clone() {
        return obtain(this.key, this.rank, this.count, this.visible);
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
    }

    private void readFromParcel(Parcel in) {
        this.key = in.readString();
        this.rank = in.readInt();
        this.count = in.readInt();
        this.visible = in.readInt() != 0;
    }

    /**
     * Return a new NotificationVisibility instance from the global pool. Allows us to
     * avoid allocating new objects in many cases.
     */
    public static NotificationVisibility obtain(String key, int rank, int count, boolean visible) {
        NotificationVisibility vo = obtain();
        vo.key = key;
        vo.rank = rank;
        vo.count = count;
        vo.visible = visible;
        return vo;
    }

    private static NotificationVisibility obtain(Parcel in) {
        NotificationVisibility vo = obtain();
        vo.readFromParcel(in);
        return vo;
    }

    private static NotificationVisibility obtain() {
        synchronized (sPool) {
            if (!sPool.isEmpty()) {
                return sPool.poll();
            }
        }
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
        if (key == null) {
            // do nothing on multiple recycles
            return;
        }
        key = null;
        if (sPool.size() < MAX_POOL_SIZE) {
            synchronized (sPool) {
                sPool.offer(this);
            }
        }
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

