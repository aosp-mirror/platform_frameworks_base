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

import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.NotificationRankerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import android.ext.services.R;

/**
 * Class that provides an updatable ranker module for the notification manager.
 * TODO: delete
 */
public final class Ranker extends NotificationRankerService {
    private static final String TAG = "RocketRanker";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @Override
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn, int importance,
            boolean user) {
        return null;
    }
}