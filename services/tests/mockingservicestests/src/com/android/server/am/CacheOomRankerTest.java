/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.DeviceConfig;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link CachedAppOptimizer}.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:CacheOomRankerTest
 */
@SuppressWarnings("GuardedBy") // No tests are concurrent, so no need to test locking.
@RunWith(MockitoJUnitRunner.class)
public class CacheOomRankerTest {
    private static final Instant NOW = LocalDate.of(2021, 1, 1).atStartOfDay(
            ZoneOffset.UTC).toInstant();

    @Mock
    private AppOpsService mAppOpsService;
    private Handler mHandler;
    private ActivityManagerService mAms;

    @Mock
    private PackageManagerInternal mPackageManagerInt;

    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();
    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    private int mNextPid = 10000;
    private int mNextUid = 30000;
    private int mNextPackageUid = 40000;
    private int mNextPackageName = 1;
    private Map<Integer, Long> mPidToRss;

    private TestExecutor mExecutor = new TestExecutor();
    private CacheOomRanker mCacheOomRanker;

    @Before
    public void setUp() {
        HandlerThread handlerThread = new HandlerThread("");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        /* allowIo */
        ServiceThread thread = new ServiceThread("TestServiceThread",
                Process.THREAD_PRIORITY_DEFAULT,
                true /* allowIo */);
        thread.start();
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mAms = new ActivityManagerService(
                new TestInjector(context), mServiceThreadRule.getThread());
        mAms.mActivityTaskManager = new ActivityTaskManagerService(context);
        mAms.mActivityTaskManager.initialize(null, null, context.getMainLooper());
        mAms.mAtmInternal = spy(mAms.mActivityTaskManager.getAtmInternal());
        mAms.mPackageManagerInt = mPackageManagerInt;
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);

