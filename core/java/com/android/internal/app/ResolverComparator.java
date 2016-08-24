/*
 * Copyright (C) 2015 The Android Open Source Project
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


package com.android.internal.app;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks and compares packages based on usage stats.
 */
class ResolverComparator implements Comparator<ResolvedComponentInfo> {
    private static final String TAG = "ResolverComparator";

    private static final boolean DEBUG = false;

    // One week
    private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 7;

    private static final long RECENCY_TIME_PERIOD = 1000 * 60 * 60 * 12;

    private static final float RECENCY_MULTIPLIER = 2.f;

    private final Collator mCollator;
    private final boolean mHttp;
    private final PackageManager mPm;
    private final UsageStatsManager mUsm;
    private final Map<String, UsageStats> mStats;
    private final long mCurrentTime;
    private final long mSinceTime;
    private final LinkedHashMap<ComponentName, ScoredTarget> mScoredTargets = new LinkedHashMap<>();
    private final String mReferrerPackage;

    public ResolverComparator(Context context, Intent intent, String referrerPackage) {
        mCollator = Collator.getInstance(context.getResources().getConfiguration().locale);
        String scheme = intent.getScheme();
        mHttp = "http".equals(scheme) || "https".equals(scheme);
        mReferrerPackage = referrerPackage;

        mPm = context.getPackageManager();
        mUsm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

        mCurrentTime = System.currentTimeMillis();
        mSinceTime = mCurrentTime - USAGE_STATS_PERIOD;
        mStats = mUsm.queryAndAggregateUsageStats(mSinceTime, mCurrentTime);
    }

    public void compute(List<ResolvedComponentInfo> targets) {
        mScoredTargets.clear();

        final long recentSinceTime = mCurrentTime - RECENCY_TIME_PERIOD;

        long mostRecentlyUsedTime = recentSinceTime + 1;
        long mostTimeSpent = 1;
        int mostLaunched = 1;

        for (ResolvedComponentInfo target : targets) {
            final ScoredTarget scoredTarget
                    = new ScoredTarget(target.getResolveInfoAt(0).activityInfo);
            mScoredTargets.put(target.name, scoredTarget);
            final UsageStats pkStats = mStats.get(target.name.getPackageName());
            if (pkStats != null) {
                // Only count recency for apps that weren't the caller
                // since the caller is always the most recent.
                // Persistent processes muck this up, so omit them too.
                if (!target.name.getPackageName().equals(mReferrerPackage)
                        && !isPersistentProcess(target)) {
                    final long lastTimeUsed = pkStats.getLastTimeUsed();
                    scoredTarget.lastTimeUsed = lastTimeUsed;
                    if (lastTimeUsed > mostRecentlyUsedTime) {
                        mostRecentlyUsedTime = lastTimeUsed;
                    }
                }
                final long timeSpent = pkStats.getTotalTimeInForeground();
                scoredTarget.timeSpent = timeSpent;
                if (timeSpent > mostTimeSpent) {
                    mostTimeSpent = timeSpent;
                }
                final int launched = pkStats.mLaunchCount;
                scoredTarget.launchCount = launched;
                if (launched > mostLaunched) {
                    mostLaunched = launched;
                }
            }
        }


        if (DEBUG) {
            Log.d(TAG, "compute - mostRecentlyUsedTime: " + mostRecentlyUsedTime
                    + " mostTimeSpent: " + mostTimeSpent
                    + " recentSinceTime: " + recentSinceTime
                    + " mostLaunched: " + mostLaunched);
        }

        for (ScoredTarget target : mScoredTargets.values()) {
            final float recency = (float) Math.max(target.lastTimeUsed - recentSinceTime, 0)
                    / (mostRecentlyUsedTime - recentSinceTime);
            final float recencyScore = recency * recency * RECENCY_MULTIPLIER;
            final float usageTimeScore = (float) target.timeSpent / mostTimeSpent;
            final float launchCountScore = (float) target.launchCount / mostLaunched;

            target.score = recencyScore + usageTimeScore + launchCountScore;
            if (DEBUG) {
                Log.d(TAG, "Scores: recencyScore: " + recencyScore
                        + " usageTimeScore: " + usageTimeScore
                        + " launchCountScore: " + launchCountScore
                        + " - " + target);
            }
        }
    }

    static boolean isPersistentProcess(ResolvedComponentInfo rci) {
        if (rci != null && rci.getCount() > 0) {
            return (rci.getResolveInfoAt(0).activityInfo.applicationInfo.flags &
                    ApplicationInfo.FLAG_PERSISTENT) != 0;
        }
        return false;
    }

    @Override
    public int compare(ResolvedComponentInfo lhsp, ResolvedComponentInfo rhsp) {
        final ResolveInfo lhs = lhsp.getResolveInfoAt(0);
        final ResolveInfo rhs = rhsp.getResolveInfoAt(0);

        // We want to put the one targeted to another user at the end of the dialog.
        if (lhs.targetUserId != UserHandle.USER_CURRENT) {
            return 1;
        }

        if (mHttp) {
            // Special case: we want filters that match URI paths/schemes to be
            // ordered before others.  This is for the case when opening URIs,
            // to make native apps go above browsers.
            final boolean lhsSpecific = ResolverActivity.isSpecificUriMatch(lhs.match);
            final boolean rhsSpecific = ResolverActivity.isSpecificUriMatch(rhs.match);
            if (lhsSpecific != rhsSpecific) {
                return lhsSpecific ? -1 : 1;
            }
        }

        final boolean lPinned = lhsp.isPinned();
        final boolean rPinned = rhsp.isPinned();

        if (lPinned && !rPinned) {
            return -1;
        } else if (!lPinned && rPinned) {
            return 1;
        }

        // Pinned items stay stable within a normal lexical sort and ignore scoring.
        if (!lPinned && !rPinned) {
            if (mStats != null) {
                final ScoredTarget lhsTarget = mScoredTargets.get(new ComponentName(
                        lhs.activityInfo.packageName, lhs.activityInfo.name));
                final ScoredTarget rhsTarget = mScoredTargets.get(new ComponentName(
                        rhs.activityInfo.packageName, rhs.activityInfo.name));
                final float diff = rhsTarget.score - lhsTarget.score;

                if (diff != 0) {
                    return diff > 0 ? 1 : -1;
                }
            }
        }

        CharSequence  sa = lhs.loadLabel(mPm);
        if (sa == null) sa = lhs.activityInfo.name;
        CharSequence  sb = rhs.loadLabel(mPm);
        if (sb == null) sb = rhs.activityInfo.name;

        return mCollator.compare(sa.toString().trim(), sb.toString().trim());
    }

    public float getScore(ComponentName name) {
        final ScoredTarget target = mScoredTargets.get(name);
        if (target != null) {
            return target.score;
        }
        return 0;
    }

    static class ScoredTarget {
        public final ComponentInfo componentInfo;
        public float score;
        public long lastTimeUsed;
        public long timeSpent;
        public long launchCount;

        public ScoredTarget(ComponentInfo ci) {
            componentInfo = ci;
        }

        @Override
        public String toString() {
            return "ScoredTarget{" + componentInfo
                    + " score: " + score
                    + " lastTimeUsed: " + lastTimeUsed
                    + " timeSpent: " + timeSpent
                    + " launchCount: " + launchCount
                    + "}";
        }
    }
}
