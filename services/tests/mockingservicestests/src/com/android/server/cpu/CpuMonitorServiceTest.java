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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.cpu.CpuAvailabilityInfo.MISSING_CPU_AVAILABILITY_PERCENT;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_ALL;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_BACKGROUND;
import static com.android.server.cpu.CpuInfoReader.CpuInfo.MISSING_FREQUENCY;
import static com.android.server.cpu.CpuInfoReader.FLAG_CPUSET_CATEGORY_BACKGROUND;
import static com.android.server.cpu.CpuInfoReader.FLAG_CPUSET_CATEGORY_TOP_APP;
import static com.android.server.cpu.CpuMonitorService.DEFAULT_MONITORING_INTERVAL_MILLISECONDS;
import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.LocalServices;
import com.android.server.Watchdog;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.stubbing.OngoingStubbing;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CpuMonitorServiceTest {
    private static final String TAG = CpuMonitorServiceTest.class.getSimpleName();
    private static final String USER_BUILD_TAG = TAG + "UserBuild";
    private static final long ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS =
            TimeUnit.SECONDS.toMillis(1);
    private static final long HANDLER_THREAD_SYNC_TIMEOUT_MILLISECONDS =
            TimeUnit.SECONDS.toMillis(5);
    private static final long TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS = 100;
    private static final long TEST_DEBUG_MONITORING_INTERVAL_MILLISECONDS = 150;
    private static final long TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS = 300;
    private static final long TEST_STOP_PERIODIC_CPUSET_READING_DELAY_MILLISECONDS = 0;
    private static final CpuAvailabilityMonitoringConfig TEST_MONITORING_CONFIG_ALL_CPUSET =
            new CpuAvailabilityMonitoringConfig.Builder(CPUSET_ALL)
                    .addThreshold(30).addThreshold(70).build();
    private static final CpuAvailabilityMonitoringConfig TEST_MONITORING_CONFIG_BG_CPUSET =
            new CpuAvailabilityMonitoringConfig.Builder(CPUSET_BACKGROUND)
                    .addThreshold(50).addThreshold(90).build();
    private static final List<StaticCpuInfo> STATIC_CPU_INFOS = List.of(
            new StaticCpuInfo(/* cpuCore= */ 0,
                    /* cpusetCategories= */ FLAG_CPUSET_CATEGORY_TOP_APP,
                    /* maxCpuFreqKHz= */ 4000),
            new StaticCpuInfo(/* cpuCore= */ 1,
                    /* cpusetCategories= */ FLAG_CPUSET_CATEGORY_TOP_APP,
                    /* maxCpuFreqKHz= */ 3000),
            new StaticCpuInfo(/* cpuCore= */ 2, /* cpusetCategories= */ FLAG_CPUSET_CATEGORY_TOP_APP
                    | FLAG_CPUSET_CATEGORY_BACKGROUND, /* maxCpuFreqKHz= */ 3000),
            new StaticCpuInfo(/* cpuCore= */ 3, /* cpusetCategories= */ FLAG_CPUSET_CATEGORY_TOP_APP
                    | FLAG_CPUSET_CATEGORY_BACKGROUND, /* maxCpuFreqKHz= */ 3000),
            new StaticCpuInfo(/* cpuCore= */ 4, /* cpusetCategories= */ FLAG_CPUSET_CATEGORY_TOP_APP
                    | FLAG_CPUSET_CATEGORY_BACKGROUND, /* maxCpuFreqKHz= */ 2000));
    private static final ArraySet<Integer> NO_OFFLINE_CORES = new ArraySet<>();

    @Mock
    private Context mMockContext;
    @Mock
    private CpuInfoReader mMockCpuInfoReader;
    @Captor
    private ArgumentCaptor<CpuAvailabilityInfo> mCpuAvailabilityInfoCaptor;
    private HandlerThread mServiceHandlerThread;
    private Handler mServiceHandler;
    private CpuMonitorService mService;
    private CpuMonitorInternal mLocalService;

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(ServiceManager.class)
            .mockStatic(Watchdog.class)
            .build();

    @Before
    public void setUp() throws Exception {
        mServiceHandlerThread = new HandlerThread(TAG);
        mService = new CpuMonitorService(mMockContext, mMockCpuInfoReader, mServiceHandlerThread,
                /* shouldDebugMonitor= */ true, TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS,
                TEST_DEBUG_MONITORING_INTERVAL_MILLISECONDS,
                TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS,
                TEST_STOP_PERIODIC_CPUSET_READING_DELAY_MILLISECONDS);

        doNothing().when(() -> ServiceManager.addService(eq("cpu_monitor"), any(Binder.class),
                anyBoolean(), anyInt()));
        doReturn(mock(Watchdog.class)).when(Watchdog::getInstance);
        when(mMockCpuInfoReader.init()).thenReturn(true);
        when(mMockCpuInfoReader.readCpuInfos()).thenReturn(new SparseArray<>());

        startService();
    }

    @After
    public void tearDown() throws Exception {
        terminateService();
    }

    @Test
    public void testAddRemoveCpuAvailabilityCallbackOnDebugBuild() throws Exception {
        CpuMonitorInternal.CpuAvailabilityCallback mockCallback = mock(
                CpuMonitorInternal.CpuAvailabilityCallback.class);

        mLocalService.addCpuAvailabilityCallback(/* executor= */ null,
                TEST_MONITORING_CONFIG_ALL_CPUSET, mockCallback);

        assertWithMessage("Monitoring interval after adding a client callback")
                .that(mService.getCurrentMonitoringIntervalMillis())
                .isEqualTo(TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS);

        // Monitoring interval changed notification is sent asynchronously from the handler thread.
        // So, sync with this thread before verifying the client call.
        syncWithHandler(mServiceHandler, /* delayMillis= */ 0);

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS))
                .onMonitoringIntervalChanged(TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS);

        verify(mockCallback, never()).onAvailabilityChanged(any());

        mLocalService.removeCpuAvailabilityCallback(mockCallback);

        assertWithMessage("Monitoring interval after removing all client callbacks")
                .that(mService.getCurrentMonitoringIntervalMillis())
                .isEqualTo(TEST_DEBUG_MONITORING_INTERVAL_MILLISECONDS);
    }

    @Test
    public void testAddRemoveCpuAvailabilityCallbackOnUserBuild() throws Exception {
        // The default service instantiated during test setUp has the debug monitoring enabled.
        // But on a user build, debug monitoring is disabled. So, replace the default service with
        // an equivalent user build service.
        replaceServiceWithUserBuildService();

        CpuMonitorInternal.CpuAvailabilityCallback mockCallback = mock(
                CpuMonitorInternal.CpuAvailabilityCallback.class);

        mLocalService.addCpuAvailabilityCallback(/* executor= */ null,
                TEST_MONITORING_CONFIG_ALL_CPUSET, mockCallback);

        assertWithMessage("Monitoring interval after adding a client callback")
                .that(mService.getCurrentMonitoringIntervalMillis())
                .isEqualTo(TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS);

        // Monitoring interval changed notification is sent asynchronously from the handler thread.
        // So, sync with this thread before verifying the client call.
        syncWithHandler(mServiceHandler, /* delayMillis= */ 0);

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS))
                .onMonitoringIntervalChanged(TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS);

        verify(mockCallback, never()).onAvailabilityChanged(any());

        mLocalService.removeCpuAvailabilityCallback(mockCallback);

        assertWithMessage("Monitoring interval after removing all client callbacks")
                .that(mService.getCurrentMonitoringIntervalMillis())
                .isEqualTo(DEFAULT_MONITORING_INTERVAL_MILLISECONDS);
    }

    @Test
    public void testRemoveInvalidCpuAvailabilityCallback() throws Exception {
        CpuMonitorInternal.CpuAvailabilityCallback mockCallback = mock(
                CpuMonitorInternal.CpuAvailabilityCallback.class);

        mLocalService.removeCpuAvailabilityCallback(mockCallback);
    }

    @Test
    public void testReceiveCpuAvailabilityCallbackOnAddingFirstCallback() throws Exception {
        // Debug monitoring is in progress but the default {@link CpuInfoReader.CpuInfo} returned by
        // the {@link CpuInfoReader.readCpuInfos} is empty, so the client won't be notified when
        // adding a callback. Inject {@link CpuInfoReader.CpuInfo}, so the client callback is
        // notified on adding a callback.
        injectCpuInfosAndWait(List.of(
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 10.0f,
                        NO_OFFLINE_CORES)));

        CpuMonitorInternal.CpuAvailabilityCallback mockCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_ALL_CPUSET);

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS))
                .onAvailabilityChanged(mCpuAvailabilityInfoCaptor.capture());

        List<CpuAvailabilityInfo> actual = mCpuAvailabilityInfoCaptor.getAllValues();

        List<CpuAvailabilityInfo> expected = List.of(
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 10, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos").that(actual).isEqualTo(expected);
    }

    @Test
    public void testReceiveCpuAvailabilityCallbackOnAddingMultipleCallbacks() throws Exception {
        addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_BG_CPUSET);

        injectCpuInfosAndWait(List.of(
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 10.0f,
                        NO_OFFLINE_CORES)));

        CpuMonitorInternal.CpuAvailabilityCallback mockCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_ALL_CPUSET);

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS))
                .onAvailabilityChanged(mCpuAvailabilityInfoCaptor.capture());

        List<CpuAvailabilityInfo> actual = mCpuAvailabilityInfoCaptor.getAllValues();

        List<CpuAvailabilityInfo> expected = List.of(
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 10, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos").that(actual).isEqualTo(expected);
    }

    @Test
    public void testCrossCpuAvailabilityThresholdsWithSingleCallback() throws Exception {
        CpuMonitorInternal.CpuAvailabilityCallback mockCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_ALL_CPUSET);

        injectCpuInfosAndWait(List.of(
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 10.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 90.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 15.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 30.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 60.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 82.0f,
                        NO_OFFLINE_CORES)));

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS).times(4))
                .onAvailabilityChanged(mCpuAvailabilityInfoCaptor.capture());

        List<CpuAvailabilityInfo> actual = mCpuAvailabilityInfoCaptor.getAllValues();

        List<CpuAvailabilityInfo> expected = List.of(
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 90, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(1).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 15, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(2).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 30,
                        /* pastNMillisAvgAvailabilityPercent= */ 45,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(3).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 82,
                        /* pastNMillisAvgAvailabilityPercent= */ 57,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos").that(actual).isEqualTo(expected);
    }

    @Test
    public void testCrossCpuAvailabilityThresholdsWithMultipleCallbacks() throws Exception {
        CpuMonitorInternal.CpuAvailabilityCallback mockAllCpusetCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_ALL_CPUSET);

        CpuMonitorInternal.CpuAvailabilityCallback mockBgCpusetCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_BG_CPUSET);

        injectCpuInfosAndWait(List.of(
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 5.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 20.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 30.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 60.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 75.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 90.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 15.0f,
                        NO_OFFLINE_CORES)));

        verify(mockAllCpusetCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS).times(3))
                .onAvailabilityChanged(mCpuAvailabilityInfoCaptor.capture());

        List<CpuAvailabilityInfo> actual = mCpuAvailabilityInfoCaptor.getAllValues();
        List<CpuAvailabilityInfo> expected = List.of(
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 30, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(1).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 75,
                        /* pastNMillisAvgAvailabilityPercent= */ 55,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(2).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 15,
                        /* pastNMillisAvgAvailabilityPercent= */ 60,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos for CPUSET_ALL callback").that(actual)
                .isEqualTo(expected);

        ArgumentCaptor<CpuAvailabilityInfo> bgCpusetAvailabilityInfoCaptor =
                ArgumentCaptor.forClass(CpuAvailabilityInfo.class);

        verify(mockBgCpusetCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS).times(3))
                .onAvailabilityChanged(bgCpusetAvailabilityInfoCaptor.capture());

        actual = bgCpusetAvailabilityInfoCaptor.getAllValues();
        expected = List.of(
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 60,
                        /* pastNMillisAvgAvailabilityPercent= */ 36,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(1).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 90,
                        /* pastNMillisAvgAvailabilityPercent= */ 75,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(2).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 15,
                        /* pastNMillisAvgAvailabilityPercent= */ 60,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos for CPUSET_BACKGROUND callback").that(actual)
                .isEqualTo(expected);
    }

    @Test
    public void testCrossCpuAvailabilityThresholdsWithOfflineCores() throws Exception {
        CpuMonitorInternal.CpuAvailabilityCallback mockAllCpusetCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_ALL_CPUSET);

        CpuMonitorInternal.CpuAvailabilityCallback mockBgCpusetCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_BG_CPUSET);

        // Disable one top-app and one all cpuset core.
        ArraySet<Integer> offlineCoresA = new ArraySet<>();
        offlineCoresA.add(1);
        offlineCoresA.add(3);

        // Disable two all cpuset cores.
        ArraySet<Integer> offlineCoresB = new ArraySet<>();
        offlineCoresB.add(2);
        offlineCoresB.add(4);

        injectCpuInfosAndWait(List.of(
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 5.0f, offlineCoresA),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 20.0f, offlineCoresB),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 30.0f, offlineCoresA),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 60.0f, offlineCoresB),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 75.0f, offlineCoresA),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 90.0f, offlineCoresB),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 15.0f,
                        offlineCoresA)));

        verify(mockAllCpusetCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS).times(3))
                .onAvailabilityChanged(mCpuAvailabilityInfoCaptor.capture());

        List<CpuAvailabilityInfo> actual = mCpuAvailabilityInfoCaptor.getAllValues();
        List<CpuAvailabilityInfo> expected = List.of(
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 30, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(1).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 75,
                        /* pastNMillisAvgAvailabilityPercent= */ 55,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(2).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 15,
                        /* pastNMillisAvgAvailabilityPercent= */ 61,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos for CPUSET_ALL callback").that(actual)
                .isEqualTo(expected);

        ArgumentCaptor<CpuAvailabilityInfo> bgCpusetAvailabilityInfoCaptor =
                ArgumentCaptor.forClass(CpuAvailabilityInfo.class);

        verify(mockBgCpusetCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS).times(3))
                .onAvailabilityChanged(bgCpusetAvailabilityInfoCaptor.capture());

        actual = bgCpusetAvailabilityInfoCaptor.getAllValues();
        expected = List.of(
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 60,
                        /* pastNMillisAvgAvailabilityPercent= */ 35,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(1).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 90,
                        /* pastNMillisAvgAvailabilityPercent= */ 75,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(2).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 15,
                        /* pastNMillisAvgAvailabilityPercent= */ 55,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos for CPUSET_BACKGROUND callback").that(actual)
                .isEqualTo(expected);
    }

    @Test
    public void testReceiveCpuAvailabilityCallbacksOnExecutorThread() throws Exception {
        Handler testHandler = new Handler(Looper.getMainLooper());

        assertWithMessage("Test main handler").that(testHandler).isNotNull();

        HandlerExecutor testExecutor = new HandlerExecutor(testHandler);

        assertWithMessage("Test main executor").that(testExecutor).isNotNull();

        CpuMonitorInternal.CpuAvailabilityCallback mockCallback =
                addCpuAvailabilityCallback(testHandler, testExecutor,
                        TEST_MONITORING_CONFIG_ALL_CPUSET);

        // CPU monitoring is started on the service handler thread. Sync with this thread before
        // proceeding. Otherwise, debug monitoring may consume the injected CPU infos and cause
        // the test to be flaky. Because the {@link addCpuAvailabilityCallback} syncs only with
        // the passed handler, the test must explicitly sync with the service handler.
        syncWithHandler(mServiceHandler, /* delayMillis= */ 0);

        injectCpuInfosAndWait(testHandler, List.of(
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 10.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 90.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 15.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 30.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 60.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 82.0f,
                        NO_OFFLINE_CORES)));

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS).times(4))
                .onAvailabilityChanged(mCpuAvailabilityInfoCaptor.capture());

        List<CpuAvailabilityInfo> actual = mCpuAvailabilityInfoCaptor.getAllValues();

        List<CpuAvailabilityInfo> expected = List.of(
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 90, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(1).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 15, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(2).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 30,
                        /* pastNMillisAvgAvailabilityPercent= */ 45,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_ALL, actual.get(3).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 82,
                        /* pastNMillisAvgAvailabilityPercent= */ 57,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos").that(actual).isEqualTo(expected);
    }

    @Test
    public void testDuplicateAddCpuAvailabilityCallback() throws Exception {
        addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_ALL_CPUSET);

        CpuMonitorInternal.CpuAvailabilityCallback mockCallback =
                addCpuAvailabilityCallback(TEST_MONITORING_CONFIG_BG_CPUSET);

        injectCpuInfosAndWait(List.of(
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 10.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 40.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 60.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 80.0f,
                        NO_OFFLINE_CORES),
                generateCpuInfosForAvailability(/* cpuAvailabilityPercent= */ 95.0f,
                        NO_OFFLINE_CORES)));

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS).times(2))
                .onAvailabilityChanged(mCpuAvailabilityInfoCaptor.capture());

        List<CpuAvailabilityInfo> actual = mCpuAvailabilityInfoCaptor.getAllValues();

        // Verify that the callback is called for the last added monitoring config.
        List<CpuAvailabilityInfo> expected = List.of(
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(0).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 60, MISSING_CPU_AVAILABILITY_PERCENT,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS),
                new CpuAvailabilityInfo(CPUSET_BACKGROUND, actual.get(1).dataTimestampUptimeMillis,
                        /* latestAvgAvailabilityPercent= */ 95,
                        /* pastNMillisAvgAvailabilityPercent= */ 78,
                        TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS));

        assertWithMessage("CPU availability infos").that(actual).isEqualTo(expected);
    }

    @Test
    public void testBootCompleted() throws Exception {
        mService.onBootPhase(PHASE_BOOT_COMPLETED);

        // Message to stop periodic cpuset reading is posted on the service handler thread. Sync
        // with this thread before proceeding.
        syncWithHandler(mServiceHandler, /* delayMillis= */ 0);

        verify(mMockCpuInfoReader, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS))
                .stopPeriodicCpusetReading();
    }

    @Test
    public void testHeavyCpuLoadMonitoring() throws Exception {
        // TODO(b/267500110): Once heavy CPU load detection logic is added, add unittest.
    }

    private void startService() {
        mService.onStart();
        mServiceHandler = mServiceHandlerThread.getThreadHandler();

        assertWithMessage("Service thread handler").that(mServiceHandler).isNotNull();

        mLocalService = LocalServices.getService(CpuMonitorInternal.class);

        assertWithMessage("CpuMonitorInternal local service").that(mLocalService).isNotNull();
    }

    private void terminateService() {
        // The CpuMonitorInternal.class service is added by the {@link CpuMonitorService#onStart}
        // call. Remove the service to ensure this service can be added again during
        // the {@link CpuMonitorService#onStart} call.
        LocalServices.removeServiceForTest(CpuMonitorInternal.class);
        if (mServiceHandlerThread != null && mServiceHandlerThread.isAlive()) {
            mServiceHandlerThread.quitSafely();
        }
    }

    private void replaceServiceWithUserBuildService() {
        terminateService();
        mServiceHandlerThread = new HandlerThread(USER_BUILD_TAG);
        mService = new CpuMonitorService(mMockContext, mMockCpuInfoReader,
                mServiceHandlerThread, /* shouldDebugMonitor= */ false,
                TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS,
                TEST_DEBUG_MONITORING_INTERVAL_MILLISECONDS,
                TEST_LATEST_AVAILABILITY_DURATION_MILLISECONDS,
                TEST_STOP_PERIODIC_CPUSET_READING_DELAY_MILLISECONDS);

        startService();
    }

    private CpuMonitorInternal.CpuAvailabilityCallback addCpuAvailabilityCallback(
            CpuAvailabilityMonitoringConfig config) throws Exception {
        return addCpuAvailabilityCallback(mServiceHandler, /* executor= */ null, config);
    }

    private CpuMonitorInternal.CpuAvailabilityCallback addCpuAvailabilityCallback(Handler handler,
            HandlerExecutor executor, CpuAvailabilityMonitoringConfig config) throws Exception {
        CpuMonitorInternal.CpuAvailabilityCallback mockCallback = mock(
                CpuMonitorInternal.CpuAvailabilityCallback.class);

        mLocalService.addCpuAvailabilityCallback(executor, config, mockCallback);

        // Monitoring interval changed notification is sent asynchronously from the given handler.
        // So, sync with this thread before verifying the client call.
        syncWithHandler(handler, /* delayMillis= */ 0);

        verify(mockCallback, timeout(ASYNC_CALLBACK_WAIT_TIMEOUT_MILLISECONDS))
                .onMonitoringIntervalChanged(TEST_NORMAL_MONITORING_INTERVAL_MILLISECONDS);

        return mockCallback;
    }

    private void injectCpuInfosAndWait(List<SparseArray<CpuInfoReader.CpuInfo>> cpuInfos)
            throws Exception {
        injectCpuInfosAndWait(mServiceHandler, cpuInfos);
    }

    private void injectCpuInfosAndWait(Handler handler,
            List<SparseArray<CpuInfoReader.CpuInfo>> cpuInfos) throws Exception {
        assertWithMessage("CPU info configs").that(cpuInfos).isNotEmpty();

        OngoingStubbing<SparseArray<CpuInfoReader.CpuInfo>> ongoingStubbing =
                when(mMockCpuInfoReader.readCpuInfos());
        for (SparseArray<CpuInfoReader.CpuInfo> cpuInfo : cpuInfos) {
            ongoingStubbing = ongoingStubbing.thenReturn(cpuInfo);
        }

        // CPU infos are read asynchronously on a separate handler thread. So, wait based on
        // the current monitoring interval and the number of CPU infos were injected.
        syncWithHandler(handler,
                /* delayMillis= */ mService.getCurrentMonitoringIntervalMillis() * cpuInfos.size());
    }

    private void syncWithHandler(Handler handler, long delayMillis) throws Exception {
        AtomicBoolean didRun = new AtomicBoolean(false);
        handler.postDelayed(() -> {
            synchronized (didRun) {
                didRun.set(true);
                didRun.notifyAll();
            }
        }, delayMillis);
        synchronized (didRun) {
            while (!didRun.get()) {
                didRun.wait(HANDLER_THREAD_SYNC_TIMEOUT_MILLISECONDS);
            }
        }
    }

    private static SparseArray<CpuInfoReader.CpuInfo> generateCpuInfosForAvailability(
            double cpuAvailabilityPercent, ArraySet<Integer> offlineCores) {
        SparseArray<CpuInfoReader.CpuInfo> cpuInfos = new SparseArray<>(STATIC_CPU_INFOS.size());
        for (StaticCpuInfo staticCpuInfo : STATIC_CPU_INFOS) {
            boolean isOnline = !offlineCores.contains(staticCpuInfo.cpuCore);
            cpuInfos.append(staticCpuInfo.cpuCore, constructCpuInfo(staticCpuInfo.cpuCore,
                    staticCpuInfo.cpusetCategories, isOnline, staticCpuInfo.maxCpuFreqKHz,
                    cpuAvailabilityPercent));
        }
        return cpuInfos;
    }

    private static CpuInfoReader.CpuInfo constructCpuInfo(int cpuCore,
            @CpuInfoReader.CpusetCategory int cpusetCategories, boolean isOnline,
            long maxCpuFreqKHz, double cpuAvailabilityPercent) {
        long availCpuFreqKHz = (long) (maxCpuFreqKHz * (cpuAvailabilityPercent / 100.0));
        long curCpuFreqKHz = maxCpuFreqKHz - availCpuFreqKHz;
        return new CpuInfoReader.CpuInfo(cpuCore, cpusetCategories, isOnline,
                isOnline ? curCpuFreqKHz : MISSING_FREQUENCY, maxCpuFreqKHz,
                /* avgTimeInStateCpuFreqKHz= */ MISSING_FREQUENCY,
                isOnline ? availCpuFreqKHz : MISSING_FREQUENCY,
                /* latestCpuUsageStats= */ null);
    }

    private static final class StaticCpuInfo {
        public final int cpuCore;
        public final int cpusetCategories;
        public final int maxCpuFreqKHz;

        StaticCpuInfo(int cpuCore, @CpuInfoReader.CpusetCategory int cpusetCategories,
                int maxCpuFreqKHz) {
            this.cpuCore = cpuCore;
            this.cpusetCategories = cpusetCategories;
            this.maxCpuFreqKHz = maxCpuFreqKHz;
        }

        @Override
        public String toString() {
            return "StaticCpuInfo{cpuCore=" + cpuCore + ", cpusetCategories=" + cpusetCategories
                    + ", maxCpuFreqKHz=" + maxCpuFreqKHz + '}';
        }
    }
}
