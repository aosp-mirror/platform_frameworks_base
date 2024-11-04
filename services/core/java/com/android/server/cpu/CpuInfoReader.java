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

import static com.android.server.cpu.CpuMonitorService.DEBUG;
import static com.android.server.cpu.CpuMonitorService.TAG;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.LongSparseLongArray;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reader to read CPU information from proc and sys fs files exposed by the Kernel. */
public final class CpuInfoReader {
    static final int FLAG_CPUSET_CATEGORY_TOP_APP = 1 << 0;
    static final int FLAG_CPUSET_CATEGORY_BACKGROUND = 1 << 1;

    private static final String CPUFREQ_DIR_PATH = "/sys/devices/system/cpu/cpufreq";
    private static final String POLICY_DIR_PREFIX = "policy";
    private static final String RELATED_CPUS_FILE = "related_cpus";
    private static final String AFFECTED_CPUS_FILE = "affected_cpus";
    private static final String CUR_SCALING_FREQ_FILE = "scaling_cur_freq";
    private static final String MAX_SCALING_FREQ_FILE = "scaling_max_freq";
    private static final String TIME_IN_STATE_FILE = "stats/time_in_state";
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
    private static final Pattern TIME_IN_STATE_PATTERN =
            Pattern.compile("(?<freqKHz>[0-9]+)\\s(?<time>[0-9]+)");
    private static final long MILLIS_PER_CLOCK_TICK = 1000L / Os.sysconf(OsConstants._SC_CLK_TCK);
    private static final long MIN_READ_INTERVAL_MILLISECONDS = 500;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"FLAG_CPUSET_CATEGORY_"}, flag = true, value = {
            FLAG_CPUSET_CATEGORY_TOP_APP,
            FLAG_CPUSET_CATEGORY_BACKGROUND
    })
    /** package **/ @interface CpusetCategory{}

    // TODO(b/242722241): Protect updatable variables with a local lock.
    private final long mMinReadIntervalMillis;
    private final SparseIntArray mCpusetCategoriesByCpus = new SparseIntArray();
    private final SparseArray<File> mCpuFreqPolicyDirsById = new SparseArray<>();
    private final SparseArray<StaticPolicyInfo> mStaticPolicyInfoById = new SparseArray<>();
    private final SparseArray<LongSparseLongArray> mTimeInStateByPolicyId = new SparseArray<>();
    private final AtomicBoolean mShouldReadCpusetCategories;

    private File mCpusetDir;
    private File mCpuFreqDir;
    private File mProcStatFile;
    private SparseArray<CpuUsageStats> mCumulativeCpuUsageStats = new SparseArray<>();
    private boolean mIsEnabled;
    private boolean mHasTimeInStateFile;
    private long mLastReadUptimeMillis;
    private SparseArray<CpuInfo> mLastReadCpuInfos;

    public CpuInfoReader() {
        this(new File(CPUSET_DIR_PATH), new File(CPUFREQ_DIR_PATH), new File(PROC_STAT_FILE_PATH),
                MIN_READ_INTERVAL_MILLISECONDS);
    }

    @VisibleForTesting
    CpuInfoReader(File cpusetDir, File cpuFreqDir, File procStatFile, long minReadIntervalMillis) {
        mCpusetDir = cpusetDir;
        mCpuFreqDir = cpuFreqDir;
        mProcStatFile = procStatFile;
        mMinReadIntervalMillis = minReadIntervalMillis;
        mShouldReadCpusetCategories = new AtomicBoolean(true);
    }

    /**
     * Initializes CpuInfoReader and returns a boolean to indicate whether the reader is enabled.
     *
     * <p>Returns {@code true} on success. Otherwise, returns {@code false}.
     */
    public boolean init() {
        if (mCpuFreqPolicyDirsById.size() > 0) {
            Slogf.w(TAG, "Ignoring duplicate CpuInfoReader init request");
            return mIsEnabled;
        }
        File[] policyDirs = mCpuFreqDir.listFiles(
                file -> file.isDirectory() && file.getName().startsWith(POLICY_DIR_PREFIX));
        if (policyDirs == null || policyDirs.length == 0) {
            Slogf.w(TAG, "Missing CPU frequency policy directories at %s",
                    mCpuFreqDir.getAbsolutePath());
            return false;
        }
        populateCpuFreqPolicyDirsById(policyDirs);
        if (mCpuFreqPolicyDirsById.size() == 0) {
            Slogf.e(TAG, "Failed to parse CPU frequency policy directory paths: %s",
                    Arrays.toString(policyDirs));
            return false;
        }
        readStaticPolicyInfo();
        if (mStaticPolicyInfoById.size() == 0) {
            Slogf.e(TAG, "Failed to read static CPU frequency policy info from policy dirs: %s",
                    Arrays.toString(policyDirs));
            return false;
        }
        if (!mProcStatFile.exists()) {
            Slogf.e(TAG, "Missing proc stat file at %s", mProcStatFile.getAbsolutePath());
            return false;
        }
        if (!readCpusetCategories()) {
            Slogf.e(TAG, "Failed to read cpuset information from %s", mCpusetDir.getAbsolutePath());
            return false;
        }
        // Certain CPU performance scaling drivers, such as intel_pstate, perform their own CPU
        // frequency transitions and do not supply this information to the Kernel's cpufreq node.
        // Thus, the `time_in_state` file won't be available on devices running such scaling
        // drivers. Check the presence of this file only once during init and do not throw error
        // when this file is missing. The implementation must accommodate such use cases.
        for (int i = 0; i < mCpuFreqPolicyDirsById.size() && !mHasTimeInStateFile; ++i) {
            // If all the CPU cores on a policy are offline, this file might be missing for the
            // policy. Make sure this file is not available on all policies before marking it as
            // missing.
            mHasTimeInStateFile |= new File(mCpuFreqPolicyDirsById.valueAt(i), TIME_IN_STATE_FILE)
                    .exists();
        }
        if (!mHasTimeInStateFile) {
            Slogf.e(TAG, "Time in state file not available for any cpufreq policy");
        }
        mIsEnabled = true;
        return true;
    }

  public void stopPeriodicCpusetReading() {
        mShouldReadCpusetCategories.set(false);
        if (!readCpusetCategories()) {
            Slogf.e(TAG, "Failed to read cpuset information from %s",
                    mCpusetDir.getAbsolutePath());
            mIsEnabled = false;
        }
    }

    /**
     * Reads CPU information from proc and sys fs files exposed by the Kernel.
     *
     * <p>Returns SparseArray keyed by CPU core ID; {@code null} on error or when disabled.
     */
    @Nullable
    public SparseArray<CpuInfo> readCpuInfos() {
        if (!mIsEnabled) {
            return null;
        }
        long uptimeMillis = SystemClock.uptimeMillis();
        if (mLastReadUptimeMillis > 0
                && uptimeMillis - mLastReadUptimeMillis < mMinReadIntervalMillis) {
            Slogf.w(TAG, "Skipping reading from device and returning the last read CpuInfos. "
                    + "Last read was %d ms ago, min read interval is %d ms",
                    uptimeMillis - mLastReadUptimeMillis, mMinReadIntervalMillis);
            return mLastReadCpuInfos;
        }
        mLastReadUptimeMillis = uptimeMillis;
        mLastReadCpuInfos = null;
        if (mShouldReadCpusetCategories.get() && !readCpusetCategories()) {
            Slogf.e(TAG, "Failed to read cpuset information from %s",
                    mCpusetDir.getAbsolutePath());
            mIsEnabled = false;
            return null;
        }
        SparseArray<CpuUsageStats> cpuUsageStatsByCpus = readLatestCpuUsageStats();
        if (cpuUsageStatsByCpus == null || cpuUsageStatsByCpus.size() == 0) {
            Slogf.e(TAG, "Failed to read latest CPU usage stats");
            return null;
        }
        SparseArray<DynamicPolicyInfo> dynamicPolicyInfoById = readDynamicPolicyInfo();
        if (dynamicPolicyInfoById.size() == 0) {
            Slogf.e(TAG, "Failed to read dynamic policy infos");
            return null;
        }
        SparseArray<CpuInfo> cpuInfoByCpus = new SparseArray<>();
        for (int i = 0; i < mStaticPolicyInfoById.size(); i++) {
            int policyId = mStaticPolicyInfoById.keyAt(i);
            StaticPolicyInfo staticPolicyInfo = mStaticPolicyInfoById.valueAt(i);
            DynamicPolicyInfo dynamicPolicyInfo = dynamicPolicyInfoById.get(policyId);
            if (dynamicPolicyInfo == null) {
                Slogf.w(TAG, "Missing dynamic policy info for policy ID %d", policyId);
                continue;
            }
            if (dynamicPolicyInfo.curCpuFreqKHz == CpuInfo.MISSING_FREQUENCY
                    || dynamicPolicyInfo.maxCpuFreqKHz == CpuInfo.MISSING_FREQUENCY) {
                Slogf.w(TAG, "Current and maximum CPU frequency information mismatch/missing for"
                        + " policy ID %d", policyId);
                continue;
            }
            if (dynamicPolicyInfo.curCpuFreqKHz > dynamicPolicyInfo.maxCpuFreqKHz) {
                Slogf.w(TAG, "Current CPU frequency (%d) is greater than maximum CPU frequency"
                        + " (%d) for policy ID (%d). Skipping CPU frequency policy",
                        dynamicPolicyInfo.curCpuFreqKHz, dynamicPolicyInfo.maxCpuFreqKHz, policyId);
                continue;
            }
            for (int coreIdx = 0; coreIdx < staticPolicyInfo.relatedCpuCores.size(); coreIdx++) {
                int relatedCpuCore = staticPolicyInfo.relatedCpuCores.get(coreIdx);
                CpuInfo prevCpuInfo = cpuInfoByCpus.get(relatedCpuCore);
                if (prevCpuInfo != null) {
                    Slogf.wtf(TAG, "CPU info already available for the CPU core %d",
                            relatedCpuCore);
                    if (prevCpuInfo.isOnline) {
                        continue;
                    }
                }
                int cpusetCategories = mCpusetCategoriesByCpus.get(relatedCpuCore, -1);
                if (cpusetCategories < 0) {
                    Slogf.w(TAG, "Missing cpuset information for the CPU core %d",
                            relatedCpuCore);
                    continue;
                }
                CpuUsageStats usageStats = cpuUsageStatsByCpus.get(relatedCpuCore);
                if (dynamicPolicyInfo.affectedCpuCores.indexOf(relatedCpuCore) < 0) {
                    cpuInfoByCpus.append(relatedCpuCore, new CpuInfo(relatedCpuCore,
                            cpusetCategories, /* isOnline= */false, CpuInfo.MISSING_FREQUENCY,
                            dynamicPolicyInfo.maxCpuFreqKHz, CpuInfo.MISSING_FREQUENCY,
                            usageStats));
                    continue;
                }
                // If a CPU core is online, it must have the usage stats. When the usage stats is
                // missing, drop the core's CPU info.
                if (usageStats == null) {
                    Slogf.w(TAG, "Missing CPU usage information for online CPU core %d",
                            relatedCpuCore);
                    continue;
                }
                CpuInfo cpuInfo = new CpuInfo(relatedCpuCore, cpusetCategories, /* isOnline= */true,
                        dynamicPolicyInfo.curCpuFreqKHz, dynamicPolicyInfo.maxCpuFreqKHz,
                        dynamicPolicyInfo.avgTimeInStateCpuFreqKHz, usageStats);
                cpuInfoByCpus.append(relatedCpuCore, cpuInfo);
                if (DEBUG) {
                    Slogf.d(TAG, "Added %s for CPU core %d", cpuInfo, relatedCpuCore);
                }
            }
        }
        mLastReadCpuInfos = cpuInfoByCpus;
        return cpuInfoByCpus;
    }

    /** Dumps the current state. */
    public void dump(IndentingPrintWriter writer) {
        writer.printf("*%s*\n", getClass().getSimpleName());
        writer.increaseIndent();    // Add intend for the outermost block.

        writer.printf("mCpusetDir = %s\n", mCpusetDir.getAbsolutePath());
        writer.printf("mCpuFreqDir = %s\n", mCpuFreqDir.getAbsolutePath());
        writer.printf("mProcStatFile = %s\n", mProcStatFile.getAbsolutePath());
        writer.printf("mIsEnabled = %s\n", mIsEnabled);
        writer.printf("mHasTimeInStateFile = %s\n", mHasTimeInStateFile);
        writer.printf("mLastReadUptimeMillis = %d\n", mLastReadUptimeMillis);
        writer.printf("mMinReadIntervalMillis = %d\n", mMinReadIntervalMillis);

        writer.printf("Cpuset categories by CPU core:\n");
        writer.increaseIndent();
        for (int i = 0; i < mCpusetCategoriesByCpus.size(); i++) {
            writer.printf("CPU core id = %d, %s\n", mCpusetCategoriesByCpus.keyAt(i),
                    toCpusetCategoriesStr(mCpusetCategoriesByCpus.valueAt(i)));
        }
        writer.decreaseIndent();

        writer.println("Cpu frequency policy directories by policy id:");
        writer.increaseIndent();
        for (int i = 0; i < mCpuFreqPolicyDirsById.size(); i++) {
            writer.printf("Policy id = %d, Dir = %s\n", mCpuFreqPolicyDirsById.keyAt(i),
                    mCpuFreqPolicyDirsById.valueAt(i));
        }
        writer.decreaseIndent();

        writer.println("Static cpu frequency policy infos by policy id:");
        writer.increaseIndent();
        for (int i = 0; i < mStaticPolicyInfoById.size(); i++) {
            writer.printf("Policy id = %d, %s\n", mStaticPolicyInfoById.keyAt(i),
                    mStaticPolicyInfoById.valueAt(i));
        }
        writer.decreaseIndent();

        writer.println("Cpu time in frequency state by policy id:");
        writer.increaseIndent();
        for (int i = 0; i < mTimeInStateByPolicyId.size(); i++) {
            writer.printf("Policy id = %d, Time(millis) in state by CPU frequency(KHz) = %s\n",
                    mTimeInStateByPolicyId.keyAt(i), mTimeInStateByPolicyId.valueAt(i));
        }
        writer.decreaseIndent();

        writer.println("Last read CPU infos:");
        writer.increaseIndent();
        for (int i = 0; i < mLastReadCpuInfos.size(); i++) {
            writer.printf("%s\n", mLastReadCpuInfos.valueAt(i));
        }
        writer.decreaseIndent();

        writer.println("Latest cumulative CPU usage stats by CPU core:");
        writer.increaseIndent();
        for (int i = 0; i < mCumulativeCpuUsageStats.size(); i++) {
            writer.printf("CPU core id = %d, %s\n", mCumulativeCpuUsageStats.keyAt(i),
                    mCumulativeCpuUsageStats.valueAt(i));
        }
        writer.decreaseIndent();

        writer.decreaseIndent();    // Remove intend for the outermost block.
    }

    /**
     * Sets the CPU frequency for testing.
     *
     * <p>Returns {@code true} on success. Otherwise, returns {@code false}.
     */
    @VisibleForTesting
    boolean setCpuFreqDir(File cpuFreqDir) {
        File[] cpuFreqPolicyDirs = cpuFreqDir.listFiles(
                file -> file.isDirectory() && file.getName().startsWith(POLICY_DIR_PREFIX));
        if (cpuFreqPolicyDirs == null || cpuFreqPolicyDirs.length == 0) {
            Slogf.w(TAG, "Failed to set CPU frequency directory. Missing policy directories at %s",
                    cpuFreqDir.getAbsolutePath());
            return false;
        }
        populateCpuFreqPolicyDirsById(cpuFreqPolicyDirs);
        int numCpuFreqPolicyDirs = mCpuFreqPolicyDirsById.size();
        int numStaticPolicyInfos = mStaticPolicyInfoById.size();
        if (numCpuFreqPolicyDirs == 0 || numCpuFreqPolicyDirs != numStaticPolicyInfos) {
            Slogf.e(TAG, "Failed to set CPU frequency directory to %s. Total CPU frequency "
                            + "policies (%d) under new path is either 0 or not equal to initial "
                            + "total CPU frequency policies. Clearing CPU frequency policy "
                            + "directories", cpuFreqDir.getAbsolutePath(), numCpuFreqPolicyDirs,
                    numStaticPolicyInfos);
            mCpuFreqPolicyDirsById.clear();
            return false;
        }
        mCpuFreqDir = cpuFreqDir;
        return true;
    }

    /**
     * Sets the proc stat file for testing.
     *
     * <p>Returns {@code true} on success. Otherwise, returns {@code false}.
     */
    @VisibleForTesting
    boolean setProcStatFile(File procStatFile) {
        if (!procStatFile.exists()) {
            Slogf.e(TAG, "Missing proc stat file at %s", procStatFile.getAbsolutePath());
            return false;
        }
        mProcStatFile = procStatFile;
        return true;
    }

    /**
     * Set the cpuset directory for testing.
     *
     * <p>Returns {@code true} on success. Otherwise, returns {@code false}.
     */
    @VisibleForTesting
    boolean setCpusetDir(File cpusetDir) {
        if (!cpusetDir.exists() && !cpusetDir.isDirectory()) {
            Slogf.e(TAG, "Missing or invalid cpuset directory at %s", cpusetDir.getAbsolutePath());
            return false;
        }
        mCpusetDir = cpusetDir;
        return true;
    }

    private void populateCpuFreqPolicyDirsById(File[] policyDirs) {
        mCpuFreqPolicyDirsById.clear();
        for (int i = 0; i < policyDirs.length; i++) {
            File policyDir = policyDirs[i];
            String policyIdStr = policyDir.getName().substring(POLICY_DIR_PREFIX.length());
            if (policyIdStr.isEmpty()) {
                continue;
            }
            mCpuFreqPolicyDirsById.append(Integer.parseInt(policyIdStr), policyDir);
            if (DEBUG) {
                Slogf.d(TAG, "Cached policy directory %s for policy id %s", policyDir, policyIdStr);
            }
        }
    }

    /**
     * Reads cpuset categories by CPU.
     *
     * <p>The cpusets are read from the cpuset category specific directories
     * under the /dev/cpuset directory. The cpuset categories are subject to change at any point
     * during system bootup, as determined by the init rules specified within the init.rc files.
     * Therefore, it's necessary to read the cpuset categories each time before accessing CPU usage
     * statistics until the system boot completes. Once the boot is complete, the latest changes to
     * the cpuset categories will take a few seconds to propagate. Thus, on boot complete,
     * the periodic reading is stopped with a delay of
     * {@link CpuMonitorService#STOP_PERIODIC_CPUSET_READING_DELAY_MILLISECONDS}.
     *
     * <p>Returns {@code true} on success. Otherwise, returns {@code false}.
     */
    private boolean readCpusetCategories() {
        File[] cpusetDirs = mCpusetDir.listFiles(File::isDirectory);
        if (cpusetDirs == null) {
            Slogf.e(TAG, "Missing cpuset directories at %s", mCpusetDir.getAbsolutePath());
            return false;
        }
        mCpusetCategoriesByCpus.clear();
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
                    // Ignore other cpuset categories because the implementation doesn't support
                    // monitoring CPU availability for other cpusets.
                    continue;
            }
            File cpuCoresFile = new File(dir.getPath(), CPUS_FILE);
            IntArray cpuCores = readCpuCores(cpuCoresFile);
            if (cpuCores == null || cpuCores.size() == 0) {
                Slogf.e(TAG, "Failed to read CPU cores from %s", cpuCoresFile.getAbsolutePath());
                continue;
            }
            for (int j = 0; j < cpuCores.size(); j++) {
                int categories = mCpusetCategoriesByCpus.get(cpuCores.get(j));
                categories |= cpusetCategory;
                mCpusetCategoriesByCpus.append(cpuCores.get(j), categories);
                if (DEBUG) {
                    Slogf.d(TAG, "Mapping CPU core id %d with cpuset categories [%s]",
                            cpuCores.get(j), toCpusetCategoriesStr(categories));
                }
            }
        }
        return mCpusetCategoriesByCpus.size() > 0;
    }

    private void readStaticPolicyInfo() {
        for (int i = 0; i < mCpuFreqPolicyDirsById.size(); i++) {
            int policyId = mCpuFreqPolicyDirsById.keyAt(i);
            File policyDir = mCpuFreqPolicyDirsById.valueAt(i);
            File cpuCoresFile = new File(policyDir, RELATED_CPUS_FILE);
            IntArray relatedCpuCores = readCpuCores(cpuCoresFile);
            if (relatedCpuCores == null || relatedCpuCores.size() == 0) {
                Slogf.e(TAG, "Failed to read related CPU cores from %s",
                        cpuCoresFile.getAbsolutePath());
                continue;
            }
            StaticPolicyInfo staticPolicyInfo = new StaticPolicyInfo(relatedCpuCores);
            mStaticPolicyInfoById.append(policyId, staticPolicyInfo);
            if (DEBUG) {
                Slogf.d(TAG, "Added static policy info %s for policy id %d", staticPolicyInfo,
                        policyId);
            }
        }
    }

    private SparseArray<DynamicPolicyInfo> readDynamicPolicyInfo() {
        SparseArray<DynamicPolicyInfo> dynamicPolicyInfoById = new SparseArray<>();
        for (int i = 0; i < mCpuFreqPolicyDirsById.size(); i++) {
            int policyId = mCpuFreqPolicyDirsById.keyAt(i);
            File policyDir = mCpuFreqPolicyDirsById.valueAt(i);
            long curCpuFreqKHz = readCpuFreqKHz(new File(policyDir, CUR_SCALING_FREQ_FILE));
            if (curCpuFreqKHz == CpuInfo.MISSING_FREQUENCY) {
                Slogf.w(TAG, "Missing current frequency information at %s",
                        policyDir.getAbsolutePath());
                continue;
            }
            long avgTimeInStateCpuFreqKHz = readAvgTimeInStateCpuFrequency(policyId, policyDir);
            File cpuCoresFile = new File(policyDir, AFFECTED_CPUS_FILE);
            IntArray affectedCpuCores = readCpuCores(cpuCoresFile);
            if (affectedCpuCores == null || affectedCpuCores.size() == 0) {
                Slogf.e(TAG, "Failed to read CPU cores from %s", cpuCoresFile.getAbsolutePath());
                continue;
            }
            long maxCpuFreqKHz = readCpuFreqKHz(new File(policyDir, MAX_SCALING_FREQ_FILE));
            if (maxCpuFreqKHz == CpuInfo.MISSING_FREQUENCY) {
                Slogf.w(TAG, "Missing max CPU frequency information at %s",
                        policyDir.getAbsolutePath());
                continue;
            }
            DynamicPolicyInfo dynamicPolicyInfo = new DynamicPolicyInfo(curCpuFreqKHz,
                    maxCpuFreqKHz, avgTimeInStateCpuFreqKHz, affectedCpuCores);
            dynamicPolicyInfoById.append(policyId, dynamicPolicyInfo);
            if (DEBUG) {
                Slogf.d(TAG, "Read dynamic policy info %s for policy id %d", dynamicPolicyInfo,
                        policyId);
            }
        }
        return dynamicPolicyInfoById;
    }

    private long readAvgTimeInStateCpuFrequency(int policyId, File policyDir) {
        LongSparseLongArray latestTimeInState = readTimeInState(policyDir);
        if (latestTimeInState == null || latestTimeInState.size() == 0) {
            return CpuInfo.MISSING_FREQUENCY;
        }
        LongSparseLongArray prevTimeInState = mTimeInStateByPolicyId.get(policyId);
        if (prevTimeInState == null) {
            mTimeInStateByPolicyId.put(policyId, latestTimeInState);
            if (DEBUG) {
                Slogf.d(TAG, "Added aggregated time in state info for policy id %d", policyId);
            }
            return calculateAvgCpuFreq(latestTimeInState);
        }
        LongSparseLongArray deltaTimeInState = calculateDeltaTimeInState(prevTimeInState,
                latestTimeInState);
        mTimeInStateByPolicyId.put(policyId, latestTimeInState);
        if (DEBUG) {
            Slogf.d(TAG, "Added latest delta time in state info for policy id %d", policyId);
        }
        return calculateAvgCpuFreq(deltaTimeInState);
    }

    @Nullable
    private LongSparseLongArray readTimeInState(File policyDir) {
        if (!mHasTimeInStateFile) {
            return null;
        }
        File timeInStateFile = new File(policyDir, TIME_IN_STATE_FILE);
        try {
            List<String> lines = Files.readAllLines(timeInStateFile.toPath());
            if (lines.isEmpty()) {
                Slogf.w(TAG, "Empty time in state file at %s", timeInStateFile.getAbsolutePath());
                return null;
            }
            LongSparseLongArray cpuTimeByFrequencies = new LongSparseLongArray();
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = TIME_IN_STATE_PATTERN.matcher(lines.get(i).trim());
                if (!m.find()) {
                    continue;
                }
                cpuTimeByFrequencies.put(Long.parseLong(m.group("freqKHz")),
                        clockTickStrToMillis(m.group("time")));
            }
            return cpuTimeByFrequencies;
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to read CPU time in state from file: %s",
                    timeInStateFile.getAbsolutePath());
        }
        return null;
    }

    private static long readCpuFreqKHz(File file) {
        if (!file.exists()) {
            Slogf.e(TAG, "CPU frequency file %s doesn't exist", file.getAbsolutePath());
            return CpuInfo.MISSING_FREQUENCY;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            if (!lines.isEmpty()) {
                long frequency = Long.parseLong(lines.get(0).trim());
                return frequency > 0 ? frequency : CpuInfo.MISSING_FREQUENCY;
            }
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to read integer content from file: %s", file.getAbsolutePath());
        }
        return CpuInfo.MISSING_FREQUENCY;
    }

    private static LongSparseLongArray calculateDeltaTimeInState(
            LongSparseLongArray prevTimeInState, LongSparseLongArray latestTimeInState) {
        int numTimeInStateEntries = latestTimeInState.size();
        LongSparseLongArray deltaTimeInState = new LongSparseLongArray(numTimeInStateEntries);
        for (int i = 0; i < numTimeInStateEntries; i++) {
            long freq = latestTimeInState.keyAt(i);
            long durationMillis = latestTimeInState.valueAt(i);
            long prevDurationMillis = prevTimeInState.get(freq);
            deltaTimeInState.put(freq, durationMillis > prevDurationMillis
                    ? (durationMillis - prevDurationMillis) : durationMillis);
        }
        return deltaTimeInState;
    }

    private static long calculateAvgCpuFreq(LongSparseLongArray timeInState) {
        double totalTimeInState = 0;
        for (int i = 0; i < timeInState.size(); i++) {
            totalTimeInState += timeInState.valueAt(i);
        }
        if (totalTimeInState == 0) {
            return CpuInfo.MISSING_FREQUENCY;
        }
        double avgFreqKHz = 0;
        for (int i = 0; i < timeInState.size(); i++) {
            avgFreqKHz += (timeInState.keyAt(i) * timeInState.valueAt(i)) / totalTimeInState;
        }
        return (long) avgFreqKHz;
    }

    /**
     * Reads the list of CPU cores from the given file.
     *
     * <p>Reads CPU cores represented in one of the below formats.
     * <ul>
     * <li> Single core id. Eg: 1
     * <li> Core id range. Eg: 1-4
     * <li> Comma separated values. Eg: 1, 3-5, 7
     * </ul>
     */
    @Nullable
    private static IntArray readCpuCores(File file) {
        if (!file.exists()) {
            Slogf.e(TAG, "Failed to read CPU cores as the file '%s' doesn't exist",
                    file.getAbsolutePath());
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            IntArray cpuCores = new IntArray(0);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] pairs = line.contains(",") ? line.split(",")
                        : line.split(" ");
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
        } catch (NumberFormatException e) {
            Slogf.e(TAG, e, "Failed to read CPU cores from %s due to incorrect file format",
                    file.getAbsolutePath());
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to read CPU cores from %s", file.getAbsolutePath());
        }
        return null;
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
                cpuUsageStats.append(Integer.parseInt(m.group("core")),
                        new CpuUsageStats(clockTickStrToMillis(m.group("userClockTicks")),
                                clockTickStrToMillis(m.group("niceClockTicks")),
                                clockTickStrToMillis(m.group("sysClockTicks")),
                                clockTickStrToMillis(m.group("idleClockTicks")),
                                clockTickStrToMillis(m.group("iowaitClockTicks")),
                                clockTickStrToMillis(m.group("irqClockTicks")),
                                clockTickStrToMillis(m.group("softirqClockTicks")),
                                clockTickStrToMillis(m.group("stealClockTicks")),
                                clockTickStrToMillis(m.group("guestClockTicks")),
                                clockTickStrToMillis(m.group("guestNiceClockTicks"))));
            }
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to read cpu usage stats from %s",
                    mProcStatFile.getAbsolutePath());
        }
        return cpuUsageStats;
    }

    private static long clockTickStrToMillis(String jiffyStr) {
        return Long.parseLong(jiffyStr) * MILLIS_PER_CLOCK_TICK;
    }

    private static String toCpusetCategoriesStr(int cpusetCategories) {
        StringBuilder builder = new StringBuilder();
        if ((cpusetCategories & FLAG_CPUSET_CATEGORY_TOP_APP) != 0) {
            builder.append("FLAG_CPUSET_CATEGORY_TOP_APP");
        }
        if ((cpusetCategories & FLAG_CPUSET_CATEGORY_BACKGROUND) != 0) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append("FLAG_CPUSET_CATEGORY_BACKGROUND");
        }
        return builder.toString();
    }

    /** Contains information for each CPU core on the system. */
    public static final class CpuInfo {
        public static final long MISSING_FREQUENCY = 0;

        public final int cpuCore;
        @CpusetCategory
        public final int cpusetCategories;
        public final boolean isOnline;
        public final long maxCpuFreqKHz;
        // Values in the below fields may be missing when a CPU core is offline.
        public final long curCpuFreqKHz;
        public final long avgTimeInStateCpuFreqKHz;
        @Nullable
        public final CpuUsageStats latestCpuUsageStats;

        private long mNormalizedAvailableCpuFreqKHz;

        CpuInfo(int cpuCore, @CpusetCategory int cpusetCategories, boolean isOnline,
                long curCpuFreqKHz, long maxCpuFreqKHz, long avgTimeInStateCpuFreqKHz,
                CpuUsageStats latestCpuUsageStats) {
            this(cpuCore, cpusetCategories, isOnline, curCpuFreqKHz, maxCpuFreqKHz,
                    avgTimeInStateCpuFreqKHz, /* normalizedAvailableCpuFreqKHz= */ 0,
                    latestCpuUsageStats);
            this.mNormalizedAvailableCpuFreqKHz = computeNormalizedAvailableCpuFreqKHz();
        }

        // Should be used only for testing.
        @VisibleForTesting
        CpuInfo(int cpuCore, @CpusetCategory int cpusetCategories, boolean isOnline,
                long curCpuFreqKHz, long maxCpuFreqKHz, long avgTimeInStateCpuFreqKHz,
                long normalizedAvailableCpuFreqKHz, CpuUsageStats latestCpuUsageStats) {
            this.cpuCore = cpuCore;
            this.cpusetCategories = cpusetCategories;
            this.isOnline = isOnline;
            this.curCpuFreqKHz = curCpuFreqKHz;
            this.maxCpuFreqKHz = maxCpuFreqKHz;
            this.avgTimeInStateCpuFreqKHz = avgTimeInStateCpuFreqKHz;
            this.latestCpuUsageStats = latestCpuUsageStats;
            this.mNormalizedAvailableCpuFreqKHz = normalizedAvailableCpuFreqKHz;
        }

        public long getNormalizedAvailableCpuFreqKHz() {
            return mNormalizedAvailableCpuFreqKHz;
        }

        @Override
        public String toString() {
            return new StringBuilder("CpuInfo{ cpuCore = ").append(cpuCore)
                    .append(", cpusetCategories = [")
                    .append(toCpusetCategoriesStr(cpusetCategories))
                    .append("], isOnline = ").append(isOnline ? "Yes" : "No")
                    .append(", curCpuFreqKHz = ")
                    .append(curCpuFreqKHz == MISSING_FREQUENCY ? "missing" : curCpuFreqKHz)
                    .append(", maxCpuFreqKHz = ")
                    .append(maxCpuFreqKHz == MISSING_FREQUENCY ? "missing" : maxCpuFreqKHz)
                    .append(", avgTimeInStateCpuFreqKHz = ")
                    .append(avgTimeInStateCpuFreqKHz == MISSING_FREQUENCY ? "missing"
                            : avgTimeInStateCpuFreqKHz)
                    .append(", latestCpuUsageStats = ").append(latestCpuUsageStats)
                    .append(", mNormalizedAvailableCpuFreqKHz = ")
                    .append(mNormalizedAvailableCpuFreqKHz)
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
                    && isOnline == other.isOnline  && curCpuFreqKHz == other.curCpuFreqKHz
                    && maxCpuFreqKHz == other.maxCpuFreqKHz
                    && avgTimeInStateCpuFreqKHz == other.avgTimeInStateCpuFreqKHz
                    && latestCpuUsageStats.equals(other.latestCpuUsageStats)
                    && mNormalizedAvailableCpuFreqKHz == other.mNormalizedAvailableCpuFreqKHz;
        }

        @Override
        public int hashCode() {
            return Objects.hash(cpuCore, cpusetCategories, isOnline, curCpuFreqKHz, maxCpuFreqKHz,
                    avgTimeInStateCpuFreqKHz, latestCpuUsageStats, mNormalizedAvailableCpuFreqKHz);
        }

        private long computeNormalizedAvailableCpuFreqKHz() {
            if (!isOnline) {
                return MISSING_FREQUENCY;
            }
            long totalTimeMillis = latestCpuUsageStats.getTotalTimeMillis();
            if (totalTimeMillis == 0) {
                Slogf.wtf(TAG, "Total CPU time millis is 0. This shouldn't happen unless stats are"
                        + " polled too frequently");
                return MISSING_FREQUENCY;
            }
            double nonIdlePercent = 100.0 * (totalTimeMillis
                    - (double) latestCpuUsageStats.idleTimeMillis) / totalTimeMillis;
            long curFreqKHz = avgTimeInStateCpuFreqKHz == MISSING_FREQUENCY
                    ? curCpuFreqKHz : avgTimeInStateCpuFreqKHz;
            double availablePercent = 100.0 - (nonIdlePercent * curFreqKHz / maxCpuFreqKHz);
            return (long) ((availablePercent * maxCpuFreqKHz) / 100.0);
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

        public long getTotalTimeMillis() {
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

    private static final class StaticPolicyInfo {
        public final IntArray relatedCpuCores;

        StaticPolicyInfo(IntArray relatedCpuCores) {
            this.relatedCpuCores = relatedCpuCores;
        }

        @Override
        public String toString() {
            return "StaticPolicyInfo{relatedCpuCores = " + relatedCpuCores + '}';
        }
    }

    private static final class DynamicPolicyInfo {
        public final long curCpuFreqKHz;
        public final long maxCpuFreqKHz;
        public final long avgTimeInStateCpuFreqKHz;
        public final IntArray affectedCpuCores;

        DynamicPolicyInfo(long curCpuFreqKHz, long maxCpuFreqKHz, long avgTimeInStateCpuFreqKHz,
                IntArray affectedCpuCores) {
            this.curCpuFreqKHz = curCpuFreqKHz;
            this.maxCpuFreqKHz = maxCpuFreqKHz;
            this.avgTimeInStateCpuFreqKHz = avgTimeInStateCpuFreqKHz;
            this.affectedCpuCores = affectedCpuCores;
        }

        @Override
        public String toString() {
            return "DynamicPolicyInfo{curCpuFreqKHz = " + curCpuFreqKHz
                    + ", maxCpuFreqKHz = " + maxCpuFreqKHz
                    + ", avgTimeInStateCpuFreqKHz = " + avgTimeInStateCpuFreqKHz
                    + ", affectedCpuCores = " + affectedCpuCores + '}';
        }
    }
}
