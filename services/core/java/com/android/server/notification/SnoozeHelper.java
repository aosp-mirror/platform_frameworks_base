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
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
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
import com.android.server.pm.PackageManagerService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * NotificationManagerService helper for handling snoozed notifications.
 */
public final class SnoozeHelper {
    public static final int XML_SNOOZED_NOTIFICATION_VERSION = 1;

    static final int CONCURRENT_SNOOZE_LIMIT = 500;

    protected static final String XML_TAG_NAME = "snoozed-notifications";

    private static final String XML_SNOOZED_NOTIFICATION = "notification";
    private static final String XML_SNOOZED_NOTIFICATION_CONTEXT = "context";
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

    // notification key : record.
    private ArrayMap<String, NotificationRecord> mSnoozedNotifications = new ArrayMap<>();
    // notification key : time-milliseconds .
    // This member stores persisted snoozed notification trigger times. it persists through reboots
    // It should have the notifications that haven't expired or re-posted yet
    private final ArrayMap<String, Long> mPersistedSnoozedNotifications = new ArrayMap<>();
    // notification key : creation ID.
    // This member stores persisted snoozed notification trigger context for the assistant
    // it persists through reboots.
    // It should have the notifications that haven't expired or re-posted yet
    private final ArrayMap<String, String>
            mPersistedSnoozedNotificationsWithContext = new ArrayMap<>();

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

    protected boolean canSnooze(int numberToSnooze) {
        synchronized (mLock) {
            if ((mSnoozedNotifications.size() + numberToSnooze) > CONCURRENT_SNOOZE_LIMIT) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    protected Long getSnoozeTimeForUnpostedNotification(int userId, String pkg, String key) {
        Long time = null;
        synchronized (mLock) {
            time = mPersistedSnoozedNotifications.get(key);
        }
        if (time == null) {
            time = 0L;
        }
        return time;
    }

    protected String getSnoozeContextForUnpostedNotification(int userId, String pkg, String key) {
        synchronized (mLock) {
            return mPersistedSnoozedNotificationsWithContext.get(key);
        }
    }

    protected boolean isSnoozed(int userId, String pkg, String key) {
        synchronized (mLock) {
            return mSnoozedNotifications.containsKey(key);
        }
    }

    protected Collection<NotificationRecord> getSnoozed(int userId, String pkg) {
        synchronized (mLock) {
            ArrayList snoozed = new ArrayList();
            for (NotificationRecord r : mSnoozedNotifications.values()) {
                if (r.getUserId() == userId && r.getSbn().getPackageName().equals(pkg)) {
                    snoozed.add(r);
                }
            }
            return snoozed;
        }
    }

    @NonNull
    ArrayList<NotificationRecord> getNotifications(String pkg,
            String groupKey, Integer userId) {
        ArrayList<NotificationRecord> records =  new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mSnoozedNotifications.size(); i++) {
                NotificationRecord r = mSnoozedNotifications.valueAt(i);
                if (r.getSbn().getPackageName().equals(pkg) && r.getUserId() == userId
                        && Objects.equals(r.getSbn().getGroup(), groupKey)) {
                    records.add(r);
                }
            }
        }
        return records;
    }

    protected NotificationRecord getNotification(String key) {
        synchronized (mLock) {
            return mSnoozedNotifications.get(key);
        }
    }

    protected @NonNull List<NotificationRecord> getSnoozed() {
        synchronized (mLock) {
            // caller filters records based on the current user profiles and listener access,
            // so just return everything
            List<NotificationRecord> snoozed = new ArrayList<>();
            snoozed.addAll(mSnoozedNotifications.values());
            return snoozed;
        }
    }

    /**
     * Snoozes a notification and schedules an alarm to repost at that time.
     */
    protected void snooze(NotificationRecord record, long duration) {
        String key = record.getKey();

        snooze(record);
        scheduleRepost(key, duration);
        Long activateAt = System.currentTimeMillis() + duration;
        synchronized (mLock) {
            mPersistedSnoozedNotifications.put(key, activateAt);
        }
    }

    /**
     * Records a snoozed notification.
     */
    protected void snooze(NotificationRecord record, String contextId) {
        if (contextId != null) {
            synchronized (mLock) {
                mPersistedSnoozedNotificationsWithContext.put(record.getKey(), contextId);
            }
        }
        snooze(record);
    }

