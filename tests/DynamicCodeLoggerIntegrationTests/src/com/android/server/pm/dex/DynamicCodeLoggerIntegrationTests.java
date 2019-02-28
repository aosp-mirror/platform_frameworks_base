/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.pm.dex;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.UiAutomation;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.util.EventLog;
import android.util.EventLog.Event;

import dalvik.system.DexClassLoader;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for {@link DynamicCodeLogger}.
 *
 * The setup for the test dynamically loads code in a jar extracted
 * from our assets (a secondary dex file).
 *
 * We then use shell commands to trigger dynamic code logging (and wait
 * for it to complete). This causes DynamicCodeLogger to log the hash of the
 * file's name and content.  We verify that this message appears in
 * the event log.
 *
 * Run with "atest DynamicCodeLoggerIntegrationTests".
 */
@LargeTest
@RunWith(JUnit4.class)
public final class DynamicCodeLoggerIntegrationTests {

    private static final String SHA_256 = "SHA-256";

    // Event log tag used for SNET related events
    private static final int SNET_TAG = 0x534e4554;

    // Subtags used to distinguish dynamic code loading events
    private static final String DCL_DEX_SUBTAG = "dcl";
    private static final String DCL_NATIVE_SUBTAG = "dcln";

    // These are job IDs from DynamicCodeLoggingService
    private static final int IDLE_LOGGING_JOB_ID = 2030028;
    private static final int AUDIT_WATCHING_JOB_ID = 203142925;

    // For tests that rely on parsing audit logs, how often to retry. (There are many reasons why
    // we might not see the audit logs, including throttling and delays in log generation, so to
    // avoid flakiness we run these tests multiple times, allowing progressively longer between
    // code loading and checking the logs on each try.)
    private static final int AUDIT_LOG_RETRIES = 10;
    private static final int RETRY_DELAY_MS = 2_000;

    private static Context sContext;
    private static int sMyUid;

    @BeforeClass
    public static void setUpAll() {
        sContext = InstrumentationRegistry.getTargetContext();
        sMyUid = android.os.Process.myUid();
    }

    @Before
    public void primeEventLog() {
        // Force a round trip to logd to make sure everything is up to date.
        // Without this the first test passes and others don't - we don't see new events in the
        // log. The exact reason is unclear.
        EventLog.writeEvent(SNET_TAG, "Dummy event");

        // Audit log messages are throttled by the kernel (at the request of logd) to 5 per
        // second, so running the tests too quickly in sequence means we lose some and get
        // spurious failures. Sigh.
        SystemClock.sleep(1000);
    }

    @Test
    public void testGeneratesEvents_standardClassLoader() throws Exception {
        File privateCopyFile = privateFile("copied.jar");
        // Obtained via "echo -n copied.jar | sha256sum"
        String expectedNameHash =
                "1B6C71DB26F36582867432CCA12FB6A517470C9F9AABE9198DD4C5C030D6DC0C";
        String expectedContentHash = copyAndHashResource("/javalib.jar", privateCopyFile);

        // Feed the jar to a class loader and make sure it contains what we expect.
        ClassLoader parentClassLoader = sContext.getClass().getClassLoader();
        ClassLoader loader =
                new DexClassLoader(privateCopyFile.toString(), null, null, parentClassLoader);
        loader.loadClass("com.android.dcl.Simple");

        // And make sure we log events about it
        long previousEventNanos = mostRecentEventTimeNanos();
        runDynamicCodeLoggingJob(IDLE_LOGGING_JOB_ID);

        assertDclLoggedSince(previousEventNanos, DCL_DEX_SUBTAG,
                expectedNameHash, expectedContentHash);
    }

    @Test
    public void testGeneratesEvents_unknownClassLoader() throws Exception {
        File privateCopyFile = privateFile("copied2.jar");
        String expectedNameHash =
                "202158B6A3169D78F1722487205A6B036B3F2F5653FDCFB4E74710611AC7EB93";
        String expectedContentHash = copyAndHashResource("/javalib.jar", privateCopyFile);

        // This time make sure an unknown class loader is an ancestor of the class loader we use.
        ClassLoader knownClassLoader = sContext.getClass().getClassLoader();
        ClassLoader unknownClassLoader = new UnknownClassLoader(knownClassLoader);
        ClassLoader loader =
                new DexClassLoader(privateCopyFile.toString(), null, null, unknownClassLoader);
        loader.loadClass("com.android.dcl.Simple");

        // And make sure we log events about it
        long previousEventNanos = mostRecentEventTimeNanos();
        runDynamicCodeLoggingJob(IDLE_LOGGING_JOB_ID);

        assertDclLoggedSince(previousEventNanos, DCL_DEX_SUBTAG,
                expectedNameHash, expectedContentHash);
    }

