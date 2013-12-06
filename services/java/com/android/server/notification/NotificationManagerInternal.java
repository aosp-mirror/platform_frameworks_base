package com.android.server.notification;

import android.app.Notification;

public interface NotificationManagerInternal {
    void enqueueNotification(String pkg, String basePkg, int callingUid, int callingPid,
            String tag, int id, Notification notification, int[] idReceived, int userId);
}
