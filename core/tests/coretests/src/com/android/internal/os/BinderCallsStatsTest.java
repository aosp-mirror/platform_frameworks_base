/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BinderInternal.CallSession;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class BinderCallsStatsTest {
    private static final int WORKSOURCE_UID = Process.FIRST_APPLICATION_UID;
    private static final int CALLING_UID = 2;
    private static final int REQUEST_SIZE = 2;
    private static final int REPLY_SIZE = 3;
    private final CachedDeviceState mDeviceState = new CachedDeviceState(false, true);
    private final TestHandler mHandler = new TestHandler();

    @Test
    public void testDetailedOff() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(false);
        bcs.setSamplingInterval(5);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, uidEntry.callCount);
        assertEquals(1, uidEntry.recordedCallCount);
        assertEquals(10, uidEntry.cpuTimeMicros);
        assertEquals(binder.getClass(), callStatsList.get(0).binderClass);
        assertEquals(1, callStatsList.get(0).transactionCode);

        // CPU usage is sampled, should not be tracked here.
        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 20;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);
        assertEquals(2, uidEntry.callCount);
        assertEquals(1, uidEntry.recordedCallCount);
        assertEquals(10, uidEntry.cpuTimeMicros);
        assertEquals(1, callStatsList.size());

        callSession = bcs.callStarted(binder, 2, WORKSOURCE_UID);
        bcs.time += 50;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);
        uidEntry = bcs.getUidEntries().get(WORKSOURCE_UID);
        assertEquals(3, uidEntry.callCount);
        assertEquals(1, uidEntry.recordedCallCount);
        // Still sampled even for another API.
        callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());
    }

    @Test
    public void testDetailedOn() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        assertEquals(1, uidEntry.callCount);
        assertEquals(10, uidEntry.cpuTimeMicros);
        assertEquals(1, new ArrayList(uidEntry.getCallStatsList()).size());

        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.get(0).callCount);
        assertEquals(10, callStatsList.get(0).cpuTimeMicros);
        assertEquals(binder.getClass(), callStatsList.get(0).binderClass);
        assertEquals(1, callStatsList.get(0).transactionCode);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 20;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        uidEntry = bcs.getUidEntries().get(WORKSOURCE_UID);
        assertEquals(2, uidEntry.callCount);
        assertEquals(30, uidEntry.cpuTimeMicros);
        callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());

        callSession = bcs.callStarted(binder, 2, WORKSOURCE_UID);
        bcs.time += 50;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);
        uidEntry = bcs.getUidEntries().get(WORKSOURCE_UID);
        assertEquals(3, uidEntry.callCount);

        // This is the first transaction of a new type, so the real CPU time will be measured
        assertEquals(80, uidEntry.cpuTimeMicros);
        callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(2, callStatsList.size());
    }

    @Test
    public void testEnableInBetweenCall() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        Binder binder = new Binder();
        bcs.callEnded(null, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(0, uidEntries.size());
    }

    @Test
    public void testInBetweenCallWhenExceptionThrown() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        Binder binder = new Binder();
        bcs.callThrewException(null, new IllegalStateException());
        bcs.callEnded(null, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(0, uidEntries.size());
    }

    @Test
    public void testSampling() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(false);
        bcs.setSamplingInterval(2);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 1000;  // shoud be ignored.
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 50;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        assertEquals(3, uidEntry.callCount);
        assertEquals(60 /* 10 + 50 */, uidEntry.cpuTimeMicros);

        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());
        BinderCallsStats.CallStat callStats = callStatsList.get(0);
        assertEquals(3, callStats.callCount);
        assertEquals(2, callStats.recordedCallCount);
        assertEquals(60, callStats.cpuTimeMicros);
        assertEquals(50, callStats.maxCpuTimeMicros);
        assertEquals(0, callStats.maxRequestSizeBytes);
        assertEquals(0, callStats.maxReplySizeBytes);
    }

    @Test
    public void testSamplingWithDifferentApis() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(false);
        bcs.setSamplingInterval(2);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 2 /* another method */, WORKSOURCE_UID);
        bcs.time += 1000;  // shoud be ignored.
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);


        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        assertEquals(2, uidEntry.callCount);
        assertEquals(1, uidEntry.recordedCallCount);
        assertEquals(10, uidEntry.cpuTimeMicros);

        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());

        BinderCallsStats.CallStat callStats = callStatsList.get(0);
        assertEquals(1, callStats.callCount);
        assertEquals(1, callStats.recordedCallCount);
        assertEquals(10, callStats.cpuTimeMicros);
        assertEquals(10, callStats.maxCpuTimeMicros);
    }

    private static class BinderWithGetTransactionName extends Binder {
        public static String getDefaultTransactionName(int code) {
            return "resolved";
        }
    }

    private static class AnotherBinderWithGetTransactionName extends Binder {
        public static String getDefaultTransactionName(int code) {
            return "foo" + code;
        }
    }

    @Test
    public void testTransactionCodeResolved() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        Binder binder = new BinderWithGetTransactionName();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        List<BinderCallsStats.ExportedCallStat> callStatsList =
                bcs.getExportedCallStats();
        assertEquals("resolved", callStatsList.get(0).methodName);
    }

    @Test
    public void testMultipleTransactionCodeResolved() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);

        Binder binder = new AnotherBinderWithGetTransactionName();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        Binder binder2 = new BinderWithGetTransactionName();
        callSession = bcs.callStarted(binder2, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 2, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        List<BinderCallsStats.ExportedCallStat> callStatsList =
                bcs.getExportedCallStats();
        assertEquals("foo1", callStatsList.get(0).methodName);
        assertEquals(AnotherBinderWithGetTransactionName.class.getName(),
                callStatsList.get(0).className);
        assertEquals("foo2", callStatsList.get(1).methodName);
        assertEquals(AnotherBinderWithGetTransactionName.class.getName(),
                callStatsList.get(1).className);
        assertEquals("resolved", callStatsList.get(2).methodName);
        assertEquals(BinderWithGetTransactionName.class.getName(),
                callStatsList.get(2).className);
    }

    @Test
    public void testResolvingCodeDoesNotThrowWhenMethodNotPresent() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        List<BinderCallsStats.ExportedCallStat> callStatsList =
                bcs.getExportedCallStats();
        assertEquals("1", callStatsList.get(0).methodName);
    }

    @Test
    public void testParcelSize() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        List<BinderCallsStats.CallStat> callStatsList =
                new ArrayList(bcs.getUidEntries().get(WORKSOURCE_UID).getCallStatsList());

        assertEquals(REQUEST_SIZE, callStatsList.get(0).maxRequestSizeBytes);
        assertEquals(REPLY_SIZE, callStatsList.get(0).maxReplySizeBytes);
    }

    @Test
    public void testMaxCpu() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 50;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        List<BinderCallsStats.CallStat> callStatsList =
                new ArrayList(bcs.getUidEntries().get(WORKSOURCE_UID).getCallStatsList());

        assertEquals(50, callStatsList.get(0).maxCpuTimeMicros);
    }

    @Test
    public void testMaxLatency() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.elapsedTime += 5;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.elapsedTime += 1;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        List<BinderCallsStats.CallStat> callStatsList =
                new ArrayList(bcs.getUidEntries().get(WORKSOURCE_UID).getCallStatsList());

        assertEquals(5, callStatsList.get(0).maxLatencyMicros);
    }

    @Test
    public void testGetHighestValues() {
        List<Integer> list = Arrays.asList(1, 2, 3, 4);
        List<Integer> highestValues = BinderCallsStats
                .getHighestValues(list, value -> value, 0.8);
        assertEquals(Arrays.asList(4, 3, 2), highestValues);
    }

    @Test
    public void testExceptionCount() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callThrewException(callSession, new IllegalStateException());
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callThrewException(callSession, new IllegalStateException());
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callThrewException(callSession, new RuntimeException());
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        ArrayMap<String, Integer> expected = new ArrayMap<>();
        expected.put("java.lang.IllegalStateException", 2);
        expected.put("java.lang.RuntimeException", 1);
        assertEquals(expected, bcs.getExceptionCounts());
    }

    @Test
    public void testNoDataCollectedBeforeInitialDeviceStateSet() {
        TestBinderCallsStats bcs = new TestBinderCallsStats(null);
        bcs.setDetailedTracking(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        bcs.setDeviceState(mDeviceState.getReadonlyClient());

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(0, uidEntries.size());
    }

    @Test
    public void testNoDataCollectedOnCharger() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        mDeviceState.setCharging(true);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        assertEquals(0, bcs.getUidEntries().size());
    }

    @Test
    public void testScreenOff() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        mDeviceState.setScreenInteractive(false);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(false, callStatsList.get(0).screenInteractive);
    }

    @Test
    public void testScreenOn() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        mDeviceState.setScreenInteractive(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(true, callStatsList.get(0).screenInteractive);
    }

    @Test
    public void testOnCharger() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        mDeviceState.setCharging(true);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        assertEquals(0, bcs.getExportedCallStats().size());
    }

    @Test
    public void testOnBattery() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        mDeviceState.setCharging(false);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        assertEquals(1, bcs.getExportedCallStats().size());
    }

    @Test
    public void testDumpDoesNotThrowException() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callThrewException(callSession, new IllegalStateException());
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        PrintWriter pw = new PrintWriter(new StringWriter());
        bcs.dump(pw, new AppIdToPackageMap(new HashMap<>()), true);
    }

    @Test
    public void testGetExportedStatsWhenDetailedTrackingDisabled() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(false);
        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        assertEquals(0, bcs.getExportedCallStats().size());
    }

    @Test
    public void testGetExportedStatsWhenDetailedTrackingEnabled() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.elapsedTime += 20;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        assertEquals(1, bcs.getExportedCallStats().size());
        BinderCallsStats.ExportedCallStat stat = bcs.getExportedCallStats().get(0);
        assertEquals(WORKSOURCE_UID, stat.workSourceUid);
        assertEquals(CALLING_UID, stat.callingUid);
        assertEquals("android.os.Binder", stat.className);
        assertEquals("1", stat.methodName);
        assertEquals(true, stat.screenInteractive);
        assertEquals(10, stat.cpuTimeMicros);
        assertEquals(10, stat.maxCpuTimeMicros);
        assertEquals(20, stat.latencyMicros);
        assertEquals(20, stat.maxLatencyMicros);
        assertEquals(1, stat.callCount);
        assertEquals(1, stat.recordedCallCount);
        assertEquals(REQUEST_SIZE, stat.maxRequestSizeBytes);
        assertEquals(REPLY_SIZE, stat.maxReplySizeBytes);
        assertEquals(0, stat.exceptionCount);
    }

    @Test
    public void testGetExportedStatsWithoutCalls() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        Binder binder = new Binder();
        assertEquals(0, bcs.getExportedCallStats().size());
    }

    @Test
    public void testGetExportedExceptionsWithoutCalls() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        Binder binder = new Binder();
        assertEquals(0, bcs.getExceptionCounts().size());
    }

    @Test
    public void testOverflow_sameEntry() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        bcs.setSamplingInterval(1);
        bcs.setMaxBinderCallStats(2);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        BinderCallsStats.UidEntry uidEntry = bcs.getUidEntries().get(WORKSOURCE_UID);
        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());
        BinderCallsStats.CallStat callStats = callStatsList.get(0);
        assertEquals(3, callStats.callCount);
    }

    @Test
    public void testOverflow_overflowEntry() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        bcs.setSamplingInterval(1);
        bcs.setMaxBinderCallStats(1);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 2, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        // Should use the same overflow entry.
        callSession = bcs.callStarted(binder, 3, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        List<BinderCallsStats.ExportedCallStat> callStatsList = bcs.getExportedCallStats();
        assertEquals(2, callStatsList.size());
        BinderCallsStats.ExportedCallStat callStats = callStatsList.get(0);
        assertEquals(1, callStats.callCount);
        assertEquals("1", callStats.methodName);
        assertEquals("android.os.Binder", callStats.className);
        assertEquals(CALLING_UID, callStats.callingUid);

        callStats = callStatsList.get(1);
        assertEquals(2, callStats.callCount);
        assertEquals("-1", callStats.methodName);
        assertEquals("com.android.internal.os.BinderCallsStats$OverflowBinder",
                callStats.className);
        assertEquals(false , callStats.screenInteractive);
        assertEquals(-1 , callStats.callingUid);
    }

    @Test
    public void testOverflow_oneOverflowEntryPerUid() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setDetailedTracking(true);
        bcs.setSamplingInterval(1);
        bcs.setMaxBinderCallStats(1);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 2, WORKSOURCE_UID + 1);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID + 1);

        // Different uids have different overflow entries.
        callSession = bcs.callStarted(binder, 2, WORKSOURCE_UID + 2);
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID + 2);

        List<BinderCallsStats.ExportedCallStat> callStatsList = bcs.getExportedCallStats();
        assertEquals(3, callStatsList.size());

        BinderCallsStats.ExportedCallStat callStats = callStatsList.get(1);
        assertEquals(WORKSOURCE_UID + 1, callStats.workSourceUid);
        assertEquals(1, callStats.callCount);
        assertEquals("com.android.internal.os.BinderCallsStats$OverflowBinder",
                callStats.className);

        callStats = callStatsList.get(2);
        assertEquals(WORKSOURCE_UID + 2, callStats.workSourceUid);
        assertEquals(1, callStats.callCount);
        assertEquals("com.android.internal.os.BinderCallsStats$OverflowBinder",
                callStats.className);
    }

    @Test
    public void testAddsDebugEntries() {
        long startTime = SystemClock.elapsedRealtime();
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setAddDebugEntries(true);
        bcs.setSamplingInterval(10);
        ArrayList<BinderCallsStats.ExportedCallStat> callStats = bcs.getExportedCallStats();
        assertEquals(4, callStats.size());
        BinderCallsStats.ExportedCallStat debugEntry1 = callStats.get(0);
        assertEquals("", debugEntry1.className);
        assertEquals("__DEBUG_start_time_millis", debugEntry1.methodName);
        assertTrue(startTime <= debugEntry1.latencyMicros);
        BinderCallsStats.ExportedCallStat debugEntry2 = callStats.get(1);
        assertEquals("", debugEntry2.className);
        assertEquals("__DEBUG_end_time_millis", debugEntry2.methodName);
        assertTrue(debugEntry1.latencyMicros <= debugEntry2.latencyMicros);
        BinderCallsStats.ExportedCallStat debugEntry3 = callStats.get(2);
        assertEquals("", debugEntry3.className);
        assertEquals("__DEBUG_battery_time_millis", debugEntry3.methodName);
        assertTrue(debugEntry3.latencyMicros >= 0);
        BinderCallsStats.ExportedCallStat debugEntry4 = callStats.get(3);
        assertEquals("__DEBUG_sampling_interval", debugEntry4.methodName);
        assertEquals(10, debugEntry4.latencyMicros);
    }

    @Test
    public void testTrackScreenInteractiveDisabled() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setTrackScreenInteractive(false);
        Binder binder = new Binder();

        mDeviceState.setScreenInteractive(false);
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        mDeviceState.setScreenInteractive(true);
        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 1000;  // shoud be ignored.
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        assertEquals(2, uidEntry.callCount);

        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());
        BinderCallsStats.CallStat callStats = callStatsList.get(0);
        assertEquals(false, callStats.screenInteractive);
        assertEquals(2, callStats.callCount);
        assertEquals(2, callStats.recordedCallCount);
    }

    @Test
    public void testTrackCallingUidDisabled() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setTrackDirectCallerUid(false);
        Binder binder = new Binder();

        bcs.setCallingUid(1);
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        bcs.setCallingUid(2);
        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 1000;  // shoud be ignored.
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        assertEquals(2, uidEntry.callCount);

        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());
        BinderCallsStats.CallStat callStats = callStatsList.get(0);
        assertEquals(-1, callStats.callingUid);
        assertEquals(2, callStats.callCount);
        assertEquals(2, callStats.recordedCallCount);
    }

    @Test
    public void testTrackScreenInteractiveDisabled_sampling() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setSamplingInterval(2);
        bcs.setTrackScreenInteractive(false);
        Binder binder = new Binder();

        mDeviceState.setScreenInteractive(false);
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        mDeviceState.setScreenInteractive(true);
        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 1000;  // shoud be ignored.
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        SparseArray<BinderCallsStats.UidEntry> uidEntries = bcs.getUidEntries();
        assertEquals(1, uidEntries.size());
        BinderCallsStats.UidEntry uidEntry = uidEntries.get(WORKSOURCE_UID);
        Assert.assertNotNull(uidEntry);
        assertEquals(2, uidEntry.callCount);

        List<BinderCallsStats.CallStat> callStatsList = new ArrayList(uidEntry.getCallStatsList());
        assertEquals(1, callStatsList.size());
        BinderCallsStats.CallStat callStats = callStatsList.get(0);
        assertEquals(false, callStats.screenInteractive);
        assertEquals(2, callStats.callCount);
        assertEquals(1, callStats.recordedCallCount);
    }

    @Test
    public void testCallStatsObserver() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setSamplingInterval(1);
        bcs.setTrackScreenInteractive(false);

        final ArrayList<BinderCallsStats.CallStat> callStatsList = new ArrayList<>();
        bcs.setCallStatsObserver((workSourceUid, callStats) -> callStatsList.addAll(callStats));

        Binder binder = new Binder();

        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 20;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        callSession = bcs.callStarted(binder, 2, WORKSOURCE_UID);
        bcs.time += 30;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        for (Runnable runnable: mHandler.mRunnables) {
            // Execute all pending runnables. Ignore the delay.
            runnable.run();
        }

        assertThat(callStatsList).hasSize(2);
        for (int i = 0; i < 2; i++) {
            BinderCallsStats.CallStat callStats = callStatsList.get(i);
            if (callStats.transactionCode == 1) {
                assertEquals(2, callStats.callCount);
                assertEquals(2, callStats.recordedCallCount);
                assertEquals(30, callStats.cpuTimeMicros);
                assertEquals(20, callStats.maxCpuTimeMicros);
            } else {
                assertEquals(1, callStats.callCount);
                assertEquals(1, callStats.recordedCallCount);
                assertEquals(30, callStats.cpuTimeMicros);
                assertEquals(30, callStats.maxCpuTimeMicros);
            }
        }
    }

    @Test
    public void testLatencyCollectionEnabled() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        bcs.setCollectLatencyData(true);

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.elapsedTime += 20;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        assertEquals(1, bcs.getLatencyObserver().getLatencyHistograms().size());
    }

    @Test
    public void testLatencyCollectionDisabledByDefault() {
        TestBinderCallsStats bcs = new TestBinderCallsStats();
        assertEquals(false, bcs.getCollectLatencyData());

        Binder binder = new Binder();
        CallSession callSession = bcs.callStarted(binder, 1, WORKSOURCE_UID);
        bcs.time += 10;
        bcs.elapsedTime += 20;
        bcs.callEnded(callSession, REQUEST_SIZE, REPLY_SIZE, WORKSOURCE_UID);

        assertEquals(0, bcs.getLatencyObserver().getLatencyHistograms().size());
    }

    private static class TestHandler extends Handler {
        ArrayList<Runnable> mRunnables = new ArrayList<>();

        TestHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            mRunnables.add(msg.getCallback());
            return true;
        }
    }

    class TestBinderCallsStats extends BinderCallsStats {
        public int callingUid = CALLING_UID;
        public long time = 1234;
        public long elapsedTime = 0;

        TestBinderCallsStats() {
            this(mDeviceState);
        }

        TestBinderCallsStats(CachedDeviceState deviceState) {
            // Make random generator not random.
            super(new Injector() {
                public Random getRandomGenerator() {
                    return new Random() {
                        int mCallCount = 0;

                        public int nextInt() {
                            return mCallCount++;
                        }
                    };
                }

                public Handler getHandler() {
                    return mHandler;
                }

                public BinderLatencyObserver getLatencyObserver() {
                    return new BinderLatencyObserverTest.TestBinderLatencyObserver();
                }
            });
            setSamplingInterval(1);
            setAddDebugEntries(false);
            setTrackScreenInteractive(true);
            setTrackDirectCallerUid(true);
            if (deviceState != null) {
                setDeviceState(deviceState.getReadonlyClient());
            }
        }

        @Override
        protected long getThreadTimeMicro() {
            return time;
        }

        @Override
        protected long getElapsedRealtimeMicro() {
            return elapsedTime;
        }

        @Override
        protected int getCallingUid() {
            return callingUid;
        }

        protected void setCallingUid(int uid) {
            callingUid = uid;
        }
    }

}
