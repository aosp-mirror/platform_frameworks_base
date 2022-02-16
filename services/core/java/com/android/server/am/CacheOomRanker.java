/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executor;

/**
 * Class to re-rank a number of the least recently used processes before they
 * are assigned oom adjust scores.
 */
public class CacheOomRanker {
    @VisibleForTesting
    static final String KEY_USE_OOM_RE_RANKING = "use_oom_re_ranking";
    private static final boolean DEFAULT_USE_OOM_RE_RANKING = false;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK = "oom_re_ranking_number_to_re_rank";
    @VisibleForTesting static final int DEFAULT_OOM_RE_RANKING_NUMBER_TO_RE_RANK = 8;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_LRU_WEIGHT = "oom_re_ranking_lru_weight";
    @VisibleForTesting static final float DEFAULT_OOM_RE_RANKING_LRU_WEIGHT = 0.35f;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_USES_WEIGHT = "oom_re_ranking_uses_weight";
    @VisibleForTesting static final float DEFAULT_OOM_RE_RANKING_USES_WEIGHT = 0.5f;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_RSS_WEIGHT = "oom_re_ranking_rss_weight";
    @VisibleForTesting static final float DEFAULT_OOM_RE_RANKING_RSS_WEIGHT = 0.15f;

    private static final Comparator<RankedProcessRecord> SCORED_PROCESS_RECORD_COMPARATOR =
            new ScoreComparator();
    private static final Comparator<RankedProcessRecord> CACHE_USE_COMPARATOR =
            new CacheUseComparator();
    private static final Comparator<RankedProcessRecord> LAST_RSS_COMPARATOR =
            new LastRssComparator();
    private static final Comparator<RankedProcessRecord> LAST_ACTIVITY_TIME_COMPARATOR =
            new LastActivityTimeComparator();

    private final Object mPhenotypeFlagLock = new Object();

    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;
    private final Object mProfilerLock;

    @GuardedBy("mPhenotypeFlagLock")
    private boolean mUseOomReRanking = DEFAULT_USE_OOM_RE_RANKING;
    // Weight to apply to the LRU ordering.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting float mLruWeight = DEFAULT_OOM_RE_RANKING_LRU_WEIGHT;
    // Weight to apply to the ordering by number of times the process has been added to the cache.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting float mUsesWeight = DEFAULT_OOM_RE_RANKING_USES_WEIGHT;
    // Weight to apply to the ordering by RSS used by the processes.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting float mRssWeight = DEFAULT_OOM_RE_RANKING_RSS_WEIGHT;

    // Positions to replace in the lru list.
    @GuardedBy("mPhenotypeFlagLock")
    private int[] mLruPositions;
    // Processes to re-rank
    @GuardedBy("mPhenotypeFlagLock")
    private RankedProcessRecord[] mScoredProcessRecords;

