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

package com.android.server.people.prediction;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.usage.UsageEvents;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Range;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.server.people.data.AppUsageStatsData;
import com.android.server.people.data.DataManager;
import com.android.server.people.data.Event;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Ranking scorer for Sharesheet targets. */
class SharesheetModelScorer {

    private static final String TAG = "SharesheetModelScorer";
    private static final boolean DEBUG = false;
    private static final Integer RECENCY_SCORE_COUNT = 6;
    private static final float RECENCY_INITIAL_BASE_SCORE = 0.4F;
    private static final float RECENCY_SCORE_INITIAL_DECAY = 0.05F;
    private static final float RECENCY_SCORE_SUBSEQUENT_DECAY = 0.02F;
    private static final long ONE_MONTH_WINDOW = TimeUnit.DAYS.toMillis(30);
    private static final long FOREGROUND_APP_PROMO_TIME_WINDOW = TimeUnit.MINUTES.toMillis(10);
    private static final float USAGE_STATS_CHOOSER_SCORE_INITIAL_DECAY = 0.9F;
    private static final float FREQUENTLY_USED_APP_SCORE_INITIAL_DECAY = 0.3F;
    @VisibleForTesting
    static final float FOREGROUND_APP_WEIGHT = 0F;
    @VisibleForTesting
    static final String CHOOSER_ACTIVITY = ChooserActivity.class.getSimpleName();

    // Keep constructor private to avoid class being instantiated.
    private SharesheetModelScorer() {
    }

    /**
     * Computes each target's recency, frequency and frequency of the same {@code shareEventType}
     * based on past sharing history. Update {@link ShareTargetPredictor.ShareTargetScore}.
     */
    static void computeScore(List<ShareTargetPredictor.ShareTarget> shareTargets,
            int shareEventType, long now) {
        if (shareTargets.isEmpty()) {
            return;
        }
        float totalFreqScore = 0f;
        int freqScoreCount = 0;
        float totalMimeFreqScore = 0f;
        int mimeFreqScoreCount = 0;
        // Top of this heap has lowest rank.
        PriorityQueue<Pair<ShareTargetRankingScore, Range<Long>>> recencyMinHeap =
                new PriorityQueue<>(RECENCY_SCORE_COUNT,
                        Comparator.comparingLong(p -> p.second.getUpper()));
        List<ShareTargetRankingScore> scoreList = new ArrayList<>(shareTargets.size());
        for (ShareTargetPredictor.ShareTarget target : shareTargets) {
            ShareTargetRankingScore shareTargetScore = new ShareTargetRankingScore();
            scoreList.add(shareTargetScore);
            if (target.getEventHistory() == null) {
                continue;
            }
            // Counts frequency
            List<Range<Long>> timeSlots = target.getEventHistory().getEventIndex(
                    Event.SHARE_EVENT_TYPES).getActiveTimeSlots();
            if (!timeSlots.isEmpty()) {
                for (Range<Long> timeSlot : timeSlots) {
                    shareTargetScore.incrementFrequencyScore(
                            getFreqDecayedOnElapsedTime(now - timeSlot.getLower()));
                }
                totalFreqScore += shareTargetScore.getFrequencyScore();
                freqScoreCount++;
            }
            // Counts frequency for sharing same mime type
            List<Range<Long>> timeSlotsOfSameType = target.getEventHistory().getEventIndex(
                    shareEventType).getActiveTimeSlots();
            if (!timeSlotsOfSameType.isEmpty()) {
                for (Range<Long> timeSlot : timeSlotsOfSameType) {
                    shareTargetScore.incrementMimeFrequencyScore(
                            getFreqDecayedOnElapsedTime(now - timeSlot.getLower()));
                }
                totalMimeFreqScore += shareTargetScore.getMimeFrequencyScore();
                mimeFreqScoreCount++;
            }
            // Records most recent targets
            Range<Long> mostRecentTimeSlot = target.getEventHistory().getEventIndex(
                    Event.SHARE_EVENT_TYPES).getMostRecentActiveTimeSlot();
            if (mostRecentTimeSlot == null) {
                continue;
            }
            if (recencyMinHeap.size() < RECENCY_SCORE_COUNT
                    || mostRecentTimeSlot.getUpper() > recencyMinHeap.peek().second.getUpper()) {
                if (recencyMinHeap.size() == RECENCY_SCORE_COUNT) {
                    recencyMinHeap.poll();
                }
                recencyMinHeap.offer(new Pair(shareTargetScore, mostRecentTimeSlot));
            }
        }
        // Calculates recency score
        while (!recencyMinHeap.isEmpty()) {
            float recencyScore = RECENCY_INITIAL_BASE_SCORE;
            if (recencyMinHeap.size() > 1) {
                recencyScore = RECENCY_INITIAL_BASE_SCORE - RECENCY_SCORE_INITIAL_DECAY
                        - RECENCY_SCORE_SUBSEQUENT_DECAY * (recencyMinHeap.size() - 2);
            }
            recencyMinHeap.poll().first.setRecencyScore(recencyScore);
        }

        Float avgFreq = freqScoreCount != 0 ? totalFreqScore / freqScoreCount : 0f;
        Float avgMimeFreq = mimeFreqScoreCount != 0 ? totalMimeFreqScore / mimeFreqScoreCount : 0f;
        for (int i = 0; i < scoreList.size(); i++) {
            ShareTargetPredictor.ShareTarget target = shareTargets.get(i);
            ShareTargetRankingScore targetScore = scoreList.get(i);
            // Normalizes freq and mimeFreq score
            targetScore.setFrequencyScore(normalizeFreqScore(
                    avgFreq.equals(0f) ? 0f : targetScore.getFrequencyScore() / avgFreq));
            targetScore.setMimeFrequencyScore(normalizeMimeFreqScore(avgMimeFreq.equals(0f) ? 0f
                    : targetScore.getMimeFrequencyScore() / avgMimeFreq));
            // Calculates total score
            targetScore.setTotalScore(
                    probOR(probOR(targetScore.getRecencyScore(), targetScore.getFrequencyScore()),
                            targetScore.getMimeFrequencyScore()));
            target.setScore(targetScore.getTotalScore());

            if (DEBUG) {
                Slog.d(TAG, String.format(
                        "SharesheetModel: packageName: %s, className: %s, shortcutId: %s, "
                                + "recency:%.2f, freq_all:%.2f, freq_mime:%.2f, total:%.2f",
                        target.getAppTarget().getPackageName(),
                        target.getAppTarget().getClassName(),
                        target.getAppTarget().getShortcutInfo() != null
                                ? target.getAppTarget().getShortcutInfo().getId() : null,
                        targetScore.getRecencyScore(),
                        targetScore.getFrequencyScore(),
                        targetScore.getMimeFrequencyScore(),
                        targetScore.getTotalScore()));
            }
        }
    }

