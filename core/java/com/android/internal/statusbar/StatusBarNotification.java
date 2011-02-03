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
import android.widget.RemoteViews;


/*
boolean clearable = !n.ongoingEvent && ((notification.flags & Notification.FLAG_NO_CLEAR) == 0);


// TODO: make this restriction do something smarter like never fill
// more than two screens.  "Why would anyone need more than 80 characters." :-/
final int maxTickerLen = 80;
if (truncatedTicker != null && truncatedTicker.length() > maxTickerLen) {
    truncatedTicker = truncatedTicker.subSequence(0, maxTickerLen);
}
*/

public class StatusBarNotification implements Parcelable {
    public static int PRIORITY_JIFFY_EXPRESS = -100;
    public static int PRIORITY_NORMAL        = 0;
    public static int PRIORITY_ONGOING       = 100;
    public static int PRIORITY_SYSTEM        = 200;

    public String pkg;
    public int id;
    public String tag;
    public int uid;
    public int initialPid;
    public Notification notification;
    public int priority = PRIORITY_NORMAL;

    public StatusBarNotification() {
    }

    public StatusBarNotification(String pkg, int id, String tag,
            int uid, int initialPid, Notification notification) {
        if (pkg == null) throw new NullPointerException();
        if (notification == null) throw new NullPointerException();

        this.pkg = pkg;
        this.id = id;
        this.tag = tag;
        this.uid = uid;
        this.initialPid = initialPid;
        this.notification = notification;

        this.priority = PRIORITY_NORMAL;
    }

    public StatusBarNotification(Parcel in) {
        readFromParcel(in);
    }

    public void readFromParcel(Parcel in) {
        this.pkg = in.readString();
        this.id = in.readInt();
        if (in.readInt() != 0) {
            this.tag = in.readString();
        } else {
            this.tag = null;
        }
        this.uid = in.readInt();
        this.initialPid = in.readInt();
        this.priority = in.readInt();
        this.notification = new Notification(in);
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(this.pkg);
        out.writeInt(this.id);
        if (this.tag != null) {
            out.writeInt(1);
            out.writeString(this.tag);
        } else {
            out.writeInt(0);
        }
        out.writeInt(this.uid);
        out.writeInt(this.initialPid);
        out.writeInt(this.priority);
        this.notification.writeToParcel(out, flags);
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

    public StatusBarNotification clone() {
        return new StatusBarNotification(this.pkg, this.id, this.tag,
                this.uid, this.initialPid, this.notification.clone());
    }

    public String toString() {
        return "StatusBarNotification(package=" + pkg + " id=" + id + " tag=" + tag
                + " notification=" + notification + " priority=" + priority + ")";
    }

    public boolean isOngoing() {
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    public boolean isClearable() {
        return ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0)
                && ((notification.flags & Notification.FLAG_NO_CLEAR) == 0);
    }
}


