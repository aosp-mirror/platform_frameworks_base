/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.Nullable;
import android.content.Context;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Handler;
import android.util.AtomicFile;
import android.util.LongArray;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A storage mechanism for BatteryUsageStats snapshots.
 */
public class BatteryUsageStatsStore {
    private static final String TAG = "BatteryUsageStatsStore";

    private static final List<BatteryUsageStatsQuery> BATTERY_USAGE_STATS_QUERY = List.of(
            new BatteryUsageStatsQuery.Builder()
                    .setMaxStatsAgeMs(0)
                    .includePowerModels()
                    .build());
    private static final String BATTERY_USAGE_STATS_DIR = "battery-usage-stats";
    private static final String SNAPSHOT_FILE_EXTENSION = ".bus";
    private static final String DIR_LOCK_FILENAME = ".lock";
    private static final String CONFIG_FILENAME = "config";
    private static final String BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP_PROPERTY =
            "BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP";
    private static final long MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES = 100 * 1024;

    private final Context mContext;
    private final BatteryStatsImpl mBatteryStats;
    private boolean mSystemReady;
    private final File mStoreDir;
    private final File mLockFile;
    private final AtomicFile mConfigFile;
    private final long mMaxStorageBytes;
    private final Handler mHandler;
    private final BatteryUsageStatsProvider mBatteryUsageStatsProvider;

    public BatteryUsageStatsStore(Context context, BatteryStatsImpl stats, File systemDir,
            Handler handler) {
        this(context, stats, systemDir, handler, MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES);
    }

    @VisibleForTesting
    public BatteryUsageStatsStore(Context context, BatteryStatsImpl batteryStats, File systemDir,
            Handler handler, long maxStorageBytes) {
        mContext = context;
        mBatteryStats = batteryStats;
        mStoreDir = new File(systemDir, BATTERY_USAGE_STATS_DIR);
        mLockFile = new File(mStoreDir, DIR_LOCK_FILENAME);
        mConfigFile = new AtomicFile(new File(mStoreDir, CONFIG_FILENAME));
        mHandler = handler;
        mMaxStorageBytes = maxStorageBytes;
        mBatteryStats.setBatteryResetListener(this::prepareForBatteryStatsReset);
        mBatteryUsageStatsProvider = new BatteryUsageStatsProvider(mContext, mBatteryStats);
    }

    /**
     * Notifies BatteryUsageStatsStore that the system server is ready.
     */
    public void onSystemReady() {
        mSystemReady = true;
    }

    private void prepareForBatteryStatsReset(int resetReason) {
        if (resetReason == BatteryStatsImpl.RESET_REASON_CORRUPT_FILE || !mSystemReady) {
            return;
        }

        final List<BatteryUsageStats> stats =
                mBatteryUsageStatsProvider.getBatteryUsageStats(BATTERY_USAGE_STATS_QUERY);
        if (stats.isEmpty()) {
            Slog.wtf(TAG, "No battery usage stats generated");
            return;
        }

        mHandler.post(() -> storeBatteryUsageStats(stats.get(0)));
    }

    private void storeBatteryUsageStats(BatteryUsageStats stats) {
        try (FileLock lock = lockSnapshotDirectory()) {
            if (!mStoreDir.exists()) {
                if (!mStoreDir.mkdirs()) {
                    Slog.e(TAG,
                            "Could not create a directory for battery usage stats snapshots");
                    return;
                }
            }
            File file = makeSnapshotFilename(stats.getStatsEndTimestamp());
            try {
                writeXmlFileLocked(stats, file);
            } catch (Exception e) {
                Slog.e(TAG, "Cannot save battery usage stats", e);
            }

            removeOldSnapshotsLocked();
        } catch (IOException e) {
            Slog.e(TAG, "Cannot lock battery usage stats directory", e);
        }
    }

