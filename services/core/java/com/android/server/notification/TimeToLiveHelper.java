/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Pair;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.PackageManagerService;

import java.io.PrintWriter;
import java.util.TreeSet;

/**
 * Handles canceling notifications when their time to live expires
 */
@FlaggedApi(Flags.FLAG_ALL_NOTIFS_NEED_TTL)
public class TimeToLiveHelper {
    private static final String TAG = TimeToLiveHelper.class.getSimpleName();
    private static final String ACTION = "com.android.server.notification.TimeToLiveHelper";

    private static final int REQUEST_CODE_TIMEOUT = 1;
    private static final String SCHEME_TIMEOUT = "timeout";
    static final String EXTRA_KEY = "key";
    private final Context mContext;
    private final NotificationManagerPrivate mNm;
    private final AlarmManager mAm;

    @VisibleForTesting
    @GuardedBy("mLock")
    final TreeSet<Pair<Long, String>> mKeys;
    final Object mLock = new Object();

    public TimeToLiveHelper(NotificationManagerPrivate nm, Context context) {
        mContext = context;
        mNm = nm;
        mAm = context.getSystemService(AlarmManager.class);
        synchronized (mLock) {
            mKeys = new TreeSet<>((left, right) -> Long.compare(left.first, right.first));
        }

        IntentFilter timeoutFilter = new IntentFilter(ACTION);
        timeoutFilter.addDataScheme(SCHEME_TIMEOUT);
        mContext.registerReceiver(mNotificationTimeoutReceiver, timeoutFilter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    void destroy() {
        mContext.unregisterReceiver(mNotificationTimeoutReceiver);
    }

    void dump(PrintWriter pw, String indent) {
        synchronized (mLock) {
            pw.println(indent + "mKeys " + mKeys);
        }
    }

    private @NonNull PendingIntent getAlarmPendingIntent(String nextKey, int flags) {
        flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_TIMEOUT,
                new Intent(ACTION)
                        .setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME)
                        .setData(new Uri.Builder()
                                .scheme(SCHEME_TIMEOUT)
                                .appendPath(nextKey)
                                .build())
                        .putExtra(EXTRA_KEY, nextKey)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                flags);
    }

    @VisibleForTesting
    void scheduleTimeoutLocked(NotificationRecord record, long currentTime) {
        synchronized (mLock) {
            removeMatchingEntry(record.getKey());

            final long timeoutAfter = currentTime + record.getNotification().getTimeoutAfter();
            if (record.getNotification().getTimeoutAfter() > 0) {
                final Long currentEarliestTime = mKeys.isEmpty() ? null : mKeys.first().first;

                // Maybe replace alarm with an earlier one
                if (currentEarliestTime == null || timeoutAfter < currentEarliestTime) {
                    if (currentEarliestTime != null) {
                        cancelFirstAlarm();
                    }
                    mKeys.add(Pair.create(timeoutAfter, record.getKey()));
                    maybeScheduleFirstAlarm();
                } else {
                    mKeys.add(Pair.create(timeoutAfter, record.getKey()));
                }
            }
        }
    }

    @VisibleForTesting
    void cancelScheduledTimeoutLocked(NotificationRecord record) {
        synchronized (mLock) {
            removeMatchingEntry(record.getKey());
        }
    }

    @GuardedBy("mLock")
    private void removeMatchingEntry(String key) {
        if (!mKeys.isEmpty() && key.equals(mKeys.first().second)) {
            // cancel the first alarm, remove the first entry, maybe schedule the alarm for the new
            // first entry
            cancelFirstAlarm();
            mKeys.remove(mKeys.first());
            maybeScheduleFirstAlarm();
        } else {
            // just remove the entry
            Pair<Long, String> trackedPair = null;
            for (Pair<Long, String> entry : mKeys) {
                if (key.equals(entry.second)) {
                    trackedPair = entry;
                    break;
                }
            }
            if (trackedPair != null) {
                mKeys.remove(trackedPair);
            }
        }
    }

    @GuardedBy("mLock")
    private void cancelFirstAlarm() {
        final PendingIntent pi = getAlarmPendingIntent(mKeys.first().second, FLAG_CANCEL_CURRENT);
        mAm.cancel(pi);
    }

    @GuardedBy("mLock")
    private void maybeScheduleFirstAlarm() {
        if (!mKeys.isEmpty()) {
            final PendingIntent piNewFirst = getAlarmPendingIntent(mKeys.first().second,
                    FLAG_UPDATE_CURRENT);
            mAm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mKeys.first().first, piNewFirst);
        }
    }

    @VisibleForTesting
    final BroadcastReceiver mNotificationTimeoutReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (ACTION.equals(action)) {
                String timeoutKey = null;
                synchronized (mLock) {
                    if (!mKeys.isEmpty()) {
                        Pair<Long, String> earliest = mKeys.first();
                        String key = intent.getStringExtra(EXTRA_KEY);
                        if (!earliest.second.equals(key)) {
                            Slog.wtf(TAG,
                                    "Alarm triggered but wasn't the earliest we were tracking");
                        }
                        removeMatchingEntry(key);
                        timeoutKey = earliest.second;
                    }
                }
                mNm.timeoutNotification(timeoutKey);
            }
        }
    };
}
