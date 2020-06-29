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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
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
        mTestInjector = new TestInjector(0, 100, mActivityManager);
        mInstance = new ShutdownCheckPoints(mTestInjector);
    }

    @Test
    public void testSystemServerEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal();

        assertTrue(dumpToString().startsWith(
                "Shutdown request from SYSTEM at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testSystemServerEntry\n at "));
    }

    @Test
    public void testSystemServiceBinderEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(Process.myPid());

        assertTrue(dumpToString().startsWith(
                "Shutdown request from SYSTEM at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testSystemServiceBinderEntry\n at "));
    }

    @Test
    public void testCallerProcessBinderEntry() throws RemoteException {
        List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfos = new ArrayList<>();
        runningAppProcessInfos.add(
                new ActivityManager.RunningAppProcessInfo("process_name", 1, new String[0]));
        when(mActivityManager.getRunningAppProcesses()).thenReturn(runningAppProcessInfos);

        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1);

        assertEquals(
                "Shutdown request from BINDER at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testCallerProcessBinderEntry\n"
                        + "From process process_name (pid=1)\n\n",
                dumpToString());
    }

    @Test
    public void testRemoteExceptionOnBinderEntry() throws RemoteException {
        when(mActivityManager.getRunningAppProcesses()).thenThrow(new RemoteException("Error"));

        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1);

        assertEquals(
                "Shutdown request from BINDER at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testRemoteExceptionOnBinderEntry\n"
                        + "From process ? (pid=1)\n\n",
                dumpToString());
    }

    @Test
    public void testUnknownProcessBinderEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1);

        assertEquals(
                "Shutdown request from BINDER at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testUnknownProcessBinderEntry\n"
                        + "From process ? (pid=1)\n\n",
                dumpToString());
    }

    @Test
    public void testSystemServiceIntentEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal("some.intent", "android");

        assertTrue(dumpToString().startsWith(
                "Shutdown request from SYSTEM at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest"
                        + ".testSystemServiceIntentEntry\n at "));
    }

    @Test
    public void testIntentEntry() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal("some.intent", "some.app");

        assertEquals(
                "Shutdown request from INTENT at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "Intent: some.intent\n"
                        + "Package: some.app\n\n",
                dumpToString());
    }

    @Test
    public void testMultipleEntries() {
        mTestInjector.setCurrentTime(1000);
        mInstance.recordCheckPointInternal(1);
        mTestInjector.setCurrentTime(2000);
        mInstance.recordCheckPointInternal(2);
        mTestInjector.setCurrentTime(3000);
        mInstance.recordCheckPointInternal("intent", "app");

        assertEquals(
                "Shutdown request from BINDER at 1970-01-01 00:00:01.000 UTC (epoch=1000)\n"
                        + "com.android.server.power.ShutdownCheckPointsTest.testMultipleEntries\n"
                        + "From process ? (pid=1)\n"
                        + "\n"
                        + "Shutdown request from BINDER at 1970-01-01 00:00:02.000 UTC (epoch=2000)"
                        + "\n"
                        + "com.android.server.power.ShutdownCheckPointsTest.testMultipleEntries\n"
                        + "From process ? (pid=2)\n"
                        + "\n"
                        + "Shutdown request from INTENT at 1970-01-01 00:00:03.000 UTC (epoch=3000)"
                        + "\n"
                        + "Intent: intent\n"
                        + "Package: app\n"
                        + "\n",
                dumpToString());
    }

    @Test
    public void testTooManyEntriesDropsOlderOnes() {
        mTestInjector.setCheckPointsLimit(2);
        ShutdownCheckPoints limitedInstance = new ShutdownCheckPoints(mTestInjector);

        mTestInjector.setCurrentTime(1000);
        limitedInstance.recordCheckPointInternal("intent.1", "app.1");
        mTestInjector.setCurrentTime(2000);
        limitedInstance.recordCheckPointInternal("intent.2", "app.2");
        mTestInjector.setCurrentTime(3000);
        limitedInstance.recordCheckPointInternal("intent.3", "app.3");

        // Drops first intent.
        assertEquals(
                "Shutdown request from INTENT at 1970-01-01 00:00:02.000 UTC (epoch=2000)\n"
                        + "Intent: intent.2\n"
                        + "Package: app.2\n"
                        + "\n"
                        + "Shutdown request from INTENT at 1970-01-01 00:00:03.000 UTC (epoch=3000)"
                        + "\n"
                        + "Intent: intent.3\n"
                        + "Package: app.3\n"
                        + "\n",
                dumpToString(limitedInstance));
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

    /** Fake system dependencies for testing. */
    private final class TestInjector implements ShutdownCheckPoints.Injector {
        private long mNow;
        private int mLimit;
        private IActivityManager mActivityManager;

        TestInjector(long now, int limit, IActivityManager activityManager) {
            mNow = now;
            mLimit = limit;
            mActivityManager = activityManager;
        }

        @Override
        public long currentTimeMillis() {
            return mNow;
        }

        @Override
        public int maxCheckPoints() {
            return mLimit;
        }

        @Override
        public IActivityManager activityManager() {
            return mActivityManager;
        }

        void setCurrentTime(long time) {
            mNow = time;
        }

        void setCheckPointsLimit(int limit) {
            mLimit = limit;
        }
    }
}