    private void snooze(NotificationRecord record) {
        if (DEBUG) {
            Slog.d(TAG, "Snoozing " + record.getKey());
        }
        synchronized (mLock) {
            mSnoozedNotifications.put(record.getKey(), record);
        }
    }

    protected boolean cancel(int userId, String pkg, String tag, int id) {
        synchronized (mLock) {
            final Set<Map.Entry<String, NotificationRecord>> records =
                    mSnoozedNotifications.entrySet();
            for (Map.Entry<String, NotificationRecord> record : records) {
                final StatusBarNotification sbn = record.getValue().getSbn();
                if (sbn.getPackageName().equals(pkg) && sbn.getUserId() == userId
                        && Objects.equals(sbn.getTag(), tag) && sbn.getId() == id) {
                    record.getValue().isCanceled = true;
                    return true;
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
            for (NotificationRecord r : mSnoozedNotifications.values()) {
                if (userIds.binarySearch(r.getUserId()) >= 0) {
                    r.isCanceled = true;
                }
            }
        }
    }

    protected boolean cancel(int userId, String pkg) {
        synchronized (mLock) {
            int n = mSnoozedNotifications.size();
            for (int i = 0; i < n; i++) {
                final NotificationRecord r = mSnoozedNotifications.valueAt(i);
                if (r.getSbn().getPackageName().equals(pkg) && r.getUserId() == userId) {
                    r.isCanceled = true;
                }
            }
            return true;
        }
    }

    /**
     * Updates the notification record so the most up to date information is shown on re-post.
     */
    protected void update(int userId, NotificationRecord record) {
        synchronized (mLock) {
            if (mSnoozedNotifications.containsKey(record.getKey())) {
                mSnoozedNotifications.put(record.getKey(), record);
            }
        }
    }

    protected void repost(String key, boolean muteOnReturn) {
        synchronized (mLock) {
            final NotificationRecord r = mSnoozedNotifications.get(key);
            if (r != null) {
                repost(key, r.getUserId(), muteOnReturn);
            }
        }
    }

    protected void repost(String key, int userId, boolean muteOnReturn) {
        NotificationRecord record;
        synchronized (mLock) {
            mPersistedSnoozedNotifications.remove(key);
            mPersistedSnoozedNotificationsWithContext.remove(key);
            record = mSnoozedNotifications.remove(key);
        }

        if (record != null && !record.isCanceled) {
            final PendingIntent pi = createPendingIntent(record.getKey());
            mAm.cancel(pi);
            MetricsLogger.action(record.getLogMaker()
                    .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                    .setType(MetricsProto.MetricsEvent.TYPE_OPEN));
            mCallback.repost(record.getUserId(), record, muteOnReturn);
        }
    }

    protected void repostGroupSummary(String pkg, int userId, String groupKey) {
        synchronized (mLock) {
            String groupSummaryKey = null;
            int n = mSnoozedNotifications.size();
            for (int i = 0; i < n; i++) {
                final NotificationRecord potentialGroupSummary = mSnoozedNotifications.valueAt(i);
                if (potentialGroupSummary.getSbn().getPackageName().equals(pkg)
                        && potentialGroupSummary.getUserId() == userId
                        && potentialGroupSummary.getSbn().isGroup()
                        && potentialGroupSummary.getNotification().isGroupSummary()
                        && groupKey.equals(potentialGroupSummary.getGroupKey())) {
                    groupSummaryKey = potentialGroupSummary.getKey();
                    break;
                }
            }

            if (groupSummaryKey != null) {
                NotificationRecord record = mSnoozedNotifications.remove(groupSummaryKey);

                if (record != null && !record.isCanceled) {
                    Runnable runnable = () -> {
                        MetricsLogger.action(record.getLogMaker()
                                .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                                .setType(MetricsProto.MetricsEvent.TYPE_OPEN));
                        mCallback.repost(record.getUserId(), record, false);
                    };
                    runnable.run();
                }
            }
        }
    }

    protected void clearData(int userId, String pkg) {
        synchronized (mLock) {
            int n = mSnoozedNotifications.size();
            for (int i = n - 1; i >= 0; i--) {
                final NotificationRecord record = mSnoozedNotifications.valueAt(i);
                if (record.getUserId() == userId && record.getSbn().getPackageName().equals(pkg)) {
                    mSnoozedNotifications.removeAt(i);
                    mPersistedSnoozedNotificationsWithContext.remove(record.getKey());
                    mPersistedSnoozedNotifications.remove(record.getKey());
                    Runnable runnable = () -> {
                        final PendingIntent pi = createPendingIntent(record.getKey());
                        mAm.cancel(pi);
                        MetricsLogger.action(record.getLogMaker()
                                .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                                .setType(MetricsProto.MetricsEvent.TYPE_DISMISS));
                    };
                    runnable.run();
                }
            }
        }
    }

    protected void clearData(int userId) {
        synchronized (mLock) {
            int n = mSnoozedNotifications.size();
            for (int i = n - 1; i >= 0; i--) {
                final NotificationRecord record = mSnoozedNotifications.valueAt(i);
                if (record.getUserId() == userId) {
                    mSnoozedNotifications.removeAt(i);
                    mPersistedSnoozedNotificationsWithContext.remove(record.getKey());
                    mPersistedSnoozedNotifications.remove(record.getKey());

                    Runnable runnable = () -> {
                        final PendingIntent pi = createPendingIntent(record.getKey());
                        mAm.cancel(pi);
                        MetricsLogger.action(record.getLogMaker()
                                .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED)
                                .setType(MetricsProto.MetricsEvent.TYPE_DISMISS));
                    };
                    runnable.run();
                }
            }
        }
    }

    private PendingIntent createPendingIntent(String key) {
        return PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_REPOST,
                new Intent(REPOST_ACTION)
                        .setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME)
                        .setData(new Uri.Builder().scheme(REPOST_SCHEME).appendPath(key).build())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_KEY, key),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void scheduleRepostsForPersistedNotifications(long currentTime) {
        synchronized (mLock) {
            for (int i = 0; i < mPersistedSnoozedNotifications.size(); i++) {
                String key = mPersistedSnoozedNotifications.keyAt(i);
                Long time = mPersistedSnoozedNotifications.valueAt(i);
                if (time != null && time > currentTime) {
                    scheduleRepostAtTime(key, time);
                }
            }
        }
    }