    /**
     * Computes ranking score for app sharing. Update {@link ShareTargetPredictor.ShareTargetScore}.
     */
    static void computeScoreForAppShare(List<ShareTargetPredictor.ShareTarget> shareTargets,
            int shareEventType, int targetsLimit, long now, @NonNull DataManager dataManager,
            @UserIdInt int callingUserId) {
        computeScore(shareTargets, shareEventType, now);
        postProcess(shareTargets, targetsLimit, dataManager, callingUserId);
    }

    private static void postProcess(List<ShareTargetPredictor.ShareTarget> shareTargets,
            int targetsLimit, @NonNull DataManager dataManager, @UserIdInt int callingUserId) {
        // Populates a map which key is package name and value is list of shareTargets descended
        // on total score.
        Map<String, List<ShareTargetPredictor.ShareTarget>> shareTargetMap = new ArrayMap<>();
        for (ShareTargetPredictor.ShareTarget shareTarget : shareTargets) {
            String packageName = shareTarget.getAppTarget().getPackageName();
            shareTargetMap.computeIfAbsent(packageName, key -> new ArrayList<>());
            List<ShareTargetPredictor.ShareTarget> targetsList = shareTargetMap.get(packageName);
            int index = 0;
            while (index < targetsList.size()) {
                if (shareTarget.getScore() > targetsList.get(index).getScore()) {
                    break;
                }
                index++;
            }
            targetsList.add(index, shareTarget);
        }
        promoteForegroundApp(shareTargetMap, dataManager, callingUserId);
        promoteMostChosenAndFrequentlyUsedApps(shareTargetMap, targetsLimit, dataManager,
                callingUserId);
    }

