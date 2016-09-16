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
import android.app.NotificationChannel;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class RankingHelper implements RankingConfig {
    private static final String TAG = "RankingHelper";

    private static final int XML_VERSION = 1;

    private static final String TAG_RANKING = "ranking";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_CHANNEL = "channel";

    private static final String ATT_VERSION = "version";
    private static final String ATT_NAME = "name";
    private static final String ATT_UID = "uid";
    private static final String ATT_ID = "id";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";

    private static final int DEFAULT_PRIORITY = Notification.PRIORITY_DEFAULT;
    private static final int DEFAULT_VISIBILITY = Ranking.VISIBILITY_NO_OVERRIDE;
    private static final int DEFAULT_IMPORTANCE = Ranking.IMPORTANCE_UNSPECIFIED;

    private final NotificationSignalExtractor[] mSignalExtractors;
    private final NotificationComparator mPreliminaryComparator = new NotificationComparator();
    private final GlobalSortKeyComparator mFinalComparator = new GlobalSortKeyComparator();

    private final ArrayMap<String, Record> mRecords = new ArrayMap<>(); // pkg|uid => Record
    private final ArrayMap<String, NotificationRecord> mProxyByGroupTmp = new ArrayMap<>();
    private final ArrayMap<String, Record> mRestoredWithoutUids = new ArrayMap<>(); // pkg => Record

    private final Context mContext;
    private final RankingHandler mRankingHandler;

    public RankingHelper(Context context, RankingHandler rankingHandler,
            NotificationUsageStats usageStats, String[] extractorNames) {
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
                    mRankingHandler.requestReconsideration(recon);
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
                    String name = parser.getAttributeValue(null, ATT_NAME);

                    if (!TextUtils.isEmpty(name)) {
                        if (forRestore) {
                            try {
                                //TODO: http://b/22388012
                                uid = pm.getPackageUidAsUser(name, UserHandle.USER_SYSTEM);
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
                        r.importance = safeInt(parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);
                        r.priority = safeInt(parser, ATT_PRIORITY, DEFAULT_PRIORITY);
                        r.visibility = safeInt(parser, ATT_VISIBILITY, DEFAULT_VISIBILITY);

                        final int innerDepth = parser.getDepth();
                        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                                && (type != XmlPullParser.END_TAG
                                || parser.getDepth() > innerDepth)) {
                            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                                continue;
                            }

                            String tagName = parser.getName();
                            if (TAG_CHANNEL.equals(tagName)) {
                                String id = parser.getAttributeValue(null, ATT_ID);
                                CharSequence channelName = parser.getAttributeValue(null, ATT_NAME);

                                if (!TextUtils.isEmpty(id)) {
                                    final NotificationChannel channel =
                                            new NotificationChannel(id, channelName);
                                    channel.populateFromXml(parser);
                                    r.channels.put(id, channel);
                                }
                            }
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
            NotificationChannel defaultChannel = createDefaultChannel();
            r.channels.put(defaultChannel.getId(), defaultChannel);
            mRecords.put(key, r);
        }
        return r;
    }

    private NotificationChannel createDefaultChannel() {
        return new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID,
                mContext.getString(R.string.default_notification_channel_label));
    }

    public void writeXml(XmlSerializer out, boolean forBackup) throws IOException {
        out.startTag(null, TAG_RANKING);
        out.attribute(null, ATT_VERSION, Integer.toString(XML_VERSION));

        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            final Record r = mRecords.valueAt(i);
            //TODO: http://b/22388012
            if (forBackup && UserHandle.getUserId(r.uid) != UserHandle.USER_SYSTEM) {
                continue;
            }
            final boolean hasNonDefaultSettings = r.importance != DEFAULT_IMPORTANCE
                    || r.priority != DEFAULT_PRIORITY || r.visibility != DEFAULT_VISIBILITY
                    || r.channels.size() > 0;
            if (hasNonDefaultSettings) {
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATT_NAME, r.pkg);
                if (r.importance != DEFAULT_IMPORTANCE) {
                    out.attribute(null, ATT_IMPORTANCE, Integer.toString(r.importance));
                }
                if (r.priority != DEFAULT_PRIORITY) {
                    out.attribute(null, ATT_PRIORITY, Integer.toString(r.priority));
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    out.attribute(null, ATT_VISIBILITY, Integer.toString(r.visibility));
                }

                if (!forBackup) {
                    out.attribute(null, ATT_UID, Integer.toString(r.uid));
                }

                for (NotificationChannel channel : r.channels.values()) {
                    channel.writeXml(out);
                }

                out.endTag(null, TAG_PACKAGE);
            }
        }
        out.endTag(null, TAG_RANKING);
    }

    private void updateConfig() {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            mSignalExtractors[i].setConfig(this);
        }
        mRankingHandler.requestSort();
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
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    /**
     * Gets priority.
     */
    @Override
    public int getPriority(String packageName, int uid) {
        return getOrCreateRecord(packageName, uid).priority;
    }

    /**
     * Sets priority.
     */
    @Override
    public void setPriority(String packageName, int uid, int priority) {
        getOrCreateRecord(packageName, uid).priority = priority;
        updateConfig();
    }

    /**
     * Gets visual override.
     */
    @Override
    public int getVisibilityOverride(String packageName, int uid) {
        return getOrCreateRecord(packageName, uid).visibility;
    }

    /**
     * Sets visibility override.
     */
    @Override
    public void setVisibilityOverride(String pkgName, int uid, int visibility) {
        getOrCreateRecord(pkgName, uid).visibility = visibility;
        updateConfig();
    }

    /**
     * Gets importance.
     */
    @Override
    public int getImportance(String packageName, int uid) {
        return getOrCreateRecord(packageName, uid).importance;
    }

    @Override
    public void createNotificationChannel(String pkg, int uid, NotificationChannel channel) {
        Record r = getOrCreateRecord(pkg, uid);
        if (r.channels.containsKey(channel.getId()) || channel.getName().equals(
                mContext.getString(R.string.default_notification_channel_label))) {
            throw new IllegalArgumentException("Channel already exists");
        }
        if (channel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
            channel.setLockscreenVisibility(Ranking.VISIBILITY_NO_OVERRIDE);
        }
        r.channels.put(channel.getId(), channel);
        updateConfig();
    }

    @Override
    public void updateNotificationChannel(int callingUid, String pkg, int uid,
            NotificationChannel updatedChannel) {
        Record r = getOrCreateRecord(pkg, uid);
        NotificationChannel channel = r.channels.get(updatedChannel.getId());
        if (channel == null) {
            throw new IllegalArgumentException("Channel does not exist");
        }
        if (!isUidSystem(callingUid)) {
            updatedChannel.setImportance(channel.getImportance());
            updatedChannel.setName(channel.getName());
        }
        if (updatedChannel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
            updatedChannel.setLockscreenVisibility(Ranking.VISIBILITY_NO_OVERRIDE);
        }
        r.channels.put(updatedChannel.getId(), updatedChannel);
        updateConfig();
    }

    @Override
    public NotificationChannel getNotificationChannel(String pkg, int uid, String channelId) {
        Record r = getOrCreateRecord(pkg, uid);
        if (channelId == null) {
            channelId = NotificationChannel.DEFAULT_CHANNEL_ID;
        }
        return r.channels.get(channelId);
    }

    @Override
    public void deleteNotificationChannel(String pkg, int uid, String channelId) {
        Record r = getOrCreateRecord(pkg, uid);
        r.channels.remove(channelId);
    }

    @Override
    public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg, int uid) {
        List<NotificationChannel> channels = new ArrayList<>();
        Record r = getOrCreateRecord(pkg, uid);
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            channels.add(r.channels.valueAt(i));
        }
        return new ParceledListSlice<NotificationChannel>(channels);
    }

    /**
     * Sets importance.
     */
    @Override
    public void setImportance(String pkgName, int uid, int importance) {
        getOrCreateRecord(pkgName, uid).importance = importance;
        updateConfig();
    }

    public void setEnabled(String packageName, int uid, boolean enabled) {
        boolean wasEnabled = getImportance(packageName, uid) != Ranking.IMPORTANCE_NONE;
        if (wasEnabled == enabled) {
            return;
        }
        setImportance(packageName, uid, enabled ? DEFAULT_IMPORTANCE : Ranking.IMPORTANCE_NONE);
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
        pw.println("Records:");
        dumpRecords(pw, prefix, filter, mRecords);
        pw.println("Restored without uid:");
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
                if (r.importance != DEFAULT_IMPORTANCE) {
                    pw.print(" importance=");
                    pw.print(Ranking.importanceToString(r.importance));
                }
                if (r.priority != DEFAULT_PRIORITY) {
                    pw.print(" priority=");
                    pw.print(Notification.priorityToString(r.priority));
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    pw.print(" visibility=");
                    pw.print(Notification.visibilityToString(r.visibility));
                }
                pw.println();
                for (NotificationChannel channel : r.channels.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.println(channel);
                }
            }
        }
    }

    public JSONObject dumpJson(NotificationManagerService.DumpFilter filter) {
        JSONObject ranking = new JSONObject();
        JSONArray records = new JSONArray();
        try {
            ranking.put("noUid", mRestoredWithoutUids.size());
        } catch (JSONException e) {
           // pass
        }
        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            final Record r = mRecords.valueAt(i);
            if (filter == null || filter.matches(r.pkg)) {
                JSONObject record = new JSONObject();
                try {
                    record.put("userId", UserHandle.getUserId(r.uid));
                    record.put("packageName", r.pkg);
                    if (r.importance != DEFAULT_IMPORTANCE) {
                        record.put("importance", Ranking.importanceToString(r.importance));
                    }
                    if (r.priority != DEFAULT_PRIORITY) {
                        record.put("priority", Notification.priorityToString(r.priority));
                    }
                    if (r.visibility != DEFAULT_VISIBILITY) {
                        record.put("visibility", Notification.visibilityToString(r.visibility));
                    }
                    for (NotificationChannel channel : r.channels.values()) {
                        record.put("channel", channel.toJson());
                    }
                } catch (JSONException e) {
                   // pass
                }
                records.put(record);
            }
        }
        try {
            ranking.put("records", records);
        } catch (JSONException e) {
            // pass
        }
        return ranking;
    }

    /**
     * Dump only the ban information as structured JSON for the stats collector.
     *
     * This is intentionally redundant with {#link dumpJson} because the old
     * scraper will expect this format.
     *
     * @param filter
     * @return
     */
    public JSONArray dumpBansJson(NotificationManagerService.DumpFilter filter) {
        JSONArray bans = new JSONArray();
        Map<Integer, String> packageBans = getPackageBans();
        for(Entry<Integer, String> ban : packageBans.entrySet()) {
            final int userId = UserHandle.getUserId(ban.getKey());
            final String packageName = ban.getValue();
            if (filter == null || filter.matches(packageName)) {
                JSONObject banJson = new JSONObject();
                try {
                    banJson.put("userId", userId);
                    banJson.put("packageName", packageName);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                bans.put(banJson);
            }
        }
        return bans;
    }

    public Map<Integer, String> getPackageBans() {
        final int N = mRecords.size();
        ArrayMap<Integer, String> packageBans = new ArrayMap<>(N);
        for (int i = 0; i < N; i++) {
            final Record r = mRecords.valueAt(i);
            if (r.importance == Ranking.IMPORTANCE_NONE) {
                packageBans.put(r.uid, r.pkg);
            }
        }
        return packageBans;
    }

    public void onPackagesChanged(boolean removingPackage, String[] pkgList) {
        if (!removingPackage || pkgList == null || pkgList.length == 0
                || mRestoredWithoutUids.isEmpty()) {
            return; // nothing to do
        }
        final PackageManager pm = mContext.getPackageManager();
        boolean updated = false;
        for (String pkg : pkgList) {
            final Record r = mRestoredWithoutUids.get(pkg);
            if (r != null) {
                try {
                    //TODO: http://b/22388012
                    r.uid = pm.getPackageUidAsUser(r.pkg, UserHandle.USER_SYSTEM);
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

    private static boolean isUidSystem(int uid) {
        final int appid = UserHandle.getAppId(uid);
        return (appid == Process.SYSTEM_UID || appid == Process.PHONE_UID || uid == 0);
    }

    private static class Record {
        static int UNKNOWN_UID = UserHandle.USER_NULL;

        String pkg;
        int uid = UNKNOWN_UID;
        int importance = DEFAULT_IMPORTANCE;
        int priority = DEFAULT_PRIORITY;
        int visibility = DEFAULT_VISIBILITY;

        ArrayMap<String, NotificationChannel> channels = new ArrayMap<>();
   }
}
