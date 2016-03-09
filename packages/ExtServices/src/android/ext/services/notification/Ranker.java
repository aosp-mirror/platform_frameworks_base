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

package android.ext.services.notification;

import android.service.notification.NotificationRankerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Class that provides an updatable ranker module for the notification manager..
 */
public final class Ranker extends NotificationRankerService {
    private static final String TAG = "RocketRanker";
    private static final boolean DEBUG =  Log.isLoggable(TAG, Log.DEBUG);;

    @Override
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn, int importance,
            boolean user) {
        if (DEBUG) Log.i(TAG, "ENQUEUED " + sbn.getKey());
        return null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (DEBUG) Log.i(TAG, "POSTED " + sbn.getKey());
    }

    @Override
    public void onListenerConnected() {
        if (DEBUG) Log.i(TAG, "CONNECTED");
    }
}