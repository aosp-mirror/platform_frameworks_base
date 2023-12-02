/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.BatteryUsageStats;
import android.os.FileUtils;
import android.os.Handler;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A storage mechanism for aggregated power/battery stats.
 */
public class PowerStatsStore {
    private static final String TAG = "PowerStatsStore";

    private static final String POWER_STATS_DIR = "power-stats";
    private static final String POWER_STATS_SPAN_FILE_EXTENSION = ".pss";
    private static final String DIR_LOCK_FILENAME = ".lock";
    private static final long MAX_POWER_STATS_SPAN_STORAGE_BYTES = 100 * 1024;

    private final File mSystemDir;
    private final File mStoreDir;
    private final File mLockFile;
    private final ReentrantLock mFileLock = new ReentrantLock();
    private FileLock mJvmLock;
    private final long mMaxStorageBytes;
    private final Handler mHandler;
    private final PowerStatsSpan.SectionReader mSectionReader;
    private volatile List<PowerStatsSpan.Metadata> mTableOfContents;

    public PowerStatsStore(@NonNull File systemDir, Handler handler,
            AggregatedPowerStatsConfig aggregatedPowerStatsConfig) {
        this(systemDir, MAX_POWER_STATS_SPAN_STORAGE_BYTES, handler,
                new DefaultSectionReader(aggregatedPowerStatsConfig));
    }

    @VisibleForTesting
    public PowerStatsStore(@NonNull File systemDir, long maxStorageBytes, Handler handler,
            @NonNull PowerStatsSpan.SectionReader sectionReader) {
        mSystemDir = systemDir;
        mStoreDir = new File(systemDir, POWER_STATS_DIR);
        mLockFile = new File(mStoreDir, DIR_LOCK_FILENAME);
        mHandler = handler;
        mMaxStorageBytes = maxStorageBytes;
        mSectionReader = sectionReader;
        mHandler.post(this::maybeClearLegacyStore);
    }

    /**
     * Returns the metadata for all {@link PowerStatsSpan}'s contained in the store.
     */
    @NonNull
    public List<PowerStatsSpan.Metadata> getTableOfContents() {
        List<PowerStatsSpan.Metadata> toc = mTableOfContents;
        if (toc != null) {
            return toc;
        }

        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        lockStoreDirectory();
        try {
            toc = new ArrayList<>();
            for (File file : mStoreDir.listFiles()) {
                String fileName = file.getName();
                if (!fileName.endsWith(POWER_STATS_SPAN_FILE_EXTENSION)) {
                    continue;
                }
                try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
                    parser.setInput(inputStream, StandardCharsets.UTF_8.name());
                    PowerStatsSpan.Metadata metadata = PowerStatsSpan.Metadata.read(parser);
                    if (metadata != null) {
                        toc.add(metadata);
                    } else {
                        Slog.e(TAG, "Removing incompatible PowerStatsSpan file: " + fileName);
                        file.delete();
                    }
                } catch (IOException | XmlPullParserException e) {
                    Slog.wtf(TAG, "Cannot read PowerStatsSpan file: " + fileName);
                }
            }
            toc.sort(PowerStatsSpan.Metadata.COMPARATOR);
            mTableOfContents = Collections.unmodifiableList(toc);
        } finally {
            unlockStoreDirectory();
        }

