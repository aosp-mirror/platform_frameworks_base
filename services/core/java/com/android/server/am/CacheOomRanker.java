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

import android.os.Process;
import android.os.SystemClock;
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
    @VisibleForTesting
    static final int DEFAULT_OOM_RE_RANKING_NUMBER_TO_RE_RANK = 8;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_PRESERVE_TOP_N_APPS =
            "oom_re_ranking_preserve_top_n_apps";
    @VisibleForTesting
    static final int DEFAULT_PRESERVE_TOP_N_APPS = 3;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_USE_FREQUENT_RSS = "oom_re_ranking_rss_use_frequent_rss";
    @VisibleForTesting
    static final boolean DEFAULT_USE_FREQUENT_RSS = true;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_RSS_UPDATE_RATE_MS = "oom_re_ranking_rss_update_rate_ms";
    @VisibleForTesting
    static final long DEFAULT_RSS_UPDATE_RATE_MS = 10_000; // 10 seconds
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_LRU_WEIGHT = "oom_re_ranking_lru_weight";
    @VisibleForTesting
    static final float DEFAULT_OOM_RE_RANKING_LRU_WEIGHT = 0.35f;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_USES_WEIGHT = "oom_re_ranking_uses_weight";
    @VisibleForTesting
    static final float DEFAULT_OOM_RE_RANKING_USES_WEIGHT = 0.5f;
    @VisibleForTesting
    static final String KEY_OOM_RE_RANKING_RSS_WEIGHT = "oom_re_ranking_rss_weight";
    @VisibleForTesting
    static final float DEFAULT_OOM_RE_RANKING_RSS_WEIGHT = 0.15f;

    private static final Comparator<RankedProcessRecord> SCORED_PROCESS_RECORD_COMPARATOR =
            new ScoreComparator();
    private static final Comparator<RankedProcessRecord> CACHE_USE_COMPARATOR =
            new CacheUseComparator();
    private static final Comparator<RankedProcessRecord> RSS_COMPARATOR =
            new RssComparator();
    private static final Comparator<RankedProcessRecord> LAST_RSS_COMPARATOR =
            new LastRssComparator();
    private static final Comparator<RankedProcessRecord> LAST_ACTIVITY_TIME_COMPARATOR =
            new LastActivityTimeComparator();

    private final Object mPhenotypeFlagLock = new Object();

    private final ActivityManagerService mService;
    private final ProcessDependencies mProcessDependencies;
    private final ActivityManagerGlobalLock mProcLock;
    private final Object mProfilerLock;

    @GuardedBy("mPhenotypeFlagLock")
    private boolean mUseOomReRanking = DEFAULT_USE_OOM_RE_RANKING;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting
    int mPreserveTopNApps = DEFAULT_PRESERVE_TOP_N_APPS;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting
    boolean mUseFrequentRss = DEFAULT_USE_FREQUENT_RSS;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting
    long mRssUpdateRateMs = DEFAULT_RSS_UPDATE_RATE_MS;
    // Weight to apply to the LRU ordering.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting
    float mLruWeight = DEFAULT_OOM_RE_RANKING_LRU_WEIGHT;
    // Weight to apply to the ordering by number of times the process has been added to the cache.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting
    float mUsesWeight = DEFAULT_OOM_RE_RANKING_USES_WEIGHT;
    // Weight to apply to the ordering by RSS used by the processes.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting
    float mRssWeight = DEFAULT_OOM_RE_RANKING_RSS_WEIGHT;

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
                            } else if (KEY_OOM_RE_RANKING_PRESERVE_TOP_N_APPS.equals(name)) {
                                updatePreserveTopNApps();
                            } else if (KEY_OOM_RE_RANKING_USE_FREQUENT_RSS.equals(name)) {
                                updateUseFrequentRss();
                            } else if (KEY_OOM_RE_RANKING_RSS_UPDATE_RATE_MS.equals(name)) {
                                updateRssUpdateRateMs();
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
        this(service, new ProcessDependenciesImpl());
    }

    @VisibleForTesting
    CacheOomRanker(final ActivityManagerService service, ProcessDependencies processDependencies) {
        mService = service;
        mProcLock = service.mProcLock;
        mProfilerLock = service.mAppProfiler.mProfilerLock;
        mProcessDependencies = processDependencies;
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
    private void updatePreserveTopNApps() {
        int preserveTopNApps = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOM_RE_RANKING_PRESERVE_TOP_N_APPS, DEFAULT_PRESERVE_TOP_N_APPS);
        if (preserveTopNApps < 0) {
            Slog.w(OomAdjuster.TAG,
                    "Found negative value for preserveTopNApps, setting to default: "
                            + preserveTopNApps);
            preserveTopNApps = DEFAULT_PRESERVE_TOP_N_APPS;
        }
        mPreserveTopNApps = preserveTopNApps;
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateRssUpdateRateMs() {
        mRssUpdateRateMs = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOM_RE_RANKING_RSS_UPDATE_RATE_MS, DEFAULT_RSS_UPDATE_RATE_MS);
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateUseFrequentRss() {
        mUseFrequentRss = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOM_RE_RANKING_USE_FREQUENT_RSS, DEFAULT_USE_FREQUENT_RSS);
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
        // The lruList is a list of processes ordered by how recently they were used. The
        // least-recently-used apps are at the beginning of the list. We keep track of two
        // indices in the lruList:
        //
        // getNumberToReRank=5, preserveTopNApps=3, lruProcessServiceStart=7,
        // lruList=
        //   0: app A       ^
        //   1: app B       | These apps are re-ranked, as they are the first five apps (see
        //   2: app C       | getNumberToReRank), excluding...
        //   3: app D       v
        //   4: app E       ^
        //   5: app F       | The three most-recently-used apps in the cache (see preserveTopNApps).
        //   6: app G       v
        //   7: service A   ^
        //   8: service B   | Everything beyond lruProcessServiceStart is ignored, as these aren't
        //   9: service C   | apps.
        //  10: activity A  |
        //      ...         |
        //
        // `numProcessesEvaluated` moves across the apps (indices 0-6) or until we've found enough
        // apps to re-rank, and made sure none of them are in the top `preserveTopNApps` apps.
        // Re-ranked apps are copied into `scoredProcessRecords`, where the re-ranking calculation
        // happens.
        //
        // Note that some apps in the `lruList` can be skipped, if they don't pass
        //`appCanBeReRanked`.

        float lruWeight;
        float usesWeight;
        float rssWeight;
        int preserveTopNApps;
        boolean useFrequentRss;
        long rssUpdateRateMs;
        int[] lruPositions;
        RankedProcessRecord[] scoredProcessRecords;

        synchronized (mPhenotypeFlagLock) {
            lruWeight = mLruWeight;
            usesWeight = mUsesWeight;
            rssWeight = mRssWeight;
            preserveTopNApps = mPreserveTopNApps;
            useFrequentRss = mUseFrequentRss;
            rssUpdateRateMs = mRssUpdateRateMs;
            lruPositions = mLruPositions;
            scoredProcessRecords = mScoredProcessRecords;
        }

        // Don't re-rank if the class hasn't been initialized with defaults.
        if (lruPositions == null || scoredProcessRecords == null) {
            return;
        }

        int numProcessesEvaluated = 0;
        // Collect the least recently used processes to re-rank, only rank cached
        // processes further down the list than mLruProcessServiceStart.
        int numProcessesReRanked = 0;
        while (numProcessesEvaluated < lruProcessServiceStart
                && numProcessesReRanked < scoredProcessRecords.length) {
            ProcessRecord process = lruList.get(numProcessesEvaluated);
            // Processes that will be assigned a cached oom adj score.
            if (appCanBeReRanked(process)) {
                scoredProcessRecords[numProcessesReRanked].proc = process;
                scoredProcessRecords[numProcessesReRanked].score = 0.0f;
                lruPositions[numProcessesReRanked] = numProcessesEvaluated;
                ++numProcessesReRanked;
            }
            ++numProcessesEvaluated;
        }

        // Count how many apps we're not re-ranking (up to preserveTopNApps).
        int numProcessesNotReRanked = 0;
        while (numProcessesEvaluated < lruProcessServiceStart
                && numProcessesNotReRanked < preserveTopNApps) {
            ProcessRecord process = lruList.get(numProcessesEvaluated);
            if (appCanBeReRanked(process)) {
                numProcessesNotReRanked++;
            }
            numProcessesEvaluated++;
        }
        // Exclude the top `preserveTopNApps` apps from re-ranking.
        if (numProcessesNotReRanked < preserveTopNApps) {
            numProcessesReRanked -= preserveTopNApps - numProcessesNotReRanked;
            if (numProcessesReRanked < 0) {
                numProcessesReRanked = 0;
            }
        }

        if (useFrequentRss) {
            // Update RSS values for re-ranked apps.
            long nowMs = SystemClock.elapsedRealtime();
            for (int i = 0; i < numProcessesReRanked; ++i) {
                RankedProcessRecord scoredProcessRecord = scoredProcessRecords[i];
                long sinceUpdateMs =
                        nowMs - scoredProcessRecord.proc.mState.getCacheOomRankerRssTimeMs();
                if (scoredProcessRecord.proc.mState.getCacheOomRankerRss() != 0
                        && sinceUpdateMs < rssUpdateRateMs) {
                    continue;
                }

                long[] rss = mProcessDependencies.getRss(scoredProcessRecord.proc.getPid());
                if (rss == null || rss.length == 0) {
                    Slog.e(
                            OomAdjuster.TAG,
                            "Process.getRss returned bad value, not re-ranking: "
                                    + Arrays.toString(rss));
                    return;
                }
                // First element is total RSS:
                // frameworks/base/core/jni/android_util_Process.cpp:1192
                scoredProcessRecord.proc.mState.setCacheOomRankerRss(rss[0], nowMs);
            }
        }

        // Add scores for each of the weighted features we want to rank based on.
        if (lruWeight > 0.0f) {
            // This doesn't use the LRU list ordering as after the first re-ranking
            // that will no longer be lru.
            Arrays.sort(scoredProcessRecords, 0, numProcessesReRanked,
                    LAST_ACTIVITY_TIME_COMPARATOR);
            addToScore(scoredProcessRecords, lruWeight);
        }
        if (rssWeight > 0.0f) {
            if (useFrequentRss) {
                Arrays.sort(scoredProcessRecords, 0, numProcessesReRanked, RSS_COMPARATOR);
            } else {
                synchronized (mService.mAppProfiler.mProfilerLock) {
                    Arrays.sort(scoredProcessRecords, 0, numProcessesReRanked, LAST_RSS_COMPARATOR);
                }
            }
            addToScore(scoredProcessRecords, rssWeight);
        }
        if (usesWeight > 0.0f) {
            Arrays.sort(scoredProcessRecords, 0, numProcessesReRanked, CACHE_USE_COMPARATOR);
            addToScore(scoredProcessRecords, usesWeight);
        }

        // Re-rank by the new combined score.
        Arrays.sort(scoredProcessRecords, 0, numProcessesReRanked,
                SCORED_PROCESS_RECORD_COMPARATOR);

        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
            boolean printedHeader = false;
            for (int i = 0; i < numProcessesReRanked; ++i) {
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

        for (int i = 0; i < numProcessesReRanked; ++i) {
            lruList.set(lruPositions[i], scoredProcessRecords[i].proc);
            scoredProcessRecords[i].proc = null;
        }
    }

    private static boolean appCanBeReRanked(ProcessRecord process) {
        return !process.isKilledByAm()
                && process.getThread() != null
                && process.mState.getCurAdj() >= ProcessList.UNKNOWN_ADJ;
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

    private static class RssComparator implements Comparator<RankedProcessRecord> {
        @Override
        public int compare(RankedProcessRecord o1, RankedProcessRecord o2) {
            // High RSS first to match least recently used.
            return Long.compare(
                    o2.proc.mState.getCacheOomRankerRss(),
                    o1.proc.mState.getCacheOomRankerRss());
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

    /**
     * Interface for mocking {@link Process} static methods.
     */
    interface ProcessDependencies {
        long[] getRss(int pid);
    }

    private static class ProcessDependenciesImpl implements ProcessDependencies {
        @Override
        public long[] getRss(int pid) {
            return Process.getRss(pid);
        }
    }
}