    private final DeviceConfig.OnPropertiesChangedListener mOnFlagsChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    synchronized (mPhenotypeFlagLock) {
                        for (String name : properties.getKeyset()) {
                            if (KEY_USE_OOM_RE_RANKING.equals(name)) {
                                updateUseOomReranking();
                            } else if (KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK.equals(name)) {
                                updateNumberToReRank();
                            } else if (KEY_OOM_RE_RANKING_LRU_WEIGHT.equals(name)) {
                                updateLruWeight();
                            } else if (KEY_OOM_RE_RANKING_USES_WEIGHT.equals(name)) {
                                updateUsesWeight();
                            } else if (KEY_OOM_RE_RANKING_RSS_WEIGHT.equals(name)) {
                                updateRssWeight();
                            }
                        }
                    }
                }
            };

    CacheOomRanker(final ActivityManagerService service) {
        mService = service;
        mProcLock = service.mProcLock;
        mProfilerLock = service.mAppProfiler.mProfilerLock;
    }

    /** Load settings from device config and register a listener for changes. */
    public void init(Executor executor) {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                executor, mOnFlagsChangedListener);
        synchronized (mPhenotypeFlagLock) {
            updateUseOomReranking();
            updateNumberToReRank();
            updateLruWeight();
            updateUsesWeight();
            updateRssWeight();
        }
    }

    /**
     * Returns whether oom re-ranking is enabled.
     */
    public boolean useOomReranking() {
        synchronized (mPhenotypeFlagLock) {
            return mUseOomReRanking;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateUseOomReranking() {
        mUseOomReRanking = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_USE_OOM_RE_RANKING, DEFAULT_USE_OOM_RE_RANKING);
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateNumberToReRank() {
        int previousNumberToReRank = getNumberToReRank();
        int numberToReRank = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK, DEFAULT_OOM_RE_RANKING_NUMBER_TO_RE_RANK);
        if (previousNumberToReRank != numberToReRank) {
            mScoredProcessRecords = new RankedProcessRecord[numberToReRank];
            for (int i = 0; i < mScoredProcessRecords.length; ++i) {
                mScoredProcessRecords[i] = new RankedProcessRecord();
            }
            mLruPositions = new int[numberToReRank];
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting
    int getNumberToReRank() {
        return mScoredProcessRecords == null ? 0 : mScoredProcessRecords.length;
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateLruWeight() {
        mLruWeight = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOM_RE_RANKING_LRU_WEIGHT, DEFAULT_OOM_RE_RANKING_LRU_WEIGHT);
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateUsesWeight() {
        mUsesWeight = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOM_RE_RANKING_USES_WEIGHT, DEFAULT_OOM_RE_RANKING_USES_WEIGHT);
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateRssWeight() {
        mRssWeight = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOM_RE_RANKING_RSS_WEIGHT, DEFAULT_OOM_RE_RANKING_RSS_WEIGHT);
    }

    /**
     * Re-rank the cached processes in the lru list with a weighted ordering
     * of lru, rss size and number of times the process has been put in the cache.
     */
    @GuardedBy({"mService", "mProcLock"})
    void reRankLruCachedAppsLSP(ArrayList<ProcessRecord> lruList, int lruProcessServiceStart) {
        float lruWeight;
        float usesWeight;
        float rssWeight;
        int[] lruPositions;
        RankedProcessRecord[] scoredProcessRecords;

        synchronized (mPhenotypeFlagLock) {
            lruWeight = mLruWeight;
            usesWeight = mUsesWeight;
            rssWeight = mRssWeight;
            lruPositions = mLruPositions;
            scoredProcessRecords = mScoredProcessRecords;
        }

        // Don't re-rank if the class hasn't been initialized with defaults.
        if (lruPositions == null || scoredProcessRecords == null) {
            return;
        }

        // Collect the least recently used processes to re-rank, only rank cached
        // processes further down the list than mLruProcessServiceStart.
        int cachedProcessPos = 0;
        for (int i = 0; i < lruProcessServiceStart
                && cachedProcessPos < scoredProcessRecords.length; ++i) {
            ProcessRecord app = lruList.get(i);
            // Processes that will be assigned a cached oom adj score.
            if (!app.isKilledByAm() && app.getThread() != null && app.mState.getCurAdj()
                    >= ProcessList.UNKNOWN_ADJ) {
                scoredProcessRecords[cachedProcessPos].proc = app;
                scoredProcessRecords[cachedProcessPos].score = 0.0f;
                lruPositions[cachedProcessPos] = i;
                ++cachedProcessPos;
            }
        }

        // TODO maybe ensure a certain number above this in the cache before re-ranking.
        if (cachedProcessPos < scoredProcessRecords.length)  {
            // Ignore we don't have enough processes to worry about re-ranking.
            return;
        }

        // Add scores for each of the weighted features we want to rank based on.
        if (lruWeight > 0.0f) {
            // This doesn't use the LRU list ordering as after the first re-ranking
            // that will no longer be lru.
            Arrays.sort(scoredProcessRecords, LAST_ACTIVITY_TIME_COMPARATOR);
            addToScore(scoredProcessRecords, lruWeight);
        }
        if (rssWeight > 0.0f) {
            synchronized (mService.mAppProfiler.mProfilerLock) {
                Arrays.sort(scoredProcessRecords, LAST_RSS_COMPARATOR);
            }
            addToScore(scoredProcessRecords, rssWeight);
        }
        if (usesWeight > 0.0f) {
            Arrays.sort(scoredProcessRecords, CACHE_USE_COMPARATOR);
            addToScore(scoredProcessRecords, usesWeight);
        }

        // Re-rank by the new combined score.
        Arrays.sort(scoredProcessRecords, SCORED_PROCESS_RECORD_COMPARATOR);

        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
            boolean printedHeader = false;
            for (int i = 0; i < scoredProcessRecords.length; ++i) {
                if (scoredProcessRecords[i].proc.getPid()
                        != lruList.get(lruPositions[i]).getPid()) {
                    if (!printedHeader) {
                        Slog.i(OomAdjuster.TAG, "reRankLruCachedApps");
                        printedHeader = true;
                    }
                    Slog.i(OomAdjuster.TAG, "  newPos=" + lruPositions[i] + " "
                            + scoredProcessRecords[i].proc);
                }
            }
        }

        for (int i = 0; i < scoredProcessRecords.length; ++i) {
            lruList.set(lruPositions[i], scoredProcessRecords[i].proc);
            scoredProcessRecords[i].proc = null;
        }
    }

    private static void addToScore(RankedProcessRecord[] scores, float weight) {
        for (int i = 1; i < scores.length; ++i) {
            scores[i].score += i * weight;
        }
    }

    void dump(PrintWriter pw) {
        pw.println("CacheOomRanker settings");
        synchronized (mPhenotypeFlagLock) {
            pw.println("  " + KEY_USE_OOM_RE_RANKING + "=" + mUseOomReRanking);
            pw.println("  " + KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK + "=" + getNumberToReRank());
            pw.println("  " + KEY_OOM_RE_RANKING_LRU_WEIGHT + "=" + mLruWeight);
            pw.println("  " + KEY_OOM_RE_RANKING_USES_WEIGHT + "=" + mUsesWeight);
            pw.println("  " + KEY_OOM_RE_RANKING_RSS_WEIGHT + "=" + mRssWeight);
        }
    }

    private static class ScoreComparator implements Comparator<RankedProcessRecord> {
        @Override
        public int compare(RankedProcessRecord o1, RankedProcessRecord o2) {
            return Float.compare(o1.score, o2.score);
        }
    }

    private static class LastActivityTimeComparator implements Comparator<RankedProcessRecord> {
        @Override
        public int compare(RankedProcessRecord o1, RankedProcessRecord o2) {
            return Long.compare(o1.proc.getLastActivityTime(), o2.proc.getLastActivityTime());
        }
    }

    private static class CacheUseComparator implements Comparator<RankedProcessRecord> {
        @Override
        public int compare(RankedProcessRecord o1, RankedProcessRecord o2) {
            return Long.compare(o1.proc.mState.getCacheOomRankerUseCount(),
                    o2.proc.mState.getCacheOomRankerUseCount());
        }
    }

    private static class LastRssComparator implements Comparator<RankedProcessRecord> {
        @Override
        public int compare(RankedProcessRecord o1, RankedProcessRecord o2) {
            // High RSS first to match least recently used.
            return Long.compare(o2.proc.mProfile.getLastRss(), o1.proc.mProfile.getLastRss());
        }
    }

    private static class RankedProcessRecord {
        public ProcessRecord proc;
        public float score;
    }
}
