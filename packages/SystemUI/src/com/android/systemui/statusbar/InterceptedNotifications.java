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

package com.android.systemui.statusbar;

import android.app.Notification;
import android.content.Context;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

public class InterceptedNotifications {
    private static final String TAG = "InterceptedNotifications";
    private static final String EXTRA_INTERCEPT = "android.intercept";
    private static final String SYNTHETIC_KEY = "InterceptedNotifications.SYNTHETIC_KEY";

    private final Context mContext;
    private final PhoneStatusBar mBar;
    private final ArrayMap<String, StatusBarNotification> mIntercepted
            = new ArrayMap<String, StatusBarNotification>();

    private String mSynKey;

    public InterceptedNotifications(Context context, PhoneStatusBar bar) {
        mContext = context;
        mBar = bar;
    }

    public void releaseIntercepted() {
        final int n = mIntercepted.size();
        for (int i = 0; i < n; i++) {
            final StatusBarNotification sbn = mIntercepted.valueAt(i);
            sbn.getNotification().extras.putBoolean(EXTRA_INTERCEPT, false);
            mBar.addNotificationInternal(sbn);
        }
        mIntercepted.clear();
        updateSyntheticNotification();
    }

    public boolean tryIntercept(StatusBarNotification notification) {
        if (!notification.getNotification().extras.getBoolean(EXTRA_INTERCEPT)) return false;
        if (shouldDisplayIntercepted()) return false;
        mIntercepted.put(notification.getKey(), notification);
        updateSyntheticNotification();
        return true;
    }

    public void remove(String key) {
        if (mIntercepted.remove(key) != null) {
            updateSyntheticNotification();
        }
    }

    public boolean isSyntheticEntry(Entry ent) {
        return ent.key.equals(SYNTHETIC_KEY);
    }

    public void update(StatusBarNotification notification) {
        if (mIntercepted.containsKey(notification.getKey())) {
            mIntercepted.put(notification.getKey(), notification);
        }
    }

    private boolean shouldDisplayIntercepted() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DISPLAY_INTERCEPTED_NOTIFICATIONS, 0) != 0;
    }

    private void updateSyntheticNotification() {
        if (mIntercepted.isEmpty()) {
            if (mSynKey != null) {
                mBar.removeNotificationInternal(mSynKey);
                mSynKey = null;
            }
            return;
        }
        final Notification n = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_qs_zen_on)
                .setContentTitle(mContext.getResources().getQuantityString(
                        R.plurals.zen_mode_notification_title,
                        mIntercepted.size(), mIntercepted.size()))
                .setContentText(mContext.getString(R.string.zen_mode_notification_text))
                .setOngoing(true)
                .build();
        final StatusBarNotification sbn = new StatusBarNotification(mContext.getPackageName(),
                mContext.getBasePackageName(),
                TAG.hashCode(), TAG, Process.myUid(), Process.myPid(), 0, n,
                mBar.getCurrentUserHandle());
        if (mSynKey == null) {
            mSynKey = sbn.getKey();
            mBar.addNotificationInternal(sbn);
        } else {
           mBar.updateNotificationInternal(sbn);
        }
        final NotificationData.Entry entry = mBar.mNotificationData.findByKey(mSynKey);
        entry.row.setOnClickListener(mSynClickListener);
    }

    private final View.OnClickListener mSynClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            releaseIntercepted();
        }
    };
}
