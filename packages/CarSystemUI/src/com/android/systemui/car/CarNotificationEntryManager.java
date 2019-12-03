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
package com.android.systemui.car;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.notification.NotificationEntryManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Car specific notification entry manager that does nothing when adding a notification.
 *
 * <p> This is because system UI notifications are disabled and we have a different implementation.
 * Please see {@link com.android.car.notification}.
 */
@Singleton
public class CarNotificationEntryManager extends NotificationEntryManager {

    @Inject
    public CarNotificationEntryManager(Context context) {
        super(context);
    }

    @Override
    public void addNotification(
            StatusBarNotification notification, NotificationListenerService.RankingMap ranking) {
    }
}