    @Test
    public void testGeneratesEvents_nativeLibrary() throws Exception {
        new TestNativeCodeWithRetries() {
            @Override
            protected void loadNativeCode(int tryNumber) throws Exception {
                // We need to use a different file name for each retry, because once a file is
                // loaded, re-loading it has no effect.
                String privateCopyName = "copied" + tryNumber + ".so";
                File privateCopyFile = privateFile(privateCopyName);
                mExpectedNameHash = hashOf(privateCopyName);
                mExpectedContentHash = copyAndHashResource(
                        libraryPath("DynamicCodeLoggerNativeTestLibrary.so"), privateCopyFile);

                System.load(privateCopyFile.toString());
            }
        }.runTest();
    }

    @Test
    public void testGeneratesEvents_nativeLibrary_escapedName() throws Exception {
        new TestNativeCodeWithRetries() {
            @Override
            protected void loadNativeCode(int tryNumber) throws Exception {
                // A file name with a space will be escaped in the audit log; verify we un-escape it
                // correctly.
                String privateCopyName = "second copy " + tryNumber + ".so";
                File privateCopyFile = privateFile(privateCopyName);
                mExpectedNameHash = hashOf(privateCopyName);
                mExpectedContentHash = copyAndHashResource(
                        libraryPath("DynamicCodeLoggerNativeTestLibrary.so"), privateCopyFile);

                System.load(privateCopyFile.toString());
            }
        }.runTest();
    }

    @Test
    public void testGeneratesEvents_nativeExecutable() throws Exception {
        new TestNativeCodeWithRetries() {
            @Override
            protected void loadNativeCode(int tryNumber) throws Exception {
                String privateCopyName = "test_executable" + tryNumber;
                File privateCopyFile = privateFile(privateCopyName);
                mExpectedNameHash = hashOf(privateCopyName);
                mExpectedContentHash = copyAndHashResource(
                        "/DynamicCodeLoggerNativeExecutable", privateCopyFile);
                assertThat(privateCopyFile.setExecutable(true)).isTrue();

                Process process = Runtime.getRuntime().exec(privateCopyFile.toString());
                int exitCode = process.waitFor();
                assertThat(exitCode).isEqualTo(0);
            }
        }.runTest();
    }

    @Test
    public void testGeneratesEvents_spoofed_validFile() throws Exception {
        File privateCopyFile = privateFile("spoofed");

        String expectedContentHash = copyAndHashResource(
                "/DynamicCodeLoggerNativeExecutable", privateCopyFile);

        EventLog.writeEvent(EventLog.getTagCode("auditd"),
                "type=1400 avc: granted { execute_no_trans } "
                        + "path=\"" + privateCopyFile + "\" "
                        + "scontext=u:r:untrusted_app_27: "
                        + "tcontext=u:object_r:app_data_file: "
                        + "tclass=file ");

        String expectedNameHash =
                "1CF36F503A02877BB775DC23C1C5A47A95F2684B6A1A83B11795B856D88861E3";

        // Run the job to scan generated audit log entries
        runDynamicCodeLoggingJob(AUDIT_WATCHING_JOB_ID);

        // And then make sure we log events about it
        long previousEventNanos = mostRecentEventTimeNanos();
        runDynamicCodeLoggingJob(IDLE_LOGGING_JOB_ID);

        assertDclLoggedSince(previousEventNanos, DCL_NATIVE_SUBTAG,
                expectedNameHash, expectedContentHash);
    }

    @Test
    public void testGeneratesEvents_spoofed_validFile_untrustedApp() throws Exception {
        File privateCopyFile = privateFile("spoofed2");

        String expectedContentHash = copyAndHashResource(
                "/DynamicCodeLoggerNativeExecutable", privateCopyFile);

        EventLog.writeEvent(EventLog.getTagCode("auditd"),
                "type=1400 avc: granted { execute_no_trans } "
                        + "path=\"" + privateCopyFile + "\" "
                        + "scontext=u:r:untrusted_app: "
                        + "tcontext=u:object_r:app_data_file: "
                        + "tclass=file ");

        String expectedNameHash =
                "3E57AA59249154C391316FDCF07C1D499C26A564E4D305833CCD9A98ED895AC9";

        // Run the job to scan generated audit log entries
        runDynamicCodeLoggingJob(AUDIT_WATCHING_JOB_ID);

        // And then make sure we log events about it
        long previousEventNanos = mostRecentEventTimeNanos();
        runDynamicCodeLoggingJob(IDLE_LOGGING_JOB_ID);

        assertDclLoggedSince(previousEventNanos, DCL_NATIVE_SUBTAG,
                expectedNameHash, expectedContentHash);
    }

