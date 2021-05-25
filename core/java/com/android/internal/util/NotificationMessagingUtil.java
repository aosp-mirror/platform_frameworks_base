/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.util;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.SparseArray;

import java.util.Collection;
import java.util.Objects;

/**
 * A util to look up messaging related functions for notifications. This is used for both the
 * ranking and the actual layout.
 */
public class NotificationMessagingUtil {

    private static final String DEFAULT_SMS_APP_SETTING = Settings.Secure.SMS_DEFAULT_APPLICATION;
    private final Context mContext;
    private SparseArray<String> mDefaultSmsApp = new SparseArray<>();

    public NotificationMessagingUtil(Context context) {
        mContext = context;
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(DEFAULT_SMS_APP_SETTING), false, mSmsContentObserver);
    }

    public boolean isImportantMessaging(StatusBarNotification sbn, int importance) {
        if (importance < NotificationManager.IMPORTANCE_LOW) {
            return false;
        }

        return hasMessagingStyle(sbn) || (isCategoryMessage(sbn) && isDefaultMessagingApp(sbn));
    }

    public boolean isMessaging(StatusBarNotification sbn) {
        return hasMessagingStyle(sbn) || isDefaultMessagingApp(sbn) || isCategoryMessage(sbn);
    }

    @SuppressWarnings("deprecation")
    private boolean isDefaultMessagingApp(StatusBarNotification sbn) {
        final int userId = sbn.getUserId();
        if (userId == UserHandle.USER_NULL || userId == UserHandle.USER_ALL) return false;
        if (mDefaultSmsApp.get(userId) == null) {
            cacheDefaultSmsApp(userId);
        }
        return Objects.equals(mDefaultSmsApp.get(userId), sbn.getPackageName());
    }

    private void cacheDefaultSmsApp(int userId) {
        mDefaultSmsApp.put(userId, Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.SMS_DEFAULT_APPLICATION, userId));
    }

    private final ContentObserver mSmsContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
            if (uris.contains(Settings.Secure.getUriFor(DEFAULT_SMS_APP_SETTING))) {
                cacheDefaultSmsApp(userId);
            }
        }
    };

    private boolean hasMessagingStyle(StatusBarNotification sbn) {
        return sbn.getNotification().isStyle(Notification.MessagingStyle.class);
    }

    private boolean isCategoryMessage(StatusBarNotification sbn) {
        return Notification.CATEGORY_MESSAGE.equals(sbn.getNotification().category);
    }
}
