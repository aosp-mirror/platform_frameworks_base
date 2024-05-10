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

package com.android.server.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.Process;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Run: atest FrameworksServicesTests:ShutdownCheckPointsTest
 */
@Presubmit
public class ShutdownCheckPointsTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private IActivityManager mActivityManager;

    private TestInjector mTestInjector;
    private ShutdownCheckPoints mInstance;

    @Before
    public void setUp() {
        Locale.setDefault(Locale.UK);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        mTestInjector = new TestInjector(mActivityManager);
        mInstance = new ShutdownCheckPoints(mTestInjector);
    }

    @Test
    public void testSystemServerEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal("reason1");

        assertTrue(dumpToString().startsWith(
                "Shutdown request from SYSTEM for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testSystemServerEntry\n at "));
    }

    @Test
    public void testSystemServerEntryWithoutReason() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(null);

        assertTrue(dumpToString().startsWith(
                "Shutdown request from SYSTEM at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"));
    }

    @Test
    public void testSystemServiceBinderEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(Process.myPid(), "reason1");

        assertTrue(dumpToString().startsWith(
                "Shutdown request from SYSTEM for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testSystemServiceBinderEntry\n at "));
    }

    @Test
    public void testCallerProcessBinderEntries() throws RemoteException {
        List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfos = new ArrayList<>();
        runningAppProcessInfos.add(
                new ActivityManager.RunningAppProcessInfo("process_name", 1, new String[0]));
        when(mActivityManager.getRunningAppProcesses()).thenReturn(runningAppProcessInfos);

        mTestInjector.setCurrentTime(1000);
        // Matching pid in getRunningAppProcesses
        mInstance.recordCheckPointInternal(1, "reason1");
        // Missing pid in getRunningAppProcesses
        mInstance.recordCheckPointInternal(2, "reason2");

        assertEquals(
                "Shutdown request from BINDER for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testCallerProcessBinderEntries\n"
                        + "From process process_name (pid=1)\n\n"
                        + "Shutdown request from BINDER for reason reason2 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testCallerProcessBinderEntries\n"
                        + "From process ? (pid=2)\n\n",
                dumpToString());
    }

    @Test
    public void testNullCallerProcessBinderEntries() throws RemoteException {
        when(mActivityManager.getRunningAppProcesses()).thenReturn(null);

        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1, "reason1");

        assertEquals(
                "Shutdown request from BINDER for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testNullCallerProcessBinderEntries\n"
                        + "From process ? (pid=1)\n\n",
                dumpToString());
    }

    @Test
    public void testRemoteExceptionOnBinderEntry() throws RemoteException {
        when(mActivityManager.getRunningAppProcesses()).thenThrow(new RemoteException("Error"));

        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1, "reason1");

        assertEquals(
                "Shutdown request from BINDER for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testRemoteExceptionOnBinderEntry\n"
                        + "From process ? (pid=1)\n\n",
                dumpToString());
    }

    @Test
    public void testUnknownProcessBinderEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1, "reason1");

        assertEquals(
                "Shutdown request from BINDER for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testUnknownProcessBinderEntry\n"
                        + "From process ? (pid=1)\n\n",
                dumpToString());
    }

    @Test
    public void testBinderEntryWithoutReason() throws RemoteException {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1, null);

        assertTrue(dumpToString().startsWith(
                "Shutdown request from BINDER at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"));
    }

    @Test
    public void testSystemServiceIntentEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal("some.intent", "android", "reason1");

        assertTrue(dumpToString().startsWith(
                "Shutdown request from SYSTEM for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testSystemServiceIntentEntry\n at "));
    }

    @Test
    public void testIntentEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal("some.intent", "some.app", "reason1");

        assertEquals(
                "Shutdown request from INTENT for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "Intent: some.intent\n"
                        + "Package: some.app\n\n",
                dumpToString());
    }

    @Test
    public void testIntentEntryWithoutReason() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal("some.intent", "some.app", null);

        assertTrue(dumpToString().startsWith(
                "Shutdown request from INTENT at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"));
    }

    @Test
    public void testMultipleEntries() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1, "reason1");
        mTestInjector.setCurrentTime(2000);
        mInstance.recordCheckPointInternal(2, "reason2");
        mTestInjector.setCurrentTime(3000);
        mInstance.recordCheckPointInternal("intent", "app", "reason3");

        assertEquals(
                "Shutdown request from BINDER for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest.testMultipleEntries\n"
                        + "From process ? (pid=1)\n\n"
                        + "Shutdown request from BINDER for reason reason2 "
                        + "at 1970-01-01 00:00:02.000 UTC (epoch=2000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest.testMultipleEntries\n"
                        + "From process ? (pid=2)\n\n"
                        + "Shutdown request from INTENT for reason reason3 "
                        + "at 1970-01-01 00:00:03.000 UTC (epoch=3000)\n"
                        + "Intent: intent\n"
                        + "Package: app\n\n",
                dumpToString());
    }

    @Test
    public void testTooManyEntriesDropsOlderOnes() {
        mTestInjector.setCheckPointsLimit(2);
        ShutdownCheckPoints limitedInstance = new ShutdownCheckPoints(mTestInjector);

        mTestInjector.setCurrentTime(1000);
        limitedInstance.recordCheckPointInternal("intent.1", "app.1", "reason1");
        mTestInjector.setCurrentTime(2000);
        limitedInstance.recordCheckPointInternal("intent.2", "app.2", "reason2");
        mTestInjector.setCurrentTime(3000);
        limitedInstance.recordCheckPointInternal("intent.3", "app.3", "reason3");

        // Drops first intent.
        assertEquals(
                "Shutdown request from INTENT for reason reason2 "
                        + "at 1970-01-01 00:00:02.000 UTC (epoch=2000)\n"
                        + "Intent: intent.2\n"
                        + "Package: app.2\n\n"
                        + "Shutdown request from INTENT for reason reason3 "
                        + "at 1970-01-01 00:00:03.000 UTC (epoch=3000)\n"
                        + "Intent: intent.3\n"
                        + "Package: app.3\n\n",
                dumpToString(limitedInstance));
    }

    @Test
    public void testDumpToFile() throws Exception {
        File tempDir = createTempDir();
        File baseFile = new File(tempDir, "checkpoints");

        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal("first.intent", "first.app", "reason1");
        dumpToFile(baseFile);

        mTestInjector.setCurrentTime(2000);
        mInstance.recordCheckPointInternal("second.intent", "second.app", "reason2");
        dumpToFile(baseFile);

        File[] dumpFiles = tempDir.listFiles();
        Arrays.sort(dumpFiles);

        assertEquals(2, dumpFiles.length);
        assertEquals(
                "Shutdown request from INTENT for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "Intent: first.intent\n"
                        + "Package: first.app\n\n",
                readFileAsString(dumpFiles[0].getAbsolutePath()));
        assertEquals(
                "Shutdown request from INTENT for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "Intent: first.intent\n"
                        + "Package: first.app\n\n"
                        + "Shutdown request from INTENT for reason reason2 "
                        + "at 1970-01-01 00:00:02.000 UTC (epoch=2000)\n"
                        + "Intent: second.intent\n"
                        + "Package: second.app\n\n",
                readFileAsString(dumpFiles[1].getAbsolutePath()));
    }

    @Test
    public void testTooManyFilesDropsOlderOnes() throws Exception {
        mTestInjector.setDumpFilesLimit(1);
        ShutdownCheckPoints instance = new ShutdownCheckPoints(mTestInjector);
        File tempDir = createTempDir();
        File baseFile = new File(tempDir, "checkpoints");

        mTestInjector.setCurrentTime(1000);
        instance.recordCheckPointInternal("first.intent", "first.app", "reason1");
        dumpToFile(instance, baseFile);

        mTestInjector.setCurrentTime(2000);
        instance.recordCheckPointInternal("second.intent", "second.app", "reason2");
        dumpToFile(instance, baseFile);

        File[] dumpFiles = tempDir.listFiles();
        assertEquals(1, dumpFiles.length);
        assertEquals(
                "Shutdown request from INTENT for reason reason1 "
                        + "at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "Intent: first.intent\n"
                        + "Package: first.app\n\n"
                        + "Shutdown request from INTENT for reason reason2 "
                        + "at 1970-01-01 00:00:02.000 UTC (epoch=2000)\n"
                        + "Intent: second.intent\n"
                        + "Package: second.app\n\n",
                readFileAsString(dumpFiles[0].getAbsolutePath()));
    }

    private String dumpToString() {
        return dumpToString(mInstance);
    }

    private String dumpToString(ShutdownCheckPoints instance) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        instance.dumpInternal(pw);
        return sw.toString();
    }

    private void dumpToFile(File baseFile) throws InterruptedException {
        dumpToFile(mInstance, baseFile);
    }

    private void dumpToFile(ShutdownCheckPoints instance, File baseFile)
            throws InterruptedException {
        Thread dumpThread = instance.newDumpThreadInternal(baseFile);
        dumpThread.start();
        dumpThread.join();
    }

    private String readFileAsString(String absolutePath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(absolutePath)), StandardCharsets.UTF_8);
    }

    private File createTempDir() throws IOException {
        File tempDir = File.createTempFile("checkpoints", "out");
        tempDir.delete();
        tempDir.mkdir();
        return tempDir;
    }

    /** Fake system dependencies for testing. */
    private static final class TestInjector implements ShutdownCheckPoints.Injector {
        private long mNow;
        private int mCheckPointsLimit;
        private int mDumpFilesLimit;
        private IActivityManager mActivityManager;

        TestInjector(IActivityManager activityManager) {
            mNow = 0;
            mCheckPointsLimit = 100;
            mDumpFilesLimit = 2;
            mActivityManager = activityManager;
        }

        @Override
        public long currentTimeMillis() {
            return mNow;
        }

        @Override
        public int maxCheckPoints() {
            return mCheckPointsLimit;
        }

        @Override
        public int maxDumpFiles() {
            return mDumpFilesLimit;
        }

        @Override
        public IActivityManager activityManager() {
            return mActivityManager;
        }

        void setCurrentTime(long time) {
            mNow = time;
        }

        void setCheckPointsLimit(int limit) {
            mCheckPointsLimit = limit;
        }

        void setDumpFilesLimit(int dumpFilesLimit) {
            mDumpFilesLimit = dumpFilesLimit;
        }
    }
}
