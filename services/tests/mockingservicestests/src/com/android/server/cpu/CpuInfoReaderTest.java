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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.cpu.CpuInfoReader.CpuInfo.MISSING_FREQUENCY;
import static com.android.server.cpu.CpuInfoReader.FLAG_CPUSET_CATEGORY_BACKGROUND;
import static com.android.server.cpu.CpuInfoReader.FLAG_CPUSET_CATEGORY_TOP_APP;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.ExpectableTestCase;

import libcore.io.Streams;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** This class contains unit tests for the {@link CpuInfoReader}. */
public final class CpuInfoReaderTest extends ExpectableTestCase {
    private static final String TAG = CpuInfoReaderTest.class.getSimpleName();
    private static final String ROOT_DIR_NAME = "CpuInfoReaderTest";
    private static final String VALID_CPUSET_DIR = "valid_cpuset";
    private static final String VALID_CPUSET_WITH_EMPTY_CPUS = "valid_cpuset_with_empty_cpus";
    private static final String VALID_CPUFREQ_WITH_EMPTY_AFFECTED_CPUS =
            "valid_cpufreq_with_empty_affected_cpus";
    private static final String VALID_CPUFREQ_WITH_EMPTY_RELATED_CPUS =
            "valid_cpufreq_with_empty_related_cpus";
    private static final String VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR =
            "valid_cpufreq_with_time_in_state";
    private static final String VALID_CPUFREQ_WITH_TIME_IN_STATE_2_DIR =
            "valid_cpufreq_with_time_in_state_2";
    private static final String VALID_CPUFREQ_WITHOUT_TIME_IN_STATE_DIR =
            "valid_cpufreq_without_time_in_state";
    private static final String VALID_CPUFREQ_WITHOUT_TIME_IN_STATE_2_DIR =
            "valid_cpufreq_without_time_in_state_2";
    private static final String VALID_PROC_STAT = "valid_proc_stat";
    private static final String VALID_PROC_STAT_2 = "valid_proc_stat_2";
    private static final String CORRUPTED_CPUFREQ_DIR = "corrupted_cpufreq";
    private static final String CORRUPTED_CPUSET_DIR = "corrupted_cpuset";
    private static final String CORRUPTED_PROC_STAT = "corrupted_proc_stat";
    private static final String EMPTY_DIR = "empty_dir";
    private static final String EMPTY_FILE = "empty_file";

    private final Context mContext = getInstrumentation().getTargetContext();
    private final File mCacheRoot = new File(mContext.getCacheDir(), ROOT_DIR_NAME);
    private final AssetManager mAssetManager = mContext.getAssets();

    @Before
    public void setUp() throws Exception {
        copyAssets(ROOT_DIR_NAME, mContext.getCacheDir());
        assertWithMessage("Cache root dir %s exists", mCacheRoot.getAbsolutePath())
                .that(mCacheRoot.exists()).isTrue();
    }

    @After
    public void tearDown() throws Exception {
        if (!deleteDirectory(mCacheRoot)) {
            Log.e(TAG, "Failed to delete cache root directory " + mCacheRoot.getAbsolutePath());
        }
    }

