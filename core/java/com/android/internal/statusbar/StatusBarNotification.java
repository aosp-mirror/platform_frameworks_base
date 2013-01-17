/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.Notification;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

/**
 * Class encapsulating a Notification. Sent by the NotificationManagerService to clients including
 * the IStatusBar (in System UI).
 */
public class StatusBarNotification implements Parcelable {
    public final String pkg;
    public final String basePkg;
    public final int id;
    public final String tag;
    public final int uid;
    public final int initialPid;
    // TODO: make this field private and move callers to an accessor that
    // ensures sourceUser is applied.
    public final Notification notification;
    public final int score;
    public final UserHandle user;
    public final long postTime;

    /** This is temporarily needed for the JB MR1 PDK. */
    @Deprecated
    public StatusBarNotification(String pkg, int id, String tag, int uid, int initialPid, int score,
            Notification notification) {
        this(pkg, id, tag, uid, initialPid, score, notification, UserHandle.OWNER);
    }

    public StatusBarNotification(String pkg, int id, String tag, int uid, int initialPid, int score,
            Notification notification, UserHandle user) {
        this(pkg, null, id, tag, uid, initialPid, score, notification, user);
    }

    public StatusBarNotification(String pkg, String basePkg, int id, String tag, int uid,
            int initialPid, int score, Notification notification, UserHandle user) {
        this(pkg, basePkg, id, tag, uid, initialPid, score, notification, user,
                System.currentTimeMillis());
    }

    public StatusBarNotification(String pkg, String basePkg, int id, String tag, int uid,
            int initialPid, int score, Notification notification, UserHandle user,
            long postTime) {
        if (pkg == null) throw new NullPointerException();
        if (notification == null) throw new NullPointerException();

        this.pkg = pkg;
        this.basePkg = pkg;
        this.id = id;
        this.tag = tag;
        this.uid = uid;
        this.initialPid = initialPid;
        this.score = score;
        this.notification = notification;
        this.user = user;
        this.notification.setUser(user);

        this.postTime = postTime;
    }

    public StatusBarNotification(Parcel in) {
        this.pkg = in.readString();
        this.basePkg = in.readString();
        this.id = in.readInt();
        if (in.readInt() != 0) {
            this.tag = in.readString();
        } else {
            this.tag = null;
        }
        this.uid = in.readInt();
        this.initialPid = in.readInt();
        this.score = in.readInt();
        this.notification = new Notification(in);
        this.user = UserHandle.readFromParcel(in);
        this.notification.setUser(this.user);
        this.postTime = in.readLong();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(this.pkg);
        out.writeString(this.basePkg);
        out.writeInt(this.id);
        if (this.tag != null) {
            out.writeInt(1);
            out.writeString(this.tag);
        } else {
            out.writeInt(0);
        }
        out.writeInt(this.uid);
        out.writeInt(this.initialPid);
        out.writeInt(this.score);
        this.notification.writeToParcel(out, flags);
        user.writeToParcel(out, flags);

        out.writeLong(this.postTime);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<StatusBarNotification> CREATOR
            = new Parcelable.Creator<StatusBarNotification>()
    {
        public StatusBarNotification createFromParcel(Parcel parcel)
        {
            return new StatusBarNotification(parcel);
        }

        public StatusBarNotification[] newArray(int size)
        {
            return new StatusBarNotification[size];
        }
    };

    @Override
    public StatusBarNotification clone() {
        return new StatusBarNotification(this.pkg, this.basePkg,
                this.id, this.tag, this.uid, this.initialPid,
                this.score, this.notification.clone(), this.user, this.postTime);
    }

    @Override
    public String toString() {
        return String.format(
                "StatusBarNotification(pkg=%s user=%s id=%d tag=%s score=%d: %s)",
                this.pkg, this.user, this.id, this.tag,
                this.score, this.notification);
    }

    public boolean isOngoing() {
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    public boolean isClearable() {
        return ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0)
                && ((notification.flags & Notification.FLAG_NO_CLEAR) == 0);
    }

    /** Returns a userHandle for the instance of the app that posted this notification. */
    public int getUserId() {
        return this.user.getIdentifier();
    }
}
