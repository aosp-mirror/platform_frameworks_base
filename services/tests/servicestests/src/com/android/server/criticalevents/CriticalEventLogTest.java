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

package com.android.server.criticalevents;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.server.ServerProtoEnums;

import com.android.framework.protobuf.nano.MessageNano;
import com.android.server.criticalevents.CriticalEventLog.ILogLoader;
import com.android.server.criticalevents.CriticalEventLog.LogLoader;
import com.android.server.criticalevents.nano.CriticalEventLogProto;
import com.android.server.criticalevents.nano.CriticalEventLogStorageProto;
import com.android.server.criticalevents.nano.CriticalEventProto;
import com.android.server.criticalevents.nano.CriticalEventProto.AppNotResponding;
import com.android.server.criticalevents.nano.CriticalEventProto.HalfWatchdog;
import com.android.server.criticalevents.nano.CriticalEventProto.JavaCrash;
import com.android.server.criticalevents.nano.CriticalEventProto.NativeCrash;
import com.android.server.criticalevents.nano.CriticalEventProto.Watchdog;

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

    private static final int SYSTEM_SERVER_UID = 1000;
    private static final int SYSTEM_APP_UID = 1001;

    private static final int DATA_APP_UID = 10_001;
    private static final int DATA_APP_UID_2 = 10_002;
    private static final int DATA_APP_UID_3 = 10_003;

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

        CriticalEventLogProto logProto = getLogOutput();

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

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_directoryDoesntExist() {
        mFolder.delete();
        setLogInstance();

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_unreadable() throws Exception {
        createTestFileWithEvents(1);
        mTestFile.setReadable(false);
        setLogInstance();

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_malformedFile() throws Exception {
        try (FileOutputStream stream = new FileOutputStream(mTestFile)) {
            stream.write("This is not a proto file.".getBytes(StandardCharsets.UTF_8));
        }
        setLogInstance();

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_emptyProto() throws Exception {
        createTestFileWithEvents(0);
        setLogInstance();

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS);
        assertThat(logProto.events).isEmpty();
    }

    @Test
    public void loadEvents_numEventsExceedsBufferCapacity() throws Exception {
        createTestFileWithEvents(10); // Ring buffer capacity is 5
        setLogInstance();

        CriticalEventLogProto logProto = getLogOutput();

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
    public void logLinesForTraceFile() {
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logWatchdog("Watchdog subject",
                UUID.fromString("123e4567-e89b-12d3-a456-556642440000"));
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logHalfWatchdog("Half watchdog subject");
        mCriticalEventLog.incTimeSeconds(1);

        assertThat(mCriticalEventLog.logLinesForSystemServerTraceFile()).isEqualTo(
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

        CriticalEventLogProto logProto = getLogOutput();

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

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 2000);
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                halfWatchdog(START_TIME_MS + 1000, "Subject 1")
        });
    }

    @Test
    public void logAnr() {
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logAnr("Subject 1", ServerProtoEnums.SYSTEM_SERVER, "AID_SYSTEM",
                SYSTEM_SERVER_UID, 0);
        mCriticalEventLog.incTimeSeconds(1);

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 2000);
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                anr(START_TIME_MS + 1000, "Subject 1", ServerProtoEnums.SYSTEM_SERVER,
                        "AID_SYSTEM", SYSTEM_SERVER_UID, 0)
        });
    }

    @Test
    public void logJavaCrash() {
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logJavaCrash("com.android.MyClass", ServerProtoEnums.SYSTEM_APP,
                "AID_RADIO", SYSTEM_APP_UID, 1);
        mCriticalEventLog.incTimeSeconds(1);

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 2000);
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                javaCrash(START_TIME_MS + 1000, "com.android.MyClass", ServerProtoEnums.SYSTEM_APP,
                        "AID_RADIO", SYSTEM_APP_UID, 1)
        });
    }

    @Test
    public void logNativeCrash() {
        mCriticalEventLog.incTimeSeconds(1);
        mCriticalEventLog.logNativeCrash(ServerProtoEnums.SYSTEM_APP, "AID_RADIO", SYSTEM_APP_UID,
                1);
        mCriticalEventLog.incTimeSeconds(1);

        CriticalEventLogProto logProto = getLogOutput();

        assertThat(logProto.timestampMs).isEqualTo(START_TIME_MS + 2000);
        assertProtoArrayEquals(logProto.events, new CriticalEventProto[]{
                nativeCrash(START_TIME_MS + 1000, ServerProtoEnums.SYSTEM_APP, "AID_RADIO",
                        SYSTEM_APP_UID, 1)
        });
    }

    @Test
    public void privacyRedaction_anr() {
        CriticalEventProto systemServerAnr = anr(START_TIME_MS + 1000, "Subject 1",
                CriticalEventProto.SYSTEM_SERVER, "AID_SYSTEM", SYSTEM_SERVER_UID, 0);
        CriticalEventProto systemAppAnr = anr(START_TIME_MS + 2000, "Subject 2",
                CriticalEventProto.SYSTEM_APP,
                "AID_RADIO", SYSTEM_APP_UID, 1);
        CriticalEventProto fooAppAnr = anr(START_TIME_MS + 3000, "Subject 3",
                CriticalEventProto.DATA_APP, "com.foo", DATA_APP_UID, 2);
        CriticalEventProto fooAppAnrUid2 = anr(START_TIME_MS + 4000, "Subject 4",
                CriticalEventProto.DATA_APP, "com.foo", DATA_APP_UID_2, 3);
        CriticalEventProto fooAppAnrUid2Redacted = anr(START_TIME_MS + 4000, "",
                CriticalEventProto.DATA_APP, "", DATA_APP_UID_2, 3);
        CriticalEventProto barAppAnr = anr(START_TIME_MS + 5000, "Subject 5",
                CriticalEventProto.DATA_APP, "com.bar", DATA_APP_UID_3, 4);
        CriticalEventProto barAppAnrRedacted = anr(START_TIME_MS + 5000, "",
                CriticalEventProto.DATA_APP, "", DATA_APP_UID_3, 4);

        addToLog(systemServerAnr, systemAppAnr, fooAppAnr, fooAppAnrUid2, barAppAnr);

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.DATA_APP, "com.foo", DATA_APP_UID).events,
                new CriticalEventProto[]{
                        systemServerAnr,
                        systemAppAnr,
                        fooAppAnr,
                        // Redacted since the trace file and ANR are for different uids.
                        fooAppAnrUid2Redacted,
                        // Redacted since the trace file and ANR are for different data apps.
                        barAppAnrRedacted
                });

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.SYSTEM_SERVER, "AID_SYSTEM",
                        SYSTEM_SERVER_UID).events,
                new CriticalEventProto[]{
                        systemServerAnr,
                        systemAppAnr,
                        fooAppAnr,
                        fooAppAnrUid2,
                        barAppAnr
                });

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.SYSTEM_APP, "AID_RADIO",
                        SYSTEM_APP_UID).events,
                new CriticalEventProto[]{
                        systemServerAnr,
                        systemAppAnr,
                        fooAppAnr,
                        fooAppAnrUid2,
                        barAppAnr
                });
    }

    @Test
    public void privacyRedaction_javaCrash() {
        CriticalEventProto systemServerCrash = javaCrash(START_TIME_MS + 1000, "Exception class 1",
                CriticalEventProto.SYSTEM_SERVER, "AID_SYSTEM",
                SYSTEM_SERVER_UID, 0);
        CriticalEventProto systemAppCrash = javaCrash(START_TIME_MS + 2000, "Exception class 2",
                CriticalEventProto.SYSTEM_APP, "AID_RADIO", SYSTEM_APP_UID, 1);
        CriticalEventProto fooAppCrash = javaCrash(START_TIME_MS + 3000, "Exception class 3",
                CriticalEventProto.DATA_APP, "com.foo", DATA_APP_UID, 2);
        CriticalEventProto fooAppCrashUid2 = javaCrash(START_TIME_MS + 4000, "Exception class 4",
                CriticalEventProto.DATA_APP, "com.foo", DATA_APP_UID_2, 3);
        CriticalEventProto fooAppCrashUid2Redacted = javaCrash(START_TIME_MS + 4000, "",
                CriticalEventProto.DATA_APP, "", DATA_APP_UID_2, 3);
        CriticalEventProto barAppCrash = javaCrash(START_TIME_MS + 5000, "Exception class 5",
                CriticalEventProto.DATA_APP, "com.bar", DATA_APP_UID_3, 4);
        CriticalEventProto barAppCrashRedacted = javaCrash(START_TIME_MS + 5000, "",
                CriticalEventProto.DATA_APP, "", DATA_APP_UID_3, 4);

        addToLog(systemServerCrash, systemAppCrash, fooAppCrash, fooAppCrashUid2, barAppCrash);

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.DATA_APP, "com.foo", DATA_APP_UID).events,
                new CriticalEventProto[]{
                        systemServerCrash,
                        systemAppCrash,
                        fooAppCrash,
                        // Redacted since the trace file and crash are for different uids.
                        fooAppCrashUid2Redacted,
                        // Redacted since the trace file and crash are for different data apps.
                        barAppCrashRedacted
                });

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.SYSTEM_SERVER, "AID_SYSTEM",
                        SYSTEM_SERVER_UID).events,
                new CriticalEventProto[]{
                        systemServerCrash,
                        systemAppCrash,
                        fooAppCrash,
                        fooAppCrashUid2,
                        barAppCrash
                });

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.SYSTEM_APP, "AID_RADIO",
                        SYSTEM_APP_UID).events,
                new CriticalEventProto[]{
                        systemServerCrash,
                        systemAppCrash,
                        fooAppCrash,
                        fooAppCrashUid2,
                        barAppCrash
                });
    }

    @Test
    public void privacyRedaction_nativeCrash() {
        CriticalEventProto systemServerCrash = nativeCrash(START_TIME_MS + 1000,
                CriticalEventProto.SYSTEM_SERVER, "AID_SYSTEM",
                SYSTEM_SERVER_UID, 0);
        CriticalEventProto systemAppCrash = nativeCrash(START_TIME_MS + 2000,
                CriticalEventProto.SYSTEM_APP, "AID_RADIO", SYSTEM_APP_UID, 1);
        CriticalEventProto fooAppCrash = nativeCrash(START_TIME_MS + 3000,
                CriticalEventProto.DATA_APP, "com.foo", DATA_APP_UID, 2);
        CriticalEventProto fooAppCrashUid2 = nativeCrash(START_TIME_MS + 4000,
                CriticalEventProto.DATA_APP, "com.foo", DATA_APP_UID_2, 3);
        CriticalEventProto fooAppCrashUid2Redacted = nativeCrash(START_TIME_MS + 4000,
                CriticalEventProto.DATA_APP, "", DATA_APP_UID_2, 3);
        CriticalEventProto barAppCrash = nativeCrash(START_TIME_MS + 5000,
                CriticalEventProto.DATA_APP, "com.bar", DATA_APP_UID_3, 4);
        CriticalEventProto barAppCrashRedacted = nativeCrash(START_TIME_MS + 5000,
                CriticalEventProto.DATA_APP, "", DATA_APP_UID_3, 4);

        addToLog(systemServerCrash, systemAppCrash, fooAppCrash, fooAppCrashUid2, barAppCrash);

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.DATA_APP, "com.foo", DATA_APP_UID).events,
                new CriticalEventProto[]{
                        systemServerCrash,
                        systemAppCrash,
                        fooAppCrash,
                        // Redacted since the trace file and crash are for different uids.
                        fooAppCrashUid2Redacted,
                        // Redacted since the trace file and crash are for different data apps.
                        barAppCrashRedacted
                });

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.SYSTEM_SERVER, "AID_SYSTEM",
                        SYSTEM_SERVER_UID).events,
                new CriticalEventProto[]{
                        systemServerCrash,
                        systemAppCrash,
                        fooAppCrash,
                        fooAppCrashUid2,
                        barAppCrash
                });

        assertProtoArrayEquals(
                getLogOutput(ServerProtoEnums.SYSTEM_APP, "AID_RADIO",
                        SYSTEM_APP_UID).events,
                new CriticalEventProto[]{
                        systemServerCrash,
                        systemAppCrash,
                        fooAppCrash,
                        fooAppCrashUid2,
                        barAppCrash
                });
    }

    @Test
    public void privacyRedaction_doesNotMutateLogState() {
        mCriticalEventLog.logAnr("ANR Subject", ServerProtoEnums.DATA_APP, "com.foo",
                10_001, DATA_APP_UID);
        mCriticalEventLog.logJavaCrash("com.foo.MyClass", ServerProtoEnums.DATA_APP, "com.foo",
                10_001, DATA_APP_UID);
        mCriticalEventLog.logNativeCrash(ServerProtoEnums.DATA_APP, "com.foo", 10_001,
                DATA_APP_UID);

        CriticalEventLogProto unredactedLogBefore = getLogOutput(ServerProtoEnums.SYSTEM_SERVER,
                "AID_SYSTEM", SYSTEM_SERVER_UID);
        CriticalEventLogProto redactedLog = getLogOutput(ServerProtoEnums.DATA_APP, "com.bar",
                DATA_APP_UID);
        CriticalEventLogProto unredactedLogAfter = getLogOutput(ServerProtoEnums.SYSTEM_SERVER,
                "AID_SYSTEM", SYSTEM_SERVER_UID);

        assertProtoNotEqual(unredactedLogBefore, redactedLog); // verify some redaction took place.
        assertProtoEquals(unredactedLogBefore, unredactedLogAfter);
    }

    @Test
    public void getOutputLogProto_numberOfEventsExceedsCapacity() {
        // Log 10 events in 10 sec (capacity = 5)
        for (int i = 0; i < 10; i++) {
            mCriticalEventLog.logWatchdog("Subject " + i,
                    UUID.fromString(UUID_STRING));
            mCriticalEventLog.incTimeSeconds(1);
        }

        CriticalEventLogProto logProto = getLogOutput();

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
        CriticalEventLogProto logProto = getLogOutput();

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

        CriticalEventLogProto logProto = getLogOutput();

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

        CriticalEventLogProto logProto = getLogOutput(log2);

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

    @Test
    public void processClassEnumParity() {
        String message = "CriticalEventProto.ProcessClass and ServerProtoEnum are out of sync.";
        assertWithMessage(message).that(CriticalEventProto.PROCESS_CLASS_UNKNOWN).isEqualTo(
                ServerProtoEnums.ERROR_SOURCE_UNKNOWN);
        assertWithMessage(message).that(CriticalEventProto.DATA_APP).isEqualTo(
                ServerProtoEnums.DATA_APP);
        assertWithMessage(message).that(CriticalEventProto.SYSTEM_APP).isEqualTo(
                ServerProtoEnums.SYSTEM_APP);
        assertWithMessage(message).that(CriticalEventProto.SYSTEM_SERVER).isEqualTo(
                ServerProtoEnums.SYSTEM_SERVER);
    }

    private void addToLog(CriticalEventProto... events) {
        for (CriticalEventProto event : events) {
            mCriticalEventLog.appendAndSave(event);
        }
    }

    private CriticalEventLogProto getLogOutput() {
        return getLogOutput(mCriticalEventLog);
    }

    private CriticalEventLogProto getLogOutput(CriticalEventLog log) {
        return getLogOutput(log, ServerProtoEnums.SYSTEM_SERVER, "AID_SYSTEM", SYSTEM_SERVER_UID);
    }

    private CriticalEventLogProto getLogOutput(int traceProcessClassEnum,
            String traceProcessName, int traceProcessUid) {
        return getLogOutput(mCriticalEventLog, traceProcessClassEnum, traceProcessName,
                traceProcessUid);
    }

    private CriticalEventLogProto getLogOutput(CriticalEventLog log, int traceProcessClassEnum,
            String traceProcessName, int traceProcessUid) {
        return log.getOutputLogProto(traceProcessClassEnum, traceProcessName, traceProcessUid);
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

    private static CriticalEventProto watchdog(long timestampMs, String subject) {
        return watchdog(timestampMs, subject, "A UUID");
    }

    private static CriticalEventProto watchdog(long timestampMs, String subject, String uuid) {
        CriticalEventProto event = new CriticalEventProto();
        event.timestampMs = timestampMs;
        event.setWatchdog(new Watchdog());
        event.getWatchdog().subject = subject;
        event.getWatchdog().uuid = uuid;
        return event;
    }

    private static CriticalEventProto halfWatchdog(long timestampMs, String subject) {
        CriticalEventProto event = new CriticalEventProto();
        event.timestampMs = timestampMs;
        event.setHalfWatchdog(new HalfWatchdog());
        event.getHalfWatchdog().subject = subject;
        return event;
    }

    private static CriticalEventProto anr(long timestampMs, String subject, int processClass,
            String processName, int uid, int pid) {
        CriticalEventProto event = new CriticalEventProto();
        event.timestampMs = timestampMs;
        event.setAnr(new AppNotResponding());
        event.getAnr().subject = subject;
        event.getAnr().processClass = processClass;
        event.getAnr().process = processName;
        event.getAnr().uid = uid;
        event.getAnr().pid = pid;
        return event;
    }

    private static CriticalEventProto javaCrash(long timestampMs, String exceptionClass,
            int processClass, String processName, int uid, int pid) {
        CriticalEventProto event = new CriticalEventProto();
        event.timestampMs = timestampMs;
        event.setJavaCrash(new JavaCrash());
        event.getJavaCrash().exceptionClass = exceptionClass;
        event.getJavaCrash().processClass = processClass;
        event.getJavaCrash().process = processName;
        event.getJavaCrash().uid = uid;
        event.getJavaCrash().pid = pid;
        return event;
    }

    private static CriticalEventProto nativeCrash(long timestampMs, int processClass,
            String processName, int uid, int pid) {
        CriticalEventProto event = new CriticalEventProto();
        event.timestampMs = timestampMs;
        event.setNativeCrash(new NativeCrash());
        event.getNativeCrash().processClass = processClass;
        event.getNativeCrash().process = processName;
        event.getNativeCrash().uid = uid;
        event.getNativeCrash().pid = pid;
        return event;
    }

    private static void assertProtoArrayEquals(MessageNano[] actual, MessageNano[] expected) {
        assertThat(expected).isNotNull();
        assertThat(actual).isNotNull();

        String baseMsg = String.format("Expected:\n%s\nGot:\n%s", Arrays.toString(expected),
                Arrays.toString(actual));
        String lengthMsg = String.format("%s\nGot different length arrays.\bExpected %d, got %d",
                baseMsg, expected.length, actual.length);
        assertWithMessage(lengthMsg).that(expected.length).isEqualTo(actual.length);
        for (int i = 0; i < expected.length; i++) {
            String pairMsg = String.format("%s\nMismatched pair.\nExpected:\n%s\nGot:\n%s",
                    baseMsg, expected[i], actual[i]);
            assertWithMessage(pairMsg).that(
                    MessageNano.messageNanoEquals(expected[i], actual[i])).isTrue();
        }
    }

    private static void assertProtoEquals(MessageNano actual, MessageNano expected) {
        String message = String.format("Expected:\n%s\nGot:\n%s", expected, actual);
        assertWithMessage(message).that(
                MessageNano.messageNanoEquals(expected, actual)).isTrue();
    }

    private static void assertProtoNotEqual(MessageNano first, MessageNano second) {
        String message = String.format("Expected protos to be different, but were equal:\n%s",
                first);
        assertWithMessage(message).that(
                MessageNano.messageNanoEquals(first, second)).isFalse();
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