    @Test
    public void testGeneratesEvents_spoofed_pathTraversal() throws Exception {
        File privateDir = privateFile("x").getParentFile();

        // Transform /a/b/c -> /a/b/c/../../.. so we get back to the root
        File pathTraversalToRoot = privateDir;
        File root = new File("/");
        while (!privateDir.equals(root)) {
            pathTraversalToRoot = new File(pathTraversalToRoot, "..");
            privateDir = privateDir.getParentFile();
        }

        File spoofedFile = new File(pathTraversalToRoot, "dev/urandom");

        assertWithMessage("Expected " + spoofedFile + " to be readable")
                .that(spoofedFile.canRead()).isTrue();

        EventLog.writeEvent(EventLog.getTagCode("auditd"),
                "type=1400 avc: granted { execute_no_trans } "
                        + "path=\"" + spoofedFile + "\" "
                        + "scontext=u:r:untrusted_app_27: "
                        + "tcontext=u:object_r:app_data_file: "
                        + "tclass=file ");

        String expectedNameHash =
                "65528FE876BD676B0DFCC9A8ACA8988E026766F99EEC1E1FB48F46B2F635E225";

        // Run the job to scan generated audit log entries
        runDynamicCodeLoggingJob(AUDIT_WATCHING_JOB_ID);

        // And then trigger generating DCL events
        long previousEventNanos = mostRecentEventTimeNanos();
        runDynamicCodeLoggingJob(IDLE_LOGGING_JOB_ID);

        assertNoDclLoggedSince(previousEventNanos, DCL_NATIVE_SUBTAG, expectedNameHash);
    }

    @Test
    public void testGeneratesEvents_spoofed_otherAppFile() throws Exception {
        File ourPath = sContext.getDatabasePath("android_pay");
        File targetPath = new File(ourPath.toString()
                .replace("com.android.frameworks.dynamiccodeloggertest", "com.google.android.gms"));

        assertWithMessage("Expected " + targetPath + " to not be readable")
                .that(targetPath.canRead()).isFalse();

        EventLog.writeEvent(EventLog.getTagCode("auditd"),
                "type=1400 avc: granted { execute_no_trans } "
                        + "path=\"" + targetPath + "\" "
                        + "scontext=u:r:untrusted_app_27: "
                        + "tcontext=u:object_r:app_data_file: "
                        + "tclass=file ");

        String expectedNameHash =
                "CBE04E8AB9E7199FC19CBAAF9C774B88E56B3B19E823F2251693380AD6F515E6";

        // Run the job to scan generated audit log entries
        runDynamicCodeLoggingJob(AUDIT_WATCHING_JOB_ID);

        // And then trigger generating DCL events
        long previousEventNanos = mostRecentEventTimeNanos();
        runDynamicCodeLoggingJob(IDLE_LOGGING_JOB_ID);

        assertNoDclLoggedSince(previousEventNanos, DCL_NATIVE_SUBTAG, expectedNameHash);
    }

    // Abstract out the logic for running a native code loading test multiple times if needed and
    // leaving time for audit messages to reach the log.
    private abstract class TestNativeCodeWithRetries {
        String mExpectedContentHash;
        String mExpectedNameHash;

        abstract void loadNativeCode(int tryNumber) throws Exception;

        final void runTest() throws Exception {
            List<String> messages = null;

            for (int i = 0; i < AUDIT_LOG_RETRIES; i++) {
                loadNativeCode(i);

                SystemClock.sleep(i * RETRY_DELAY_MS);

                // Run the job to scan generated audit log entries
                runDynamicCodeLoggingJob(AUDIT_WATCHING_JOB_ID);

                // And then make sure we log events about it
                long previousEventNanos = mostRecentEventTimeNanos();
                runDynamicCodeLoggingJob(IDLE_LOGGING_JOB_ID);

                messages = findMatchingEvents(
                        previousEventNanos, DCL_NATIVE_SUBTAG, mExpectedNameHash);
                if (!messages.isEmpty()) {
                    break;
                }
            }

            assertHasDclLog(messages, mExpectedContentHash);
        }
    }

    private static File privateFile(String name) {
        return new File(sContext.getDir("dcl", Context.MODE_PRIVATE), name);
    }

    private String libraryPath(final String libraryName) {
        // This may be deprecated. but it tells us the ABI of this process which is exactly what we
        // want.
        return "/lib/" + Build.CPU_ABI + "/" + libraryName;
    }

