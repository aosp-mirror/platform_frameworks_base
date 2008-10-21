package com.android.server.status;

import android.app.PendingIntent;
import android.widget.RemoteViews;

public class NotificationData {
    public String pkg;
    public int id;
    public CharSequence tickerText;

    public long when;
    public boolean ongoingEvent;
    public boolean clearable;

    public RemoteViews contentView;
    public PendingIntent contentIntent;

    public PendingIntent deleteIntent;

    public NotificationData() {
    }

    public String toString() {
        return "NotificationData(package=" + pkg + " tickerText=" + tickerText
                + " ongoingEvent=" + ongoingEvent + " contentIntent=" + contentIntent
                + " deleteIntent=" + deleteIntent
                + " clearable=" + clearable
                + " contentView=" + contentView + " when=" + when + ")";
    }
}
