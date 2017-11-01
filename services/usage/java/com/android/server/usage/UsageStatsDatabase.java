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
import android.os.Build;
import android.os.SystemProperties;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides an interface to query for UsageStat data from an XML database.
 */
class UsageStatsDatabase {
    private static final int CURRENT_VERSION = 3;

    // Current version of the backup schema
    static final int BACKUP_VERSION = 1;

    // Key under which the payload blob is stored
    // same as UsageStatsBackupHelper.KEY_USAGE_STATS
    static final String KEY_USAGE_STATS = "usage_stats";


    private static final String TAG = "UsageStatsDatabase";
    private static final boolean DEBUG = UsageStatsService.DEBUG;
    private static final String BAK_SUFFIX = ".bak";
    private static final String CHECKED_IN_SUFFIX = UsageStatsXml.CHECKED_IN_SUFFIX;
    private static final String RETENTION_LEN_KEY = "ro.usagestats.chooser.retention";
    private static final int SELECTION_LOG_RETENTION_LEN =
            SystemProperties.getInt(RETENTION_LEN_KEY, 14);

    private final Object mLock = new Object();
    private final File[] mIntervalDirs;
    private final TimeSparseArray<AtomicFile>[] mSortedStatFiles;
    private final UnixCalendar mCal;
    private final File mVersionFile;
    private boolean mFirstUpdate;
    private boolean mNewUpdate;

    public UsageStatsDatabase(File dir) {
        mIntervalDirs = new File[] {
                new File(dir, "daily"),
                new File(dir, "weekly"),
                new File(dir, "monthly"),
                new File(dir, "yearly"),
        };
        mVersionFile = new File(dir, "version");
        mSortedStatFiles = new TimeSparseArray[mIntervalDirs.length];
        mCal = new UnixCalendar(0);
    }

    /**
     * Initialize any directories required and index what stats are available.
     */
    public void init(long currentTimeMillis) {
        synchronized (mLock) {
            for (File f : mIntervalDirs) {
                f.mkdirs();
                if (!f.exists()) {
                    throw new IllegalStateException("Failed to create directory "
                            + f.getAbsolutePath());
                }
            }

            checkVersionAndBuildLocked();
            indexFilesLocked();

            // Delete files that are in the future.
            for (TimeSparseArray<AtomicFile> files : mSortedStatFiles) {
                final int startIndex = files.closestIndexOnOrAfter(currentTimeMillis);
                if (startIndex < 0) {
                    continue;
                }

                final int fileCount = files.size();
                for (int i = startIndex; i < fileCount; i++) {
                    files.valueAt(i).delete();
                }

                // Remove in a separate loop because any accesses (valueAt)
                // will cause a gc in the SparseArray and mess up the order.
                for (int i = startIndex; i < fileCount; i++) {
                    files.removeAt(i);
                }
            }
        }
    }

    public interface CheckinAction {
        boolean checkin(IntervalStats stats);
    }

    /**
     * Calls {@link CheckinAction#checkin(IntervalStats)} on the given {@link CheckinAction}
     * for all {@link IntervalStats} that haven't been checked-in.
     * If any of the calls to {@link CheckinAction#checkin(IntervalStats)} returns false or throws
     * an exception, the check-in will be aborted.
     *
     * @param checkinAction The callback to run when checking-in {@link IntervalStats}.
     * @return true if the check-in succeeded.
     */
    public boolean checkinDailyFiles(CheckinAction checkinAction) {
        synchronized (mLock) {
            final TimeSparseArray<AtomicFile> files =
                    mSortedStatFiles[UsageStatsManager.INTERVAL_DAILY];
            final int fileCount = files.size();

            // We may have holes in the checkin (if there was an error)
            // so find the last checked-in file and go from there.
            int lastCheckin = -1;
            for (int i = 0; i < fileCount - 1; i++) {
                if (files.valueAt(i).getBaseFile().getPath().endsWith(CHECKED_IN_SUFFIX)) {
                    lastCheckin = i;
                }
            }

            final int start = lastCheckin + 1;
            if (start == fileCount - 1) {
                return true;
            }

            try {
                IntervalStats stats = new IntervalStats();
                for (int i = start; i < fileCount - 1; i++) {
                    UsageStatsXml.read(files.valueAt(i), stats);
                    if (!checkinAction.checkin(stats)) {
                        return false;
                    }
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed to check-in", e);
                return false;
            }

            // We have successfully checked-in the stats, so rename the files so that they
            // are marked as checked-in.
            for (int i = start; i < fileCount - 1; i++) {
                final AtomicFile file = files.valueAt(i);
                final File checkedInFile = new File(
                        file.getBaseFile().getPath() + CHECKED_IN_SUFFIX);
                if (!file.getBaseFile().renameTo(checkedInFile)) {
                    // We must return success, as we've already marked some files as checked-in.
                    // It's better to repeat ourselves than to lose data.
                    Slog.e(TAG, "Failed to mark file " + file.getBaseFile().getPath()
                            + " as checked-in");
                    return true;
                }

                // AtomicFile needs to set a new backup path with the same -c extension, so
                // we replace the old AtomicFile with the updated one.
                files.setValueAt(i, new AtomicFile(checkedInFile));
            }
        }
        return true;
    }

    private void indexFilesLocked() {
        final FilenameFilter backupFileFilter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return !name.endsWith(BAK_SUFFIX);
            }
        };

