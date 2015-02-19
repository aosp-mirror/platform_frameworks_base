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
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RankingHelper implements RankingConfig {
    private static final String TAG = "RankingHelper";
    private static final boolean DEBUG = false;

    private static final int XML_VERSION = 1;

    private static final String TAG_RANKING = "ranking";
    private static final String TAG_PACKAGE = "package";
    private static final String ATT_VERSION = "version";

    private static final String ATT_NAME = "name";
    private static final String ATT_UID = "uid";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";

    private final NotificationSignalExtractor[] mSignalExtractors;
    private final NotificationComparator mPreliminaryComparator = new NotificationComparator();
    private final GlobalSortKeyComparator mFinalComparator = new GlobalSortKeyComparator();

    // Package name to uid, to priority. Would be better as Table<String, Int, Int>
    private final ArrayMap<String, SparseIntArray> mPackagePriorities;
    private final ArrayMap<String, SparseIntArray> mPackageVisibilities;
    private final ArrayMap<String, NotificationRecord> mProxyByGroupTmp;

    private final Context mContext;
    private final Handler mRankingHandler;

    public RankingHelper(Context context, Handler rankingHandler, String[] extractorNames) {
        mContext = context;
        mRankingHandler = rankingHandler;
        mPackagePriorities = new ArrayMap<String, SparseIntArray>();
        mPackageVisibilities = new ArrayMap<String, SparseIntArray>();

        final int N = extractorNames.length;
        mSignalExtractors = new NotificationSignalExtractor[N];
        for (int i = 0; i < N; i++) {
            try {
                Class<?> extractorClass = mContext.getClassLoader().loadClass(extractorNames[i]);
                NotificationSignalExtractor extractor =
                        (NotificationSignalExtractor) extractorClass.newInstance();
                extractor.initialize(mContext);
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
        mProxyByGroupTmp = new ArrayMap<String, NotificationRecord>();
    }

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

    public void readXml(XmlPullParser parser) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return;
        String tag = parser.getName();
        if (!TAG_RANKING.equals(tag)) return;
        mPackagePriorities.clear();
        final int version = safeInt(parser, ATT_VERSION, XML_VERSION);
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && TAG_RANKING.equals(tag)) {
                return;
            }
            if (type == XmlPullParser.START_TAG) {
                if (TAG_PACKAGE.equals(tag)) {
                    int uid = safeInt(parser, ATT_UID, UserHandle.USER_ALL);
                    int priority = safeInt(parser, ATT_PRIORITY, Notification.PRIORITY_DEFAULT);
                    int vis = safeInt(parser, ATT_VISIBILITY,
                            NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE);
                    String name = parser.getAttributeValue(null, ATT_NAME);

                    if (!TextUtils.isEmpty(name)) {
                        if (priority != Notification.PRIORITY_DEFAULT) {
                            SparseIntArray priorityByUid = mPackagePriorities.get(name);
                            if (priorityByUid == null) {
                                priorityByUid = new SparseIntArray();
                                mPackagePriorities.put(name, priorityByUid);
                            }
                            priorityByUid.put(uid, priority);
                        }
                        if (vis != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) {
                            SparseIntArray visibilityByUid = mPackageVisibilities.get(name);
                            if (visibilityByUid == null) {
                                visibilityByUid = new SparseIntArray();
                                mPackageVisibilities.put(name, visibilityByUid);
                            }
                            visibilityByUid.put(uid, vis);
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, TAG_RANKING);
        out.attribute(null, ATT_VERSION, Integer.toString(XML_VERSION));

        final Set<String> packageNames = new ArraySet<>(mPackagePriorities.size()
                + mPackageVisibilities.size());
        packageNames.addAll(mPackagePriorities.keySet());
        packageNames.addAll(mPackageVisibilities.keySet());
        final Set<Integer> packageUids = new ArraySet<>();
        for (String packageName : packageNames) {
            packageUids.clear();
            SparseIntArray priorityByUid = mPackagePriorities.get(packageName);
            SparseIntArray visibilityByUid = mPackageVisibilities.get(packageName);
            if (priorityByUid != null) {
                final int M = priorityByUid.size();
                for (int j = 0; j < M; j++) {
                    packageUids.add(priorityByUid.keyAt(j));
                }
            }
            if (visibilityByUid != null) {
                final int M = visibilityByUid.size();
                for (int j = 0; j < M; j++) {
                    packageUids.add(visibilityByUid.keyAt(j));
                }
            }
            for (Integer uid : packageUids) {
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATT_NAME, packageName);
                if (priorityByUid != null) {
                    final int priority = priorityByUid.get(uid);
                    if (priority != Notification.PRIORITY_DEFAULT) {
                        out.attribute(null, ATT_PRIORITY, Integer.toString(priority));
                    }
                }
                if (visibilityByUid != null) {
                    final int visibility = visibilityByUid.get(uid);
                    if (visibility != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) {
                        out.attribute(null, ATT_VISIBILITY, Integer.toString(visibility));
                    }
                }
                out.attribute(null, ATT_UID, Integer.toString(uid));
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

    @Override
    public int getPackagePriority(String packageName, int uid) {
        int priority = Notification.PRIORITY_DEFAULT;
        SparseIntArray priorityByUid = mPackagePriorities.get(packageName);
        if (priorityByUid != null) {
            priority = priorityByUid.get(uid, Notification.PRIORITY_DEFAULT);
        }
        return priority;
    }

    @Override
    public void setPackagePriority(String packageName, int uid, int priority) {
        if (priority == getPackagePriority(packageName, uid)) {
            return;
        }
        SparseIntArray priorityByUid = mPackagePriorities.get(packageName);
        if (priorityByUid == null) {
            priorityByUid = new SparseIntArray();
            mPackagePriorities.put(packageName, priorityByUid);
        }
        priorityByUid.put(uid, priority);
        updateConfig();
    }

    @Override
    public int getPackageVisibilityOverride(String packageName, int uid) {
        int visibility = NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE;
        SparseIntArray visibilityByUid = mPackageVisibilities.get(packageName);
        if (visibilityByUid != null) {
            visibility = visibilityByUid.get(uid,
                    NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE);
        }
        return visibility;
    }

    @Override
    public void setPackageVisibilityOverride(String packageName, int uid, int visibility) {
        if (visibility == getPackageVisibilityOverride(packageName, uid)) {
            return;
        }
        SparseIntArray visibilityByUid = mPackageVisibilities.get(packageName);
        if (visibilityByUid == null) {
            visibilityByUid = new SparseIntArray();
            mPackageVisibilities.put(packageName, visibilityByUid);
        }
        visibilityByUid.put(uid, visibility);
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
        final int N = mPackagePriorities.size();
        if (filter == null) {
            pw.print(prefix);
            pw.println("package priorities:");
        }
        for (int i = 0; i < N; i++) {
            String name = mPackagePriorities.keyAt(i);
            if (filter == null || filter.matches(name)) {
                SparseIntArray priorityByUid = mPackagePriorities.get(name);
                final int M = priorityByUid.size();
                for (int j = 0; j < M; j++) {
                    int uid = priorityByUid.keyAt(j);
                    int priority = priorityByUid.get(uid);
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print(name);
                    pw.print(" (");
                    pw.print(uid);
                    pw.print(") has priority: ");
                    pw.println(priority);
                }
            }
        }
    }
}
