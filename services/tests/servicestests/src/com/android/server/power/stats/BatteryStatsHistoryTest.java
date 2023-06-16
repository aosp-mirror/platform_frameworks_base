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
 * limitations under the License.
 */

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryStats.CpuUsageDetails;
import android.os.BatteryStats.EnergyConsumerDetails;
import android.os.BatteryStats.HistoryItem;
import android.os.Parcel;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.Clock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test BatteryStatsHistory.
 */
@RunWith(AndroidJUnit4.class)
public class BatteryStatsHistoryTest {
    private static final String TAG = "BatteryStatsHistoryTest";
    private final Parcel mHistoryBuffer = Parcel.obtain();
    private File mSystemDir;
    private File mHistoryDir;
    private final Clock mClock = new MockClock();
    private BatteryStatsHistory mHistory;
    @Mock
    private BatteryStatsHistory.TraceDelegate mTracer;
    @Mock
    private BatteryStatsHistory.HistoryStepDetailsCalculator mStepDetailsCalculator;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getContext();
        mSystemDir = context.getDataDir();
        mHistoryDir = new File(mSystemDir, "battery-history");
        String[] files = mHistoryDir.list();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                new File(mHistoryDir, files[i]).delete();
            }
        }
        mHistoryDir.delete();
        mHistory = new BatteryStatsHistory(mHistoryBuffer, mSystemDir, 32, 1024,
                mStepDetailsCalculator, mClock, mTracer);

        when(mStepDetailsCalculator.getHistoryStepDetails())
                .thenReturn(new BatteryStats.HistoryStepDetails());
    }

    @Test
    public void testAtraceBinaryState1() {
        InOrder inOrder = Mockito.inOrder(mTracer);
        Mockito.when(mTracer.tracingEnabled()).thenReturn(true);

        mHistory.recordStateStartEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG);
        mHistory.recordStateStopEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG);
        mHistory.recordStateStartEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG);

        inOrder.verify(mTracer).traceCounter("battery_stats.mobile_radio", 1);
        inOrder.verify(mTracer).traceCounter("battery_stats.mobile_radio", 0);
        inOrder.verify(mTracer).traceCounter("battery_stats.mobile_radio", 1);
    }

    @Test
    public void testAtraceBinaryState2() {
        InOrder inOrder = Mockito.inOrder(mTracer);
        Mockito.when(mTracer.tracingEnabled()).thenReturn(true);

        mHistory.recordState2StartEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), HistoryItem.STATE2_WIFI_ON_FLAG);
        mHistory.recordState2StopEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), HistoryItem.STATE2_WIFI_ON_FLAG);
        mHistory.recordState2StartEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), HistoryItem.STATE2_WIFI_ON_FLAG);

        inOrder.verify(mTracer).traceCounter("battery_stats.wifi", 1);
        inOrder.verify(mTracer).traceCounter("battery_stats.wifi", 0);
        inOrder.verify(mTracer).traceCounter("battery_stats.wifi", 1);
    }

    @Test
    public void testAtraceNumericalState() {
        InOrder inOrder = Mockito.inOrder(mTracer);
        Mockito.when(mTracer.tracingEnabled()).thenReturn(true);

        mHistory.recordDataConnectionTypeChangeEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), 1);
        mHistory.recordDataConnectionTypeChangeEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), 2);
        mHistory.recordDataConnectionTypeChangeEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), 3);

        inOrder.verify(mTracer).traceCounter("battery_stats.data_conn", 1);
        inOrder.verify(mTracer).traceCounter("battery_stats.data_conn", 2);
        inOrder.verify(mTracer).traceCounter("battery_stats.data_conn", 3);
    }

    @Test
    public void testAtraceInstantEvent() {
        InOrder inOrder = Mockito.inOrder(mTracer);
        Mockito.when(mTracer.tracingEnabled()).thenReturn(true);

        mHistory.recordEvent(mClock.elapsedRealtime(), mClock.uptimeMillis(),
                HistoryItem.EVENT_WAKEUP_AP, "", 1234);
        mHistory.recordEvent(mClock.elapsedRealtime(), mClock.uptimeMillis(),
                HistoryItem.EVENT_JOB_START, "jobname", 2468);
        mHistory.recordEvent(mClock.elapsedRealtime(), mClock.uptimeMillis(),
                HistoryItem.EVENT_JOB_FINISH, "jobname", 2468);

        inOrder.verify(mTracer).traceInstantEvent("battery_stats.wakeupap", "wakeupap=1234:\"\"");
        inOrder.verify(mTracer).traceInstantEvent("battery_stats.job", "+job=2468:\"jobname\"");
        inOrder.verify(mTracer).traceInstantEvent("battery_stats.job", "-job=2468:\"jobname\"");
    }

    @Test
    public void testConstruct() {
        createActiveFile(mHistory);
        verifyFileNumbers(mHistory, Arrays.asList(0));
        verifyActiveFile(mHistory, "0.bin");
    }

    @Test
    public void testStartNextFile() {
        List<Integer> fileList = new ArrayList<>();
        fileList.add(0);
        createActiveFile(mHistory);

        // create file 1 to 31.
        for (int i = 1; i < 32; i++) {
            fileList.add(i);
            mHistory.startNextFile();
            createActiveFile(mHistory);
            verifyFileNumbers(mHistory, fileList);
            verifyActiveFile(mHistory, i + ".bin");
        }

        // create file 32
        mHistory.startNextFile();
        createActiveFile(mHistory);
        fileList.add(32);
        fileList.remove(0);
        // verify file 0 is deleted.
        verifyFileDeleted("0.bin");
        verifyFileNumbers(mHistory, fileList);
        verifyActiveFile(mHistory, "32.bin");

        // create file 33
        mHistory.startNextFile();
        createActiveFile(mHistory);
        // verify file 1 is deleted
        fileList.add(33);
        fileList.remove(0);
        verifyFileDeleted("1.bin");
        verifyFileNumbers(mHistory, fileList);
        verifyActiveFile(mHistory, "33.bin");

        assertEquals(0, mHistory.getHistoryUsedSize());

        // create a new BatteryStatsHistory object, it will pick up existing history files.
        BatteryStatsHistory history2 = new BatteryStatsHistory(mHistoryBuffer, mSystemDir, 32, 1024,
                null, mClock, mTracer);
        // verify constructor can pick up all files from file system.
        verifyFileNumbers(history2, fileList);
        verifyActiveFile(history2, "33.bin");

        history2.reset();
        createActiveFile(history2);
        // verify all existing files are deleted.
        for (int i = 2; i < 33; ++i) {
            verifyFileDeleted(i + ".bin");
        }

        // verify file 0 is created
        verifyFileNumbers(history2, Arrays.asList(0));
        verifyActiveFile(history2, "0.bin");

        // create file 1.
        history2.startNextFile();
        createActiveFile(history2);
        verifyFileNumbers(history2, Arrays.asList(0, 1));
        verifyActiveFile(history2, "1.bin");
    }

    private void verifyActiveFile(BatteryStatsHistory history, String file) {
        final File expectedFile = new File(mHistoryDir, file);
        assertEquals(expectedFile.getPath(), history.getActiveFile().getBaseFile().getPath());
        assertTrue(expectedFile.exists());
    }

    private void verifyFileNumbers(BatteryStatsHistory history, List<Integer> fileList) {
        assertEquals(fileList.size(), history.getFilesNumbers().size());
        for (int i = 0; i < fileList.size(); i++) {
            assertEquals(fileList.get(i), history.getFilesNumbers().get(i));
            final File expectedFile =
                    new File(mHistoryDir, fileList.get(i) + ".bin");
            assertTrue(expectedFile.exists());
        }
    }

    private void verifyFileDeleted(String file) {
        assertFalse(new File(mHistoryDir, file).exists());
    }

    private void createActiveFile(BatteryStatsHistory history) {
        final File file = history.getActiveFile().getBaseFile();
        try {
            file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Error creating history file " + file.getPath(), e);
        }
    }

    @Test
    public void testRecordMeasuredEnergyDetails() {
        mHistory.forceRecordAllHistory();
        mHistory.startRecordingHistory(0, 0, /* reset */ true);
        mHistory.setBatteryState(true /* charging */, BatteryManager.BATTERY_STATUS_CHARGING, 80,
                1234);

        EnergyConsumerDetails details = new EnergyConsumerDetails();
        EnergyConsumerDetails.EnergyConsumer consumer1 =
                new EnergyConsumerDetails.EnergyConsumer();
        consumer1.type = 42;
        consumer1.ordinal = 0;
        consumer1.name = "A";

        EnergyConsumerDetails.EnergyConsumer consumer2 =
                new EnergyConsumerDetails.EnergyConsumer();
        consumer2.type = 777;
        consumer2.ordinal = 0;
        consumer2.name = "B/0";

        EnergyConsumerDetails.EnergyConsumer consumer3 =
                new EnergyConsumerDetails.EnergyConsumer();
        consumer3.type = 777;
        consumer3.ordinal = 1;
        consumer3.name = "B/1";

        EnergyConsumerDetails.EnergyConsumer consumer4 =
                new EnergyConsumerDetails.EnergyConsumer();
        consumer4.type = 314;
        consumer4.ordinal = 1;
        consumer4.name = "C";

        details.consumers =
                new EnergyConsumerDetails.EnergyConsumer[]{consumer1, consumer2, consumer3,
                        consumer4};
        details.chargeUC = new long[details.consumers.length];
        for (int i = 0; i < details.chargeUC.length; i++) {
            details.chargeUC[i] = 100L * i;
        }
        details.chargeUC[3] = BatteryStats.POWER_DATA_UNAVAILABLE;

        mHistory.recordEnergyConsumerDetails(200, 200, details);

        BatteryStatsHistoryIterator iterator = mHistory.iterate();
        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull(); // First item contains current time only

        assertThat(item = iterator.next()).isNotNull();

        String dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("+200ms");
        assertThat(dump).contains("ext=energy:A=0 B/0=100 B/1=200");
        assertThat(dump).doesNotContain("C=");

        String checkin = toString(item, /* checkin */ true);
        assertThat(checkin).contains("XE");
        assertThat(checkin).contains("A=0,B/0=100,B/1=200");
        assertThat(checkin).doesNotContain("C=");
    }

    @Test
    public void cpuUsageDetails() {
        mHistory.forceRecordAllHistory();
        mHistory.startRecordingHistory(0, 0, /* reset */ true);
        mHistory.setBatteryState(true /* charging */, BatteryManager.BATTERY_STATUS_CHARGING, 80,
                1234);

        CpuUsageDetails details = new CpuUsageDetails();
        details.cpuBracketDescriptions = new String[] {"low", "Med", "HIGH"};
        details.uid = 10123;
        details.cpuUsageMs = new long[] { 100, 200, 300};
        mHistory.recordCpuUsage(200, 200, details);

        details.uid = 10321;
        details.cpuUsageMs = new long[] { 400, 500, 600};
        mHistory.recordCpuUsage(300, 300, details);

        BatteryStatsHistoryIterator iterator = mHistory.iterate();
        BatteryStats.HistoryItem item = new BatteryStats.HistoryItem();
        assertThat(item = iterator.next()).isNotNull(); // First item contains current time only

        assertThat(item = iterator.next()).isNotNull();

        String dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("+200ms");
        assertThat(dump).contains("ext=cpu:u0a123: 100, 200, 300");
        assertThat(dump).contains("ext=cpu-bracket:0:low");
        assertThat(dump).contains("ext=cpu-bracket:1:Med");
        assertThat(dump).contains("ext=cpu-bracket:2:HIGH");

        String checkin = toString(item, /* checkin */ true);
        assertThat(checkin).contains("XB,3,0,low");
        assertThat(checkin).contains("XB,3,1,Med");
        assertThat(checkin).contains("XB,3,2,HIGH");
        assertThat(checkin).contains("XC,10123,100,200,300");

        assertThat(item = iterator.next()).isNotNull();

        dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("+300ms");
        assertThat(dump).contains("ext=cpu:u0a321: 400, 500, 600");
        // Power bracket descriptions are written only once
        assertThat(dump).doesNotContain("ext=cpu-bracket");

        checkin = toString(item, /* checkin */ true);
        assertThat(checkin).doesNotContain("XB");
        assertThat(checkin).contains("XC,10321,400,500,600");
    }

    private String toString(BatteryStats.HistoryItem item, boolean checkin) {
        BatteryStats.HistoryPrinter printer = new BatteryStats.HistoryPrinter();
        StringWriter writer = new StringWriter();
        PrintWriter pw = new PrintWriter(writer);
        printer.printNextItem(pw, item, 0, checkin, /* verbose */ true);
        pw.flush();
        return writer.toString();
    }

    @Test
    public void testVarintParceler() {
        long[] values = {
                0,
                1,
                42,
                0x1234,
                0x10000000,
                0x12345678,
                0x7fffffff,
                0xffffffffL,
                0x100000000000L,
                0x123456789012L,
                0x1000000000000000L,
                0x1234567890123456L,
                0x7fffffffffffffffL,
                0xffffffffffffffffL};

        // Parcel subarrays of different lengths and assert the size of the resulting parcel
        testVarintParceler(Arrays.copyOfRange(values, 0, 1), 4);   // v. 8
        testVarintParceler(Arrays.copyOfRange(values, 0, 2), 4);   // v. 16
        testVarintParceler(Arrays.copyOfRange(values, 0, 3), 4);   // v. 24
        testVarintParceler(Arrays.copyOfRange(values, 0, 4), 8);   // v. 32
        testVarintParceler(Arrays.copyOfRange(values, 0, 5), 12);  // v. 40
        testVarintParceler(Arrays.copyOfRange(values, 0, 6), 16);  // v. 48
        testVarintParceler(Arrays.copyOfRange(values, 0, 7), 20);  // v. 56
        testVarintParceler(Arrays.copyOfRange(values, 0, 8), 28);  // v. 64
        testVarintParceler(Arrays.copyOfRange(values, 0, 9), 32);  // v. 72
        testVarintParceler(Arrays.copyOfRange(values, 0, 10), 40); // v. 80
        testVarintParceler(Arrays.copyOfRange(values, 0, 11), 48); // v. 88
        testVarintParceler(Arrays.copyOfRange(values, 0, 12), 60); // v. 96
        testVarintParceler(Arrays.copyOfRange(values, 0, 13), 68); // v. 104
        testVarintParceler(Arrays.copyOfRange(values, 0, 14), 76); // v. 112
    }

    private void testVarintParceler(long[] values, int expectedLength) {
        BatteryStatsHistory.VarintParceler parceler = new BatteryStatsHistory.VarintParceler();
        Parcel parcel = Parcel.obtain();
        parcel.writeString("begin");
        int pos = parcel.dataPosition();
        parceler.writeLongArray(parcel, values);
        int length = parcel.dataPosition() - pos;
        parcel.writeString("end");

        byte[] bytes = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);

        assertThat(parcel.readString()).isEqualTo("begin");

        long[] result = new long[values.length];
        parceler.readLongArray(parcel, result);

        assertThat(result).isEqualTo(values);
        assertThat(length).isEqualTo(expectedLength);

        assertThat(parcel.readString()).isEqualTo("end");

        parcel.recycle();
    }
}