    private void scheduleRepost(String key, long duration) {
        scheduleRepostAtTime(key, System.currentTimeMillis() + duration);
    }

    private void scheduleRepostAtTime(String key, long time) {
        Runnable runnable = () -> {
            final long identity = Binder.clearCallingIdentity();
            try {
                final PendingIntent pi = createPendingIntent(key);
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
            for (String key : mSnoozedNotifications.keySet()) {
                pw.print(INDENT);
                pw.println("key: " + key);
            }
            pw.println("\n Pending snoozed notifications");
            for (String key : mPersistedSnoozedNotifications.keySet()) {
                pw.print(INDENT);
                pw.println("key: " + key + " until: " + mPersistedSnoozedNotifications.get(key));
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

    private <T> void writeXml(TypedXmlSerializer out, ArrayMap<String, T> targets, String tag,
            Inserter<T> attributeInserter) throws IOException {
        for (int j = 0; j < targets.size(); j++) {
            String key = targets.keyAt(j);
            // T is a String (snoozed until context) or Long (snoozed until time)
            T value = targets.valueAt(j);

            out.startTag(null, tag);

            attributeInserter.insert(value);

            out.attributeInt(null, XML_SNOOZED_NOTIFICATION_VERSION_LABEL,
                    XML_SNOOZED_NOTIFICATION_VERSION);
            out.attribute(null, XML_SNOOZED_NOTIFICATION_KEY, key);

            out.endTag(null, tag);
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
                    if (tag.equals(XML_SNOOZED_NOTIFICATION)) {
                        final Long time = parser.getAttributeLong(
                                null, XML_SNOOZED_NOTIFICATION_TIME, 0);
                        if (time > currentTime) { //only read new stuff
                            synchronized (mLock) {
                                mPersistedSnoozedNotifications.put(key, time);
                            }
                        }
                    }
                    if (tag.equals(XML_SNOOZED_NOTIFICATION_CONTEXT)) {
                        final String creationId = parser.getAttributeValue(
                                null, XML_SNOOZED_NOTIFICATION_CONTEXT_ID);
                        synchronized (mLock) {
                            mPersistedSnoozedNotificationsWithContext.put(key, creationId);
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
