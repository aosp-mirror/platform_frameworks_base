/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.android.framework.protobuf.nano.MessageNano;
import com.android.server.am.CriticalEventLog.ILogLoader;
import com.android.server.am.CriticalEventLog.LogLoader;
import com.android.server.am.nano.CriticalEventLogProto;
import com.android.server.am.nano.CriticalEventLogStorageProto;
import com.android.server.am.nano.CriticalEventProto;
import com.android.server.am.nano.CriticalEventProto.HalfWatchdog;
import com.android.server.am.nano.CriticalEventProto.Watchdog;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

/**
 * Test class for {@link CriticalEventLog}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:CriticalEventLogTest
 */
public class CriticalEventLogTest {
    /** Epoch time when the critical event log is instantiated. */
    private static final long START_TIME_MS = 1577880000000L; // 2020-01-01 12:00:00.000 UTC

    /** Max number of events to include in the log. */
    private static final int BUFFER_CAPACITY = 5;

    /** Max age of events to include in the output log proto. */
    private static final Duration LOG_WINDOW = Duration.ofMinutes(5);

    /** How long to wait between consecutive saves of the log to disk. */
    private static final Duration MIN_TIME_BETWEEN_SAVES = Duration.ofSeconds(2);

    private static final String UUID_STRING = "123e4567-e89b-12d3-a456-556642440000";

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private TestableCriticalEventLog mCriticalEventLog;
    private File mTestFile;

    @Before
    public void setup() throws IOException {
        mTestFile = mFolder.newFile(CriticalEventLog.FILENAME);
        setLogInstance();
    }

    @Test
    public void loadEvents_validContents() throws Exception {
        createTestFileWithEvents(2);
        setLogInstance(); // Log instance reads the proto file at initialization.

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertProtoArrayEquals(
                logProto.events,
                new CriticalEventProto[]{
                        watchdog(START_TIME_MS - 2000, "Old watchdog 1"),
                        watchdog(START_TIME_MS - 1000, "Old watchdog 2"),
                });
    }