        return toc;
    }

    /**
     * Saves the specified span in the store.
     */
    public void storePowerStatsSpan(PowerStatsSpan span) {
        maybeClearLegacyStore();
        lockStoreDirectory();
        try {
            if (!mStoreDir.exists()) {
                if (!mStoreDir.mkdirs()) {
                    Slog.e(TAG, "Could not create a directory for power stats store");
                    return;
                }
            }

            AtomicFile file = new AtomicFile(makePowerStatsSpanFilename(span.getId()));
            file.write(out-> {
                try {
                    span.writeXml(out, Xml.newBinarySerializer());
                } catch (Exception e) {
                    // AtomicFile will log the exception and delete the file.
                    throw new RuntimeException(e);
                }
            });
            mTableOfContents = null;
            removeOldSpansLocked();
        } finally {
            unlockStoreDirectory();
        }
    }

    /**
     * Loads the PowerStatsSpan identified by its ID. Only loads the sections with
     * the specified types.  Loads all sections if no sectionTypes is empty.
     */
    @Nullable
    public PowerStatsSpan loadPowerStatsSpan(long id, String... sectionTypes) {
        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        lockStoreDirectory();
        try {
            File file = makePowerStatsSpanFilename(id);
            try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
                return PowerStatsSpan.read(inputStream, parser, mSectionReader, sectionTypes);
            } catch (IOException | XmlPullParserException e) {
                Slog.wtf(TAG, "Cannot read PowerStatsSpan file: " + file, e);
            }
        } finally {
            unlockStoreDirectory();
        }
        return null;
    }

    void storeAggregatedPowerStats(AggregatedPowerStats stats) {
        PowerStatsSpan span = createPowerStatsSpan(stats);
        if (span == null) {
            return;
        }
        storePowerStatsSpan(span);
    }

    static PowerStatsSpan createPowerStatsSpan(AggregatedPowerStats stats) {
        List<AggregatedPowerStats.ClockUpdate> clockUpdates = stats.getClockUpdates();
        if (clockUpdates.isEmpty()) {
            Slog.w(TAG, "No clock updates in aggregated power stats " + stats);
            return null;
        }

        long monotonicTime = clockUpdates.get(0).monotonicTime;
        long durationSum = 0;
        PowerStatsSpan span = new PowerStatsSpan(monotonicTime);
        for (int i = 0; i < clockUpdates.size(); i++) {
            AggregatedPowerStats.ClockUpdate clockUpdate = clockUpdates.get(i);
            long duration;
            if (i == clockUpdates.size() - 1) {
                duration = stats.getDuration() - durationSum;
            } else {
                duration = clockUpdate.monotonicTime - monotonicTime;
            }
            span.addTimeFrame(clockUpdate.monotonicTime, clockUpdate.currentTime, duration);
            monotonicTime = clockUpdate.monotonicTime;
            durationSum += duration;
        }

        span.addSection(new AggregatedPowerStatsSection(stats));
        return span;
    }

    /**
     * Stores a {@link PowerStatsSpan} containing a single section for the supplied
     * battery usage stats.
     */
    public void storeBatteryUsageStats(long monotonicStartTime,
            BatteryUsageStats batteryUsageStats) {
        PowerStatsSpan span = new PowerStatsSpan(monotonicStartTime);
        span.addTimeFrame(monotonicStartTime, batteryUsageStats.getStatsStartTimestamp(),
                batteryUsageStats.getStatsDuration());
        span.addSection(new BatteryUsageStatsSection(batteryUsageStats));
        storePowerStatsSpan(span);
    }

    /**
     * Creates a file name by formatting the span ID as a 19-digit zero-padded number.
     * This ensures that the lexicographically sorted directory follows the chronological order.
     */
    private File makePowerStatsSpanFilename(long id) {
        return new File(mStoreDir, String.format(Locale.ENGLISH, "%019d", id)
                                   + POWER_STATS_SPAN_FILE_EXTENSION);
    }

    private void maybeClearLegacyStore() {
        File legacyStoreDir = new File(mSystemDir, "battery-usage-stats");
        if (legacyStoreDir.exists()) {
            FileUtils.deleteContentsAndDir(legacyStoreDir);
        }
    }

    private void lockStoreDirectory() {
        mFileLock.lock();

        // Lock the directory from access by other JVMs
        try {
            mLockFile.getParentFile().mkdirs();
            mLockFile.createNewFile();
            mJvmLock = FileChannel.open(mLockFile.toPath(), StandardOpenOption.WRITE).lock();
        } catch (IOException e) {
            Slog.e(TAG, "Cannot lock snapshot directory", e);
        }
    }

    private void unlockStoreDirectory() {
        try {
            mJvmLock.close();
        } catch (IOException e) {
            Slog.e(TAG, "Cannot unlock snapshot directory", e);
        } finally {
            mFileLock.unlock();
        }
    }

    private void removeOldSpansLocked() {
        // Read the directory list into a _sorted_ map.  The alphanumeric ordering
        // corresponds to the historical order of snapshots because the file names
        // are timestamps zero-padded to the same length.
        long totalSize = 0;
        TreeMap<File, Long> mFileSizes = new TreeMap<>();
        for (File file : mStoreDir.listFiles()) {
            final long fileSize = file.length();
            totalSize += fileSize;
            if (file.getName().endsWith(POWER_STATS_SPAN_FILE_EXTENSION)) {
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
                Slog.e(TAG, "Cannot delete power stats span " + file);
            }
            totalSize -= entry.getValue();
            mFileSizes.remove(file);
            mTableOfContents = null;
        }
    }

    /**
     * Deletes all contents from the store.
     */
    public void reset() {
        lockStoreDirectory();
        try {
            for (File file : mStoreDir.listFiles()) {
                if (file.getName().endsWith(POWER_STATS_SPAN_FILE_EXTENSION)) {
                    if (!file.delete()) {
                        Slog.e(TAG, "Cannot delete power stats span " + file);
                    }
                }
            }
            mTableOfContents = List.of();
        } finally {
            unlockStoreDirectory();
        }
    }

    /**
     * Prints the summary of contents of the store: only metadata, but not the actual stored
     * objects.
     */
    public void dumpTableOfContents(IndentingPrintWriter ipw) {
        ipw.println("Power stats store TOC");
        ipw.increaseIndent();
        List<PowerStatsSpan.Metadata> contents = getTableOfContents();
        for (PowerStatsSpan.Metadata metadata : contents) {
            metadata.dump(ipw);
        }
        ipw.decreaseIndent();
    }

    /**
     * Prints the contents of the store.
     */
    public void dump(IndentingPrintWriter ipw) {
        ipw.println("Power stats store");
        ipw.increaseIndent();
        List<PowerStatsSpan.Metadata> contents = getTableOfContents();
        for (PowerStatsSpan.Metadata metadata : contents) {
            PowerStatsSpan span = loadPowerStatsSpan(metadata.getId());
            if (span != null) {
                span.dump(ipw);
            }
        }
        ipw.decreaseIndent();
    }

    private static class DefaultSectionReader implements PowerStatsSpan.SectionReader {
        private final AggregatedPowerStatsConfig mAggregatedPowerStatsConfig;

        DefaultSectionReader(AggregatedPowerStatsConfig aggregatedPowerStatsConfig) {
            mAggregatedPowerStatsConfig = aggregatedPowerStatsConfig;
        }

        @Override
        public PowerStatsSpan.Section read(String sectionType, TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            switch (sectionType) {
                case AggregatedPowerStatsSection.TYPE:
                    return new AggregatedPowerStatsSection(
                            AggregatedPowerStats.createFromXml(parser,
                                    mAggregatedPowerStatsConfig));
                case BatteryUsageStatsSection.TYPE:
                    return new BatteryUsageStatsSection(
                            BatteryUsageStats.createFromXml(parser));
                default:
                    return null;
            }
        }
    }
}
