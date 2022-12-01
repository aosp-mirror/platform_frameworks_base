/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.cpu;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.system.Os;
import android.system.OsConstants;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reader to read CPU information from proc and sys fs files exposed by the Kernel. */
public final class CpuInfoReader {
    static final String TAG = CpuInfoReader.class.getSimpleName();
    static final int FLAG_CPUSET_CATEGORY_TOP_APP = 1 << 0;
    static final int FLAG_CPUSET_CATEGORY_BACKGROUND = 1 << 1;

    private static final String CPUFREQ_DIR_PATH = "/sys/devices/system/cpu/cpufreq";
    private static final String POLICY_DIR_PREFIX = "policy";
    private static final String RELATED_CPUS_FILE = "related_cpus";
    private static final String MAX_CPUFREQ_FILE = "cpuinfo_max_freq";
    private static final String MAX_SCALING_FREQ_FILE = "scaling_max_freq";
    private static final String CPUSET_DIR_PATH = "/dev/cpuset";
    private static final String CPUSET_TOP_APP_DIR = "top-app";
    private static final String CPUSET_BACKGROUND_DIR = "background";
    private static final String CPUS_FILE = "cpus";
    private static final String PROC_STAT_FILE_PATH = "/proc/stat";
    private static final Pattern PROC_STAT_PATTERN =
            Pattern.compile("cpu(?<core>[0-9]+)\\s(?<userClockTicks>[0-9]+)\\s"
                    + "(?<niceClockTicks>[0-9]+)\\s(?<sysClockTicks>[0-9]+)\\s"
                    + "(?<idleClockTicks>[0-9]+)\\s(?<iowaitClockTicks>[0-9]+)\\s"
                    + "(?<irqClockTicks>[0-9]+)\\s(?<softirqClockTicks>[0-9]+)\\s"
                    + "(?<stealClockTicks>[0-9]+)\\s(?<guestClockTicks>[0-9]+)\\s"
                    + "(?<guestNiceClockTicks>[0-9]+)");
    private static final long MILLIS_PER_JIFFY = 1000L / Os.sysconf(OsConstants._SC_CLK_TCK);

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"FLAG_CPUSET_CATEGORY_"}, flag = true, value = {
            FLAG_CPUSET_CATEGORY_TOP_APP,
            FLAG_CPUSET_CATEGORY_BACKGROUND
    })
    private @interface CpusetCategory{}

    private final File mCpusetDir;
    private final File mCpuFreqDir;
    private final File mProcStatFile;
    private final SparseIntArray mCpusetCategoriesByCpus = new SparseIntArray();
    private final SparseArray<Long> mMaxCpuFrequenciesByCpus = new SparseArray<>();

    private File[] mCpuFreqPolicyDirs;
    private SparseArray<CpuUsageStats> mCumulativeCpuUsageStats = new SparseArray<>();
    private boolean mIsEnabled;

    public CpuInfoReader() {
        this(new File(CPUSET_DIR_PATH), new File(CPUFREQ_DIR_PATH), new File(PROC_STAT_FILE_PATH));
    }

    @VisibleForTesting
    CpuInfoReader(File cpusetDir, File cpuFreqDir, File procStatFile) {
        mCpusetDir = cpusetDir;
        mCpuFreqDir = cpuFreqDir;
        mProcStatFile = procStatFile;
    }

    /** Inits CpuInfoReader and returns a boolean to indicate whether the reader is enabled. */
    public boolean init() {
        mCpuFreqPolicyDirs = mCpuFreqDir.listFiles(
                file -> file.isDirectory() && file.getName().startsWith(POLICY_DIR_PREFIX));
        if (mCpuFreqPolicyDirs == null || mCpuFreqPolicyDirs.length == 0) {
            Slogf.w(TAG, "Missing CPU frequency policy directories at %s",
                    mCpuFreqDir.getAbsolutePath());
            return false;
        }
        if (!mProcStatFile.exists()) {
            Slogf.e(TAG, "Missing proc stat file at %s", mProcStatFile.getAbsolutePath());
            return false;
        }
        readCpusetCategories();
        if (mCpusetCategoriesByCpus.size() == 0) {
            Slogf.e(TAG, "Failed to read cpuset information read from %s",
                    mCpusetDir.getAbsolutePath());
            return false;
        }
        readMaxCpuFrequencies();
        if (mMaxCpuFrequenciesByCpus.size() == 0) {
            Slogf.e(TAG, "Failed to read max CPU frequencies from policy directories at %s",
                    mCpuFreqDir.getAbsolutePath());
            return false;
        }
        mIsEnabled = true;
        return true;
    }

    /** Reads CPU information from proc and sys fs files exposed by the Kernel. */
    public List<CpuInfo> readCpuInfos() {
        if (!mIsEnabled) {
            return Collections.emptyList();
        }
        SparseArray<CpuUsageStats> latestCpuUsageStats = readLatestCpuUsageStats();
        if (latestCpuUsageStats == null) {
            Slogf.e(TAG, "Failed to read latest CPU usage stats");
            return Collections.emptyList();
        }
        // TODO(b/217422127): Read current CPU frequencies and populate the CpuInfo.
        return Collections.emptyList();
    }

    private void readCpusetCategories() {
        File[] cpusetDirs = mCpusetDir.listFiles(File::isDirectory);
        if (cpusetDirs == null) {
            Slogf.e(TAG, "Missing cpuset directories at %s", mCpusetDir.getAbsolutePath());
            return;
        }
        for (int i = 0; i < cpusetDirs.length; i++) {
            File dir = cpusetDirs[i];
            @CpusetCategory int cpusetCategory;
            switch (dir.getName()) {
                case CPUSET_TOP_APP_DIR:
                    cpusetCategory = FLAG_CPUSET_CATEGORY_TOP_APP;
                    break;
                case CPUSET_BACKGROUND_DIR:
                    cpusetCategory = FLAG_CPUSET_CATEGORY_BACKGROUND;
                    break;
                default:
                    continue;
            }
            File cpuCoresFile = new File(dir.getPath(), CPUS_FILE);
            List<Integer> cpuCores = readCpuCores(cpuCoresFile);
            if (cpuCores.isEmpty()) {
                Slogf.e(TAG, "Failed to read CPU cores from %s", cpuCoresFile.getAbsolutePath());
                continue;
            }
            for (int j = 0; j < cpuCores.size(); j++) {
                int categories = mCpusetCategoriesByCpus.get(cpuCores.get(j));
                categories |= cpusetCategory;
                mCpusetCategoriesByCpus.append(cpuCores.get(j), categories);
            }
        }
    }

    private void readMaxCpuFrequencies() {
        for (int i = 0; i < mCpuFreqPolicyDirs.length; i++) {
            File policyDir = mCpuFreqPolicyDirs[i];
            long maxCpuFreqKHz = readMaxCpuFrequency(policyDir);
            if (maxCpuFreqKHz == 0) {
                Slogf.w(TAG, "Invalid max CPU frequency read from %s", policyDir.getAbsolutePath());
                continue;
            }
            File cpuCoresFile = new File(policyDir, RELATED_CPUS_FILE);
            List<Integer> cpuCores = readCpuCores(cpuCoresFile);
            if (cpuCores.isEmpty()) {
                Slogf.e(TAG, "Failed to read CPU cores from %s", cpuCoresFile.getAbsolutePath());
                continue;
            }
            for (int j = 0; j < cpuCores.size(); j++) {
                mMaxCpuFrequenciesByCpus.append(cpuCores.get(j), maxCpuFreqKHz);
            }
        }
    }

    private long readMaxCpuFrequency(File policyDir) {
        long curCpuFreqKHz = readCpuFreqKHz(new File(policyDir, MAX_CPUFREQ_FILE));
        return curCpuFreqKHz > 0 ? curCpuFreqKHz
                : readCpuFreqKHz(new File(policyDir, MAX_SCALING_FREQ_FILE));
    }

    private static long readCpuFreqKHz(File file) {
        if (!file.exists()) {
            Slogf.e(TAG, "CPU frequency file %s doesn't exist", file.getAbsolutePath());
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            if (!lines.isEmpty()) {
                long frequency = Long.parseLong(lines.get(0).trim());
                return frequency > 0 ? frequency : 0;
            }
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to read integer content from file: %s", file.getAbsolutePath());
        }
        return 0;
    }

    /**
     * Reads the list of CPU cores from the given file.
     *
     * Reads CPU cores represented in one of the below formats.
     * <ul>
     * <li> Single core id. Eg: 1
     * <li> Core id range. Eg: 1-4
     * <li> Comma separated values. Eg: 1, 3-5, 7
     * </ul>
     */
    private static List<Integer> readCpuCores(File file) {
        if (!file.exists()) {
            Slogf.e(TAG, "Failed to read CPU cores as the file '%s' doesn't exist",
                    file.getAbsolutePath());
            return Collections.emptyList();
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            List<Integer> cpuCores = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String[] pairs = lines.get(i).trim().split(",");
                for (int j = 0; j < pairs.length; j++) {
                    String[] minMaxPairs = pairs[j].split("-");
                    if (minMaxPairs.length >= 2) {
                        int min = Integer.parseInt(minMaxPairs[0]);
                        int max = Integer.parseInt(minMaxPairs[1]);
                        if (min > max) {
                            continue;
                        }
                        for (int id = min; id <= max; id++) {
                            cpuCores.add(id);
                        }
                    } else if (minMaxPairs.length == 1) {
                        cpuCores.add(Integer.parseInt(minMaxPairs[0]));
                    } else {
                        Slogf.w(TAG, "Invalid CPU core range format %s", pairs[j]);
                    }
                }
            }
            return cpuCores;
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to read CPU cores from %s", file.getAbsolutePath());
        }
        return Collections.emptyList();
    }

    @Nullable
    private SparseArray<CpuUsageStats> readLatestCpuUsageStats() {
        SparseArray<CpuUsageStats> cumulativeCpuUsageStats = readCumulativeCpuUsageStats();
        if (cumulativeCpuUsageStats.size() == 0) {
            Slogf.e(TAG, "Failed to read cumulative CPU usage stats");
            return null;
        }
        SparseArray<CpuUsageStats> deltaCpuUsageStats = new SparseArray();
        for (int i = 0; i < cumulativeCpuUsageStats.size(); i++) {
            int cpu = cumulativeCpuUsageStats.keyAt(i);
            CpuUsageStats newStats = cumulativeCpuUsageStats.valueAt(i);
            CpuUsageStats oldStats = mCumulativeCpuUsageStats.get(cpu);
            deltaCpuUsageStats.append(cpu, oldStats == null ? newStats : newStats.delta(oldStats));
        }
        mCumulativeCpuUsageStats = cumulativeCpuUsageStats;
        return deltaCpuUsageStats;
    }

    private SparseArray<CpuUsageStats> readCumulativeCpuUsageStats() {
        SparseArray<CpuUsageStats> cpuUsageStats = new SparseArray<>();
        try {
            List<String> lines = Files.readAllLines(mProcStatFile.toPath());
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = PROC_STAT_PATTERN.matcher(lines.get(i).trim());
                if (!m.find()) {
                    continue;
                }
                cpuUsageStats.append(Integer.parseInt(Objects.requireNonNull(m.group("core"))),
                        new CpuUsageStats(jiffyStrToMillis(m.group("userClockTicks")),
                                jiffyStrToMillis(m.group("niceClockTicks")),
                                jiffyStrToMillis(m.group("sysClockTicks")),
                                jiffyStrToMillis(m.group("idleClockTicks")),
                                jiffyStrToMillis(m.group("iowaitClockTicks")),
                                jiffyStrToMillis(m.group("irqClockTicks")),
                                jiffyStrToMillis(m.group("softirqClockTicks")),
                                jiffyStrToMillis(m.group("stealClockTicks")),
                                jiffyStrToMillis(m.group("guestClockTicks")),
                                jiffyStrToMillis(m.group("guestNiceClockTicks"))));
            }
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to read cpu usage stats from %s",
                    mProcStatFile.getAbsolutePath());
        }
        return cpuUsageStats;
    }

    private static long jiffyStrToMillis(String jiffyStr) {
        return Long.parseLong(Objects.requireNonNull(jiffyStr)) * MILLIS_PER_JIFFY;
    }

    /** Contains information for each CPU core on the system. */
    public static final class CpuInfo {
        public final int cpuCore;
        public final @CpusetCategory int cpusetCategories;
        public final long curCpuFreqKHz;
        public final long maxCpuFreqKHz;
        public final CpuUsageStats latestCpuUsageStats;

        CpuInfo(int cpuCore, @CpusetCategory int cpusetCategories, long curCpuFreqKHz,
                long maxCpuFreqKHz, CpuUsageStats latestCpuUsageStats) {
            this.cpuCore = cpuCore;
            this.cpusetCategories = cpusetCategories;
            this.curCpuFreqKHz = curCpuFreqKHz;
            this.maxCpuFreqKHz = maxCpuFreqKHz;
            this.latestCpuUsageStats = latestCpuUsageStats;
        }

        @Override
        public String toString() {
            return new StringBuilder("CpuInfo{ cpuCore = ").append(cpuCore)
                    .append(", cpusetCategories = ").append(cpusetCategories)
                    .append(", curCpuFreqKHz = ").append(curCpuFreqKHz)
                    .append(", maxCpuFreqKHz = ").append(maxCpuFreqKHz)
                    .append(", latestCpuUsageStats = ").append(latestCpuUsageStats)
                    .append(" }").toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CpuInfo)) {
                return false;
            }
            CpuInfo other = (CpuInfo) obj;
            return cpuCore == other.cpuCore && cpusetCategories == other.cpusetCategories
                    && curCpuFreqKHz == other.curCpuFreqKHz
                    && maxCpuFreqKHz == other.maxCpuFreqKHz
                    && latestCpuUsageStats.equals(other.latestCpuUsageStats);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cpuCore, cpusetCategories, curCpuFreqKHz, maxCpuFreqKHz,
                    latestCpuUsageStats);
        }
    }

    /** CPU time spent in different modes. */
    public static final class CpuUsageStats {
        public final long userTimeMillis;
        public final long niceTimeMillis;
        public final long systemTimeMillis;
        public final long idleTimeMillis;
        public final long iowaitTimeMillis;
        public final long irqTimeMillis;
        public final long softirqTimeMillis;
        public final long stealTimeMillis;
        public final long guestTimeMillis;
        public final long guestNiceTimeMillis;

        public CpuUsageStats(long userTimeMillis, long niceTimeMillis, long systemTimeMillis,
                long idleTimeMillis, long iowaitTimeMillis, long irqTimeMillis,
                long softirqTimeMillis, long stealTimeMillis, long guestTimeMillis,
                long guestNiceTimeMillis) {
            this.userTimeMillis = userTimeMillis;
            this.niceTimeMillis = niceTimeMillis;
            this.systemTimeMillis = systemTimeMillis;
            this.idleTimeMillis = idleTimeMillis;
            this.iowaitTimeMillis = iowaitTimeMillis;
            this.irqTimeMillis = irqTimeMillis;
            this.softirqTimeMillis = softirqTimeMillis;
            this.stealTimeMillis = stealTimeMillis;
            this.guestTimeMillis = guestTimeMillis;
            this.guestNiceTimeMillis = guestNiceTimeMillis;
        }

        public long getTotalTime() {
            return userTimeMillis + niceTimeMillis + systemTimeMillis + idleTimeMillis
                    + iowaitTimeMillis + irqTimeMillis + softirqTimeMillis + stealTimeMillis
                    + guestTimeMillis + guestNiceTimeMillis;
        }

        @Override
        public String toString() {
            return new StringBuilder("CpuUsageStats{ userTimeMillis = ")
                    .append(userTimeMillis)
                    .append(", niceTimeMillis = ").append(niceTimeMillis)
                    .append(", systemTimeMillis = ").append(systemTimeMillis)
                    .append(", idleTimeMillis = ").append(idleTimeMillis)
                    .append(", iowaitTimeMillis = ").append(iowaitTimeMillis)
                    .append(", irqTimeMillis = ").append(irqTimeMillis)
                    .append(", softirqTimeMillis = ").append(softirqTimeMillis)
                    .append(", stealTimeMillis = ").append(stealTimeMillis)
                    .append(", guestTimeMillis = ").append(guestTimeMillis)
                    .append(", guestNiceTimeMillis = ").append(guestNiceTimeMillis)
                    .append(" }").toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CpuUsageStats)) {
                return false;
            }
            CpuUsageStats other = (CpuUsageStats) obj;
            return userTimeMillis == other.userTimeMillis && niceTimeMillis == other.niceTimeMillis
                    && systemTimeMillis == other.systemTimeMillis
                    && idleTimeMillis == other.idleTimeMillis
                    && iowaitTimeMillis == other.iowaitTimeMillis
                    && irqTimeMillis == other.irqTimeMillis
                    && softirqTimeMillis == other.softirqTimeMillis
                    && stealTimeMillis == other.stealTimeMillis
                    && guestTimeMillis == other.guestTimeMillis
                    && guestNiceTimeMillis == other.guestNiceTimeMillis;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userTimeMillis, niceTimeMillis, systemTimeMillis, idleTimeMillis,
                    iowaitTimeMillis, irqTimeMillis, softirqTimeMillis, stealTimeMillis,
                    guestTimeMillis,
                    guestNiceTimeMillis);
        }

        CpuUsageStats delta(CpuUsageStats rhs) {
            return new CpuUsageStats(diff(userTimeMillis, rhs.userTimeMillis),
                    diff(niceTimeMillis, rhs.niceTimeMillis),
                    diff(systemTimeMillis, rhs.systemTimeMillis),
                    diff(idleTimeMillis, rhs.idleTimeMillis),
                    diff(iowaitTimeMillis, rhs.iowaitTimeMillis),
                    diff(irqTimeMillis, rhs.irqTimeMillis),
                    diff(softirqTimeMillis, rhs.softirqTimeMillis),
                    diff(stealTimeMillis, rhs.stealTimeMillis),
                    diff(guestTimeMillis, rhs.guestTimeMillis),
                    diff(guestNiceTimeMillis, rhs.guestNiceTimeMillis));
        }

        private static long diff(long lhs, long rhs) {
            return lhs > rhs ? lhs - rhs : 0;
        }
    }
}
