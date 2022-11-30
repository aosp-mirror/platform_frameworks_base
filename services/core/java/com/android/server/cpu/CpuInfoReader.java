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
        return true;
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
}
