/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.util.Log;

import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.notification.collection.NotifCollection;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Initialization code for the new notification pipeline.
 */
@Singleton
public class NewNotifPipeline {
    private final NotifCollection mNotifCollection;

    @Inject
    public NewNotifPipeline(
            NotifCollection notifCollection) {
        mNotifCollection = notifCollection;
    }

    /** Hooks the new pipeline up to NotificationManager */
    public void initialize(
            NotificationListener notificationService) {
        mNotifCollection.attach(notificationService);

        Log.d(TAG, "Notif pipeline initialized");
    }

    private static final String TAG = "NewNotifPipeline";
}
