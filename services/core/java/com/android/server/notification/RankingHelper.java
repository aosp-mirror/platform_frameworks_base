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
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RankingHelper implements RankingConfig {
    private static final String TAG = "RankingHelper";

    private static final int XML_VERSION = 1;

    private static final String TAG_RANKING = "ranking";
    private static final String TAG_PACKAGE = "package";
    private static final String ATT_VERSION = "version";
    private static final String TAG_TOPIC = "topic";

    private static final String ATT_NAME = "name";
    private static final String ATT_UID = "uid";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_TOPIC_ID = "id";
    private static final String ATT_TOPIC_LABEL = "label";

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
                    int priority = safeInt(parser, ATT_PRIORITY, DEFAULT_PRIORITY);
                    int vis = safeInt(parser, ATT_VISIBILITY, DEFAULT_VISIBILITY);
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
                        r.priority = priority;
                        r.visibility = vis;

                        // Migrate package level settings to the default topic.
                        // Might be overwritten by parseTopics.
                        Topic defaultTopic = r.topics.get(Notification.TOPIC_DEFAULT);
                        defaultTopic.priority = priority;
                        defaultTopic.visibility = vis;

                        parseTopics(r, parser);
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    public void parseTopics(Record r, XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final int innerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (TAG_TOPIC.equals(tagName)) {
                int priority = safeInt(parser, ATT_PRIORITY, DEFAULT_PRIORITY);
                int vis = safeInt(parser, ATT_VISIBILITY, DEFAULT_VISIBILITY);
                int importance = safeInt(parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);
                String id = parser.getAttributeValue(null, ATT_TOPIC_ID);
                CharSequence label = parser.getAttributeValue(null, ATT_TOPIC_LABEL);

                if (!TextUtils.isEmpty(id)) {
                    Topic topic = new Topic(new Notification.Topic(id, label));

                    if (priority != DEFAULT_PRIORITY) {
                        topic.priority = priority;
                    }
                    if (vis != DEFAULT_VISIBILITY) {
                        topic.visibility = vis;
                    }
                    if (importance != DEFAULT_IMPORTANCE) {
                        topic.importance = importance;
                    }
                    r.topics.put(id, topic);
                }
            }
        }
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
            r.topics.put(Notification.TOPIC_DEFAULT, new Topic(createDefaultTopic()));
            mRecords.put(key, r);
        }
        return r;
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

            writeTopicsXml(out, r);
            out.endTag(null, TAG_PACKAGE);
        }
        out.endTag(null, TAG_RANKING);
    }

    public void writeTopicsXml(XmlSerializer out, Record r) throws IOException {
        for (Topic t : r.topics.values()) {
            out.startTag(null, TAG_TOPIC);
            out.attribute(null, ATT_TOPIC_ID, t.topic.getId());
            out.attribute(null, ATT_TOPIC_LABEL, t.topic.getLabel().toString());
            if (t.priority != DEFAULT_PRIORITY) {
                out.attribute(null, ATT_PRIORITY, Integer.toString(t.priority));
            }
            if (t.visibility != DEFAULT_VISIBILITY) {
                out.attribute(null, ATT_VISIBILITY, Integer.toString(t.visibility));
            }
            if (t.importance != DEFAULT_IMPORTANCE) {
                out.attribute(null, ATT_IMPORTANCE, Integer.toString(t.importance));
            }
            out.endTag(null, TAG_TOPIC);
        }
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
    public List<Notification.Topic> getTopics(String packageName, int uid) {
        final Record r = getOrCreateRecord(packageName, uid);
        List<Notification.Topic> topics = new ArrayList<>();
        for (Topic t : r.topics.values()) {
            topics.add(t.topic);
        }
        return topics;
    }

    @Override
    public boolean hasBannedTopics(String packageName, int uid) {
        final Record r = getOrCreateRecord(packageName, uid);
        for (Topic t : r.topics.values()) {
            if (t.importance == Ranking.IMPORTANCE_NONE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets priority. If a topic is given, returns the priority of that topic. Otherwise, the
     * priority of the app.
     */
    @Override
    public int getPriority(String packageName, int uid, Notification.Topic topic) {
        final Record r = getOrCreateRecord(packageName, uid);
        if (topic == null) {
            return r.priority;
        }
        return getOrCreateTopic(r, topic).priority;
    }

    /**
     * Sets priority. If a topic is given, sets the priority of that topic. If not,
     * sets the default priority for all new topics that appear in the future, and resets
     * the priority of all current topics.
     */
    @Override
    public void setPriority(String packageName, int uid, Notification.Topic topic,
            int priority) {
        final Record r = getOrCreateRecord(packageName, uid);
        if (topic == null) {
            r.priority = priority;
            for (Topic t : r.topics.values()) {
                t.priority = priority;
            }
        } else {
            getOrCreateTopic(r, topic).priority = priority;
        }
        updateConfig();
    }

    /**
     * Gets visual override. If a topic is given, returns the override of that topic. Otherwise, the
     * override of the app.
     */
    @Override
    public int getVisibilityOverride(String packageName, int uid, Notification.Topic topic) {
        final Record r = getOrCreateRecord(packageName, uid);
        if (topic == null) {
            return r.visibility;
        }
        return getOrCreateTopic(r, topic).visibility;
    }

    /**
     * Sets visibility override. If a topic is given, sets the override of that topic. If not,
     * sets the default override for all new topics that appear in the future, and resets
     * the override of all current topics.
     */
    @Override
    public void setVisibilityOverride(String pkgName, int uid, Notification.Topic topic,
        int visibility) {
        final Record r = getOrCreateRecord(pkgName, uid);
        if (topic == null) {
            r.visibility = visibility;
            for (Topic t : r.topics.values()) {
                t.visibility = visibility;
            }
        } else {
            getOrCreateTopic(r, topic).visibility = visibility;
        }
        updateConfig();
    }

    /**
     * Gets importance. If a topic is given, returns the importance of that topic. Otherwise, the
     * importance of the app.
     */
    @Override
    public int getImportance(String packageName, int uid, Notification.Topic topic) {
        final Record r = getOrCreateRecord(packageName, uid);
        if (topic == null) {
            return r.importance;
        }
        return getOrCreateTopic(r, topic).importance;
    }

    /**
     * Sets importance. If a topic is given, sets the importance of that topic. If not, sets the
     * default importance for all new topics that appear in the future, and resets
     * the importance of all current topics (unless the app is being blocked).
     */
    @Override
    public void setImportance(String pkgName, int uid, Notification.Topic topic,
            int importance) {
        final Record r = getOrCreateRecord(pkgName, uid);
        if (topic == null) {
            r.importance = importance;
            if (Ranking.IMPORTANCE_NONE != importance) {
                for (Topic t : r.topics.values()) {
                    t.importance = importance;
                }
            }
        } else {
            getOrCreateTopic(r, topic).importance = importance;
        }
        updateConfig();
    }

    @Override
    public boolean doesAppUseTopics(String pkgName, int uid) {
        final Record r = getOrCreateRecord(pkgName, uid);
        int numTopics = r.topics.size();
        if (numTopics == 0
                || (numTopics == 1 && r.topics.containsKey(Notification.TOPIC_DEFAULT))) {
            return false;
        } else {
            return true;
        }
    }

    private Topic getOrCreateTopic(Record r, Notification.Topic topic) {
        if (topic == null) {
            topic = createDefaultTopic();
        }
        Topic t = r.topics.get(topic.getId());
        if (t != null) {
            return t;
        } else {
            t = new Topic(topic);
            t.importance = r.importance;
            t.priority = r.priority;
            t.visibility = r.visibility;
            r.topics.put(topic.getId(), t);
            return t;
        }
    }

    private Notification.Topic createDefaultTopic() {
        return new Notification.Topic(Notification.TOPIC_DEFAULT,
                mContext.getString(R.string.default_notification_topic_label));
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
                    pw.print(Ranking.importanceToString(r.priority));
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    pw.print(" visibility=");
                    pw.print(Ranking.importanceToString(r.visibility));
                }
                pw.println();
                for (Topic t : r.topics.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.print(t.topic.getId());
                    if (t.priority != DEFAULT_PRIORITY) {
                        pw.print(" priority=");
                        pw.print(Notification.priorityToString(t.priority));
                    }
                    if (t.visibility != DEFAULT_VISIBILITY) {
                        pw.print(" visibility=");
                        pw.print(Notification.visibilityToString(t.visibility));
                    }
                    if (t.importance != DEFAULT_IMPORTANCE) {
                        pw.print(" importance=");
                        pw.print(Ranking.importanceToString(t.importance));
                    }
                    pw.println();
                }
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

    private static class Record {
        static int UNKNOWN_UID = UserHandle.USER_NULL;

        String pkg;
        int uid = UNKNOWN_UID;
        int importance = DEFAULT_IMPORTANCE;
        int priority = DEFAULT_PRIORITY;
        int visibility = DEFAULT_VISIBILITY;
        Map<String, Topic> topics = new ArrayMap<>();
   }

    private static class Topic {
        Notification.Topic topic;
        int priority = DEFAULT_PRIORITY;
        int visibility = DEFAULT_VISIBILITY;
        int importance = DEFAULT_IMPORTANCE;

        public Topic(Notification.Topic topic) {
            this.topic = topic;
        }
    }
}