    /**
     * Returns the timestamps of the stored BatteryUsageStats snapshots. The timestamp corresponds
     * to the time the snapshot was taken {@link BatteryUsageStats#getStatsEndTimestamp()}.
     */
    public long[] listBatteryUsageStatsTimestamps() {
        LongArray timestamps = new LongArray(100);
        try (FileLock lock = lockSnapshotDirectory()) {
            for (File file : mStoreDir.listFiles()) {
                String fileName = file.getName();
                if (fileName.endsWith(SNAPSHOT_FILE_EXTENSION)) {
                    try {
                        String fileNameWithoutExtension = fileName.substring(0,
                                fileName.length() - SNAPSHOT_FILE_EXTENSION.length());
                        timestamps.add(Long.parseLong(fileNameWithoutExtension));
                    } catch (NumberFormatException e) {
                        Slog.wtf(TAG, "Invalid format of BatteryUsageStats snapshot file name: "
                                + fileName);
                    }
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Cannot lock battery usage stats directory", e);
        }
        return timestamps.toArray();
    }

    /**
     * Reads the specified snapshot of BatteryUsageStats.  Returns null if the snapshot
     * does not exist.
     */
    @Nullable
    public BatteryUsageStats loadBatteryUsageStats(long timestamp) {
        try (FileLock lock = lockSnapshotDirectory()) {
            File file = makeSnapshotFilename(timestamp);
            try {
                return readXmlFileLocked(file);
            } catch (Exception e) {
                Slog.e(TAG, "Cannot read battery usage stats", e);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Cannot lock battery usage stats directory", e);
        }
        return null;
    }

    /**
     * Saves the supplied timestamp of the BATTERY_USAGE_STATS_BEFORE_RESET statsd atom pull
     * in persistent file.
     */
    public void setLastBatteryUsageStatsBeforeResetAtomPullTimestamp(long timestamp) {
        Properties props = new Properties();
        try (FileLock lock = lockSnapshotDirectory()) {
            try (InputStream in = mConfigFile.openRead()) {
                props.load(in);
            } catch (IOException e) {
                Slog.e(TAG, "Cannot load config file " + mConfigFile, e);
            }
            props.put(BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP_PROPERTY,
                    String.valueOf(timestamp));
            FileOutputStream out = null;
            try {
                out = mConfigFile.startWrite();
                props.store(out, "Statsd atom pull timestamps");
                mConfigFile.finishWrite(out);
            } catch (IOException e) {
                mConfigFile.failWrite(out);
                Slog.e(TAG, "Cannot save config file " + mConfigFile, e);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Cannot lock battery usage stats directory", e);
        }
    }

    /**
     * Retrieves the previously saved timestamp of the last BATTERY_USAGE_STATS_BEFORE_RESET
     * statsd atom pull.
     */
    public long getLastBatteryUsageStatsBeforeResetAtomPullTimestamp() {
        Properties props = new Properties();
        try (FileLock lock = lockSnapshotDirectory()) {
            try (InputStream in = mConfigFile.openRead()) {
                props.load(in);
            } catch (IOException e) {
                Slog.e(TAG, "Cannot load config file " + mConfigFile, e);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Cannot lock battery usage stats directory", e);
        }
        return Long.parseLong(
                props.getProperty(BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP_PROPERTY, "0"));
    }

    private FileLock lockSnapshotDirectory() throws IOException {
        mLockFile.getParentFile().mkdirs();
        mLockFile.createNewFile();
        return FileChannel.open(mLockFile.toPath(), StandardOpenOption.WRITE).lock();
    }

    /**
     * Creates a file name by formatting the timestamp as 19-digit zero-padded number.
     * This ensures that sorted directory list follows the chronological order.
     */
    private File makeSnapshotFilename(long statsEndTimestamp) {
        return new File(mStoreDir, String.format(Locale.ENGLISH, "%019d", statsEndTimestamp)
                + SNAPSHOT_FILE_EXTENSION);
    }

    private void writeXmlFileLocked(BatteryUsageStats stats, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            TypedXmlSerializer serializer = Xml.newBinarySerializer();
            serializer.setOutput(out, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            stats.writeXml(serializer);
            serializer.endDocument();
        }
    }

    private BatteryUsageStats readXmlFileLocked(File file)
            throws IOException, XmlPullParserException {
        try (InputStream in = new FileInputStream(file)) {
            TypedXmlPullParser parser = Xml.newBinaryPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            return BatteryUsageStats.createFromXml(parser);
        }
    }

    private void removeOldSnapshotsLocked() {
        // Read the directory list into a _sorted_ map.  The alphanumeric ordering
        // corresponds to the historical order of snapshots because the file names
        // are timestamps zero-padded to the same length.
        long totalSize = 0;
        TreeMap<File, Long> mFileSizes = new TreeMap<>();
        for (File file : mStoreDir.listFiles()) {
            final long fileSize = file.length();
            totalSize += fileSize;
            if (file.getName().endsWith(SNAPSHOT_FILE_EXTENSION)) {
                mFileSizes.put(file, fileSize);
            }
        }

        while (totalSize > mMaxStorageBytes) {
            final Map.Entry<File, Long> entry = mFileSizes.firstEntry();
            if (entry == null) {
                break;
            }

            File file = entry.getKey();
            if (!file.delete()) {
                Slog.e(TAG, "Cannot delete battery usage stats " + file);
            }
            totalSize -= entry.getValue();
            mFileSizes.remove(file);
        }
    }
}
