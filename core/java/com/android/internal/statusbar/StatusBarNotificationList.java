/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;
import java.util.ArrayList;

public class StatusBarNotificationList implements Parcelable {
    private class Entry {
        IBinder key;
        public StatusBarNotification notification;

        void writeToParcel(Parcel out, int flags) {
            out.writeStrongBinder(key);
            notification.writeToParcel(out, flags);
        }

        void readFromParcel(Parcel in) {
            key = in.readStrongBinder();
            notification = new StatusBarNotification(in);
        }

        public Entry clone() {
            Entry that = new Entry();
            that.key = this.key;
            that.notification = this.notification.clone();
            return that;
        }
    }

    private ArrayList<Entry> mEntries = new ArrayList<Entry>();

    public StatusBarNotificationList() {
    }

    public StatusBarNotificationList(Parcel in) {
        readFromParcel(in);
    }
    
    public void readFromParcel(Parcel in) {
        final int N = in.readInt();
        for (int i=0; i<N; i++) {
            Entry e = new Entry();
            e.readFromParcel(in);
            mEntries.add(e);
        }
    }

    public void writeToParcel(Parcel out, int flags) {
        final int N = mEntries.size();
        out.writeInt(N);
        for (int i=0; i<N; i++) {
            mEntries.get(i).writeToParcel(out, flags);
        }
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable.Creator that instantiates StatusBarNotificationList objects
     */
    public static final Parcelable.Creator<StatusBarNotificationList> CREATOR
            = new Parcelable.Creator<StatusBarNotificationList>()
    {
        public StatusBarNotificationList createFromParcel(Parcel parcel)
        {
            return new StatusBarNotificationList(parcel);
        }

        public StatusBarNotificationList[] newArray(int size)
        {
            return new StatusBarNotificationList[size];
        }
    };

    public void copyFrom(StatusBarNotificationList that) {
        mEntries.clear();
        final int N = that.mEntries.size();
        for (int i=0; i<N; i++) {
            mEntries.add(that.mEntries.get(i).clone());
        }
    }

    public void dump(PrintWriter pw) {
        final int N = mEntries.size();
        pw.println("Notification list:");
        for (int i=0; i<N; i++) {
            Entry e = mEntries.get(i);
            pw.printf("  %2d: %s\n", i, e.notification.toString());
        }
    }


    public int size() {
        return mEntries.size();
    }

    public IBinder add(StatusBarNotification notification) {
        if (notification == null) throw new NullPointerException();

        Entry entry = new Entry();
        entry.key = new Binder();
        entry.notification = notification.clone();

        // TODO: Sort correctly by "when"
        mEntries.add(entry);

        return entry.key;
    }

    public void update(IBinder key, StatusBarNotification notification) {
        final int index = getIndex(key);
        if (index < 0) {
            throw new IllegalArgumentException("got invalid key: " + key);
        }
        final Entry entry = mEntries.get(index);
        entry.notification = notification.clone();
    }

    public void remove(IBinder key) {
        final int index = getIndex(key);
        if (index < 0) {
            throw new IllegalArgumentException("got invalid key: " + key);
        }
        mEntries.remove(index);
    }

    public int getIndex(IBinder key) {
        final ArrayList<Entry> entries = mEntries;
        final int N = entries.size();
        for (int i=0; i<N; i++) {
            if (entries.get(i).key == key) {
                return i;
            }
        }
        return -1;
    }

    public StatusBarNotification getNotification(int index) {
        return mEntries.get(index).notification;
    }
}