    /**
     * Promotes frequently chosen sharing apps and frequently used sharing apps as per
     * UsageStatsManager, if recommended apps based on sharing history have not reached the limit
     * (e.g. user did not share any content in last couple weeks)
     */
    private static void promoteMostChosenAndFrequentlyUsedApps(
            Map<String, List<ShareTargetPredictor.ShareTarget>> shareTargetMap, int targetsLimit,
            @NonNull DataManager dataManager, @UserIdInt int callingUserId) {
        int validPredictionNum = 0;
        float minValidScore = 1f;
        for (List<ShareTargetPredictor.ShareTarget> targets : shareTargetMap.values()) {
            for (ShareTargetPredictor.ShareTarget target : targets) {
                if (target.getScore() > 0f) {
                    validPredictionNum++;
                    minValidScore = Math.min(target.getScore(), minValidScore);
                }
            }
        }
        // Skips if recommended apps based on sharing history have already reached the limit.
        if (validPredictionNum >= targetsLimit) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, AppUsageStatsData> appStatsMap =
                dataManager.queryAppUsageStats(
                        callingUserId, now - ONE_MONTH_WINDOW, now, shareTargetMap.keySet());
        // Promotes frequently chosen sharing apps as per UsageStatsManager.
        minValidScore = promoteApp(shareTargetMap, appStatsMap, AppUsageStatsData::getChosenCount,
                USAGE_STATS_CHOOSER_SCORE_INITIAL_DECAY * minValidScore, minValidScore);
        // Promotes frequently used sharing apps as per UsageStatsManager.
        promoteApp(shareTargetMap, appStatsMap, AppUsageStatsData::getLaunchCount,
                FREQUENTLY_USED_APP_SCORE_INITIAL_DECAY * minValidScore, minValidScore);
    }

    private static float promoteApp(
            Map<String, List<ShareTargetPredictor.ShareTarget>> shareTargetMap,
            Map<String, AppUsageStatsData> appStatsMap,
            Function<AppUsageStatsData, Integer> countFunc, float baseScore, float minValidScore) {
        int maxCount = 0;
        for (AppUsageStatsData data : appStatsMap.values()) {
            maxCount = Math.max(maxCount, countFunc.apply(data));
        }
        if (maxCount > 0) {
            for (Map.Entry<String, AppUsageStatsData> entry : appStatsMap.entrySet()) {
                if (!shareTargetMap.containsKey(entry.getKey())) {
                    continue;
                }
                ShareTargetPredictor.ShareTarget target = shareTargetMap.get(entry.getKey()).get(0);
                if (target.getScore() > 0f) {
                    continue;
                }
                float curScore = baseScore * countFunc.apply(entry.getValue()) / maxCount;
                target.setScore(curScore);
                if (curScore > 0) {
                    minValidScore = Math.min(minValidScore, curScore);
                }
                if (DEBUG) {
                    Slog.d(TAG, String.format(
                            "SharesheetModel: promote as per AppUsageStats packageName: %s, "
                                    + "className: %s, total:%.2f",
                            target.getAppTarget().getPackageName(),
                            target.getAppTarget().getClassName(),
                            target.getScore()));
                }
            }
        }
        return minValidScore;
    }

    /**
     * Promotes the foreground app just prior to source sharing app. Share often occurs between
     * two apps the user is switching.
     */
    private static void promoteForegroundApp(
            Map<String, List<ShareTargetPredictor.ShareTarget>> shareTargetMap,
            @NonNull DataManager dataManager, @UserIdInt int callingUserId) {
        String sharingForegroundApp = findSharingForegroundApp(shareTargetMap, dataManager,
                callingUserId);
        if (sharingForegroundApp != null) {
            ShareTargetPredictor.ShareTarget target = shareTargetMap.get(sharingForegroundApp).get(
                    0);
            target.setScore(probOR(target.getScore(), FOREGROUND_APP_WEIGHT));
            if (DEBUG) {
                Slog.d(TAG, String.format(
                        "SharesheetModel: promoteForegroundApp packageName: %s, className: %s, "
                                + "total:%.2f",
                        target.getAppTarget().getPackageName(),
                        target.getAppTarget().getClassName(),
                        target.getScore()));
            }
        }
    }