    @Test
    public void testReadCpuInfoWithTimeInState() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR), getCacheFile(VALID_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();
        expectedCpuInfos.append(0, new CpuInfoReader.CpuInfo(/* cpuCore= */ 0,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_230_000,
                /* maxCpuFreqKHz= */ 2_500_000, /* avgTimeInStateCpuFreqKHz= */ 488_095,
                /* normalizedAvailableCpuFreqKHz= */ 2_402_267,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_249_610,
                        /* niceTimeMillis= */ 7_950_930, /* systemTimeMillis= */ 52_227_050,
                        /* idleTimeMillis= */ 409_036_950, /* iowaitTimeMillis= */ 1_322_810,
                        /* irqTimeMillis= */ 8_146_740, /* softirqTimeMillis= */ 428_970,
                        /* stealTimeMillis= */ 81_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_450_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ 502_380,
                /* normalizedAvailableCpuFreqKHz= */ 2_693_525,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_949_280,
                        /* niceTimeMillis= */ 7_799_450, /* systemTimeMillis= */ 54_004_020,
                        /* idleTimeMillis= */ 402_707_120, /* iowaitTimeMillis= */ 1_186_960,
                        /* irqTimeMillis= */ 14_786_940, /* softirqTimeMillis= */ 1_498_130,
                        /* stealTimeMillis= */ 78_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(2, new CpuInfoReader.CpuInfo(/* cpuCore= */ 2,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ 464_285,
                /* normalizedAvailableCpuFreqKHz= */ 1_901_608,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_959_280,
                        /* niceTimeMillis= */ 7_789_450, /* systemTimeMillis= */ 54_014_020,
                        /* idleTimeMillis= */ 402_717_120, /* iowaitTimeMillis= */ 1_166_960,
                        /* irqTimeMillis= */ 14_796_940, /* softirqTimeMillis= */ 1_478_130,
                        /* stealTimeMillis= */ 88_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(3, new CpuInfoReader.CpuInfo(/* cpuCore= */ 3,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ 464_285,
                /* normalizedAvailableCpuFreqKHz= */ 1_907_125,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_349_610,
                        /* niceTimeMillis= */ 7_850_930, /* systemTimeMillis= */ 52_127_050,
                        /* idleTimeMillis= */ 409_136_950, /* iowaitTimeMillis= */ 1_332_810,
                        /* irqTimeMillis= */ 8_136_740, /* softirqTimeMillis= */ 438_970,
                        /* stealTimeMillis= */ 71_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos first snapshot", expectedCpuInfos, actualCpuInfos);

        cpuInfoReader.setCpuFreqDir(getCacheFile(VALID_CPUFREQ_WITH_TIME_IN_STATE_2_DIR));
        cpuInfoReader.setProcStatFile(getCacheFile(VALID_PROC_STAT_2));

        actualCpuInfos = cpuInfoReader.readCpuInfos();

        expectedCpuInfos.clear();
        expectedCpuInfos.append(0, new CpuInfoReader.CpuInfo(/* cpuCore= */ 0,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_600_000, /* avgTimeInStateCpuFreqKHz= */ 419_354,
                /* normalizedAvailableCpuFreqKHz= */ 2_525_919,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 10_000_000,
                        /* niceTimeMillis= */ 1_000_000, /* systemTimeMillis= */ 10_000_000,
                        /* idleTimeMillis= */ 110_000_000, /* iowaitTimeMillis= */ 1_100_000,
                        /* irqTimeMillis= */ 1_400_000, /* softirqTimeMillis= */ 80_000,
                        /* stealTimeMillis= */ 21_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 2_800_000,
                /* maxCpuFreqKHz= */ 2_900_000, /* avgTimeInStateCpuFreqKHz= */ 429_032,
                /* normalizedAvailableCpuFreqKHz= */ 2_503_009,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 900_000,
                        /* niceTimeMillis= */ 1_000_000, /* systemTimeMillis= */ 10_000_000,
                        /* idleTimeMillis= */ 1_000_000, /* iowaitTimeMillis= */ 90_000,
                        /* irqTimeMillis= */ 200_000, /* softirqTimeMillis= */ 100_000,
                        /* stealTimeMillis= */ 100_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(2, new CpuInfoReader.CpuInfo(/* cpuCore= */ 2,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ true, /* curCpuFreqKHz= */ 2_000_000,
                /* maxCpuFreqKHz= */ 2_100_000, /* avgTimeInStateCpuFreqKHz= */ 403_225,
                /* normalizedAvailableCpuFreqKHz= */ 1_788_209,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 10_000_000,
                        /* niceTimeMillis= */ 2_000_000, /* systemTimeMillis= */ 0,
                        /* idleTimeMillis= */ 10_000_000, /* iowaitTimeMillis= */ 1_000_000,
                        /* irqTimeMillis= */ 20_000_000, /* softirqTimeMillis= */ 1_000_000,
                        /* stealTimeMillis= */ 100_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(3, new CpuInfoReader.CpuInfo(/* cpuCore= */ 3,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ false, /* curCpuFreqKHz= */ MISSING_FREQUENCY,
                /* maxCpuFreqKHz= */ 2_100_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ MISSING_FREQUENCY,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 2_000_000,
                        /* niceTimeMillis= */ 1_000_000, /* systemTimeMillis= */ 1_000_000,
                        /* idleTimeMillis= */ 100_000, /* iowaitTimeMillis= */ 100_000,
                        /* irqTimeMillis= */ 100_000, /* softirqTimeMillis= */ 1_000_000,
                        /* stealTimeMillis= */ 1_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos second snapshot", expectedCpuInfos, actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithoutTimeInState() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITHOUT_TIME_IN_STATE_DIR),
                getCacheFile(VALID_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();
        expectedCpuInfos.append(0, new CpuInfoReader.CpuInfo(/* cpuCore= */ 0,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_230_000,
                /* maxCpuFreqKHz= */ 2_500_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 2_253_713,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_249_610,
                        /* niceTimeMillis= */ 7_950_930, /* systemTimeMillis= */ 52_227_050,
                        /* idleTimeMillis= */ 409_036_950, /* iowaitTimeMillis= */ 1_322_810,
                        /* irqTimeMillis= */ 8_146_740, /* softirqTimeMillis= */ 428_970,
                        /* stealTimeMillis= */ 81_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_450_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 2_492_687,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_949_280,
                        /* niceTimeMillis= */ 7_799_450, /* systemTimeMillis= */ 54_004_020,
                        /* idleTimeMillis= */ 402_707_120, /* iowaitTimeMillis= */ 1_186_960,
                        /* irqTimeMillis= */ 14_786_940, /* softirqTimeMillis= */ 1_498_130,
                        /* stealTimeMillis= */ 78_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(2, new CpuInfoReader.CpuInfo(/* cpuCore= */ 2,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 1_788_079,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_959_280,
                        /* niceTimeMillis= */ 7_789_450, /* systemTimeMillis= */ 54_014_020,
                        /* idleTimeMillis= */ 402_717_120, /* iowaitTimeMillis= */ 1_166_960,
                        /* irqTimeMillis= */ 14_796_940, /* softirqTimeMillis= */ 1_478_130,
                        /* stealTimeMillis= */ 88_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(3, new CpuInfoReader.CpuInfo(/* cpuCore= */ 3,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 1_799_962,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_349_610,
                        /* niceTimeMillis= */ 7_850_930, /* systemTimeMillis= */ 52_127_050,
                        /* idleTimeMillis= */ 409_136_950, /* iowaitTimeMillis= */ 1_332_810,
                        /* irqTimeMillis= */ 8_136_740, /* softirqTimeMillis= */ 438_970,
                        /* stealTimeMillis= */ 71_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos first snapshot without time_in_state file", expectedCpuInfos,
                actualCpuInfos);

        cpuInfoReader.setCpuFreqDir(getCacheFile(VALID_CPUFREQ_WITHOUT_TIME_IN_STATE_2_DIR));
        cpuInfoReader.setProcStatFile(getCacheFile(VALID_PROC_STAT_2));

        actualCpuInfos = cpuInfoReader.readCpuInfos();

        expectedCpuInfos.clear();
        expectedCpuInfos.append(0, new CpuInfoReader.CpuInfo(/* cpuCore= */ 0,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_500_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 2323347,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 10_000_000,
                        /* niceTimeMillis= */ 1_000_000, /* systemTimeMillis= */ 10_000_000,
                        /* idleTimeMillis= */ 110_000_000, /* iowaitTimeMillis= */ 1_100_000,
                        /* irqTimeMillis= */ 1_400_000, /* softirqTimeMillis= */ 80_000,
                        /* stealTimeMillis= */ 21_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 2_800_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 209111,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 900_000,
                        /* niceTimeMillis= */ 1_000_000, /* systemTimeMillis= */ 10_000_000,
                        /* idleTimeMillis= */ 1_000_000, /* iowaitTimeMillis= */ 90_000,
                        /* irqTimeMillis= */ 200_000, /* softirqTimeMillis= */ 100_000,
                        /* stealTimeMillis= */ 100_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(2, new CpuInfoReader.CpuInfo(/* cpuCore= */ 2,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ true, /* curCpuFreqKHz= */ 2_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 453514,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 10_000_000,
                        /* niceTimeMillis= */ 2_000_000, /* systemTimeMillis= */ 0,
                        /* idleTimeMillis= */ 10_000_000, /* iowaitTimeMillis= */ 1_000_000,
                        /* irqTimeMillis= */ 20_000_000, /* softirqTimeMillis= */ 1_000_000,
                        /* stealTimeMillis= */ 100_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(3, new CpuInfoReader.CpuInfo(/* cpuCore= */ 3,
                FLAG_CPUSET_CATEGORY_TOP_APP | FLAG_CPUSET_CATEGORY_BACKGROUND,
                /* isOnline= */ true, /* curCpuFreqKHz= */ 2_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                /* normalizedAvailableCpuFreqKHz= */ 37728,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 2_000_000,
                        /* niceTimeMillis= */ 1_000_000, /* systemTimeMillis= */ 1_000_000,
                        /* idleTimeMillis= */ 100_000, /* iowaitTimeMillis= */ 100_000,
                        /* irqTimeMillis= */ 100_000, /* softirqTimeMillis= */ 1_000_000,
                        /* stealTimeMillis= */ 1_000, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos second snapshot without time_in_state file", expectedCpuInfos,
                actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithCorruptedCpuset() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(CORRUPTED_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR),
                getCacheFile(VALID_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();
        expectedCpuInfos.append(0, new CpuInfoReader.CpuInfo(/* cpuCore= */ 0,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_230_000,
                /* maxCpuFreqKHz= */ 2_500_000, /* avgTimeInStateCpuFreqKHz= */ 488_095,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_249_610,
                        /* niceTimeMillis= */ 7_950_930, /* systemTimeMillis= */ 52_227_050,
                        /* idleTimeMillis= */ 409_036_950, /* iowaitTimeMillis= */ 1_322_810,
                        /* irqTimeMillis= */ 8_146_740, /* softirqTimeMillis= */ 428_970,
                        /* stealTimeMillis= */ 81_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_450_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ 502_380,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_949_280,
                        /* niceTimeMillis= */ 7_799_450, /* systemTimeMillis= */ 54_004_020,
                        /* idleTimeMillis= */ 402_707_120, /* iowaitTimeMillis= */ 1_186_960,
                        /* irqTimeMillis= */ 14_786_940, /* softirqTimeMillis= */ 1_498_130,
                        /* stealTimeMillis= */ 78_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(2, new CpuInfoReader.CpuInfo(/* cpuCore= */ 2,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ 464_285,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_959_280,
                        /* niceTimeMillis= */ 7_789_450, /* systemTimeMillis= */ 54_014_020,
                        /* idleTimeMillis= */ 402_717_120, /* iowaitTimeMillis= */ 1_166_960,
                        /* irqTimeMillis= */ 14_796_940, /* softirqTimeMillis= */ 1_478_130,
                        /* stealTimeMillis= */ 88_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos with corrupted cpuset", expectedCpuInfos, actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithCorruptedCpufreq() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(CORRUPTED_CPUFREQ_DIR), getCacheFile(VALID_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();

        compareCpuInfos("CPU infos with corrupted CPU frequency", expectedCpuInfos, actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithCorruptedProcStat() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR),
                getCacheFile(CORRUPTED_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();
        expectedCpuInfos.append(0, new CpuInfoReader.CpuInfo(/* cpuCore= */ 0,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_230_000,
                /* maxCpuFreqKHz= */ 2_500_000, /* avgTimeInStateCpuFreqKHz= */ 488_095,
                /* normalizedAvailableCpuFreqKHz= */ 2_402_267,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_249_610,
                        /* niceTimeMillis= */ 7_950_930, /* systemTimeMillis= */ 52_227_050,
                        /* idleTimeMillis= */ 409_036_950, /* iowaitTimeMillis= */ 1_322_810,
                        /* irqTimeMillis= */ 8_146_740, /* softirqTimeMillis= */ 428_970,
                        /* stealTimeMillis= */ 81_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_450_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ 502_380,
                /* normalizedAvailableCpuFreqKHz= */ 2_693_525,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_949_280,
                        /* niceTimeMillis= */ 7_799_450, /* systemTimeMillis= */ 54_004_020,
                        /* idleTimeMillis= */ 402_707_120, /* iowaitTimeMillis= */ 1_186_960,
                        /* irqTimeMillis= */ 14_786_940, /* softirqTimeMillis= */ 1_498_130,
                        /* stealTimeMillis= */ 78_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos with corrupted proc stat", expectedCpuInfos, actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithEmptyCpuset() throws Exception {
        File emptyDir = getCacheFile(EMPTY_DIR);
        assertWithMessage("Make empty dir %s", emptyDir).that(emptyDir.mkdir()).isTrue();
        CpuInfoReader cpuInfoReader = new CpuInfoReader(emptyDir, getCacheFile(
                VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR),
                getCacheFile(VALID_PROC_STAT), /* minReadIntervalMillis= */0);

        assertWithMessage("Init CPU reader info").that(cpuInfoReader.init()).isFalse();

        assertWithMessage("Cpu infos with empty cpuset").that(cpuInfoReader.readCpuInfos())
                .isNull();
    }

    @Test
    public void testReadCpuInfoWithEmptyCpufreq() throws Exception {
        File emptyDir = getCacheFile(EMPTY_DIR);
        assertWithMessage("Make empty dir %s", emptyDir).that(emptyDir.mkdir()).isTrue();
        CpuInfoReader cpuInfoReader = new CpuInfoReader(getCacheFile(VALID_CPUSET_DIR), emptyDir,
                getCacheFile(VALID_PROC_STAT), /* minReadIntervalMillis= */0);

        assertWithMessage("Init CPU reader info").that(cpuInfoReader.init()).isFalse();

        assertWithMessage("Cpu infos with empty CPU frequency").that(cpuInfoReader.readCpuInfos())
                .isNull();
    }

    @Test
    public void testReadCpuInfoWithEmptyRelatedCpus() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITH_EMPTY_RELATED_CPUS),
                getCacheFile(VALID_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();

        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_450_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ 502_380,
                /* normalizedAvailableCpuFreqKHz= */ 2_693_525,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_949_280,
                        /* niceTimeMillis= */ 7_799_450, /* systemTimeMillis= */ 54_004_020,
                        /* idleTimeMillis= */ 402_707_120, /* iowaitTimeMillis= */ 1_186_960,
                        /* irqTimeMillis= */ 14_786_940, /* softirqTimeMillis= */ 1_498_130,
                        /* stealTimeMillis= */ 78_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos with policy 0 containing an empty related_cpus file",
                expectedCpuInfos, actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithEmptyCpusetCpus() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(VALID_CPUSET_WITH_EMPTY_CPUS),
                getCacheFile(VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR),
                getCacheFile(VALID_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();
        expectedCpuInfos.append(0, new CpuInfoReader.CpuInfo(/* cpuCore= */ 0,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_230_000,
                /* maxCpuFreqKHz= */ 2_500_000, /* avgTimeInStateCpuFreqKHz= */ 488_095,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_249_610,
                        /* niceTimeMillis= */ 7_950_930, /* systemTimeMillis= */ 52_227_050,
                        /* idleTimeMillis= */ 409_036_950, /* iowaitTimeMillis= */ 1_322_810,
                        /* irqTimeMillis= */ 8_146_740, /* softirqTimeMillis= */ 428_970,
                        /* stealTimeMillis= */ 81_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_450_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ 502_380,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_949_280,
                        /* niceTimeMillis= */ 7_799_450, /* systemTimeMillis= */ 54_004_020,
                        /* idleTimeMillis= */ 402_707_120, /* iowaitTimeMillis= */ 1_186_960,
                        /* irqTimeMillis= */ 14_786_940, /* softirqTimeMillis= */ 1_498_130,
                        /* stealTimeMillis= */ 78_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(2, new CpuInfoReader.CpuInfo(/* cpuCore= */ 2,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ 464_285,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_959_280,
                        /* niceTimeMillis= */ 7_789_450, /* systemTimeMillis= */ 54_014_020,
                        /* idleTimeMillis= */ 402_717_120, /* iowaitTimeMillis= */ 1_166_960,
                        /* irqTimeMillis= */ 14_796_940, /* softirqTimeMillis= */ 1_478_130,
                        /* stealTimeMillis= */ 88_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));
        expectedCpuInfos.append(3, new CpuInfoReader.CpuInfo(/* cpuCore= */ 3,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_000_000,
                /* maxCpuFreqKHz= */ 2_000_000, /* avgTimeInStateCpuFreqKHz= */ 464_285,
                /* normalizedAvailableCpuFreqKHz= */ 1_907_125,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 32_349_610,
                        /* niceTimeMillis= */ 7_850_930, /* systemTimeMillis= */ 52_127_050,
                        /* idleTimeMillis= */ 409_136_950, /* iowaitTimeMillis= */ 1_332_810,
                        /* irqTimeMillis= */ 8_136_740, /* softirqTimeMillis= */ 438_970,
                        /* stealTimeMillis= */ 71_950, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos with empty background cpu set", expectedCpuInfos,
                actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithEmptyAffectedCpus() throws Exception {
        CpuInfoReader cpuInfoReader = newCpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITH_EMPTY_AFFECTED_CPUS),
                getCacheFile(VALID_PROC_STAT));

        SparseArray<CpuInfoReader.CpuInfo> actualCpuInfos = cpuInfoReader.readCpuInfos();
        SparseArray<CpuInfoReader.CpuInfo> expectedCpuInfos = new SparseArray<>();

        expectedCpuInfos.append(1, new CpuInfoReader.CpuInfo(/* cpuCore= */ 1,
                FLAG_CPUSET_CATEGORY_TOP_APP, /* isOnline= */ true, /* curCpuFreqKHz= */ 1_450_000,
                /* maxCpuFreqKHz= */ 2_800_000, /* avgTimeInStateCpuFreqKHz= */ 502_380,
                /* normalizedAvailableCpuFreqKHz= */ 2_693_525,
                new CpuInfoReader.CpuUsageStats(/* userTimeMillis= */ 28_949_280,
                        /* niceTimeMillis= */ 7_799_450, /* systemTimeMillis= */ 54_004_020,
                        /* idleTimeMillis= */ 402_707_120, /* iowaitTimeMillis= */ 1_186_960,
                        /* irqTimeMillis= */ 14_786_940, /* softirqTimeMillis= */ 1_498_130,
                        /* stealTimeMillis= */ 78_780, /* guestTimeMillis= */ 0,
                        /* guestNiceTimeMillis= */ 0)));

        compareCpuInfos("CPU infos with policy 0 containing an empty affected_cpus file",
                expectedCpuInfos, actualCpuInfos);
    }

    @Test
    public void testReadCpuInfoWithEmptyProcStat() throws Exception {
        File emptyFile = getCacheFile(EMPTY_FILE);
        assertWithMessage("Create empty file %s", emptyFile).that(emptyFile.createNewFile())
                .isTrue();
        CpuInfoReader cpuInfoReader = new CpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR), getCacheFile(EMPTY_FILE),
                /* minReadIntervalMillis= */0);

        assertWithMessage("Cpu infos with empty proc stat").that(cpuInfoReader.readCpuInfos())
                .isNull();
    }

    @Test
    public void testReadingTooFrequentlyReturnsLastReadCpuInfos() throws Exception {
        CpuInfoReader cpuInfoReader = new CpuInfoReader(getCacheFile(VALID_CPUSET_DIR),
                getCacheFile(VALID_CPUFREQ_WITH_TIME_IN_STATE_DIR), getCacheFile(VALID_PROC_STAT),
                /* minReadIntervalMillis= */ 60_000);
        assertWithMessage("Initialize CPU info reader").that(cpuInfoReader.init()).isTrue();

        SparseArray<CpuInfoReader.CpuInfo> firstCpuInfos = cpuInfoReader.readCpuInfos();
        assertWithMessage("CPU infos first snapshot").that(firstCpuInfos).isNotNull();
        assertWithMessage("CPU infos first snapshot size").that(firstCpuInfos.size())
                .isGreaterThan(0);

        SparseArray<CpuInfoReader.CpuInfo> secondCpuInfos = cpuInfoReader.readCpuInfos();
        compareCpuInfos("CPU infos second snapshot", firstCpuInfos, secondCpuInfos);

        SparseArray<CpuInfoReader.CpuInfo> thirdCpuInfos = cpuInfoReader.readCpuInfos();
        compareCpuInfos("CPU infos third snapshot", firstCpuInfos, thirdCpuInfos);
    }

    private void compareCpuInfos(String message,
            SparseArray<CpuInfoReader.CpuInfo> expected,
            SparseArray<CpuInfoReader.CpuInfo> actual) {
        assertWithMessage("%s. Total CPU infos", message).that(actual.size())
                .isEqualTo(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            int cpuCoreId = expected.keyAt(i);
            CpuInfoReader.CpuInfo expectedCpuInfo = expected.valueAt(i);
            CpuInfoReader.CpuInfo actualCpuInfo = actual.get(cpuCoreId);
            expectWithMessage("%s. Core %s's CPU info", message, cpuCoreId).that(actualCpuInfo)
                    .isEqualTo(expectedCpuInfo);
        }
    }

    private File getCacheFile(String assetName) {
        return new File(mCacheRoot, assetName);
    }

    private void copyAssets(String assetPath, File targetRoot) throws Exception {
        File target = new File(targetRoot, assetPath);
        String[] assets = mAssetManager.list(assetPath);
        if (assets == null || assets.length == 0) {
            try (InputStream in = mAssetManager.open(assetPath);
                 OutputStream out = new FileOutputStream(target)) {
                Streams.copy(in, out);
            }
            return;
        }
        assertWithMessage("Make target directory %s", target).that(target.mkdir()).isTrue();
        for (String assetName : assets) {
            copyAssets(String.format("%s%s%s", assetPath, File.separator, assetName), targetRoot);
        }
    }

    private static CpuInfoReader newCpuInfoReader(File cpusetDir, File cpuFreqDir,
            File procStatFile) {
        CpuInfoReader cpuInfoReader = new CpuInfoReader(cpusetDir, cpuFreqDir, procStatFile,
                /* minReadIntervalMillis= */ 0);
        assertWithMessage("Initialize CPU info reader").that(cpuInfoReader.init()).isTrue();
        return cpuInfoReader;
    }

    private static boolean deleteDirectory(File rootDir) {
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            return false;
        }
        for (File file : Objects.requireNonNull(rootDir.listFiles())) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            } else if (!file.delete()) {
                return false;
            }
        }
        return rootDir.delete();
    }
}
