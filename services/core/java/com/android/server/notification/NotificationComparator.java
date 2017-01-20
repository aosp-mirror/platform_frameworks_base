/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.notification;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArrayMap;

import java.util.Comparator;
import java.util.Objects;

/**
 * Sorts notifications individually into attention-relevant order.
 */
public class NotificationComparator
        implements Comparator<NotificationRecord> {

    private final String DEFAULT_SMS_APP_SETTING = Settings.Secure.SMS_DEFAULT_APPLICATION;

    private final Context mContext;
    private String mDefaultPhoneApp;
    private ArrayMap<Integer, String> mDefaultSmsApp = new ArrayMap<>();

    public NotificationComparator(Context context) {
        mContext = context;
        mContext.registerReceiver(mPhoneAppBroadcastReceiver,
                new IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED));
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(DEFAULT_SMS_APP_SETTING), false, mSmsContentObserver);
    }

    @Override
    public int compare(NotificationRecord left, NotificationRecord right) {
        // first all colorized notifications
        boolean leftImportantColorized = isImportantColorized(left);
        boolean rightImportantColorized = isImportantColorized(right);

        if (leftImportantColorized != rightImportantColorized) {
            return -1 * Boolean.compare(leftImportantColorized, rightImportantColorized);
        }

        // sufficiently important ongoing notifications of certain categories
        boolean leftImportantOngoing = isImportantOngoing(left);
        boolean rightImportantOngoing = isImportantOngoing(right);

        if (leftImportantOngoing != rightImportantOngoing) {
            // by ongoing, ongoing higher than non-ongoing
            return -1 * Boolean.compare(leftImportantOngoing, rightImportantOngoing);
        }

        // Next: sufficiently import person to person communication
        boolean leftPeople = isImportantMessaging(left);
        boolean rightPeople = isImportantMessaging(right);

        if (leftPeople && rightPeople){
            // by contact proximity, close to far. if same proximity, check further fields.
            if (Float.compare(left.getContactAffinity(), right.getContactAffinity()) != 0) {
                return -1 * Float.compare(left.getContactAffinity(), right.getContactAffinity());
            }
        } else if (leftPeople != rightPeople) {
            // People, messaging higher than non-messaging
            return -1 * Boolean.compare(leftPeople, rightPeople);
        }

        final int leftImportance = left.getImportance();
        final int rightImportance = right.getImportance();
        if (leftImportance != rightImportance) {
            // by importance, high to low
            return -1 * Integer.compare(leftImportance, rightImportance);
        }

        // Whether or not the notification can bypass DND.
        final int leftPackagePriority = left.getPackagePriority();
        final int rightPackagePriority = right.getPackagePriority();
        if (leftPackagePriority != rightPackagePriority) {
            // by priority, high to low
            return -1 * Integer.compare(leftPackagePriority, rightPackagePriority);
        }

        final int leftPriority = left.sbn.getNotification().priority;
        final int rightPriority = right.sbn.getNotification().priority;
        if (leftPriority != rightPriority) {
            // by priority, high to low
            return -1 * Integer.compare(leftPriority, rightPriority);
        }

        // then break ties by time, most recent first
        return -1 * Long.compare(left.getRankingTimeMs(), right.getRankingTimeMs());
    }

    private boolean isImportantColorized(NotificationRecord record) {
        if (record.getImportance() < NotificationManager.IMPORTANCE_LOW) {
            return false;
        }
        return record.getNotification().isColorized();
    }

    private boolean isImportantOngoing(NotificationRecord record) {
        if (!isOngoing(record)) {
            return false;
        }

        if (record.getImportance() < NotificationManager.IMPORTANCE_LOW) {
            return false;
        }

        // TODO: add whitelist

        return isCall(record) || isMediaNotification(record);
    }

    protected boolean isImportantMessaging(NotificationRecord record) {
        if (record.getImportance() < NotificationManager.IMPORTANCE_LOW) {
            return false;
        }

        Class<? extends Notification.Style> style = getNotificationStyle(record);
        if (Notification.MessagingStyle.class.equals(style)) {
            return true;
        }

        if (record.getContactAffinity() > ValidateNotificationPeople.NONE) {
            return true;
        }

        if (record.getNotification().category == Notification.CATEGORY_MESSAGE
                && isDefaultMessagingApp(record)) {
            return true;
        }

        return false;
    }

    private boolean isOngoing(NotificationRecord record) {
        final int ongoingFlags =
                Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_ONGOING_EVENT;
        return (record.getNotification().flags & ongoingFlags) != 0;
    }

    private Class<? extends Notification.Style> getNotificationStyle(NotificationRecord record) {
        String templateClass =
                record.getNotification().extras.getString(Notification.EXTRA_TEMPLATE);

        if (!TextUtils.isEmpty(templateClass)) {
            return Notification.getNotificationStyleClass(templateClass);
        }
        return null;
    }

    private boolean isMediaNotification(NotificationRecord record) {
        return record.getNotification().extras.getParcelable(
                Notification.EXTRA_MEDIA_SESSION) != null;
    }

    private boolean isCall(NotificationRecord record) {
        return record.getNotification().category == Notification.CATEGORY_CALL
                && isDefaultPhoneApp(record.sbn.getPackageName());
    }

    private boolean isDefaultPhoneApp(String pkg) {
        if (mDefaultPhoneApp == null) {
            final TelecomManager telecomm =
                    (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
            mDefaultPhoneApp = telecomm != null ? telecomm.getDefaultDialerPackage() : null;
        }
        return Objects.equals(pkg, mDefaultPhoneApp);
    }

    @SuppressWarnings("deprecation")
    private boolean isDefaultMessagingApp(NotificationRecord record) {
        final int userId = record.getUserId();
        if (userId == UserHandle.USER_NULL || userId == UserHandle.USER_ALL) return false;
        if (mDefaultSmsApp.get(userId) == null) {
            mDefaultSmsApp.put(userId, Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.SMS_DEFAULT_APPLICATION, userId));
        }
        return Objects.equals(mDefaultSmsApp.get(userId), record.sbn.getPackageName());
    }

    private final BroadcastReceiver mPhoneAppBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mDefaultPhoneApp =
                    intent.getStringExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME);
        }
    };

    private final ContentObserver mSmsContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (Settings.Secure.getUriFor(DEFAULT_SMS_APP_SETTING).equals(uri)) {
                mDefaultSmsApp.put(userId, Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.SMS_DEFAULT_APPLICATION, userId));

            }
        }
    };
}