    /**
     * Find the foreground app just prior to source sharing app from usageStatsManager. Returns null
     * if it is not available.
     */
    @Nullable
    private static String findSharingForegroundApp(
            Map<String, List<ShareTargetPredictor.ShareTarget>> shareTargetMap,
            @NonNull DataManager dataManager, @UserIdInt int callingUserId) {
        String sharingForegroundApp = null;
        long now = System.currentTimeMillis();
        List<UsageEvents.Event> events = dataManager.queryAppMovingToForegroundEvents(
                callingUserId, now - FOREGROUND_APP_PROMO_TIME_WINDOW, now);
        String sourceApp = null;
        for (int i = events.size() - 1; i >= 0; i--) {
            String className = events.get(i).getClassName();
            String packageName = events.get(i).getPackageName();
            if (packageName == null || (className != null && className.contains(CHOOSER_ACTIVITY))
                    || packageName.contains(CHOOSER_ACTIVITY)) {
                continue;
            }
            if (sourceApp == null) {
                sourceApp = packageName;
            } else if (!packageName.equals(sourceApp) && shareTargetMap.containsKey(packageName)) {
                sharingForegroundApp = packageName;
                break;
            }
        }
        return sharingForegroundApp;
    }

    /**
     * Probabilistic OR (also known as the algebraic sum). If a <= 1 and b <= 1, the result will be
     * <= 1.0.
     */
    private static float probOR(float a, float b) {
        return 1f - (1f - a) * (1f - b);
    }

    /** Counts frequency of share targets. Decays frequency for old shares. */
    private static float getFreqDecayedOnElapsedTime(long elapsedTimeMillis) {
        Duration duration = Duration.ofMillis(elapsedTimeMillis);
        if (duration.compareTo(Duration.ofDays(1)) <= 0) {
            return 1.0f;
        } else if (duration.compareTo(Duration.ofDays(3)) <= 0) {
            return 0.9f;
        } else if (duration.compareTo(Duration.ofDays(7)) <= 0) {
            return 0.8f;
        } else if (duration.compareTo(Duration.ofDays(14)) <= 0) {
            return 0.7f;
        } else {
            return 0.6f;
        }
    }

    /** Normalizes frequency score. */
    private static float normalizeFreqScore(double freqRatio) {
        if (freqRatio >= 2.5) {
            return 0.2f;
        } else if (freqRatio >= 1.5) {
            return 0.15f;
        } else if (freqRatio >= 1.0) {
            return 0.1f;
        } else if (freqRatio >= 0.75) {
            return 0.05f;
        } else {
            return 0f;
        }
    }

    /** Normalizes mimetype-specific frequency score. */
    private static float normalizeMimeFreqScore(double freqRatio) {
        if (freqRatio >= 2.0) {
            return 0.2f;
        } else if (freqRatio >= 1.2) {
            return 0.15f;
        } else if (freqRatio > 0.0) {
            return 0.1f;
        } else {
            return 0f;
        }
    }

    private static class ShareTargetRankingScore {

        private float mRecencyScore = 0f;
        private float mFrequencyScore = 0f;
        private float mMimeFrequencyScore = 0f;
        private float mTotalScore = 0f;

        float getTotalScore() {
            return mTotalScore;
        }

        void setTotalScore(float totalScore) {
            mTotalScore = totalScore;
        }

        float getRecencyScore() {
            return mRecencyScore;
        }

        void setRecencyScore(float recencyScore) {
            mRecencyScore = recencyScore;
        }

        float getFrequencyScore() {
            return mFrequencyScore;
        }

        void setFrequencyScore(float frequencyScore) {
            mFrequencyScore = frequencyScore;
        }

        void incrementFrequencyScore(float incremental) {
            mFrequencyScore += incremental;
        }

        float getMimeFrequencyScore() {
            return mMimeFrequencyScore;
        }

        void setMimeFrequencyScore(float mimeFrequencyScore) {
            mMimeFrequencyScore = mimeFrequencyScore;
        }

        void incrementMimeFrequencyScore(float incremental) {
            mMimeFrequencyScore += incremental;
        }
    }
}
