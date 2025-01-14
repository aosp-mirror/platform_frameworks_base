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
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryStats.HistoryItem;
import android.os.ConditionVariable;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.platform.test.ravenwood.RavenwoodRule;
import android.telephony.NetworkRegistrationInfo;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * Test BatteryStatsHistory.
 */
@RunWith(AndroidJUnit4.class)
@EnableFlags({com.android.server.power.optimization.Flags
        .FLAG_EXTENDED_BATTERY_HISTORY_CONTINUOUS_COLLECTION_ENABLED})
public class BatteryStatsHistoryTest {
    private static final String TAG = "BatteryStatsHistoryTest";

    private static final int MAX_HISTORY_BUFFER_SIZE = 1024;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private final Parcel mHistoryBuffer = Parcel.obtain();
    private File mSystemDir;
    private File mHistoryDir;
    private final MockClock mClock = new MockClock();
    private final MonotonicClock mMonotonicClock = new MonotonicClock(0, mClock);
    private BatteryHistoryDirectory mDirectory;
    private BatteryStatsHistory mHistory;
    private BatteryStats.HistoryPrinter mHistoryPrinter;
    @Mock
    private BatteryStatsHistory.TraceDelegate mTracer;
    @Mock
    private BatteryStatsHistory.HistoryStepDetailsCalculator mStepDetailsCalculator;
    @Mock
    private BatteryStatsHistory.EventLogger mEventLogger;
    private List<String> mReadFiles = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        mSystemDir = Files.createTempDirectory("BatteryStatsHistoryTest").toFile();
        mHistoryDir = new File(mSystemDir, "battery-history");
        String[] files = mHistoryDir.list();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                new File(mHistoryDir, files[i]).delete();
            }
        }
        mHistoryDir.delete();


        BatteryHistoryDirectory.Compressor compressor;
        if (RavenwoodRule.isOnRavenwood()) {
            compressor = new BatteryHistoryDirectory.Compressor() {
                @Override
                public void compress(OutputStream stream, byte[] data) throws IOException {
                    stream.write(data);
                }

                @Override
                public void uncompress(byte[] data, InputStream stream) throws IOException {
                    readFully(data, stream);
                }
            };
        } else {
            compressor = BatteryHistoryDirectory.DEFAULT_COMPRESSOR;
        }
        mDirectory = new BatteryHistoryDirectory(mHistoryDir, 32768, compressor);

        mClock.realtime = 123;
        mClock.currentTime = 1743645660000L;    //  2025-04-03, 2:01:00 AM

        mHistory = new BatteryStatsHistory(mHistoryBuffer, MAX_HISTORY_BUFFER_SIZE, mDirectory,
                mStepDetailsCalculator, mClock, mMonotonicClock, mTracer,
                mEventLogger);
        mHistory.forceRecordAllHistory();
        mHistory.startRecordingHistory(mClock.realtime, mClock.uptime, false);

        when(mStepDetailsCalculator.getHistoryStepDetails())
                .thenReturn(new BatteryStats.HistoryStepDetails());

        mHistoryPrinter = new BatteryStats.HistoryPrinter(TimeZone.getTimeZone("GMT"));
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
    public void testAtraceExcludedState() {
        Mockito.when(mTracer.tracingEnabled()).thenReturn(true);

        mHistory.recordStateStartEvent(mClock.elapsedRealtime(),
                mClock.uptimeMillis(), HistoryItem.STATE_WAKE_LOCK_FLAG);

        Mockito.verify(mTracer, Mockito.never()).traceCounter(
                Mockito.anyString(), Mockito.anyInt());
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
        verifyFileNames(mHistory, Arrays.asList("123.bh"));
        verifyActiveFile(mHistory, "123.bh");
    }

    @Test
    public void testStartNextFile() {
        mHistory.forceRecordAllHistory();
        mDirectory.setFileCompressionEnabled(false);

        mClock.realtime = 123;

        List<String> fileList = new ArrayList<>();
        fileList.add("123.bh");
        createActiveFile(mHistory);
        fillActiveFile(mHistory);

        // create file 1 to 31.
        for (int i = 1; i < 32; i++) {
            mClock.realtime = 1000 * i;
            fileList.add(mClock.realtime + ".bh");

            mHistory.startNextFragment(mClock.realtime);
            createActiveFile(mHistory);
            fillActiveFile(mHistory);

            verifyFileNames(mHistory, fileList);
            verifyActiveFile(mHistory, mClock.realtime + ".bh");
        }

        // create file 32
        mClock.realtime = 1000 * 32;
        mHistory.startNextFragment(mClock.realtime);
        createActiveFile(mHistory);
        fillActiveFile(mHistory);
        fileList.add("32000.bh");
        fileList.remove(0);
        // verify file 0 is deleted.
        verifyFileDeleted("123.bh");
        verifyFileNames(mHistory, fileList);
        verifyActiveFile(mHistory, "32000.bh");

        // create file 33
        mClock.realtime = 1000 * 33;
        mHistory.startNextFragment(mClock.realtime);
        createActiveFile(mHistory);
        fillActiveFile(mHistory);
        fileList.add("33000.bh");
        fileList.remove(0);
        mHistory.writeHistory();

        // verify file 1 is deleted
        verifyFileDeleted("1000.bh");
        verifyFileNames(mHistory, fileList);
        verifyActiveFile(mHistory, "33000.bh");

        // create a new BatteryStatsHistory object, it will pick up existing history files.
        BatteryStatsHistory history2 = new BatteryStatsHistory(mHistoryBuffer, 1024, mDirectory,
                null, mClock, mMonotonicClock, mTracer, mEventLogger);
        // verify constructor can pick up all files from file system.
        verifyFileNames(history2, fileList);
        verifyActiveFile(history2, "33000.bh");

        mClock.realtime = 1234567;

        history2.reset();
        createActiveFile(history2);

        // verify all existing files are deleted.
        for (String file : fileList) {
            verifyFileDeleted(file);
        }

        // verify file 0 is created
        verifyFileNames(history2, Arrays.asList("1234567.bh"));
        verifyActiveFile(history2, "1234567.bh");

        // create file 1.
        mClock.realtime = 2345678;

        history2.startNextFragment(mClock.realtime);
        createActiveFile(history2);
        verifyFileNames(history2, Arrays.asList("1234567.bh", "2345678.bh"));
        verifyActiveFile(history2, "2345678.bh");
    }

    @Test
    public void unconstrainedIteration() {
        prepareMultiFileHistory();

        mReadFiles.clear();

        // Make an immutable copy and spy on it
        mHistory = spy(mHistory.copy());

        doAnswer(invocation -> {
            BatteryHistoryDirectory.BatteryHistoryFile file = invocation.getArgument(1);
            mReadFiles.add(file.atomicFile.getBaseFile().getName());
            return invocation.callRealMethod();
        }).when(mHistory).readFragmentToParcel(any(), any());

        // Prepare history for iteration
        mHistory.iterate(0, MonotonicClock.UNDEFINED);

        Parcel parcel = mHistory.getNextParcel(0, Long.MAX_VALUE);
        assertThat(parcel).isNotNull();
        assertThat(mReadFiles).containsExactly("123.bh");

        // Skip to the end to force reading the next parcel
        parcel.setDataPosition(parcel.dataSize());
        mReadFiles.clear();
        parcel = mHistory.getNextParcel(0, Long.MAX_VALUE);
        assertThat(parcel).isNotNull();
        assertThat(mReadFiles).containsExactly("1000.bh");

        parcel.setDataPosition(parcel.dataSize());
        mReadFiles.clear();
        parcel = mHistory.getNextParcel(0, Long.MAX_VALUE);
        assertThat(parcel).isNotNull();
        assertThat(mReadFiles).containsExactly("2000.bh");

        parcel.setDataPosition(parcel.dataSize());
        mReadFiles.clear();
        parcel = mHistory.getNextParcel(0, Long.MAX_VALUE);
        assertThat(parcel).isNull();
        assertThat(mReadFiles).isEmpty();
    }

    @Test
    public void constrainedIteration() {
        prepareMultiFileHistory();

        mReadFiles.clear();

        // Make an immutable copy and spy on it
        mHistory = spy(mHistory.copy());

        doAnswer(invocation -> {
            BatteryHistoryDirectory.BatteryHistoryFile file = invocation.getArgument(1);
            mReadFiles.add(file.atomicFile.getBaseFile().getName());
            return invocation.callRealMethod();
        }).when(mHistory).readFragmentToParcel(any(), any());

        // Prepare history for iteration
        mHistory.iterate(1000, 3000);

        Parcel parcel = mHistory.getNextParcel(1000, 3000);
        assertThat(parcel).isNotNull();
        assertThat(mReadFiles).containsExactly("1000.bh");

        // Skip to the end to force reading the next parcel
        parcel.setDataPosition(parcel.dataSize());
        mReadFiles.clear();
        parcel = mHistory.getNextParcel(1000, 3000);
        assertThat(parcel).isNotNull();
        assertThat(mReadFiles).containsExactly("2000.bh");

        parcel.setDataPosition(parcel.dataSize());
        mReadFiles.clear();
        parcel = mHistory.getNextParcel(1000, 3000);
        assertThat(parcel).isNull();
        assertThat(mReadFiles).isEmpty();
    }

    private void prepareMultiFileHistory() {
        mClock.realtime = 1000;
        mClock.uptime = 1000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.EVENT_JOB_START, "job", 42);

        mHistory.startNextFragment(mClock.realtime);       // 1000.bh

        mClock.realtime = 2000;
        mClock.uptime = 2000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.EVENT_JOB_FINISH, "job", 42);

        mHistory.startNextFragment(mClock.realtime);       // 2000.bh

        mClock.realtime = 3000;
        mClock.uptime = 3000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                HistoryItem.EVENT_ALARM, "alarm", 42);

        // Flush accumulated history to disk
        mHistory.startNextFragment(mClock.realtime);
    }

    private void verifyActiveFile(BatteryStatsHistory history, String file) {
        final File expectedFile = new File(mHistoryDir, file);
        assertEquals(expectedFile.getPath(),
                ((BatteryHistoryDirectory.BatteryHistoryFile) history.getActiveFragment())
                        .atomicFile.getBaseFile().getPath());
        assertTrue(expectedFile.exists());
    }

    private void verifyFileNames(BatteryStatsHistory history, List<String> fileList) {
        awaitCompletion();
        List<String> fileNames =
                ((BatteryHistoryDirectory) history.getBatteryHistoryStore()).getFileNames();
        assertThat(fileNames).isEqualTo(fileList);
        for (int i = 0; i < fileList.size(); i++) {
            final File expectedFile = new File(mHistoryDir, fileList.get(i));
            assertWithMessage("File does not exist " + expectedFile)
                    .that(expectedFile.exists()).isTrue();
        }
    }

    private void verifyFileDeleted(String file) {
        awaitCompletion();
        assertFalse(new File(mHistoryDir, file).exists());
    }

    private void createActiveFile(BatteryStatsHistory history) {
        File file = ((BatteryHistoryDirectory.BatteryHistoryFile) history.getActiveFragment())
                .atomicFile.getBaseFile();
        if (file.exists()) {
            return;
        }
        try {
            file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Error creating history file " + file.getPath(), e);
        }
    }

    private void fillActiveFile(BatteryStatsHistory history) {
        // Create roughly 1K of history
        int initialSize = history.getHistoryUsedSize();
        while (history.getHistoryUsedSize() < initialSize + 1000) {
            history.recordCurrentTimeChange(mClock.realtime, mClock.uptime, 0xFFFFFFFFL);
        }
    }

    @Test
    public void recordPowerStats() {
        PowerStats.Descriptor descriptor = new PowerStats.Descriptor(42, "foo", 1, null, 0, 2,
                new PersistableBundle());
        PowerStats powerStats = new PowerStats(descriptor);
        powerStats.durationMs = 100;
        powerStats.stats[0] = 200;
        powerStats.uidStats.put(300, new long[]{400, 500});
        powerStats.uidStats.put(600, new long[]{700, 800});

        mClock.advance(200);
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        BatteryStatsHistoryIterator iterator = mHistory.iterate(0, MonotonicClock.UNDEFINED);
        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull(); // First item contains current time only

        assertThat(item = iterator.next()).isNotNull();

        String dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.200");
        assertThat(dump).contains("duration=100");
        assertThat(dump).contains("foo=[200]");
        assertThat(dump).contains("300: [400, 500]");
        assertThat(dump).contains("600: [700, 800]");
    }

    @Test
    public void testNrState_dump() {
        mHistory.setBatteryState(true /* charging */, BatteryManager.BATTERY_STATUS_CHARGING, 80,
                1234);

        mClock.advance(200);
        mHistory.recordNrStateChangeEvent(mClock.realtime, mClock.uptime,
                NetworkRegistrationInfo.NR_STATE_RESTRICTED);
        mClock.advance(100);
        mHistory.recordNrStateChangeEvent(mClock.realtime, mClock.uptime,
                NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED);
        mClock.advance(100);
        mHistory.recordNrStateChangeEvent(mClock.realtime, mClock.uptime,
                NetworkRegistrationInfo.NR_STATE_CONNECTED);
        mClock.advance(100);
        mHistory.recordNrStateChangeEvent(mClock.realtime, mClock.uptime,
                NetworkRegistrationInfo.NR_STATE_NONE);

        BatteryStatsHistoryIterator iterator = mHistory.iterate(0, MonotonicClock.UNDEFINED);
        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull(); // First item contains current time only

        assertThat(item = iterator.next()).isNotNull();
        String dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.200");
        assertThat(dump).contains("nr_state=restricted");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.300");
        assertThat(dump).contains("nr_state=not_restricted");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.400");
        assertThat(dump).contains("nr_state=connected");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.500");
        assertThat(dump).contains("nr_state=none");
    }

    @Test
    public void testNrState_checkin() {
        mHistory.setBatteryState(true /* charging */, BatteryManager.BATTERY_STATUS_CHARGING, 80,
                1234);

        mHistory.recordNrStateChangeEvent(200, 200,
                NetworkRegistrationInfo.NR_STATE_RESTRICTED);
        mHistory.recordNrStateChangeEvent(300, 300,
                NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED);
        mHistory.recordNrStateChangeEvent(400, 400,
                NetworkRegistrationInfo.NR_STATE_CONNECTED);
        mHistory.recordNrStateChangeEvent(500, 500,
                NetworkRegistrationInfo.NR_STATE_NONE);

        BatteryStatsHistoryIterator iterator = mHistory.iterate(0, MonotonicClock.UNDEFINED);
        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull(); // First item contains current time only

        assertThat(item = iterator.next()).isNotNull();
        String dump = toString(item, /* checkin */ true);
        assertThat(dump).contains("nrs=1");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ true);
        assertThat(dump).contains("nrs=2");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ true);
        assertThat(dump).contains("nrs=3");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ true);
        assertThat(dump).contains("nrs=0");
    }

    @Test
    public void testNrState_aTrace() {
        InOrder inOrder = Mockito.inOrder(mTracer);
        Mockito.when(mTracer.tracingEnabled()).thenReturn(true);

        mHistory.recordNrStateChangeEvent(mClock.elapsedRealtime(), mClock.uptimeMillis(),
                NetworkRegistrationInfo.NR_STATE_RESTRICTED);
        mHistory.recordNrStateChangeEvent(mClock.elapsedRealtime(), mClock.uptimeMillis(),
                NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED);
        mHistory.recordNrStateChangeEvent(mClock.elapsedRealtime(), mClock.uptimeMillis(),
                NetworkRegistrationInfo.NR_STATE_CONNECTED);
        mHistory.recordNrStateChangeEvent(mClock.elapsedRealtime(), mClock.uptimeMillis(),
                NetworkRegistrationInfo.NR_STATE_NONE);

        inOrder.verify(mTracer).traceCounter("battery_stats.nr_state",
                NetworkRegistrationInfo.NR_STATE_RESTRICTED);
        inOrder.verify(mTracer).traceCounter("battery_stats.nr_state",
                NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED);
        inOrder.verify(mTracer).traceCounter("battery_stats.nr_state",
                NetworkRegistrationInfo.NR_STATE_CONNECTED);
        inOrder.verify(mTracer).traceCounter("battery_stats.nr_state",
                NetworkRegistrationInfo.NR_STATE_NONE);
    }

    @Test
    public void largeTagPool() {
        // Keep the preserved part of history short - we only need to capture the very tail of
        // history.
        mHistory = new BatteryStatsHistory(mHistoryBuffer, 6000, mDirectory,
                mStepDetailsCalculator, mClock, mMonotonicClock, mTracer, mEventLogger);

        mHistory.forceRecordAllHistory();

        mClock.realtime = 2_000_000;
        mClock.uptime = 1_000_000;
        // More than 32k strings
        final int tagCount = 0x7FFF + 20;
        for (int tag = 0; tag < tagCount; ) {
            mClock.realtime += 10;
            mClock.uptime += 10;
            mHistory.recordEvent(mClock.realtime, mClock.uptime, HistoryItem.EVENT_ALARM_START,
                    "a" + (tag++), 42);

            mHistory.setBatteryState(true, BatteryManager.BATTERY_STATUS_CHARGING, tag % 50, 0);
            mClock.realtime += 10;
            mClock.uptime += 10;
            mHistory.recordWakelockStartEvent(mClock.realtime, mClock.uptime, "w" + tag, 42);
            mClock.realtime += 10;
            mClock.uptime += 10;
            mHistory.recordWakelockStopEvent(mClock.realtime, mClock.uptime, "w" + tag, 42);
            tag++;

            mHistory.recordWakeupEvent(mClock.realtime, mClock.uptime, "wr" + (tag++));
        }

        int eventTagsPooled = 0;
        int eventTagsUnpooled = 0;
        int wakelockTagsPooled = 0;
        int wakelockTagsUnpooled = 0;
        int wakeReasonTagsPooled = 0;
        int wakeReasonTagsUnpooled = 0;
        for (BatteryStatsHistoryIterator iterator =
                mHistory.iterate(0, MonotonicClock.UNDEFINED); iterator.hasNext(); ) {
            HistoryItem item = iterator.next();
            if (item.cmd != HistoryItem.CMD_UPDATE) {
                continue;
            }
            String checkinDump = toString(item, true);
            if (item.eventCode == HistoryItem.EVENT_ALARM_START) {
                if (item.eventTag.poolIdx != BatteryStats.HistoryTag.HISTORY_TAG_POOL_OVERFLOW) {
                    eventTagsPooled++;
                    assertThat(checkinDump).contains("+Eal=" + item.eventTag.poolIdx);
                } else {
                    eventTagsUnpooled++;
                    assertThat(checkinDump).contains("+Eal=42:\"" + item.eventTag.string + "\"");
                }
            }

            if (item.wakelockTag != null) {
                if (item.wakelockTag.poolIdx != BatteryStats.HistoryTag.HISTORY_TAG_POOL_OVERFLOW) {
                    wakelockTagsPooled++;
                    assertThat(checkinDump).contains("w=" + item.wakelockTag.poolIdx);
                } else {
                    wakelockTagsUnpooled++;
                    assertThat(checkinDump).contains("w=42:\"" + item.wakelockTag.string + "\"");
                }
            }

            if (item.wakeReasonTag != null) {
                if (item.wakeReasonTag.poolIdx
                        != BatteryStats.HistoryTag.HISTORY_TAG_POOL_OVERFLOW) {
                    wakeReasonTagsPooled++;
                    assertThat(checkinDump).contains("wr=" + item.wakeReasonTag.poolIdx);
                } else {
                    wakeReasonTagsUnpooled++;
                    assertThat(checkinDump).contains("wr=0:\"" + item.wakeReasonTag.string + "\"");
                }
            }
        }

        // Self-check - ensure that we have all cases represented in the test
        assertThat(eventTagsPooled).isGreaterThan(0);
        assertThat(eventTagsUnpooled).isGreaterThan(0);
        assertThat(wakelockTagsPooled).isGreaterThan(0);
        assertThat(wakelockTagsUnpooled).isGreaterThan(0);
        assertThat(wakeReasonTagsPooled).isGreaterThan(0);
        assertThat(wakeReasonTagsUnpooled).isGreaterThan(0);
    }

    @Test
    public void recordProcStateChange() {
        mClock.advance(200);
        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, 42,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);

        mClock.advance(100);
        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, 42,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);

        mClock.advance(100);
        // Large UID, > 0xFFFFFF
        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime,
                UserHandle.getUid(777, Process.LAST_ISOLATED_UID),
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        BatteryStatsHistoryIterator iterator = mHistory.iterate(0, MonotonicClock.UNDEFINED);
        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull(); // First item contains current time only

        assertThat(item = iterator.next()).isNotNull();

        String dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.200");
        assertThat(dump).contains("procstate: 42: bg");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.300");
        assertThat(dump).contains("procstate: 42: fg");

        assertThat(item = iterator.next()).isNotNull();
        dump = toString(item, /* checkin */ false);
        assertThat(dump).contains("04-03 02:01:00.400");
        assertThat(dump).contains("procstate: u777i999: fgs");
    }

    private String toString(BatteryStats.HistoryItem item, boolean checkin) {
        StringWriter writer = new StringWriter();
        PrintWriter pw = new PrintWriter(writer);
        mHistoryPrinter.printNextItem(pw, item, 0, checkin, /* verbose */ true);
        pw.flush();
        return writer.toString();
    }

    @Test
    public void getMonotonicHistorySize() {
        long lastHistorySize = mHistory.getMonotonicHistorySize();

        mClock.realtime = 1000;
        mClock.uptime = 1000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.EVENT_JOB_START, "job", 42);
        long size = mHistory.getMonotonicHistorySize();
        assertThat(size).isGreaterThan(lastHistorySize);
        lastHistorySize = size;

        mHistory.startNextFragment(mClock.realtime);

        size = mHistory.getMonotonicHistorySize();
        assertThat(size).isEqualTo(lastHistorySize);

        mClock.realtime = 2000;
        mClock.uptime = 2000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.EVENT_JOB_FINISH, "job", 42);

        size = mHistory.getMonotonicHistorySize();
        assertThat(size).isGreaterThan(lastHistorySize);
        lastHistorySize = size;

        mHistory.startNextFragment(mClock.realtime);

        mClock.realtime = 3000;
        mClock.uptime = 3000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                HistoryItem.EVENT_ALARM, "alarm", 42);

        size = mHistory.getMonotonicHistorySize();
        assertThat(size).isGreaterThan(lastHistorySize);
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
        testVarintParceler(Arrays.copyOfRange(values, 0, 0), 0);
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

    @Test
    public void compressHistoryFiles() {
        // The first history file will be uncompressed
        mDirectory.setFileCompressionEnabled(false);

        mClock.realtime = 1000;
        mClock.uptime = 1000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.EVENT_JOB_START, "job", 42);

        mHistory.startNextFragment(mClock.realtime);

        // The second file will be compressed
        mDirectory.setFileCompressionEnabled(true);

        mClock.realtime = 2000;
        mClock.uptime = 2000;
        mHistory.recordEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.EVENT_JOB_FINISH, "job", 42);

        mHistory.startNextFragment(mClock.realtime);

        awaitCompletion();

        assertThat(historySummary(mHistory)).isEqualTo(List.of("+42:job", "-42:job"));

        Parcel parcel = Parcel.obtain();
        mHistory.writeToBatteryUsageStatsParcel(parcel, Long.MAX_VALUE);
        parcel.setDataPosition(0);

        BatteryStatsHistory actual = BatteryStatsHistory.createFromBatteryUsageStatsParcel(parcel);
        assertThat(historySummary(actual)).isEqualTo(List.of("+42:job", "-42:job"));
    }

    private List<String> historySummary(BatteryStatsHistory history) {
        List<String> events = new ArrayList<>();
        try (BatteryStatsHistoryIterator it = history.iterate(0, Long.MAX_VALUE)) {
            HistoryItem item;
            while ((item = it.next()) != null) {
                if ((item.eventCode & HistoryItem.EVENT_TYPE_MASK) == HistoryItem.EVENT_JOB) {
                    events.add(((item.eventCode & HistoryItem.EVENT_FLAG_START) != 0 ? "+" : "-")
                            + item.eventTag.uid + ":" + item.eventTag.string);
                }
            }
        }
        return events;
    }

    private static void awaitCompletion() {
        ConditionVariable done = new ConditionVariable();
        BackgroundThread.getHandler().post(done::open);
        done.block();
    }
}
