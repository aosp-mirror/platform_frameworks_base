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
package com.android.server.notification;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * NotificationManagerService helper for handling snoozed notifications.
 */
public class SnoozeHelper {
    private static final String TAG = "SnoozeHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String INDENT = "    ";

    private static final String REPOST_ACTION = SnoozeHelper.class.getSimpleName() + ".EVALUATE";
    private static final int REQUEST_CODE_REPOST = 1;
    private static final String REPOST_SCHEME = "repost";
    private static final String EXTRA_KEY = "key";
    private static final String EXTRA_USER_ID = "userId";

    private final Context mContext;
    private AlarmManager mAm;
    private final ManagedServices.UserProfiles mUserProfiles;

    // User id : package name : notification key : record.
    private ArrayMap<Integer, ArrayMap<String, ArrayMap<String, NotificationRecord>>>
            mSnoozedNotifications = new ArrayMap<>();
    // notification key : package.
    private ArrayMap<String, String> mPackages = new ArrayMap<>();
    // key : userId
    private ArrayMap<String, Integer> mUsers = new ArrayMap<>();
    private Callback mCallback;

    public SnoozeHelper(Context context, Callback callback,
            ManagedServices.UserProfiles userProfiles) {
        mContext = context;
        IntentFilter filter = new IntentFilter(REPOST_ACTION);
        filter.addDataScheme(REPOST_SCHEME);
        mContext.registerReceiver(mBroadcastReceiver, filter);
        mAm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mCallback = callback;
        mUserProfiles = userProfiles;
    }

    protected boolean isSnoozed(int userId, String pkg, String key) {
        return mSnoozedNotifications.containsKey(userId)
                && mSnoozedNotifications.get(userId).containsKey(pkg)
                && mSnoozedNotifications.get(userId).get(pkg).containsKey(key);
    }

    protected Collection<NotificationRecord> getSnoozed(int userId, String pkg) {
        if (mSnoozedNotifications.containsKey(userId)
                && mSnoozedNotifications.get(userId).containsKey(pkg)) {
            return mSnoozedNotifications.get(userId).get(pkg).values();
        }
        return Collections.EMPTY_LIST;
    }

    protected @NonNull List<NotificationRecord> getSnoozed() {
        List<NotificationRecord> snoozedForUser = new ArrayList<>();
        IntArray userIds = mUserProfiles.getCurrentProfileIds();
        if (userIds != null) {
            final int N = userIds.size();
            for (int i = 0; i < N; i++) {
                final ArrayMap<String, ArrayMap<String, NotificationRecord>> snoozedPkgs =
                        mSnoozedNotifications.get(userIds.get(i));
                if (snoozedPkgs != null) {
                    final int M = snoozedPkgs.size();
                    for (int j = 0; j < M; j++) {
                        final ArrayMap<String, NotificationRecord> records = snoozedPkgs.valueAt(j);
                        if (records != null) {
                            snoozedForUser.addAll(records.values());
                        }
                    }
                }
            }
        }
        return snoozedForUser;
    }

    /**
     * Snoozes a notification and schedules an alarm to repost at that time.
     */
    protected void snooze(NotificationRecord record, long duration) {
        snooze(record);
        scheduleRepost(record.sbn.getPackageName(), record.getKey(), record.getUserId(), duration);
    }

    /**
     * Records a snoozed notification.
     */
    protected void snooze(NotificationRecord record) {
        int userId = record.getUser().getIdentifier();
        if (DEBUG) {
            Slog.d(TAG, "Snoozing " + record.getKey());
        }
        ArrayMap<String, ArrayMap<String, NotificationRecord>> records =
                mSnoozedNotifications.get(userId);
        if (records == null) {
            records = new ArrayMap<>();
        }
        ArrayMap<String, NotificationRecord> pkgRecords = records.get(record.sbn.getPackageName());
        if (pkgRecords == null) {
            pkgRecords = new ArrayMap<>();
        }
        pkgRecords.put(record.getKey(), record);
        records.put(record.sbn.getPackageName(), pkgRecords);
        mSnoozedNotifications.put(userId, records);
        mPackages.put(record.getKey(), record.sbn.getPackageName());
        mUsers.put(record.getKey(), userId);
    }