        // Index the available usage stat files on disk.
        for (int i = 0; i < mSortedStatFiles.length; i++) {
            if (mSortedStatFiles[i] == null) {
                mSortedStatFiles[i] = new TimeSparseArray<>();
            } else {
                mSortedStatFiles[i].clear();
            }
            File[] files = mIntervalDirs[i].listFiles(backupFileFilter);
            if (files != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Found " + files.length + " stat files for interval " + i);
                }

                for (File f : files) {
                    final AtomicFile af = new AtomicFile(f);
                    try {
                        mSortedStatFiles[i].put(UsageStatsXml.parseBeginTime(af), af);
                    } catch (IOException e) {
                        Slog.e(TAG, "failed to index file: " + f, e);
                    }
                }
            }
        }
    }

    /**
     * Is this the first update to the system from L to M?
     */
    boolean isFirstUpdate() {
        return mFirstUpdate;
    }

    /**
     * Is this a system update since we started tracking build fingerprint in the version file?
     */
    boolean isNewUpdate() {
        return mNewUpdate;
    }

    private void checkVersionAndBuildLocked() {
        int version;
        String buildFingerprint;
        String currentFingerprint = getBuildFingerprint();
        mFirstUpdate = true;
        mNewUpdate = true;
        try (BufferedReader reader = new BufferedReader(new FileReader(mVersionFile))) {
            version = Integer.parseInt(reader.readLine());
            buildFingerprint = reader.readLine();
            if (buildFingerprint != null) {
                mFirstUpdate = false;
            }
            if (currentFingerprint.equals(buildFingerprint)) {
                mNewUpdate = false;
            }
        } catch (NumberFormatException | IOException e) {
            version = 0;
        }

        if (version != CURRENT_VERSION) {
            Slog.i(TAG, "Upgrading from version " + version + " to " + CURRENT_VERSION);
            doUpgradeLocked(version);
        }

        if (version != CURRENT_VERSION || mNewUpdate) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(mVersionFile))) {
                writer.write(Integer.toString(CURRENT_VERSION));
                writer.write("\n");
                writer.write(currentFingerprint);
                writer.write("\n");
                writer.flush();
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write new version");
                throw new RuntimeException(e);
            }
        }
    }

    private String getBuildFingerprint() {
        return Build.VERSION.RELEASE + ";"
                + Build.VERSION.CODENAME + ";"
                + Build.VERSION.INCREMENTAL;
    }

    private void doUpgradeLocked(int thisVersion) {
        if (thisVersion < 2) {
            // Delete all files if we are version 0. This is a pre-release version,
            // so this is fine.
            Slog.i(TAG, "Deleting all usage stats files");
            for (int i = 0; i < mIntervalDirs.length; i++) {
                File[] files = mIntervalDirs[i].listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            }
        }
    }

    public void onTimeChanged(long timeDiffMillis) {
        synchronized (mLock) {
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("Time changed by ");
            TimeUtils.formatDuration(timeDiffMillis, logBuilder);
            logBuilder.append(".");

            int filesDeleted = 0;
            int filesMoved = 0;

            for (TimeSparseArray<AtomicFile> files : mSortedStatFiles) {
                final int fileCount = files.size();
                for (int i = 0; i < fileCount; i++) {
                    final AtomicFile file = files.valueAt(i);
                    final long newTime = files.keyAt(i) + timeDiffMillis;
                    if (newTime < 0) {
                        filesDeleted++;
                        file.delete();
                    } else {
                        try {
                            file.openRead().close();
                        } catch (IOException e) {
                            // Ignore, this is just to make sure there are no backups.
                        }

                        String newName = Long.toString(newTime);
                        if (file.getBaseFile().getName().endsWith(CHECKED_IN_SUFFIX)) {
                            newName = newName + CHECKED_IN_SUFFIX;
                        }

                        final File newFile = new File(file.getBaseFile().getParentFile(), newName);
                        filesMoved++;
                        file.getBaseFile().renameTo(newFile);
                    }
                }
                files.clear();
            }

            logBuilder.append(" files deleted: ").append(filesDeleted);
            logBuilder.append(" files moved: ").append(filesMoved);
            Slog.i(TAG, logBuilder.toString());

            // Now re-index the new files.
            indexFilesLocked();
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
     * Figures out what to extract from the given IntervalStats object.
     */
    interface StatCombiner<T> {

        /**
         * Implementations should extract interesting from <code>stats</code> and add it
         * to the <code>accumulatedResult</code> list.
         *
         * If the <code>stats</code> object is mutable, <code>mutable</code> will be true,
         * which means you should make a copy of the data before adding it to the
         * <code>accumulatedResult</code> list.
         *
         * @param stats The {@link IntervalStats} object selected.
         * @param mutable Whether or not the data inside the stats object is mutable.
         * @param accumulatedResult The list to which to add extracted data.
         */
        void combine(IntervalStats stats, boolean mutable, List<T> accumulatedResult);
    }

    /**
     * Find all {@link IntervalStats} for the given range and interval type.
     */
    public <T> List<T> queryUsageStats(int intervalType, long beginTime, long endTime,
            StatCombiner<T> combiner) {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            final TimeSparseArray<AtomicFile> intervalStats = mSortedStatFiles[intervalType];

            if (endTime <= beginTime) {
                if (DEBUG) {
                    Slog.d(TAG, "endTime(" + endTime + ") <= beginTime(" + beginTime + ")");
                }
                return null;
            }

            int startIndex = intervalStats.closestIndexOnOrBefore(beginTime);
            if (startIndex < 0) {
                // All the stats available have timestamps after beginTime, which means they all
                // match.
                startIndex = 0;
            }

            int endIndex = intervalStats.closestIndexOnOrBefore(endTime);
            if (endIndex < 0) {
                // All the stats start after this range ends, so nothing matches.
                if (DEBUG) {
                    Slog.d(TAG, "No results for this range. All stats start after.");
                }
                return null;
            }

            if (intervalStats.keyAt(endIndex) == endTime) {
                // The endTime is exclusive, so if we matched exactly take the one before.
                endIndex--;
                if (endIndex < 0) {
                    // All the stats start after this range ends, so nothing matches.
                    if (DEBUG) {
                        Slog.d(TAG, "No results for this range. All stats start after.");
                    }
                    return null;
                }
            }

            final IntervalStats stats = new IntervalStats();
            final ArrayList<T> results = new ArrayList<>();
            for (int i = startIndex; i <= endIndex; i++) {
                final AtomicFile f = intervalStats.valueAt(i);

                if (DEBUG) {
                    Slog.d(TAG, "Reading stat file " + f.getBaseFile().getAbsolutePath());
                }

                try {
                    UsageStatsXml.read(f, stats);
                    if (beginTime < stats.endTime) {
                        combiner.combine(stats, false, results);
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to read usage stats file", e);
                    // We continue so that we return results that are not
                    // corrupt.
                }
            }
            return results;
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
    public void prune(final long currentTimeMillis) {
        synchronized (mLock) {
            mCal.setTimeInMillis(currentTimeMillis);
            mCal.addYears(-3);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_YEARLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(currentTimeMillis);
            mCal.addMonths(-6);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_MONTHLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(currentTimeMillis);
            mCal.addWeeks(-4);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_WEEKLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(currentTimeMillis);
            mCal.addDays(-7);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_DAILY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(currentTimeMillis);
            mCal.addDays(-SELECTION_LOG_RETENTION_LEN);
            for (int i = 0; i < mIntervalDirs.length; ++i) {
                pruneChooserCountsOlderThan(mIntervalDirs[i], mCal.getTimeInMillis());
            }

            // We must re-index our file list or we will be trying to read
            // deleted files.
            indexFilesLocked();
        }
    }

    private static void pruneFilesOlderThan(File dir, long expiryTime) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String path = f.getPath();
                if (path.endsWith(BAK_SUFFIX)) {
                    f = new File(path.substring(0, path.length() - BAK_SUFFIX.length()));
                }

                long beginTime;
                try {
                    beginTime = UsageStatsXml.parseBeginTime(f);
                } catch (IOException e) {
                    beginTime = 0;
                }

                if (beginTime < expiryTime) {
                    new AtomicFile(f).delete();
                }
            }
        }
    }

    private static void pruneChooserCountsOlderThan(File dir, long expiryTime) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String path = f.getPath();
                if (path.endsWith(BAK_SUFFIX)) {
                    f = new File(path.substring(0, path.length() - BAK_SUFFIX.length()));
                }

                long beginTime;
                try {
                    beginTime = UsageStatsXml.parseBeginTime(f);
                } catch (IOException e) {
                    beginTime = 0;
                }

                if (beginTime < expiryTime) {
                    try {
                        final AtomicFile af = new AtomicFile(f);
                        final IntervalStats stats = new IntervalStats();
                        UsageStatsXml.read(af, stats);
                        final int pkgCount = stats.packageStats.size();
                        for (int i = 0; i < pkgCount; i++) {
                            UsageStats pkgStats = stats.packageStats.valueAt(i);
                            if (pkgStats.mChooserCounts != null) {
                                pkgStats.mChooserCounts.clear();
                            }
                        }
                        UsageStatsXml.write(af, stats);
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to delete chooser counts from usage stats file", e);
                    }
                }
            }
        }
    }

    /**
     * Update the stats in the database. They may not be written to disk immediately.
     */
    public void putUsageStats(int intervalType, IntervalStats stats) throws IOException {
        if (stats == null) return;
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


    /* Backup/Restore Code */
    byte[] getBackupPayload(String key) {
        synchronized (mLock) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (KEY_USAGE_STATS.equals(key)) {
                prune(System.currentTimeMillis());
                DataOutputStream out = new DataOutputStream(baos);
                try {
                    out.writeInt(BACKUP_VERSION);

                    out.writeInt(mSortedStatFiles[UsageStatsManager.INTERVAL_DAILY].size());
                    for (int i = 0; i < mSortedStatFiles[UsageStatsManager.INTERVAL_DAILY].size();
                            i++) {
                        writeIntervalStatsToStream(out,
                                mSortedStatFiles[UsageStatsManager.INTERVAL_DAILY].valueAt(i));
                    }

                    out.writeInt(mSortedStatFiles[UsageStatsManager.INTERVAL_WEEKLY].size());
                    for (int i = 0; i < mSortedStatFiles[UsageStatsManager.INTERVAL_WEEKLY].size();
                            i++) {
                        writeIntervalStatsToStream(out,
                                mSortedStatFiles[UsageStatsManager.INTERVAL_WEEKLY].valueAt(i));
                    }

                    out.writeInt(mSortedStatFiles[UsageStatsManager.INTERVAL_MONTHLY].size());
                    for (int i = 0; i < mSortedStatFiles[UsageStatsManager.INTERVAL_MONTHLY].size();
                            i++) {
                        writeIntervalStatsToStream(out,
                                mSortedStatFiles[UsageStatsManager.INTERVAL_MONTHLY].valueAt(i));
                    }

                    out.writeInt(mSortedStatFiles[UsageStatsManager.INTERVAL_YEARLY].size());
                    for (int i = 0; i < mSortedStatFiles[UsageStatsManager.INTERVAL_YEARLY].size();
                            i++) {
                        writeIntervalStatsToStream(out,
                                mSortedStatFiles[UsageStatsManager.INTERVAL_YEARLY].valueAt(i));
                    }
                    if (DEBUG) Slog.i(TAG, "Written " + baos.size() + " bytes of data");
                } catch (IOException ioe) {
                    Slog.d(TAG, "Failed to write data to output stream", ioe);
                    baos.reset();
                }
            }
            return baos.toByteArray();
        }

    }

    void applyRestoredPayload(String key, byte[] payload) {
        synchronized (mLock) {
            if (KEY_USAGE_STATS.equals(key)) {
                // Read stats files for the current device configs
                IntervalStats dailyConfigSource =
                        getLatestUsageStats(UsageStatsManager.INTERVAL_DAILY);
                IntervalStats weeklyConfigSource =
                        getLatestUsageStats(UsageStatsManager.INTERVAL_WEEKLY);
                IntervalStats monthlyConfigSource =
                        getLatestUsageStats(UsageStatsManager.INTERVAL_MONTHLY);
                IntervalStats yearlyConfigSource =
                        getLatestUsageStats(UsageStatsManager.INTERVAL_YEARLY);

                try {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
                    int backupDataVersion = in.readInt();

                    // Can't handle this backup set
                    if (backupDataVersion < 1 || backupDataVersion > BACKUP_VERSION) return;

                    // Delete all stats files
                    // Do this after reading version and before actually restoring
                    for (int i = 0; i < mIntervalDirs.length; i++) {
                        deleteDirectoryContents(mIntervalDirs[i]);
                    }

                    int fileCount = in.readInt();
                    for (int i = 0; i < fileCount; i++) {
                        IntervalStats stats = deserializeIntervalStats(getIntervalStatsBytes(in));
                        stats = mergeStats(stats, dailyConfigSource);
                        putUsageStats(UsageStatsManager.INTERVAL_DAILY, stats);
                    }

                    fileCount = in.readInt();
                    for (int i = 0; i < fileCount; i++) {
                        IntervalStats stats = deserializeIntervalStats(getIntervalStatsBytes(in));
                        stats = mergeStats(stats, weeklyConfigSource);
                        putUsageStats(UsageStatsManager.INTERVAL_WEEKLY, stats);
                    }

                    fileCount = in.readInt();
                    for (int i = 0; i < fileCount; i++) {
                        IntervalStats stats = deserializeIntervalStats(getIntervalStatsBytes(in));
                        stats = mergeStats(stats, monthlyConfigSource);
                        putUsageStats(UsageStatsManager.INTERVAL_MONTHLY, stats);
                    }

                    fileCount = in.readInt();
                    for (int i = 0; i < fileCount; i++) {
                        IntervalStats stats = deserializeIntervalStats(getIntervalStatsBytes(in));
                        stats = mergeStats(stats, yearlyConfigSource);
                        putUsageStats(UsageStatsManager.INTERVAL_YEARLY, stats);
                    }
                    if (DEBUG) Slog.i(TAG, "Completed Restoring UsageStats");
                } catch (IOException ioe) {
                    Slog.d(TAG, "Failed to read data from input stream", ioe);
                } finally {
                    indexFilesLocked();
                }
            }
        }
    }

    /**
     * Get the Configuration Statistics from the current device statistics and merge them
     * with the backed up usage statistics.
     */
    private IntervalStats mergeStats(IntervalStats beingRestored, IntervalStats onDevice) {
        if (onDevice == null) return beingRestored;
        if (beingRestored == null) return null;
        beingRestored.activeConfiguration = onDevice.activeConfiguration;
        beingRestored.configurations.putAll(onDevice.configurations);
        beingRestored.events = onDevice.events;
        return beingRestored;
    }

    private void writeIntervalStatsToStream(DataOutputStream out, AtomicFile statsFile)
            throws IOException {
        IntervalStats stats = new IntervalStats();
        try {
            UsageStatsXml.read(statsFile, stats);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read usage stats file", e);
            out.writeInt(0);
            return;
        }
        sanitizeIntervalStatsForBackup(stats);
        byte[] data = serializeIntervalStats(stats);
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] getIntervalStatsBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] buffer = new byte[length];
        in.read(buffer, 0, length);
        return buffer;
    }

    private static void sanitizeIntervalStatsForBackup(IntervalStats stats) {
        if (stats == null) return;
        stats.activeConfiguration = null;
        stats.configurations.clear();
        if (stats.events != null) stats.events.clear();
    }

    private static byte[] serializeIntervalStats(IntervalStats stats) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        try {
            out.writeLong(stats.beginTime);
            UsageStatsXml.write(out, stats);
        } catch (IOException ioe) {
            Slog.d(TAG, "Serializing IntervalStats Failed", ioe);
            baos.reset();
        }
        return baos.toByteArray();
    }

    private static IntervalStats deserializeIntervalStats(byte[] data) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream in = new DataInputStream(bais);
        IntervalStats stats = new IntervalStats();
        try {
            stats.beginTime = in.readLong();
            UsageStatsXml.read(in, stats);
        } catch (IOException ioe) {
            Slog.d(TAG, "DeSerializing IntervalStats Failed", ioe);
            stats = null;
        }
        return stats;
    }

    private static void deleteDirectoryContents(File directory) {
        File[] files = directory.listFiles();
        for (File file : files) {
            deleteDirectory(file);
        }
    }

    private static void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isDirectory()) {
                    file.delete();
                } else {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }
}