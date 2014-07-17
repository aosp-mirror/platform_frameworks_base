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

class UsageStatsDatabase {
    private static final String TAG = "UsageStatsDatabase";
    private static final boolean DEBUG = UsageStatsService.DEBUG;

    private final Object mLock = new Object();
    private final File[] mBucketDirs;
    private final TimeSparseArray<AtomicFile>[] mSortedStatFiles;
    private final Calendar mCal;

    public UsageStatsDatabase(File dir) {
        mBucketDirs = new File[] {
                new File(dir, "daily"),
                new File(dir, "weekly"),
                new File(dir, "monthly"),
                new File(dir, "yearly"),
        };
        mSortedStatFiles = new TimeSparseArray[mBucketDirs.length];
        mCal = Calendar.getInstance();
    }

    void init() {
        synchronized (mLock) {
            for (File f : mBucketDirs) {
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
                File[] files = mBucketDirs[i].listFiles(backupFileFilter);
                if (files != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Found " + files.length + " stat files for bucket " + i);
                    }

                    for (File f : files) {
                        mSortedStatFiles[i].put(Long.parseLong(f.getName()), new AtomicFile(f));
                    }
                }
            }
        }
    }

    public UsageStats getLatestUsageStats(int bucketType) {
        synchronized (mLock) {
            if (bucketType < 0 || bucketType >= mBucketDirs.length) {
                throw new IllegalArgumentException("Bad bucket type " + bucketType);
            }

            final int fileCount = mSortedStatFiles[bucketType].size();
            if (fileCount == 0) {
                return null;
            }

            try {
                final AtomicFile f = mSortedStatFiles[bucketType].valueAt(fileCount - 1);
                UsageStats stats = UsageStatsXml.read(f);
                stats.mLastTimeSaved = f.getLastModifiedTime();
                return stats;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to read usage stats file", e);
            }
        }
        return null;
    }

    public UsageStats[] getUsageStats(int bucketType, long beginTime, int limit) {
        synchronized (mLock) {
            if (bucketType < 0 || bucketType >= mBucketDirs.length) {
                throw new IllegalArgumentException("Bad bucket type " + bucketType);
            }

            if (limit <= 0) {
                return UsageStats.EMPTY_STATS;
            }

            int startIndex = mSortedStatFiles[bucketType].closestIndexAfter(beginTime);
            if (startIndex < 0) {
                return UsageStats.EMPTY_STATS;
            }

            final int realLimit = Math.min(limit, mSortedStatFiles[bucketType].size() - startIndex);
            try {
                ArrayList<UsageStats> stats = new ArrayList<>(realLimit);
                for (int i = 0; i < realLimit; i++) {
                    final AtomicFile f = mSortedStatFiles[bucketType].valueAt(startIndex + i);

                    if (DEBUG) {
                        Slog.d(TAG, "Reading stat file " + f.getBaseFile().getAbsolutePath());
                    }

                    UsageStats stat = UsageStatsXml.read(f);
                    if (beginTime < stat.mEndTimeStamp) {
                        stat.mLastTimeSaved = f.getLastModifiedTime();
                        stats.add(stat);
                    }
                }
                return stats.toArray(new UsageStats[stats.size()]);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to read usage stats file", e);
                return UsageStats.EMPTY_STATS;
            }
        }
    }

    public void prune() {
        synchronized (mLock) {
            long timeNow = System.currentTimeMillis();

            mCal.setTimeInMillis(timeNow);
            mCal.add(Calendar.MONTH, -6);
            pruneFilesOlderThan(mBucketDirs[UsageStatsManager.MONTHLY_BUCKET],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(timeNow);
            mCal.add(Calendar.WEEK_OF_YEAR, -4);
            pruneFilesOlderThan(mBucketDirs[UsageStatsManager.WEEKLY_BUCKET],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(timeNow);
            mCal.add(Calendar.DAY_OF_YEAR, -7);
            pruneFilesOlderThan(mBucketDirs[UsageStatsManager.DAILY_BUCKET],
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

    public void putUsageStats(int bucketType, UsageStats stats)
            throws IOException {
        synchronized (mLock) {
            if (bucketType < 0 || bucketType >= mBucketDirs.length) {
                throw new IllegalArgumentException("Bad bucket type " + bucketType);
            }

            AtomicFile f = mSortedStatFiles[bucketType].get(stats.mBeginTimeStamp);
            if (f == null) {
                f = new AtomicFile(new File(mBucketDirs[bucketType],
                        Long.toString(stats.mBeginTimeStamp)));
                mSortedStatFiles[bucketType].append(stats.mBeginTimeStamp, f);
            }

            UsageStatsXml.write(stats, f);
            stats.mLastTimeSaved = f.getLastModifiedTime();
        }
    }

}