        mPidToRss = new HashMap<>();
        mCacheOomRanker = new CacheOomRanker(
                mAms,
                pid -> {
                    Long rss = mPidToRss.get(pid);
                    assertThat(rss).isNotNull();
                    return new long[]{rss};
                }
        );
        mCacheOomRanker.init(mExecutor);
    }

    @Test
    public void init_listensForConfigChanges() throws InterruptedException {
        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_USE_OOM_RE_RANKING,
                Boolean.TRUE.toString(), true);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.useOomReranking()).isTrue();
        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_USE_OOM_RE_RANKING, Boolean.FALSE.toString(), false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.useOomReranking()).isFalse();

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK,
                Integer.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_NUMBER_TO_RE_RANK + 2),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.getNumberToReRank())
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_NUMBER_TO_RE_RANK + 2);

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_PRESERVE_TOP_N_APPS,
                Integer.toString(CacheOomRanker.DEFAULT_PRESERVE_TOP_N_APPS + 1),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.mPreserveTopNApps)
                .isEqualTo(CacheOomRanker.DEFAULT_PRESERVE_TOP_N_APPS + 1);

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_LRU_WEIGHT,
                Float.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_LRU_WEIGHT + 0.1f),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.mLruWeight)
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_LRU_WEIGHT + 0.1f);

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_RSS_WEIGHT,
                Float.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_RSS_WEIGHT - 0.1f),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.mRssWeight)
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_RSS_WEIGHT - 0.1f);

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_USES_WEIGHT,
                Float.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_USES_WEIGHT + 0.2f),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.mUsesWeight)
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_USES_WEIGHT + 0.2f);
    }

    @Test
    public void reRankLruCachedApps_lruImpactsOrdering() throws InterruptedException {
        setConfig(/* numberToReRank= */ 5,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 0.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */1.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord lastUsed40MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(lastUsed40MinutesAgo);
        ProcessRecord lastUsed42MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(lastUsed42MinutesAgo);
        ProcessRecord lastUsed60MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(60, ChronoUnit.MINUTES).toEpochMilli(), 1024L, 10000);
        processList.add(lastUsed60MinutesAgo);
        ProcessRecord lastUsed15MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(lastUsed15MinutesAgo);
        ProcessRecord lastUsed17MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 1024L, 20);
        processList.add(lastUsed17MinutesAgo);
        // Only re-ranking 5 entries so this should stay in most recent position.
        ProcessRecord lastUsed30MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 1024L, 20);
        processList.add(lastUsed30MinutesAgo);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 5 ordered by least recently used first, then last processes position unchanged.
        assertThat(processList).containsExactly(lastUsed60MinutesAgo, lastUsed42MinutesAgo,
                lastUsed40MinutesAgo, lastUsed17MinutesAgo, lastUsed15MinutesAgo,
                lastUsed30MinutesAgo).inOrder();
    }

    @Test
    public void reRankLruCachedApps_rssImpactsOrdering() throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 0.0f,
                /* pssWeight= */ 1.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord rss10k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(rss10k);
        ProcessRecord rss20k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(rss20k);
        ProcessRecord rss1k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(60, ChronoUnit.MINUTES).toEpochMilli(), 1024L, 10000);
        processList.add(rss1k);
        ProcessRecord rss100k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(rss100k);
        ProcessRecord rss2k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(rss2k);
        ProcessRecord rss15k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 20);
        processList.add(rss15k);
        // Only re-ranking 6 entries so this should stay in most recent position.
        ProcessRecord rss16k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 20);
        processList.add(rss16k);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 6 ordered by largest pss, then last processes position unchanged.
        assertThat(processList).containsExactly(rss100k, rss20k, rss15k, rss10k, rss2k, rss1k,
                rss16k).inOrder();
    }

    @Test
    public void reRankLruCachedApps_rssImpactsOrdering_cachedRssValues()
            throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 10000000,
                /* usesWeight= */ 0.0f,
                /* pssWeight= */ 1.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord rss10k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(rss10k);
        ProcessRecord rss20k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(rss20k);
        ProcessRecord rss1k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(60, ChronoUnit.MINUTES).toEpochMilli(), 1024L, 10000);
        processList.add(rss1k);
        ProcessRecord rss100k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(rss100k);
        ProcessRecord rss2k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(rss2k);
        ProcessRecord rss15k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 20);
        processList.add(rss15k);
        // Only re-ranking 6 entries so this should stay in most recent position.
        ProcessRecord rss16k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 20);
        processList.add(rss16k);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());
        // First 6 ordered by largest pss, then last processes position unchanged.
        assertThat(processList).containsExactly(rss100k, rss20k, rss15k, rss10k, rss2k, rss1k,
                rss16k).inOrder();

        // Clear mPidToRss so that Process.getRss calls fail.
        mPidToRss.clear();
        // Mix up the process list to ensure that CacheOomRanker actually re-ranks.
        Collections.swap(processList, 0, 1);

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());
        // Re ranking is the same.
        assertThat(processList).containsExactly(rss100k, rss20k, rss15k, rss10k, rss2k, rss1k,
                rss16k).inOrder();
    }

    @Test
    public void reRankLruCachedApps_rssImpactsOrdering_profileRss()
            throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ false,
                /* rssUpdateRateMs= */ 10000000,
                /* usesWeight= */ 0.0f,
                /* pssWeight= */ 1.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord rss10k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 0L, 1000);
        rss10k.mProfile.setLastRss(10 * 1024L);
        processList.add(rss10k);
        ProcessRecord rss20k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 0L, 2000);
        rss20k.mProfile.setLastRss(20 * 1024L);
        processList.add(rss20k);
        ProcessRecord rss1k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(60, ChronoUnit.MINUTES).toEpochMilli(), 0L, 10000);
        rss1k.mProfile.setLastRss(1024L);
        processList.add(rss1k);
        ProcessRecord rss100k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 0L, 10);
        rss100k.mProfile.setLastRss(100 * 1024L);
        processList.add(rss100k);
        ProcessRecord rss2k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 0L, 20);
        rss2k.mProfile.setLastRss(2 * 1024L);
        processList.add(rss2k);
        ProcessRecord rss15k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 20);
        rss15k.mProfile.setLastRss(15 * 1024L);
        processList.add(rss15k);
        // Only re-ranking 6 entries so this should stay in most recent position.
        ProcessRecord rss16k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 20);
        rss16k.mProfile.setLastRss(16 * 1024L);
        processList.add(rss16k);
        list.setLruProcessServiceStartLSP(processList.size());

        // This should not be used, as RSS values are taken from mProfile.
        mPidToRss.clear();

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());
        // First 6 ordered by largest pss, then last processes position unchanged.
        assertThat(processList).containsExactly(rss100k, rss20k, rss15k, rss10k, rss2k, rss1k,
                rss16k).inOrder();

        // Clear mPidToRss so that Process.getRss calls fail.
        mPidToRss.clear();
        // Mix up the process list to ensure that CacheOomRanker actually re-ranks.
        Collections.swap(processList, 0, 1);

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());
        // Re ranking is the same.
        assertThat(processList).containsExactly(rss100k, rss20k, rss15k, rss10k, rss2k, rss1k,
                rss16k).inOrder();
    }


    @Test
    public void reRankLruCachedApps_usesImpactsOrdering() throws InterruptedException {
        setConfig(/* numberToReRank= */ 4,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(used10);
        ProcessRecord used20 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(used20);
        // Only re-ranking 6 entries so last two should stay in most recent position.
        ProcessRecord used500 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        processList.add(used500);
        ProcessRecord used200 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        processList.add(used200);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 4 ordered by uses, then last processes position unchanged.
        assertThat(processList).containsExactly(used10, used20, used1000, used2000, used500,
                used200).inOrder();
    }

    @Test
    public void reRankLruCachedApps_fewProcesses() throws InterruptedException {
        setConfig(/* numberToReRank= */ 4,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(used10);
        ProcessRecord foregroundAdj = nextProcessRecord(ProcessList.FOREGROUND_APP_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(foregroundAdj);
        ProcessRecord serviceAdj = nextProcessRecord(ProcessList.SERVICE_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        processList.add(serviceAdj);
        ProcessRecord systemAdj = nextProcessRecord(ProcessList.SYSTEM_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        processList.add(systemAdj);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // 6 processes, only 3 in eligible for cache, so only those are re-ranked.
        assertThat(processList).containsExactly(used10, used1000, used2000,
                foregroundAdj, serviceAdj, systemAdj).inOrder();
    }

    @Test
    public void reRankLruCachedApps_fewNonServiceProcesses() throws InterruptedException {
        setConfig(/* numberToReRank= */ 4,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(used10);
        ProcessRecord service1 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(service1);
        ProcessRecord service2 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        processList.add(service2);
        ProcessRecord service3 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        processList.add(service3);
        list.setLruProcessServiceStartLSP(3);

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // Services unchanged, rest re-ranked.
        assertThat(processList).containsExactly(used10, used1000, used2000, service1, service2,
                service3).inOrder();
    }

    @Test
    public void reRankLruCachedApps_manyProcessesThenFew() throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* preserveTopNApps= */ 0,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList set1List = new ProcessList();
        ArrayList<ProcessRecord> set1ProcessList = set1List.getLruProcessesLSP();
        ProcessRecord set1Used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        set1ProcessList.add(set1Used1000);
        ProcessRecord set1Used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        set1ProcessList.add(set1Used2000);
        ProcessRecord set1Used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        set1ProcessList.add(set1Used10);
        ProcessRecord set1Uses20 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        set1ProcessList.add(set1Uses20);
        ProcessRecord set1Uses500 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        set1ProcessList.add(set1Uses500);
        ProcessRecord set1Uses200 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        set1ProcessList.add(set1Uses200);
        set1List.setLruProcessServiceStartLSP(set1ProcessList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(set1ProcessList,
                set1List.getLruProcessServiceStartLOSP());
        assertThat(set1ProcessList).containsExactly(set1Used10, set1Uses20, set1Uses200,
                set1Uses500, set1Used1000, set1Used2000).inOrder();

        ProcessList set2List = new ProcessList();
        ArrayList<ProcessRecord> set2ProcessList = set2List.getLruProcessesLSP();
        ProcessRecord set2Used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        set2ProcessList.add(set2Used1000);
        ProcessRecord set2Used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        set2ProcessList.add(set2Used2000);
        ProcessRecord set2Used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        set2ProcessList.add(set2Used10);
        ProcessRecord set2ForegroundAdj = nextProcessRecord(ProcessList.FOREGROUND_APP_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        set2ProcessList.add(set2ForegroundAdj);
        ProcessRecord set2ServiceAdj = nextProcessRecord(ProcessList.SERVICE_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        set2ProcessList.add(set2ServiceAdj);
        ProcessRecord set2SystemAdj = nextProcessRecord(ProcessList.SYSTEM_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        set2ProcessList.add(set2SystemAdj);
        set2List.setLruProcessServiceStartLSP(set2ProcessList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(set2ProcessList,
                set2List.getLruProcessServiceStartLOSP());
        assertThat(set2ProcessList).containsExactly(set2Used10, set2Used1000, set2Used2000,
                set2ForegroundAdj, set2ServiceAdj, set2SystemAdj).inOrder();
    }

    @Test
    public void reRankLruCachedApps_preservesTopNApps() throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* preserveTopNApps= */ 3,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(used10);
        // Preserving the top 3 processes, so these should not be re-ranked.
        ProcessRecord used20 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(used20);
        ProcessRecord used500 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        processList.add(used500);
        ProcessRecord used200 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        processList.add(used200);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 3 ordered by uses, then last processes position unchanged.
        assertThat(processList).containsExactly(used10, used1000, used2000, used20, used500,
                used200).inOrder();
    }

    @Test
    public void reRankLruCachedApps_preservesTopNApps_allAppsUnchanged()
            throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* preserveTopNApps= */ 100,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(used10);
        ProcessRecord used20 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(used20);
        ProcessRecord used500 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        processList.add(used500);
        ProcessRecord used200 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        processList.add(used200);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // Nothing reordered, as we preserve the top 100 apps.
        assertThat(processList).containsExactly(used1000, used2000, used10, used20, used500,
                used200).inOrder();
    }

    @Test
    public void reRankLruCachedApps_preservesTopNApps_negativeReplacedWithDefault()
            throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* preserveTopNApps= */ -100,
                /* useFrequentRss= */ true,
                /* rssUpdateRateMs= */ 0,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(40, ChronoUnit.MINUTES).toEpochMilli(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(42, ChronoUnit.MINUTES).toEpochMilli(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(15, ChronoUnit.MINUTES).toEpochMilli(), 100 * 1024L, 10);
        processList.add(used10);
        // Negative preserveTopNApps interpreted as the default (3), so the last three are unranked.
        ProcessRecord used20 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(17, ChronoUnit.MINUTES).toEpochMilli(), 2 * 1024L, 20);
        processList.add(used20);
        ProcessRecord used500 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 15 * 1024L, 500);
        processList.add(used500);
        ProcessRecord used200 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                NOW.minus(30, ChronoUnit.MINUTES).toEpochMilli(), 16 * 1024L, 200);
        processList.add(used200);
        list.setLruProcessServiceStartLSP(processList.size());

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 3 apps re-ranked, as preserveTopNApps is interpreted as 3.
        assertThat(processList).containsExactly(used10, used1000, used2000, used20, used500,
                used200).inOrder();
    }

    private void setConfig(int numberToReRank, int preserveTopNApps, boolean useFrequentRss,
            long rssUpdateRateMs, float usesWeight, float pssWeight, float lruWeight)
            throws InterruptedException {
        mExecutor.init(4);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK,
                Integer.toString(numberToReRank),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_PRESERVE_TOP_N_APPS,
                Integer.toString(preserveTopNApps),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_USE_FREQUENT_RSS,
                Boolean.toString(useFrequentRss),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_RSS_UPDATE_RATE_MS,
                Long.toString(rssUpdateRateMs),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_LRU_WEIGHT,
                Float.toString(lruWeight),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_RSS_WEIGHT,
                Float.toString(pssWeight),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_USES_WEIGHT,
                Float.toString(usesWeight),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.getNumberToReRank()).isEqualTo(numberToReRank);
        assertThat(mCacheOomRanker.mUseFrequentRss).isEqualTo(useFrequentRss);
        assertThat(mCacheOomRanker.mRssUpdateRateMs).isEqualTo(rssUpdateRateMs);
        assertThat(mCacheOomRanker.mRssWeight).isEqualTo(pssWeight);
        assertThat(mCacheOomRanker.mUsesWeight).isEqualTo(usesWeight);
        assertThat(mCacheOomRanker.mLruWeight).isEqualTo(lruWeight);
    }

    private ProcessRecord nextProcessRecord(int setAdj, long lastActivityTime, long lastRss,
            int wentToForegroundCount) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = "a.package.name" + mNextPackageName++;
        ProcessRecord app = new ProcessRecord(mAms, ai, ai.packageName + ":process", mNextUid++);
        app.setPid(mNextPid++);
        app.info.uid = mNextPackageUid++;
        // Exact value does not mater, it can be any state for which compaction is allowed.
        app.mState.setSetProcState(PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        app.mState.setCurAdj(setAdj);
        app.setLastActivityTime(lastActivityTime);
        mPidToRss.put(app.getPid(), lastRss);
        for (int i = 0; i < wentToForegroundCount; ++i) {
            app.mState.setSetProcState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
            app.mState.setSetProcState(ActivityManager.PROCESS_STATE_CACHED_RECENT);
        }
        // Sets the thread returned by ProcessRecord#getThread, which we use to check whether the
        // app is currently launching.
        ProcessStatsService processStatsService = new ProcessStatsService(
                mock(ActivityManagerService.class), new File(Environment.getDataSystemCeDirectory(),
                "procstats"));
        app.makeActive(mock(ApplicationThreadDeferred.class), processStatsService);
        return app;
    }

    private class TestExecutor implements Executor {
        private CountDownLatch mLatch;

        private void init(int count) {
            mLatch = new CountDownLatch(count);
        }

        private void init() {
            init(1);
        }

        private void waitForLatch() throws InterruptedException {
            mLatch.await(5, TimeUnit.SECONDS);
        }

        @Override
        public void execute(Runnable command) {
            command.run();
            mLatch.countDown();
        }
    }

    private class TestInjector extends ActivityManagerService.Injector {
        private TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(File recentAccessesFile, File storageFile,
                Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandler;
        }
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
