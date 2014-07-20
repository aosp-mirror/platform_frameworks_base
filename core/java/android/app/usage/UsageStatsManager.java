/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage;

import android.content.Context;
import android.os.RemoteException;

public final class UsageStatsManager {
    /**
     * {@hide}
     */
    public static final int DAILY_BUCKET = 0;

    /**
     * {@hide}
     */
    public static final int WEEKLY_BUCKET = 1;

    /**
     * {@hide}
     */
    public static final int MONTHLY_BUCKET = 2;

    /**
     * {@hide}
     */
    public static final int YEARLY_BUCKET = 3;

    /**
     * {@hide}
     */
    public static final int BUCKET_COUNT = 4;

    private final Context mContext;
    private final IUsageStatsManager mService;

    /**
     * {@hide}
     */
    public UsageStatsManager(Context context, IUsageStatsManager service) {
        mContext = context;
        mService = service;
    }

    public UsageStats[] getDailyStatsSince(long time) {
        try {
            return mService.getStatsSince(DAILY_BUCKET, time, mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    public UsageStats[] getWeeklyStatsSince(long time) {
        try {
            return mService.getStatsSince(WEEKLY_BUCKET, time, mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    public UsageStats[] getMonthlyStatsSince(long time) {
        try {
            return mService.getStatsSince(MONTHLY_BUCKET, time, mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    public UsageStats[] getYearlyStatsSince(long time) {
        try {
            return mService.getStatsSince(YEARLY_BUCKET, time, mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    public UsageStats getRecentStatsSince(long time) {
        UsageStats aggregatedStats = null;
        long lastTime = time;
        UsageStats[] stats;
        while (true) {
            stats = getDailyStatsSince(lastTime);
            if (stats == null || stats.length == 0) {
                break;
            }

            for (UsageStats stat : stats) {
                lastTime = stat.mEndTimeStamp;

                if (aggregatedStats == null) {
                    aggregatedStats = new UsageStats();
                    aggregatedStats.mBeginTimeStamp = stat.mBeginTimeStamp;
                }

                aggregatedStats.mEndTimeStamp = stat.mEndTimeStamp;

                final int pkgCount = stat.getPackageCount();
                for (int i = 0; i < pkgCount; i++) {
                    final PackageUsageStats pkgStats = stat.getPackage(i);
                    final PackageUsageStats aggPkgStats =
                            aggregatedStats.getOrCreatePackageUsageStats(pkgStats.mPackageName);
                    aggPkgStats.mTotalTimeSpent += pkgStats.mTotalTimeSpent;
                    aggPkgStats.mLastTimeUsed = pkgStats.mLastTimeUsed;
                    aggPkgStats.mLastEvent = pkgStats.mLastEvent;
                }
            }
        }
        return aggregatedStats;
    }
}
