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

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telecom.TelecomManager;

import com.android.internal.util.NotificationMessagingUtil;

import java.util.Comparator;
import java.util.Objects;

/**
 * Sorts notifications individually into attention-relevant order.
 */
public class NotificationComparator
        implements Comparator<NotificationRecord> {

    private final Context mContext;
    private final NotificationMessagingUtil mMessagingUtil;
    private String mDefaultPhoneApp;

    public NotificationComparator(Context context) {
        mContext = context;
        mContext.registerReceiver(mPhoneAppBroadcastReceiver,
                new IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED));
        mMessagingUtil = new NotificationMessagingUtil(mContext);
    }

    @Override
    public int compare(NotificationRecord left, NotificationRecord right) {
        final int leftImportance = left.getImportance();
        final int rightImportance = right.getImportance();
        final boolean isLeftHighImportance = leftImportance >= IMPORTANCE_DEFAULT;
        final boolean isRightHighImportance = rightImportance >= IMPORTANCE_DEFAULT;

        if (isLeftHighImportance != isRightHighImportance) {
            // by importance bucket, high importance higher than low importance
            return -1 * Boolean.compare(isLeftHighImportance, isRightHighImportance);
        }

        // If a score has been assigned by notification assistant service, use this service
        // rank results within each bucket instead of this comparator implementation.
        if (left.getRankingScore() != right.getRankingScore()) {
            return -1 * Float.compare(left.getRankingScore(), right.getRankingScore());
        }

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

        boolean leftMessaging = isImportantMessaging(left);
        boolean rightMessaging = isImportantMessaging(right);
        if (leftMessaging != rightMessaging) {
            return -1 * Boolean.compare(leftMessaging, rightMessaging);
        }

        // Next: sufficiently import person to person communication
        boolean leftPeople = isImportantPeople(left);
        boolean rightPeople = isImportantPeople(right);
        final int contactAffinityComparison =
                Float.compare(left.getContactAffinity(), right.getContactAffinity());

        if (leftPeople && rightPeople){
            // by contact proximity, close to far. if same proximity, check further fields.
            if (contactAffinityComparison != 0) {
                return -1 * contactAffinityComparison;
            }
        } else if (leftPeople != rightPeople) {
            // People, messaging higher than non-messaging
            return -1 * Boolean.compare(leftPeople, rightPeople);
        }

        if (leftImportance != rightImportance) {
            // by importance, high to low
            return -1 * Integer.compare(leftImportance, rightImportance);
        }

        // by contact proximity, close to far. if same proximity, check further fields.
        if (contactAffinityComparison != 0) {
            return -1 * contactAffinityComparison;
        }

        // Whether or not the notification can bypass DND.
        final int leftPackagePriority = left.getPackagePriority();
        final int rightPackagePriority = right.getPackagePriority();
        if (leftPackagePriority != rightPackagePriority) {
            // by priority, high to low
            return -1 * Integer.compare(leftPackagePriority, rightPackagePriority);
        }

        final int leftPriority = left.getSbn().getNotification().priority;
        final int rightPriority = right.getSbn().getNotification().priority;
        if (leftPriority != rightPriority) {
            // by priority, high to low
            return -1 * Integer.compare(leftPriority, rightPriority);
        }

        final boolean leftInterruptive = left.isInterruptive();
        final boolean rightInterruptive = right.isInterruptive();
        if (leftInterruptive != rightInterruptive) {
            return -1 * Boolean.compare(leftInterruptive, rightInterruptive);
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

        return isCall(record) || isMediaNotification(record);
    }

    protected boolean isImportantPeople(NotificationRecord record) {
        if (record.getImportance() < NotificationManager.IMPORTANCE_LOW) {
            return false;
        }
        if (record.getContactAffinity() > ValidateNotificationPeople.NONE) {
            return true;
        }
        return false;
    }

    protected boolean isImportantMessaging(NotificationRecord record) {
        return mMessagingUtil.isImportantMessaging(record.getSbn(), record.getImportance());
    }

    private boolean isOngoing(NotificationRecord record) {
        final int ongoingFlags = Notification.FLAG_FOREGROUND_SERVICE;
        return (record.getNotification().flags & ongoingFlags) != 0;
    }

    private boolean isMediaNotification(NotificationRecord record) {
        return record.getNotification().hasMediaSession();
    }

    private boolean isCall(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_CALL)
                && isDefaultPhoneApp(record.getSbn().getPackageName());
    }

    private boolean isDefaultPhoneApp(String pkg) {
        if (mDefaultPhoneApp == null) {
            final TelecomManager telecomm =
                    (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
            mDefaultPhoneApp = telecomm != null ? telecomm.getDefaultDialerPackage() : null;
        }
        return Objects.equals(pkg, mDefaultPhoneApp);
    }

    private final BroadcastReceiver mPhoneAppBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mDefaultPhoneApp =
                    intent.getStringExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME);
        }
    };
}
