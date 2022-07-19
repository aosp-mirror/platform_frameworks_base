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
import android.annotation.UserIdInt;
import android.app.AlarmManager;
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
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.PackageManagerService;

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
    public static final int XML_SNOOZED_NOTIFICATION_VERSION = 1;

    protected static final String XML_TAG_NAME = "snoozed-notifications";

    private static final String XML_SNOOZED_NOTIFICATION = "notification";
    private static final String XML_SNOOZED_NOTIFICATION_CONTEXT = "context";
    private static final String XML_SNOOZED_NOTIFICATION_PKG = "pkg";
    private static final String XML_SNOOZED_NOTIFICATION_USER_ID = "user-id";
    private static final String XML_SNOOZED_NOTIFICATION_KEY = "key";
    //the time the snoozed notification should be reposted
    private static final String XML_SNOOZED_NOTIFICATION_TIME = "time";
    private static final String XML_SNOOZED_NOTIFICATION_CONTEXT_ID = "id";
    private static final String XML_SNOOZED_NOTIFICATION_VERSION_LABEL = "version";


    private static final String TAG = "SnoozeHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String INDENT = "    ";

    private static final String REPOST_ACTION = SnoozeHelper.class.getSimpleName() + ".EVALUATE";
    private static final int REQUEST_CODE_REPOST = 1;
    private static final String REPOST_SCHEME = "repost";
    static final String EXTRA_KEY = "key";
    private static final String EXTRA_USER_ID = "userId";

    private final Context mContext;
    private AlarmManager mAm;
    private final ManagedServices.UserProfiles mUserProfiles;

    // User id | package name : notification key : record.
    private ArrayMap<String, ArrayMap<String, NotificationRecord>>
            mSnoozedNotifications = new ArrayMap<>();
    // User id | package name : notification key : time-milliseconds .
    // This member stores persisted snoozed notification trigger times. it persists through reboots
    // It should have the notifications that haven't expired or re-posted yet
    private final ArrayMap<String, ArrayMap<String, Long>>
            mPersistedSnoozedNotifications = new ArrayMap<>();
    // User id | package name : notification key : creation ID .
    // This member stores persisted snoozed notification trigger context for the assistant
    // it persists through reboots.
    // It should have the notifications that haven't expired or re-posted yet
    private final ArrayMap<String, ArrayMap<String, String>>
            mPersistedSnoozedNotificationsWithContext = new ArrayMap<>();
    // notification key : package.
    private ArrayMap<String, String> mPackages = new ArrayMap<>();
    // key : userId
    private ArrayMap<String, Integer> mUsers = new ArrayMap<>();
    private Callback mCallback;

    private final Object mLock = new Object();

    public SnoozeHelper(Context context, Callback callback,
            ManagedServices.UserProfiles userProfiles) {
        mContext = context;
        IntentFilter filter = new IntentFilter(REPOST_ACTION);
        filter.addDataScheme(REPOST_SCHEME);
        mContext.registerReceiver(mBroadcastReceiver, filter,
                Context.RECEIVER_EXPORTED_UNAUDITED);
        mAm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mCallback = callback;
        mUserProfiles = userProfiles;
    }

    private String getPkgKey(@UserIdInt int userId, String pkg) {
        return userId + "|" + pkg;
    }

    void cleanupPersistedContext(String key){
        synchronized (mLock) {
            int userId = mUsers.get(key);
            String pkg = mPackages.get(key);
            removeRecordLocked(pkg, key, userId, mPersistedSnoozedNotificationsWithContext);
        }
    }

    @NonNull
    protected Long getSnoozeTimeForUnpostedNotification(int userId, String pkg, String key) {
        Long time = null;
        synchronized (mLock) {
           ArrayMap<String, Long> snoozed =
                   mPersistedSnoozedNotifications.get(getPkgKey(userId, pkg));
           if (snoozed != null) {
               time = snoozed.get(key);
           }
        }
        if (time == null) {
            time = 0L;
        }
        return time;
    }

    protected String getSnoozeContextForUnpostedNotification(int userId, String pkg, String key) {
        synchronized (mLock) {
            ArrayMap<String, String> snoozed =
                    mPersistedSnoozedNotificationsWithContext.get(getPkgKey(userId, pkg));
            if (snoozed != null) {
                return snoozed.get(key);
            }
        }
        return null;
    }

    protected boolean isSnoozed(int userId, String pkg, String key) {
        synchronized (mLock) {
            return mSnoozedNotifications.containsKey(getPkgKey(userId, pkg))
                    && mSnoozedNotifications.get(getPkgKey(userId, pkg)).containsKey(key);
        }
    }

    protected Collection<NotificationRecord> getSnoozed(int userId, String pkg) {
        synchronized (mLock) {
            if (mSnoozedNotifications.containsKey(getPkgKey(userId, pkg))) {
                return mSnoozedNotifications.get(getPkgKey(userId, pkg)).values();
            }
        }
        return Collections.EMPTY_LIST;
    }

    @NonNull
    ArrayList<NotificationRecord> getNotifications(String pkg,
            String groupKey, Integer userId) {
        ArrayList<NotificationRecord> records =  new ArrayList<>();
        synchronized (mLock) {
            ArrayMap<String, NotificationRecord> allRecords =
                    mSnoozedNotifications.get(getPkgKey(userId, pkg));
            if (allRecords != null) {
                for (int i = 0; i < allRecords.size(); i++) {
                    NotificationRecord r = allRecords.valueAt(i);
                    String currentGroupKey = r.getSbn().getGroup();
                    if (Objects.equals(currentGroupKey, groupKey)) {
                        records.add(r);
                    }
                }
            }
        }
        return records;
    }

    protected NotificationRecord getNotification(String key) {
        synchronized (mLock) {
            if (!mUsers.containsKey(key) || !mPackages.containsKey(key)) {
                Slog.w(TAG, "Snoozed data sets no longer agree for " + key);
                return null;
            }
            int userId = mUsers.get(key);
            String pkg = mPackages.get(key);
            ArrayMap<String, NotificationRecord> snoozed =
                    mSnoozedNotifications.get(getPkgKey(userId, pkg));
            if (snoozed == null) {
                return null;
            }
            return snoozed.get(key);
        }
    }

    protected @NonNull List<NotificationRecord> getSnoozed() {
        synchronized (mLock) {
            // caller filters records based on the current user profiles and listener access, so just
            // return everything
            List<NotificationRecord> snoozed = new ArrayList<>();
            for (String userPkgKey : mSnoozedNotifications.keySet()) {
                ArrayMap<String, NotificationRecord> snoozedRecords =
                        mSnoozedNotifications.get(userPkgKey);
                snoozed.addAll(snoozedRecords.values());
            }
            return snoozed;
        }
    }

    /**
     * Snoozes a notification and schedules an alarm to repost at that time.
     */
    protected void snooze(NotificationRecord record, long duration) {
        String pkg = record.getSbn().getPackageName();
        String key = record.getKey();
        int userId = record.getUser().getIdentifier();

        snooze(record);
        scheduleRepost(pkg, key, userId, duration);
        Long activateAt = System.currentTimeMillis() + duration;
        synchronized (mLock) {
            storeRecordLocked(pkg, key, userId, mPersistedSnoozedNotifications, activateAt);
        }
    }

    /**
     * Records a snoozed notification.
     */
    protected void snooze(NotificationRecord record, String contextId) {
        int userId = record.getUser().getIdentifier();
        if (contextId != null) {
            synchronized (mLock) {
                storeRecordLocked(record.getSbn().getPackageName(), record.getKey(),
                        userId, mPersistedSnoozedNotificationsWithContext, contextId);
            }
        }
        snooze(record);
    }

    private void snooze(NotificationRecord record) {
        int userId = record.getUser().getIdentifier();
        if (DEBUG) {
            Slog.d(TAG, "Snoozing " + record.getKey());
        }
        synchronized (mLock) {
            storeRecordLocked(record.getSbn().getPackageName(), record.getKey(),
                    userId, mSnoozedNotifications, record);
        }
    }

    private <T> void storeRecordLocked(String pkg, String key, Integer userId,
            ArrayMap<String, ArrayMap<String, T>> targets, T object) {

        mPackages.put(key, pkg);
        mUsers.put(key, userId);
        ArrayMap<String, T> keyToValue = targets.get(getPkgKey(userId, pkg));
        if (keyToValue == null) {
            keyToValue = new ArrayMap<>();
        }
        keyToValue.put(key, object);
        targets.put(getPkgKey(userId, pkg), keyToValue);
    }

    private <T> T removeRecordLocked(String pkg, String key, Integer userId,
            ArrayMap<String, ArrayMap<String, T>> targets) {
        T object = null;
        ArrayMap<String, T> keyToValue = targets.get(getPkgKey(userId, pkg));
        if (keyToValue == null) {
            return null;
        }
        object = keyToValue.remove(key);
        if (keyToValue.size() == 0) {
            targets.remove(getPkgKey(userId, pkg));
        }
        return object;
    }

    protected boolean cancel(int userId, String pkg, String tag, int id) {
        synchronized (mLock) {
            ArrayMap<String, NotificationRecord> recordsForPkg =
                    mSnoozedNotifications.get(getPkgKey(userId, pkg));
            if (recordsForPkg != null) {
                final Set<Map.Entry<String, NotificationRecord>> records = recordsForPkg.entrySet();
                for (Map.Entry<String, NotificationRecord> record : records) {
                    final StatusBarNotification sbn = record.getValue().getSbn();
                    if (Objects.equals(sbn.getTag(), tag) && sbn.getId() == id) {
                        record.getValue().isCanceled = true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected void cancel(int userId, boolean includeCurrentProfiles) {
        synchronized (mLock) {
            if (mSnoozedNotifications.size() == 0) {
                return;
            }
            IntArray userIds = new IntArray();
            userIds.add(userId);
            if (includeCurrentProfiles) {
                userIds = mUserProfiles.getCurrentProfileIds();
            }
            for (ArrayMap<String, NotificationRecord> snoozedRecords : mSnoozedNotifications.values()) {
                for (NotificationRecord r : snoozedRecords.values()) {
                    if (userIds.binarySearch(r.getUserId()) >= 0) {
                        r.isCanceled = true;
                    }
                }
            }
        }
    }

    protected boolean cancel(int userId, String pkg) {
        synchronized (mLock) {
            ArrayMap<String, NotificationRecord> records =
                    mSnoozedNotifications.get(getPkgKey(userId, pkg));
            if (records == null) {
                return false;
            }
            int N = records.size();
            for (int i = 0; i < N; i++) {
                records.valueAt(i).isCanceled = true;
            }
            return true;
        }
    }

    /**
     * Updates the notification record so the most up to date information is shown on re-post.
     */
    protected void update(int userId, NotificationRecord record) {
        synchronized (mLock) {
            ArrayMap<String, NotificationRecord> records =
                    mSnoozedNotifications.get(getPkgKey(userId, record.getSbn().getPackageName()));
            if (records == null) {
                return;
            }
            records.put(record.getKey(), record);
        }
    }

    protected void repost(String key, boolean muteOnReturn) {
        synchronized (mLock) {
            Integer userId = mUsers.get(key);
            if (userId != null) {
                repost(key, userId, muteOnReturn);
            }
        }
    }

    protected void repost(String key, int userId, boolean muteOnReturn) {
        NotificationRecord record;
        synchronized (mLock) {
            final String pkg = mPackages.remove(key);
            mUsers.remove(key);
            removeRecordLocked(pkg, key, userId, mPersistedSnoozedNotifications);
            removeRecordLocked(pkg, key, userId, mPersistedSnoozedNotificationsWithContext);
            ArrayMap<String, NotificationRecord> records =
                    mSnoozedNotifications.get(getPkgKey(userId, pkg));
            if (records == null) {
                return;
            }
            record = records.remove(key);

        }

        if (record != null && !record.isCanceled) {
            final PendingIntent pi = createPendingIntent(
                    record.getSbn().getPackageName(), record.getKey(), userId);
            mAm.cancel(pi);
            MetricsLogger.action(record.getLogMaker()
                    .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                    .setType(MetricsProto.MetricsEvent.TYPE_OPEN));
            mCallback.repost(userId, record, muteOnReturn);
        }
    }

    protected void repostGroupSummary(String pkg, int userId, String groupKey) {
        synchronized (mLock) {
            ArrayMap<String, NotificationRecord> recordsByKey
                    = mSnoozedNotifications.get(getPkgKey(userId, pkg));
            if (recordsByKey == null) {
                return;
            }

            String groupSummaryKey = null;
            int N = recordsByKey.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord potentialGroupSummary = recordsByKey.valueAt(i);
                if (potentialGroupSummary.getSbn().isGroup()
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
                    Runnable runnable = () -> {
                        MetricsLogger.action(record.getLogMaker()
                                .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                                .setType(MetricsProto.MetricsEvent.TYPE_OPEN));
                        mCallback.repost(userId, record, false);
                    };
                    runnable.run();
                }
            }
        }
    }

    protected void clearData(int userId, String pkg) {
        synchronized (mLock) {
            ArrayMap<String, NotificationRecord> records =
                    mSnoozedNotifications.get(getPkgKey(userId, pkg));
            if (records == null) {
                return;
            }
            for (int i = records.size() - 1; i >= 0; i--) {
                final NotificationRecord r = records.removeAt(i);
                if (r != null) {
                    mPackages.remove(r.getKey());
                    mUsers.remove(r.getKey());
                    Runnable runnable = () -> {
                        final PendingIntent pi = createPendingIntent(pkg, r.getKey(), userId);
                        mAm.cancel(pi);
                        MetricsLogger.action(r.getLogMaker()
                                .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                                .setType(MetricsProto.MetricsEvent.TYPE_DISMISS));
                    };
                    runnable.run();
                }
            }
        }
    }

    private PendingIntent createPendingIntent(String pkg, String key, int userId) {
        return PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_REPOST,
                new Intent(REPOST_ACTION)
                        .setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME)
                        .setData(new Uri.Builder().scheme(REPOST_SCHEME).appendPath(key).build())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_KEY, key)
                        .putExtra(EXTRA_USER_ID, userId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void scheduleRepostsForPersistedNotifications(long currentTime) {
        synchronized (mLock) {
            for (ArrayMap<String, Long> snoozed : mPersistedSnoozedNotifications.values()) {
                for (int i = 0; i < snoozed.size(); i++) {
                    String key = snoozed.keyAt(i);
                    Long time = snoozed.valueAt(i);
                    String pkg = mPackages.get(key);
                    Integer userId = mUsers.get(key);
                    if (time == null || pkg == null || userId == null) {
                        Slog.w(TAG, "data out of sync: " + time + "|" + pkg + "|" + userId);
                        continue;
                    }
                    if (time != null && time > currentTime) {
                        scheduleRepostAtTime(pkg, key, userId, time);
                    }
                }
            }
        }
    }

    private void scheduleRepost(String pkg, String key, int userId, long duration) {
        scheduleRepostAtTime(pkg, key, userId, System.currentTimeMillis() + duration);
    }

    private void scheduleRepostAtTime(String pkg, String key, int userId, long time) {
        Runnable runnable = () -> {
            final long identity = Binder.clearCallingIdentity();
            try {
                final PendingIntent pi = createPendingIntent(pkg, key, userId);
                mAm.cancel(pi);
                if (DEBUG) Slog.d(TAG, "Scheduling evaluate for " + new Date(time));
                mAm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        };
        runnable.run();
    }

    public void dump(PrintWriter pw, NotificationManagerService.DumpFilter filter) {
        synchronized (mLock) {
            pw.println("\n  Snoozed notifications:");
            for (String userPkgKey : mSnoozedNotifications.keySet()) {
                pw.print(INDENT);
                pw.println("key: " + userPkgKey);
                ArrayMap<String, NotificationRecord> snoozedRecords =
                        mSnoozedNotifications.get(userPkgKey);
                Set<String> snoozedKeys = snoozedRecords.keySet();
                for (String key : snoozedKeys) {
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.println(key);
                }
            }
            pw.println("\n Pending snoozed notifications");
            for (String userPkgKey : mPersistedSnoozedNotifications.keySet()) {
                pw.print(INDENT);
                pw.println("key: " + userPkgKey);
                ArrayMap<String, Long> snoozedRecords =
                        mPersistedSnoozedNotifications.get(userPkgKey);
                if (snoozedRecords == null) {
                    continue;
                }
                Set<String> snoozedKeys = snoozedRecords.keySet();
                for (String key : snoozedKeys) {
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.print(key);
                    pw.print(INDENT);
                    pw.println(snoozedRecords.get(key));
                }
            }
        }
    }

    protected void writeXml(TypedXmlSerializer out) throws IOException {
        synchronized (mLock) {
            final long currentTime = System.currentTimeMillis();
            out.startTag(null, XML_TAG_NAME);
            writeXml(out, mPersistedSnoozedNotifications, XML_SNOOZED_NOTIFICATION,
                    value -> {
                        if (value < currentTime) {
                            return;
                        }
                        out.attributeLong(null, XML_SNOOZED_NOTIFICATION_TIME,
                                value);
                    });
            writeXml(out, mPersistedSnoozedNotificationsWithContext,
                    XML_SNOOZED_NOTIFICATION_CONTEXT,
                    value -> {
                        out.attribute(null, XML_SNOOZED_NOTIFICATION_CONTEXT_ID,
                                value);
                    });
            out.endTag(null, XML_TAG_NAME);
        }
    }

    private interface Inserter<T> {
        void insert(T t) throws IOException;
    }

    private <T> void writeXml(TypedXmlSerializer out,
            ArrayMap<String, ArrayMap<String, T>> targets, String tag,
            Inserter<T> attributeInserter)
            throws IOException {
        final int M = targets.size();
        for (int i = 0; i < M; i++) {
            // T is a String (snoozed until context) or Long (snoozed until time)
            ArrayMap<String, T> keyToValue = targets.valueAt(i);
            for (int j = 0; j < keyToValue.size(); j++) {
                String key = keyToValue.keyAt(j);
                T value = keyToValue.valueAt(j);
                String pkg = mPackages.get(key);
                Integer userId = mUsers.get(key);

                if (pkg == null || userId == null) {
                    Slog.w(TAG, "pkg " + pkg + " or user " + userId + " missing for " + key);
                    continue;
                }

                out.startTag(null, tag);

                attributeInserter.insert(value);

                out.attributeInt(null, XML_SNOOZED_NOTIFICATION_VERSION_LABEL,
                        XML_SNOOZED_NOTIFICATION_VERSION);
                out.attribute(null, XML_SNOOZED_NOTIFICATION_KEY, key);
                out.attribute(null, XML_SNOOZED_NOTIFICATION_PKG, pkg);
                out.attributeInt(null, XML_SNOOZED_NOTIFICATION_USER_ID, userId);

                out.endTag(null, tag);
            }
        }
    }

    protected void readXml(TypedXmlPullParser parser, long currentTime)
            throws XmlPullParserException, IOException {
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (type == XmlPullParser.END_TAG
                    && XML_TAG_NAME.equals(tag)) {
                break;
            }
            if (type == XmlPullParser.START_TAG
                    && (XML_SNOOZED_NOTIFICATION.equals(tag)
                        || tag.equals(XML_SNOOZED_NOTIFICATION_CONTEXT))
                    && parser.getAttributeInt(null, XML_SNOOZED_NOTIFICATION_VERSION_LABEL, -1)
                        == XML_SNOOZED_NOTIFICATION_VERSION) {
                try {
                    final String key = parser.getAttributeValue(null, XML_SNOOZED_NOTIFICATION_KEY);
                    final String pkg = parser.getAttributeValue(null, XML_SNOOZED_NOTIFICATION_PKG);
                    final int userId = parser.getAttributeInt(
                            null, XML_SNOOZED_NOTIFICATION_USER_ID, UserHandle.USER_ALL);
                    if (tag.equals(XML_SNOOZED_NOTIFICATION)) {
                        final Long time = parser.getAttributeLong(
                                null, XML_SNOOZED_NOTIFICATION_TIME, 0);
                        if (time > currentTime) { //only read new stuff
                            synchronized (mLock) {
                                storeRecordLocked(
                                        pkg, key, userId, mPersistedSnoozedNotifications, time);
                            }
                        }
                    }
                    if (tag.equals(XML_SNOOZED_NOTIFICATION_CONTEXT)) {
                        final String creationId = parser.getAttributeValue(
                                null, XML_SNOOZED_NOTIFICATION_CONTEXT_ID);
                        synchronized (mLock) {
                            storeRecordLocked(
                                    pkg, key, userId, mPersistedSnoozedNotificationsWithContext,
                                    creationId);
                        }
                    }
                } catch (Exception e) {
                    Slog.e(TAG,  "Exception in reading snooze data from policy xml", e);
                }
            }
        }
    }

    @VisibleForTesting
    void setAlarmManager(AlarmManager am) {
        mAm = am;
    }

    protected interface Callback {
        void repost(int userId, NotificationRecord r, boolean muteOnReturn);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Reposting notification");
            }
            if (REPOST_ACTION.equals(intent.getAction())) {
                repost(intent.getStringExtra(EXTRA_KEY), intent.getIntExtra(EXTRA_USER_ID,
                        UserHandle.USER_SYSTEM), false);
            }
        }
    };
}
