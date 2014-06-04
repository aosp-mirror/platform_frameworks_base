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
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

public class InterceptedNotifications {
    private static final String TAG = "InterceptedNotifications";
    private static final String SYNTHETIC_KEY = "InterceptedNotifications.SYNTHETIC_KEY";

    private final Context mContext;
    private final PhoneStatusBar mBar;
    private final ArrayMap<String, StatusBarNotification> mIntercepted
            = new ArrayMap<String, StatusBarNotification>();
    private final ArraySet<String> mReleased = new ArraySet<String>();

    private String mSynKey;

    public InterceptedNotifications(Context context, PhoneStatusBar bar) {
        mContext = context;
        mBar = bar;
    }

    public void releaseIntercepted() {
        final int n = mIntercepted.size();
        for (int i = 0; i < n; i++) {
            final StatusBarNotification sbn = mIntercepted.valueAt(i);
            mReleased.add(sbn.getKey());
            mBar.displayNotification(sbn, null);
        }
        mIntercepted.clear();
        updateSyntheticNotification();
    }

    public boolean tryIntercept(StatusBarNotification notification, RankingMap rankingMap) {
        if (rankingMap == null) return false;
        if (shouldDisplayIntercepted()) return false;
        if (mReleased.contains(notification.getKey())) return false;
        Ranking ranking = rankingMap.getRanking(notification.getKey());
        if (!ranking.isInterceptedByDoNotDisturb()) return false;
        mIntercepted.put(notification.getKey(), notification);
        updateSyntheticNotification();
        return true;
    }

    public void retryIntercepts(RankingMap ranking) {
        if (ranking == null) return;

        final int N = mIntercepted.size();
        final ArraySet<String> removed = new ArraySet<String>(N);
        for (int i = 0; i < N; i++) {
            final StatusBarNotification sbn = mIntercepted.valueAt(i);
            if (!tryIntercept(sbn, ranking)) {
                removed.add(sbn.getKey());
                mBar.displayNotification(sbn, ranking);
            }
        }
        if (!removed.isEmpty()) {
            mIntercepted.removeAll(removed);
            updateSyntheticNotification();
        }
    }

    public void remove(String key) {
        if (mIntercepted.remove(key) != null) {
            updateSyntheticNotification();
        }
        mReleased.remove(key);
    }

    public boolean isSyntheticEntry(Entry ent) {
        return ent.key.equals(mSynKey);
    }

    private boolean shouldDisplayIntercepted() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DISPLAY_INTERCEPTED_NOTIFICATIONS, 0) != 0;
    }

    private void updateSyntheticNotification() {
        if (mIntercepted.isEmpty()) {
            if (mSynKey != null) {
                mBar.removeNotificationInternal(mSynKey, null);
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
            mBar.displayNotification(sbn, null);
        } else {
           mBar.updateNotificationInternal(sbn, null);
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
