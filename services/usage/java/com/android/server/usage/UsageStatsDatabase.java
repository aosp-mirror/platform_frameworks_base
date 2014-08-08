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

package com.android.server.usage;

import android.app.usage.TimeSparseArray;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.util.AtomicFile;
import android.util.Slog;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Provides an interface to query for UsageStat data from an XML database.
 */
class UsageStatsDatabase {
    private static final String TAG = "UsageStatsDatabase";
    private static final boolean DEBUG = UsageStatsService.DEBUG;

    private final Object mLock = new Object();
    private final File[] mIntervalDirs;
    private final TimeSparseArray<AtomicFile>[] mSortedStatFiles;
    private final Calendar mCal;

    public UsageStatsDatabase(File dir) {
        mIntervalDirs = new File[] {
                new File(dir, "daily"),
                new File(dir, "weekly"),
                new File(dir, "monthly"),
                new File(dir, "yearly"),
        };
        mSortedStatFiles = new TimeSparseArray[mIntervalDirs.length];
        mCal = Calendar.getInstance();
    }

    /**
     * Initialize any directories required and index what stats are available.
     */
    void init() {
        synchronized (mLock) {
            for (File f : mIntervalDirs) {
                f.mkdirs();
                if (!f.exists()) {
                    throw new IllegalStateException("Failed to create directory "
                            + f.getAbsolutePath());
                }
            }

            final FilenameFilter backupFileFilter = new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return !name.endsWith(".bak");
                }
            };

            // Index the available usage stat files on disk.
            for (int i = 0; i < mSortedStatFiles.length; i++) {
                mSortedStatFiles[i] = new TimeSparseArray<>();
                File[] files = mIntervalDirs[i].listFiles(backupFileFilter);
                if (files != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Found " + files.length + " stat files for interval " + i);
                    }

                    for (File f : files) {
                        mSortedStatFiles[i].put(Long.parseLong(f.getName()), new AtomicFile(f));
                    }
                }
            }
        }
    }

    /**
     * Get the latest stats that exist for this interval type.
     */
    public IntervalStats getLatestUsageStats(int intervalType) {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            final int fileCount = mSortedStatFiles[intervalType].size();
            if (fileCount == 0) {
                return null;
            }

            try {
                final AtomicFile f = mSortedStatFiles[intervalType].valueAt(fileCount - 1);
                IntervalStats stats = new IntervalStats();
                UsageStatsXml.read(f, stats);
                return stats;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to read usage stats file", e);
            }
        }
        return null;
    }

    /**
     * Get the time at which the latest stats begin for this interval type.
     */
    public long getLatestUsageStatsBeginTime(int intervalType) {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            final int statsFileCount = mSortedStatFiles[intervalType].size();
            if (statsFileCount > 0) {
                return mSortedStatFiles[intervalType].keyAt(statsFileCount - 1);
            }
            return -1;
        }
    }

    /**
     * Find all {@link UsageStats} for the given range and interval type.
     */
    public List<UsageStats> queryUsageStats(int intervalType, long beginTime, long endTime) {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            if (endTime < beginTime) {
                return null;
            }

            final int startIndex = mSortedStatFiles[intervalType].closestIndexOnOrBefore(beginTime);
            if (startIndex < 0) {
                return null;
            }

            int endIndex = mSortedStatFiles[intervalType].closestIndexOnOrAfter(endTime);
            if (endIndex < 0) {
                endIndex = mSortedStatFiles[intervalType].size() - 1;
            }

            try {
                IntervalStats stats = new IntervalStats();
                ArrayList<UsageStats> results = new ArrayList<>();
                for (int i = startIndex; i <= endIndex; i++) {
                    final AtomicFile f = mSortedStatFiles[intervalType].valueAt(i);

                    if (DEBUG) {
                        Slog.d(TAG, "Reading stat file " + f.getBaseFile().getAbsolutePath());
                    }

                    UsageStatsXml.read(f, stats);
                    if (beginTime < stats.endTime) {
                        results.addAll(stats.stats.values());
                    }
                }
                return results;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to read usage stats file", e);
                return null;
            }
        }
    }

    /**
     * Find the interval that best matches this range.
     *
     * TODO(adamlesinski): Use endTimeStamp in best fit calculation.
     */
    public int findBestFitBucket(long beginTimeStamp, long endTimeStamp) {
        synchronized (mLock) {
            int bestBucket = -1;
            long smallestDiff = Long.MAX_VALUE;
            for (int i = mSortedStatFiles.length - 1; i >= 0; i--) {
                final int index = mSortedStatFiles[i].closestIndexOnOrBefore(beginTimeStamp);
                int size = mSortedStatFiles[i].size();
                if (index >= 0 && index < size) {
                    // We have some results here, check if they are better than our current match.
                    long diff = Math.abs(mSortedStatFiles[i].keyAt(index) - beginTimeStamp);
                    if (diff < smallestDiff) {
                        smallestDiff = diff;
                        bestBucket = i;
                    }
                }
            }
            return bestBucket;
        }
    }

    /**
     * Remove any usage stat files that are too old.
     */
    public void prune() {
        synchronized (mLock) {
            long timeNow = System.currentTimeMillis();

            mCal.setTimeInMillis(timeNow);
            mCal.add(Calendar.MONTH, -6);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_MONTHLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(timeNow);
            mCal.add(Calendar.WEEK_OF_YEAR, -4);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_WEEKLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(timeNow);
            mCal.add(Calendar.DAY_OF_YEAR, -7);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_DAILY],
                    mCal.getTimeInMillis());
        }
    }

    private static void pruneFilesOlderThan(File dir, long expiryTime) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                long beginTime = Long.parseLong(f.getName());
                if (beginTime < expiryTime) {
                    new AtomicFile(f).delete();
                }
            }
        }
    }

    /**
     * Update the stats in the database. They may not be written to disk immediately.
     */
    public void putUsageStats(int intervalType, IntervalStats stats) throws IOException {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            AtomicFile f = mSortedStatFiles[intervalType].get(stats.beginTime);
            if (f == null) {
                f = new AtomicFile(new File(mIntervalDirs[intervalType],
                        Long.toString(stats.beginTime)));
                mSortedStatFiles[intervalType].put(stats.beginTime, f);
            }

            UsageStatsXml.write(f, stats);
            stats.lastTimeSaved = f.getLastModifiedTime();
        }
    }
}
