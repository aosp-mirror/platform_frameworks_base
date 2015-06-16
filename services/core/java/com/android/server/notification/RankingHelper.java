/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.notification;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class RankingHelper implements RankingConfig {
    private static final String TAG = "RankingHelper";

    private static final int XML_VERSION = 1;

    private static final String TAG_RANKING = "ranking";
    private static final String TAG_PACKAGE = "package";
    private static final String ATT_VERSION = "version";

    private static final String ATT_NAME = "name";
    private static final String ATT_UID = "uid";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_PEEKABLE = "peekable";
    private static final String ATT_VISIBILITY = "visibility";

    private static final int DEFAULT_PRIORITY = Notification.PRIORITY_DEFAULT;
    private static final boolean DEFAULT_PEEKABLE = true;
    private static final int DEFAULT_VISIBILITY =
            NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE;

    private final NotificationSignalExtractor[] mSignalExtractors;
    private final NotificationComparator mPreliminaryComparator = new NotificationComparator();
    private final GlobalSortKeyComparator mFinalComparator = new GlobalSortKeyComparator();

    private final ArrayMap<String, Record> mRecords = new ArrayMap<>(); // pkg|uid => Record
    private final ArrayMap<String, NotificationRecord> mProxyByGroupTmp = new ArrayMap<>();
    private final ArrayMap<String, Record> mRestoredWithoutUids = new ArrayMap<>(); // pkg => Record

    private final Context mContext;
    private final Handler mRankingHandler;

    public RankingHelper(Context context, Handler rankingHandler, NotificationUsageStats usageStats,
            String[] extractorNames) {
        mContext = context;
        mRankingHandler = rankingHandler;

        final int N = extractorNames.length;
        mSignalExtractors = new NotificationSignalExtractor[N];
        for (int i = 0; i < N; i++) {
            try {
                Class<?> extractorClass = mContext.getClassLoader().loadClass(extractorNames[i]);
                NotificationSignalExtractor extractor =
                        (NotificationSignalExtractor) extractorClass.newInstance();
                extractor.initialize(mContext, usageStats);
                extractor.setConfig(this);
                mSignalExtractors[i] = extractor;
            } catch (ClassNotFoundException e) {
                Slog.w(TAG, "Couldn't find extractor " + extractorNames[i] + ".", e);
            } catch (InstantiationException e) {
                Slog.w(TAG, "Couldn't instantiate extractor " + extractorNames[i] + ".", e);
            } catch (IllegalAccessException e) {
                Slog.w(TAG, "Problem accessing extractor " + extractorNames[i] + ".", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationSignalExtractor> T findExtractor(Class<T> extractorClass) {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            final NotificationSignalExtractor extractor = mSignalExtractors[i];
            if (extractorClass.equals(extractor.getClass())) {
                return (T) extractor;
            }
        }
        return null;
    }

    public void extractSignals(NotificationRecord r) {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            NotificationSignalExtractor extractor = mSignalExtractors[i];
            try {
                RankingReconsideration recon = extractor.process(r);
                if (recon != null) {
                    Message m = Message.obtain(mRankingHandler,
                            NotificationManagerService.MESSAGE_RECONSIDER_RANKING, recon);
                    long delay = recon.getDelay(TimeUnit.MILLISECONDS);
                    mRankingHandler.sendMessageDelayed(m, delay);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "NotificationSignalExtractor failed.", t);
            }
        }
    }

    public void readXml(XmlPullParser parser, boolean forRestore)
            throws XmlPullParserException, IOException {
        final PackageManager pm = mContext.getPackageManager();
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return;
        String tag = parser.getName();
        if (!TAG_RANKING.equals(tag)) return;
        mRecords.clear();
        mRestoredWithoutUids.clear();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && TAG_RANKING.equals(tag)) {
                return;
            }
            if (type == XmlPullParser.START_TAG) {
                if (TAG_PACKAGE.equals(tag)) {
                    int uid = safeInt(parser, ATT_UID, Record.UNKNOWN_UID);
                    int priority = safeInt(parser, ATT_PRIORITY, DEFAULT_PRIORITY);
                    boolean peekable = safeBool(parser, ATT_PEEKABLE, DEFAULT_PEEKABLE);
                    int vis = safeInt(parser, ATT_VISIBILITY, DEFAULT_VISIBILITY);
                    String name = parser.getAttributeValue(null, ATT_NAME);

                    if (!TextUtils.isEmpty(name)) {
                        if (forRestore) {
                            try {
                                uid = pm.getPackageUid(name, UserHandle.USER_OWNER);
                            } catch (NameNotFoundException e) {
                                // noop
                            }
                        }
                        Record r = null;
                        if (uid == Record.UNKNOWN_UID) {
                            r = mRestoredWithoutUids.get(name);
                            if (r == null) {
                                r = new Record();
                                mRestoredWithoutUids.put(name, r);
                            }
                        } else {
                            r = getOrCreateRecord(name, uid);
                        }
                        if (priority != DEFAULT_PRIORITY) {
                            r.priority = priority;
                        }
                        if (peekable != DEFAULT_PEEKABLE) {
                            r.peekable = peekable;
                        }
                        if (vis != DEFAULT_VISIBILITY) {
                            r.visibility = vis;
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    private static String recordKey(String pkg, int uid) {
        return pkg + "|" + uid;
    }

    private Record getOrCreateRecord(String pkg, int uid) {
        final String key = recordKey(pkg, uid);
        Record r = mRecords.get(key);
        if (r == null) {
            r = new Record();
            r.pkg = pkg;
            r.uid = uid;
            mRecords.put(key, r);
        }
        return r;
    }

    private void removeDefaultRecords() {
        final int N = mRecords.size();
        for (int i = N - 1; i >= 0; i--) {
            final Record r = mRecords.valueAt(i);
            if (r.priority == DEFAULT_PRIORITY && r.peekable == DEFAULT_PEEKABLE
                    && r.visibility == DEFAULT_VISIBILITY) {
                mRecords.remove(i);
            }
        }
    }

    public void writeXml(XmlSerializer out, boolean forBackup) throws IOException {
        out.startTag(null, TAG_RANKING);
        out.attribute(null, ATT_VERSION, Integer.toString(XML_VERSION));

        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            final Record r = mRecords.valueAt(i);
            if (forBackup && UserHandle.getUserId(r.uid) != UserHandle.USER_OWNER) {
                continue;
            }
            out.startTag(null, TAG_PACKAGE);
            out.attribute(null, ATT_NAME, r.pkg);
            if (r.priority != DEFAULT_PRIORITY) {
                out.attribute(null, ATT_PRIORITY, Integer.toString(r.priority));
            }
            if (r.peekable != DEFAULT_PEEKABLE) {
                out.attribute(null, ATT_PEEKABLE, Boolean.toString(r.peekable));
            }
            if (r.visibility != DEFAULT_VISIBILITY) {
                out.attribute(null, ATT_VISIBILITY, Integer.toString(r.visibility));
            }
            if (!forBackup) {
                out.attribute(null, ATT_UID, Integer.toString(r.uid));
            }
            out.endTag(null, TAG_PACKAGE);
        }
        out.endTag(null, TAG_RANKING);
    }

    private void updateConfig() {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            mSignalExtractors[i].setConfig(this);
        }
        mRankingHandler.sendEmptyMessage(NotificationManagerService.MESSAGE_RANKING_CONFIG_CHANGE);
    }

    public void sort(ArrayList<NotificationRecord> notificationList) {
        final int N = notificationList.size();
        // clear global sort keys
        for (int i = N - 1; i >= 0; i--) {
            notificationList.get(i).setGlobalSortKey(null);
        }

        // rank each record individually
        Collections.sort(notificationList, mPreliminaryComparator);

        synchronized (mProxyByGroupTmp) {
            // record individual ranking result and nominate proxies for each group
            for (int i = N - 1; i >= 0; i--) {
                final NotificationRecord record = notificationList.get(i);
                record.setAuthoritativeRank(i);
                final String groupKey = record.getGroupKey();
                boolean isGroupSummary = record.getNotification().isGroupSummary();
                if (isGroupSummary || !mProxyByGroupTmp.containsKey(groupKey)) {
                    mProxyByGroupTmp.put(groupKey, record);
                }
            }
            // assign global sort key:
            //   is_recently_intrusive:group_rank:is_group_summary:group_sort_key:rank
            for (int i = 0; i < N; i++) {
                final NotificationRecord record = notificationList.get(i);
                NotificationRecord groupProxy = mProxyByGroupTmp.get(record.getGroupKey());
                String groupSortKey = record.getNotification().getSortKey();

                // We need to make sure the developer provided group sort key (gsk) is handled
                // correctly:
                //   gsk="" < gsk=non-null-string < gsk=null
                //
                // We enforce this by using different prefixes for these three cases.
                String groupSortKeyPortion;
                if (groupSortKey == null) {
                    groupSortKeyPortion = "nsk";
                } else if (groupSortKey.equals("")) {
                    groupSortKeyPortion = "esk";
                } else {
                    groupSortKeyPortion = "gsk=" + groupSortKey;
                }

                boolean isGroupSummary = record.getNotification().isGroupSummary();
                record.setGlobalSortKey(
                        String.format("intrsv=%c:grnk=0x%04x:gsmry=%c:%s:rnk=0x%04x",
                        record.isRecentlyIntrusive() ? '0' : '1',
                        groupProxy.getAuthoritativeRank(),
                        isGroupSummary ? '0' : '1',
                        groupSortKeyPortion,
                        record.getAuthoritativeRank()));
            }
            mProxyByGroupTmp.clear();
        }

        // Do a second ranking pass, using group proxies
        Collections.sort(notificationList, mFinalComparator);
    }

    public int indexOf(ArrayList<NotificationRecord> notificationList, NotificationRecord target) {
        return Collections.binarySearch(notificationList, target, mFinalComparator);
    }

    private static int safeInt(XmlPullParser parser, String att, int defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseInt(val, defValue);
    }

    private static int tryParseInt(String value, int defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private static boolean safeBool(XmlPullParser parser, String att, boolean defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseBool(val, defValue);
    }

    private static boolean tryParseBool(String value, boolean defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        return Boolean.valueOf(value);
    }

    @Override
    public int getPackagePriority(String packageName, int uid) {
        final Record r = mRecords.get(recordKey(packageName, uid));
        return r != null ? r.priority : DEFAULT_PRIORITY;
    }

    @Override
    public void setPackagePriority(String packageName, int uid, int priority) {
        if (priority == getPackagePriority(packageName, uid)) {
            return;
        }
        getOrCreateRecord(packageName, uid).priority = priority;
        removeDefaultRecords();
        updateConfig();
    }

    @Override
    public boolean getPackagePeekable(String packageName, int uid) {
        final Record r = mRecords.get(recordKey(packageName, uid));
        return r != null ? r.peekable : DEFAULT_PEEKABLE;
    }

    @Override
    public void setPackagePeekable(String packageName, int uid, boolean peekable) {
        if (peekable == getPackagePeekable(packageName, uid)) {
            return;
        }
        getOrCreateRecord(packageName, uid).peekable = peekable;
        removeDefaultRecords();
        updateConfig();
    }

    @Override
    public int getPackageVisibilityOverride(String packageName, int uid) {
        final Record r = mRecords.get(recordKey(packageName, uid));
        return r != null ? r.visibility : DEFAULT_VISIBILITY;
    }

    @Override
    public void setPackageVisibilityOverride(String packageName, int uid, int visibility) {
        if (visibility == getPackageVisibilityOverride(packageName, uid)) {
            return;
        }
        getOrCreateRecord(packageName, uid).visibility = visibility;
        removeDefaultRecords();
        updateConfig();
    }

    public void dump(PrintWriter pw, String prefix, NotificationManagerService.DumpFilter filter) {
        if (filter == null) {
            final int N = mSignalExtractors.length;
            pw.print(prefix);
            pw.print("mSignalExtractors.length = ");
            pw.println(N);
            for (int i = 0; i < N; i++) {
                pw.print(prefix);
                pw.print("  ");
                pw.println(mSignalExtractors[i]);
            }
        }
        if (filter == null) {
            pw.print(prefix);
            pw.println("per-package config:");
        }
        dumpRecords(pw, prefix, filter, mRecords);
        dumpRecords(pw, prefix, filter, mRestoredWithoutUids);
    }

    private static void dumpRecords(PrintWriter pw, String prefix,
            NotificationManagerService.DumpFilter filter, ArrayMap<String, Record> records) {
        final int N = records.size();
        for (int i = 0; i < N; i++) {
            final Record r = records.valueAt(i);
            if (filter == null || filter.matches(r.pkg)) {
                pw.print(prefix);
                pw.print("  ");
                pw.print(r.pkg);
                pw.print(" (");
                pw.print(r.uid == Record.UNKNOWN_UID ? "UNKNOWN_UID" : Integer.toString(r.uid));
                pw.print(')');
                if (r.priority != DEFAULT_PRIORITY) {
                    pw.print(" priority=");
                    pw.print(Notification.priorityToString(r.priority));
                }
                if (r.peekable != DEFAULT_PEEKABLE) {
                    pw.print(" peekable=");
                    pw.print(r.peekable);
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    pw.print(" visibility=");
                    pw.print(Notification.visibilityToString(r.visibility));
                }
                pw.println();
            }
        }
    }

    public void onPackagesChanged(boolean queryReplace, String[] pkgList) {
        if (queryReplace || pkgList == null || pkgList.length == 0
                || mRestoredWithoutUids.isEmpty()) {
            return; // nothing to do
        }
        final PackageManager pm = mContext.getPackageManager();
        boolean updated = false;
        for (String pkg : pkgList) {
            final Record r = mRestoredWithoutUids.get(pkg);
            if (r != null) {
                try {
                    r.uid = pm.getPackageUid(r.pkg, UserHandle.USER_OWNER);
                    mRestoredWithoutUids.remove(pkg);
                    mRecords.put(recordKey(r.pkg, r.uid), r);
                    updated = true;
                } catch (NameNotFoundException e) {
                    // noop
                }
            }
        }
        if (updated) {
            updateConfig();
        }
    }

    private static class Record {
        static int UNKNOWN_UID = UserHandle.USER_NULL;

        String pkg;
        int uid = UNKNOWN_UID;
        int priority = DEFAULT_PRIORITY;
        boolean peekable = DEFAULT_PEEKABLE;
        int visibility = DEFAULT_VISIBILITY;
    }

}
