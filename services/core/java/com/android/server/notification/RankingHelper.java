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

import static android.app.Flags.restrictAudioAttributesAlarm;
import static android.app.Flags.restrictAudioAttributesCall;
import static android.app.Flags.restrictAudioAttributesMedia;
import static android.app.Flags.sortSectionByTime;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.text.TextUtils.formatSimple;

import android.annotation.NonNull;
import android.content.Context;
import android.service.notification.RankingHelperProto;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.compat.IPlatformCompat;
import com.android.tools.r8.keepanno.annotations.KeepItemKind;
import com.android.tools.r8.keepanno.annotations.KeepTarget;
import com.android.tools.r8.keepanno.annotations.UsesReflection;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class RankingHelper {
    private static final String TAG = "RankingHelper";

    private final NotificationSignalExtractor[] mSignalExtractors;
    private final Comparator mPreliminaryComparator;
    private final GlobalSortKeyComparator mFinalComparator = new GlobalSortKeyComparator();

    private final ArrayMap<String, NotificationRecord> mProxyByGroupTmp = new ArrayMap<>();

    private final Context mContext;
    private final RankingHandler mRankingHandler;

    @UsesReflection(
            value = {
                @KeepTarget(
                        kind = KeepItemKind.CLASS_AND_MEMBERS,
                        instanceOfClassConstantExclusive = NotificationSignalExtractor.class,
                        methodName = "<init>")
            })
    public RankingHelper(Context context, RankingHandler rankingHandler, RankingConfig config,
            ZenModeHelper zenHelper, NotificationUsageStats usageStats, String[] extractorNames,
            IPlatformCompat platformCompat, GroupHelper groupHelper) {
        mContext = context;
        mRankingHandler = rankingHandler;
        if (sortSectionByTime()) {
            mPreliminaryComparator = new NotificationTimeComparator();
        } else {
            mPreliminaryComparator = new NotificationComparator(mContext);
        }

        final int N = extractorNames.length;
        mSignalExtractors = new NotificationSignalExtractor[N];
        for (int i = 0; i < N; i++) {
            try {
                Class<?> extractorClass = mContext.getClassLoader().loadClass(extractorNames[i]);
                NotificationSignalExtractor extractor =
                        (NotificationSignalExtractor) extractorClass.newInstance();
                extractor.initialize(mContext, usageStats);
                extractor.setConfig(config);
                extractor.setZenHelper(zenHelper);
                extractor.setGroupHelper(groupHelper);
                if (restrictAudioAttributesAlarm() || restrictAudioAttributesMedia()
                        || restrictAudioAttributesCall()) {
                    extractor.setCompatChangeLogger(platformCompat);
                }
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

    public void sort(ArrayList<NotificationRecord> notificationList) {
        final int N = notificationList.size();
        // clear global sort keys
        for (int i = N - 1; i >= 0; i--) {
            notificationList.get(i).setGlobalSortKey(null);
        }

        // Rank each record individually.
        if (sortSectionByTime()) {
            notificationList.sort(mPreliminaryComparator);
        } else {
            // Lock comparator state for consistent compare() results.
            synchronized (((NotificationComparator) mPreliminaryComparator).mStateLock) {
                notificationList.sort(mPreliminaryComparator);
            }
        }

        synchronized (mProxyByGroupTmp) {
            // record individual ranking result and nominate proxies for each group
            for (int i = 0; i < N; i++) {
                final NotificationRecord record = notificationList.get(i);
                record.setAuthoritativeRank(i);
                if (sortSectionByTime()) {
                    final String groupKey = record.getGroupKey();
                    NotificationRecord existingProxy = mProxyByGroupTmp.get(groupKey);
                    // summaries are mostly hidden in systemui - if there is a child notification,
                    // use its rank
                    if (existingProxy == null || existingProxy.getNotification().isGroupSummary()) {
                        mProxyByGroupTmp.put(groupKey, record);
                    }
                } else {
                    final String groupKey = record.getGroupKey();
                    NotificationRecord existingProxy = mProxyByGroupTmp.get(groupKey);
                    if (existingProxy == null) {
                        mProxyByGroupTmp.put(groupKey, record);
                    }
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
                char intrusiveRank = sortSectionByTime()
                        ? '2'
                        : record.isRecentlyIntrusive() && record.getImportance() > IMPORTANCE_MIN
                        ? '0' : '1';
                record.setGlobalSortKey(
                        formatSimple("crtcl=0x%04x:intrsv=%c:grnk=0x%04x:gsmry=%c:%s:rnk=0x%04x",
                        record.getCriticality(),
                        intrusiveRank,
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

    public void dump(PrintWriter pw, String prefix,
            @NonNull NotificationManagerService.DumpFilter filter) {
        final int N = mSignalExtractors.length;
        pw.print(prefix);
        pw.print("mSignalExtractors.length = ");
        pw.println(N);
        for (int i = 0; i < N; i++) {
            pw.print(prefix);
            pw.print("  ");
            pw.println(mSignalExtractors[i].getClass().getSimpleName());
        }
    }

    public void dump(ProtoOutputStream proto,
            @NonNull NotificationManagerService.DumpFilter filter) {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            proto.write(RankingHelperProto.NOTIFICATION_SIGNAL_EXTRACTORS,
                    mSignalExtractors[i].getClass().getSimpleName());
        }
    }
}