    private static String copyAndHashResource(String resourcePath, File copyTo) throws Exception {
        MessageDigest hasher = MessageDigest.getInstance(SHA_256);

        // Copy the jar from our Java resources to a private data directory
        Class<?> thisClass = DynamicCodeLoggerIntegrationTests.class;
        try (InputStream input = thisClass.getResourceAsStream(resourcePath);
             OutputStream output = new FileOutputStream(copyTo)) {
            byte[] buffer = new byte[1024];
            while (true) {
                int numRead = input.read(buffer);
                if (numRead < 0) {
                    break;
                }
                output.write(buffer, 0, numRead);
                hasher.update(buffer, 0, numRead);
            }
        }

        // Compute the SHA-256 of the file content so we can check that it is the same as the value
        // we see logged.
        return toHexString(hasher);
    }

    private String hashOf(String input) throws Exception {
        MessageDigest hasher = MessageDigest.getInstance(SHA_256);
        hasher.update(input.getBytes());
        return toHexString(hasher);
    }

    private static String toHexString(MessageDigest hasher) {
        Formatter formatter = new Formatter();
        for (byte b : hasher.digest()) {
            formatter.format("%02X", b);
        }

        return formatter.toString();
    }

    private static void runDynamicCodeLoggingJob(int jobId) throws Exception {
        // This forces the DynamicCodeLoggingService job to start now.
        runCommand("cmd jobscheduler run -f android " + jobId);
        // Wait for the job to have run.
        long startTime = SystemClock.elapsedRealtime();
        while (true) {
            String response = runCommand(
                    "cmd jobscheduler get-job-state android " + jobId);
            if (!response.contains("pending") && !response.contains("active")) {
                break;
            }
            // Don't wait forever - if it's taken > 10s then something is very wrong.
            if (SystemClock.elapsedRealtime() - startTime > TimeUnit.SECONDS.toMillis(10)) {
                throw new AssertionError("Job has not completed: " + response);
            }
            SystemClock.sleep(100);
        }
    }

    private static String runCommand(String command) throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[1000];
        UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ParcelFileDescriptor fd = ui.executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            while (true) {
                int count = input.read(buffer);
                if (count == -1) {
                    break;
                }
                response.write(buffer, 0, count);
            }
        }
        return response.toString("UTF-8");
    }

    private static long mostRecentEventTimeNanos() throws Exception {
        List<Event> events = readSnetEvents();
        return events.isEmpty() ? 0 : events.get(events.size() - 1).getTimeNanos();
    }

    private static void assertDclLoggedSince(long previousEventNanos, String expectedSubTag,
            String expectedNameHash, String expectedContentHash) throws Exception {
        List<String> messages =
                findMatchingEvents(previousEventNanos, expectedSubTag, expectedNameHash);

        assertHasDclLog(messages, expectedContentHash);
    }

    private static void assertHasDclLog(List<String> messages, String expectedContentHash) {
        assertWithMessage("Expected exactly one matching log entry").that(messages).hasSize(1);
        assertThat(messages.get(0)).endsWith(expectedContentHash);
    }

    private static void assertNoDclLoggedSince(long previousEventNanos, String expectedSubTag,
            String expectedNameHash) throws Exception {
        List<String> messages =
                findMatchingEvents(previousEventNanos, expectedSubTag, expectedNameHash);

        assertWithMessage("Expected no matching log entries").that(messages).isEmpty();
    }

    private static List<String> findMatchingEvents(long previousEventNanos, String expectedSubTag,
            String expectedNameHash) throws Exception {
        List<String> messages = new ArrayList<>();

        for (Event event : readSnetEvents()) {
            if (event.getTimeNanos() <= previousEventNanos) {
                continue;
            }

            Object data = event.getData();
            if (!(data instanceof Object[])) {
                continue;
            }
            Object[] fields = (Object[]) data;

            // We only care about DCL events that we generated.
            String subTag = (String) fields[0];
            if (!expectedSubTag.equals(subTag)) {
                continue;
            }
            int uid = (int) fields[1];
            if (uid != sMyUid) {
                continue;
            }

            String message = (String) fields[2];
            if (!message.startsWith(expectedNameHash)) {
                continue;
            }

            messages.add(message);
            //assertThat(message).endsWith(expectedContentHash);
        }
        return messages;
    }

    private static List<Event> readSnetEvents() throws Exception {
        List<Event> events = new ArrayList<>();
        EventLog.readEvents(new int[] { SNET_TAG }, events);
        return events;
    }

    /**
     * A class loader that does nothing useful, but importantly doesn't extend BaseDexClassLoader.
     */
    private static class UnknownClassLoader extends ClassLoader {
        UnknownClassLoader(ClassLoader parent) {
            super(parent);
        }
    }
}