    protected boolean cancel(int userId, String pkg, String tag, int id) {
        if (mSnoozedNotifications.containsKey(userId)) {
            ArrayMap<String, NotificationRecord> recordsForPkg =
                    mSnoozedNotifications.get(userId).get(pkg);
            if (recordsForPkg != null) {
                final Set<Map.Entry<String, NotificationRecord>> records = recordsForPkg.entrySet();
                for (Map.Entry<String, NotificationRecord> record : records) {
                    final StatusBarNotification sbn = record.getValue().sbn;
                    if (Objects.equals(sbn.getTag(), tag) && sbn.getId() == id) {
                        record.getValue().isCanceled = true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected boolean cancel(int userId, boolean includeCurrentProfiles) {
        int[] userIds = {userId};
        if (includeCurrentProfiles) {
            userIds = mUserProfiles.getCurrentProfileIds().toArray();
        }
        final int N = userIds.length;
        for (int i = 0; i < N; i++) {
            final ArrayMap<String, ArrayMap<String, NotificationRecord>> snoozedPkgs =
                    mSnoozedNotifications.get(userIds[i]);
            if (snoozedPkgs != null) {
                final int M = snoozedPkgs.size();
                for (int j = 0; j < M; j++) {
                    final ArrayMap<String, NotificationRecord> records = snoozedPkgs.valueAt(j);
                    if (records != null) {
                        int P = records.size();
                        for (int k = 0; k < P; k++) {
                            records.valueAt(k).isCanceled = true;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    protected boolean cancel(int userId, String pkg) {
        if (mSnoozedNotifications.containsKey(userId)) {
            if (mSnoozedNotifications.get(userId).containsKey(pkg)) {
                ArrayMap<String, NotificationRecord> records =
                        mSnoozedNotifications.get(userId).get(pkg);
                int N = records.size();
                for (int i = 0; i < N; i++) {
                    records.valueAt(i).isCanceled = true;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Updates the notification record so the most up to date information is shown on re-post.
     */
    protected void update(int userId, NotificationRecord record) {
        ArrayMap<String, ArrayMap<String, NotificationRecord>> records =
                mSnoozedNotifications.get(userId);
        if (records == null) {
            return;
        }
        ArrayMap<String, NotificationRecord> pkgRecords = records.get(record.sbn.getPackageName());
        if (pkgRecords == null) {
            return;
        }
        NotificationRecord existing = pkgRecords.get(record.getKey());
        if (existing != null && existing.isCanceled) {
            return;
        }
        pkgRecords.put(record.getKey(), record);
    }

    protected void repost(String key) {
        Integer userId = mUsers.get(key);
        if (userId != null) {
            repost(key, userId);
        }
    }

    protected void repost(String key, int userId) {
        final String pkg = mPackages.remove(key);
        ArrayMap<String, ArrayMap<String, NotificationRecord>> records =
                mSnoozedNotifications.get(userId);
        if (records == null) {
            return;
        }
        ArrayMap<String, NotificationRecord> pkgRecords = records.get(pkg);
        if (pkgRecords == null) {
            return;
        }
        final NotificationRecord record = pkgRecords.remove(key);
        mPackages.remove(key);
        mUsers.remove(key);

        if (record != null && !record.isCanceled) {
            MetricsLogger.action(record.getLogMaker()
                    .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                    .setType(MetricsProto.MetricsEvent.TYPE_OPEN));
            mCallback.repost(userId, record);
        }
    }

    protected void repostGroupSummary(String pkg, int userId, String groupKey) {
        if (mSnoozedNotifications.containsKey(userId)) {
            ArrayMap<String, ArrayMap<String, NotificationRecord>> keysByPackage
                    = mSnoozedNotifications.get(userId);

            if (keysByPackage != null && keysByPackage.containsKey(pkg)) {
                ArrayMap<String, NotificationRecord> recordsByKey = keysByPackage.get(pkg);

                if (recordsByKey != null) {
                    String groupSummaryKey = null;
                    int N = recordsByKey.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationRecord potentialGroupSummary = recordsByKey.valueAt(i);
                        if (potentialGroupSummary.sbn.isGroup()
                                && potentialGroupSummary.getNotification().isGroupSummary()
                                && groupKey.equals(potentialGroupSummary.getGroupKey())) {
                            groupSummaryKey = potentialGroupSummary.getKey();
                            break;
                        }
                    }

                    if (groupSummaryKey != null) {
                        NotificationRecord record = recordsByKey.remove(groupSummaryKey);
                        mPackages.remove(groupSummaryKey);
                        mUsers.remove(groupSummaryKey);

                        if (record != null && !record.isCanceled) {
                            MetricsLogger.action(record.getLogMaker()
                                    .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                                    .setType(MetricsProto.MetricsEvent.TYPE_OPEN));
                            mCallback.repost(userId, record);
                        }
                    }
                }
            }
        }
    }

    protected void clearData(int userId, String pkg) {
        ArrayMap<String, ArrayMap<String, NotificationRecord>> records =
                mSnoozedNotifications.get(userId);
        if (records == null) {
            return;
        }
        ArrayMap<String, NotificationRecord> pkgRecords = records.get(pkg);
        if (pkgRecords == null) {
            return;
        }
        for (int i = pkgRecords.size() - 1; i >= 0; i--) {
            final NotificationRecord r = pkgRecords.removeAt(i);
            if (r != null) {
                mPackages.remove(r.getKey());
                mUsers.remove(r.getKey());
                final PendingIntent pi = createPendingIntent(pkg, r.getKey(), userId);
                mAm.cancel(pi);
                MetricsLogger.action(r.getLogMaker()
                        .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                        .setType(MetricsProto.MetricsEvent.TYPE_DISMISS));
            }
        }
    }

    private PendingIntent createPendingIntent(String pkg, String key, int userId) {
        return PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_REPOST,
                new Intent(REPOST_ACTION)
                        .setData(new Uri.Builder().scheme(REPOST_SCHEME).appendPath(key).build())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_KEY, key)
                        .putExtra(EXTRA_USER_ID, userId),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void scheduleRepost(String pkg, String key, int userId, long duration) {
        long identity = Binder.clearCallingIdentity();
        try {
            final PendingIntent pi = createPendingIntent(pkg, key, userId);
            mAm.cancel(pi);
            long time = SystemClock.elapsedRealtime() + duration;
            if (DEBUG) Slog.d(TAG, "Scheduling evaluate for " + new Date(time));
            mAm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, pi);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void dump(PrintWriter pw, NotificationManagerService.DumpFilter filter) {
        pw.println("\n  Snoozed notifications:");
        for (int userId : mSnoozedNotifications.keySet()) {
            pw.print(INDENT);
            pw.println("user: " + userId);
            ArrayMap<String, ArrayMap<String, NotificationRecord>> snoozedPkgs =
                    mSnoozedNotifications.get(userId);
            for (String pkg : snoozedPkgs.keySet()) {
                pw.print(INDENT);
                pw.print(INDENT);
                pw.println("package: " + pkg);
                Set<String> snoozedKeys = snoozedPkgs.get(pkg).keySet();
                for (String key : snoozedKeys) {
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.println(key);
                }
            }
        }
    }

    protected void writeXml(XmlSerializer out, boolean forBackup) throws IOException {

    }

    public void readXml(XmlPullParser parser, boolean forRestore)
            throws XmlPullParserException, IOException {

    }

    @VisibleForTesting
    void setAlarmManager(AlarmManager am) {
        mAm = am;
    }

    protected interface Callback {
        void repost(int userId, NotificationRecord r);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Reposting notification");
            }
            if (REPOST_ACTION.equals(intent.getAction())) {
                repost(intent.getStringExtra(EXTRA_KEY), intent.getIntExtra(EXTRA_USER_ID,
                        UserHandle.USER_SYSTEM));
            }
        }
    };
}
