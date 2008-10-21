package com.android.server.status;

import android.os.IBinder;
import android.view.View;

class StatusBarNotification {
    IBinder key;
    NotificationData data;
    View view;
    View contentView;
}