    @Test
    public void loadEvents_fileDoesntExist() {
        mTestFile.delete();
        setLogInstance();

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_directoryDoesntExist() {
        mFolder.delete();
        setLogInstance();

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_unreadable() throws Exception {
        createTestFileWithEvents(1);
        mTestFile.setReadable(false);
        setLogInstance();

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_malformedFile() throws Exception {
        try (FileOutputStream stream = new FileOutputStream(mTestFile)) {
            stream.write("This is not a proto file.".getBytes(StandardCharsets.UTF_8));
        }
        setLogInstance();

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_emptyProto() throws Exception {
        createTestFileWithEvents(0);
        setLogInstance();

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_numEventsExceedsBufferCapacity() throws Exception {
        createTestFileWithEvents(10); // Ring buffer capacity is 5
        setLogInstance();

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        // Log contains the last 5 events only.
        assertProtoArrayEquals(
                logProto.events,
                new CriticalEventProto[]{
                        watchdog(START_TIME_MS - 5000, "Old watchdog 6"),
                        watchdog(START_TIME_MS - 4000, "Old watchdog 7"),
                        watchdog(START_TIME_MS - 3000, "Old watchdog 8"),
                        watchdog(START_TIME_MS - 2000, "Old watchdog 9"),
                        watchdog(START_TIME_MS - 1000, "Old watchdog 10"),
                });
    }

    @Test
    public void logLinesForAnrFile() {
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logWatchdog("Watchdog subject",
                UUID.fromString("123e4567-e89b-12d3-a456-556642440000"));
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logHalfWatchdog("Half watchdog subject");
        mCriticalEventLog.incTimeSeconds(1);

        assertThat(mCriticalEventLog.logLinesForAnrFile()).isEqualTo(
                "--- CriticalEventLog ---\n"
                        + "capacity: 5\n"
                        + "events <\n"
                        + "  timestamp_ms: 1577880001000\n"
                        + "  watchdog <\n"
                        + "    subject: \"Watchdog subject\"\n"
                        + "    uuid: \"123e4567-e89b-12d3-a456-556642440000\"\n"
                        + "  >\n"
                        + ">\n"
                        + "events <\n"
                        + "  timestamp_ms: 1577880002000\n"
                        + "  half_watchdog <\n"
                        + "    subject: \"Half watchdog subject\"\n"
                        + "  >\n"
                        + ">\n"
                        + "timestamp_ms: 1577880003000\n"
                        + "window_ms: 300000\n\n");
    }

    @Test
    public void logWatchdog() {
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logWatchdog("Subject 1",
                UUID.fromString("123e4567-e89b-12d3-a456-556642440000"));
        mCriticalEventLog.incTimeSeconds(1);

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 2000);
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                watchdog(START_TIME_MS + 1000, "Subject 1", "123e4567-e89b-12d3-a456-556642440000")
        });
    }

    @Test
    public void logHalfWatchdog() {
        setLogInstance();
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logHalfWatchdog("Subject 1");
        mCriticalEventLog.incTimeSeconds(1);

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 2000);
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                halfWatchdog(START_TIME_MS + 1000, "Subject 1")
        });
    }

    @Test
    public void getOutputLogProto_numberOfEventsExceedsCapacity() {
        // Log 10 events in 10 sec (capacity = 5)
        for (int i = 0; i < 10; i++) {
            mCriticalEventLog.logWatchdog("Subject " + i,
                    UUID.fromString(UUID_STRING));
            mCriticalEventLog.incTimeSeconds(1);
        }

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 10000);
        assertThat(logProto.windowMs).isEqualTo(300_000); // 5 minutes
        assertThat(logProto.capacity).isEqualTo(5);

        // Only last 5 events are included in log output.
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                watchdog(START_TIME_MS + 5000, "Subject 5", UUID_STRING),
                watchdog(START_TIME_MS + 6000, "Subject 6", UUID_STRING),
                watchdog(START_TIME_MS + 7000, "Subject 7", UUID_STRING),
                watchdog(START_TIME_MS + 8000, "Subject 8", UUID_STRING),
                watchdog(START_TIME_MS + 9000, "Subject 9", UUID_STRING),
        });
    }

    @Test
    public void getOutputLogProto_logContainsOldEvents() {
        long logTimestamp = START_TIME_MS + Duration.ofDays(1).toMillis();

        // Old events (older than 5 mins)
        mCriticalEventLog.setCurrentTimeMillis(logTimestamp - Duration.ofSeconds(302).toMillis());
        mCriticalEventLog.logHalfWatchdog("Old event 1"); // 5m2s old
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logHalfWatchdog("Old event 2"); // 5m1s old
        mCriticalEventLog.incTimeSeconds(1);

        // New events (5 mins old or less)
        mCriticalEventLog.logHalfWatchdog("New event 1"); // 5m0s old
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logHalfWatchdog("New event 2"); // 5m59s old

        mCriticalEventLog.setCurrentTimeMillis(logTimestamp);
        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        assertThat(logProto.timestampMs).isEqualTo(logTimestamp);
        assertThat(logProto.windowMs).isEqualTo(300_000); // 5 minutes
        assertThat(logProto.capacity).isEqualTo(5);

        // Only events with age <= 5 min are included
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                halfWatchdog(logTimestamp - Duration.ofSeconds(300).toMillis(), "New event 1"),
                halfWatchdog(logTimestamp - Duration.ofSeconds(299).toMillis(), "New event 2"),
        });
    }

    @Test
    public void getOutputLogProto_logHasNotBeenLoadedFromDiskYet() throws Exception {
        createTestFileWithEvents(5);
        setLogInstance(new NoOpLogLoader());

        CriticalEventLogProto logProto = mCriticalEventLog.getRecentEvents();

        // Output log is empty.
        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void saveEventsToDiskNow() throws Exception {
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logWatchdog("Watchdog subject", UUID.fromString(UUID_STRING));

        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logHalfWatchdog("Half watchdog subject");

        // Don't need to call saveEventsToDiskNow since it's called after every event
        // when mSaveImmediately = true.

        CriticalEventLogStorageProto expected = new CriticalEventLogStorageProto();
        expected.events = new CriticalEventProto[]{
                watchdog(START_TIME_MS + 1000, "Watchdog subject", UUID_STRING),
                halfWatchdog(START_TIME_MS + 2000, "Half watchdog subject")
        };

        assertThat(MessageNano.messageNanoEquals(getEventsWritten(), expected)).isTrue();
    }

    @Test
    public void saveDelayMs() {
        // First save has no delay
        assertThat(mCriticalEventLog.saveDelayMs()).isEqualTo(0L);

        // Save log, then next save delay is in 2s
        mCriticalEventLog.saveLogToFileNow();
        assertThat(mCriticalEventLog.saveDelayMs()).isEqualTo(2000L);
        mCriticalEventLog.incTimeSeconds(1);
        assertThat(mCriticalEventLog.saveDelayMs()).isEqualTo(1000L);

        // Save again, save delay is 2s again.
        mCriticalEventLog.saveLogToFileNow();
        assertThat(mCriticalEventLog.saveDelayMs()).isEqualTo(2000L);
    }

    @Test
    public void simulateReboot_saveAndLoadCycle() {
        TestableCriticalEventLog log1 = setLogInstance();

        // Log 8 events
        for (int i = 0; i < 8; i++) {
            log1.logHalfWatchdog("Old subject " + i);
            log1.incTimeSeconds(1);
        }

        // Simulate reboot by making new log instance.
        TestableCriticalEventLog log2 = setLogInstance();
        assertThat(log1).isNotSameInstanceAs(log2);

        // Log one more event
        log2.setCurrentTimeMillis(START_TIME_MS + 20_000);
        log2.logHalfWatchdog("New subject");
        log2.incTimeSeconds(1);

        CriticalEventLogProto logProto = log2.getRecentEvents();

        // Log contains 4 + 1 events.
        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 21_000);
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                halfWatchdog(START_TIME_MS + 4000, "Old subject 4"),
                halfWatchdog(START_TIME_MS + 5000, "Old subject 5"),
                halfWatchdog(START_TIME_MS + 6000, "Old subject 6"),
                halfWatchdog(START_TIME_MS + 7000, "Old subject 7"),
                halfWatchdog(START_TIME_MS + 20_000, "New subject")
        });
    }

    private CriticalEventLogStorageProto getEventsWritten() throws IOException {
        return CriticalEventLogStorageProto.parseFrom(
                Files.readAllBytes(mTestFile.toPath()));
    }

    /**
     * Creates a log file containing some watchdog events.
     *
     * They occur at a rate of one per second, with the last at 1 sec before START_TIME_MS.
     */
    private void createTestFileWithEvents(int numEvents) throws Exception {
        CriticalEventLogStorageProto log = new CriticalEventLogStorageProto();
        log.events = new CriticalEventProto[numEvents];
        long startTimeMs = START_TIME_MS - (numEvents * 1000L);

        for (int i = 0; i < numEvents; i++) {
            long timestampMs = startTimeMs + (i * 1000L);
            String subject = String.format("Old watchdog %d", i + 1);
            log.events[i] = watchdog(timestampMs, subject);
        }

        try (FileOutputStream stream = new FileOutputStream(mTestFile)) {
            stream.write(CriticalEventLogStorageProto.toByteArray(log));
        }
    }

    private CriticalEventProto watchdog(long timestampMs, String subject) {
        return watchdog(timestampMs, subject, "A UUID");
    }

    private CriticalEventProto watchdog(long timestampMs, String subject, String uuid) {
        CriticalEventProto event = new CriticalEventProto();
        event.timestampMs = timestampMs;
        event.setWatchdog(new Watchdog());
        event.getWatchdog().subject = subject;
        event.getWatchdog().uuid = uuid;
        return event;
    }

    private CriticalEventProto halfWatchdog(long timestampMs, String subject) {
        CriticalEventProto event = new CriticalEventProto();
        event.timestampMs = timestampMs;
        event.setHalfWatchdog(new HalfWatchdog());
        event.getHalfWatchdog().subject = subject;
        return event;
    }

    private static void assertProtoArrayEquals(MessageNano[] actual, MessageNano[] expected) {
        assertThat(expected).isNotNull();
        assertThat(actual).isNotNull();

        String message =
                "Expected:\n" + Arrays.toString(expected) + "\nGot:\n" + Arrays.toString(actual);
        assertWithMessage(message).that(expected.length).isEqualTo(actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertWithMessage(message).that(
                    MessageNano.messageNanoEquals(expected[i], actual[i])).isTrue();
        }
    }

    private TestableCriticalEventLog setLogInstance() {
        return setLogInstance(new LogLoader());
    }

    private TestableCriticalEventLog setLogInstance(ILogLoader logLoader) {
        mCriticalEventLog = new TestableCriticalEventLog(mFolder.getRoot().getAbsolutePath(),
                logLoader);
        return mCriticalEventLog;
    }

    private static class TestableCriticalEventLog extends CriticalEventLog {
        private long mNowMillis = START_TIME_MS;

        TestableCriticalEventLog(String logDir, ILogLoader logLoader) {
            super(logDir,
                    BUFFER_CAPACITY,
                    (int) LOG_WINDOW.toMillis(),
                    MIN_TIME_BETWEEN_SAVES.toMillis(),
                    /* loadAndSaveImmediately= */ true,
                    logLoader);
        }

        @Override
        protected long getWallTimeMillis() {
            return mNowMillis;
        }

        void incTimeSeconds(int seconds) {
            mNowMillis += (seconds * 1000L);
        }

        void setCurrentTimeMillis(long millis) {
            mNowMillis = millis;
        }
    }

    /**
     * A log loader that does nothing.
     *
     * Used to check behaviour when log loading is slow since the loading happens
     * asynchronously.
     */
    private static class NoOpLogLoader implements ILogLoader {
        @Override
        public void load(File logFile,
                CriticalEventLog.ThreadSafeRingBuffer<CriticalEventProto> buffer) {
            // Do nothing.
        }
    }
}
