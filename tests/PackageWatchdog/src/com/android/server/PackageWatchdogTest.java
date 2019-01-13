/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server;

import static com.android.server.PackageWatchdog.TRIGGER_FAILURE_COUNT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.test.TestLooper;
import android.support.test.InstrumentationRegistry;

import com.android.server.PackageWatchdog.PackageHealthObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

// TODO(zezeozue): Write test without using PackageWatchdog#getPackages. Just rely on
// behavior of observers receiving crash notifications or not to determine if it's registered
/**
 * Test PackageWatchdog.
 */
public class PackageWatchdogTest {
    private static final String APP_A = "com.package.a";
    private static final String APP_B = "com.package.b";
    private static final String OBSERVER_NAME_1 = "observer1";
    private static final String OBSERVER_NAME_2 = "observer2";
    private static final String OBSERVER_NAME_3 = "observer3";
    private static final long SHORT_DURATION = TimeUnit.SECONDS.toMillis(1);
    private static final long LONG_DURATION = TimeUnit.SECONDS.toMillis(5);
    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mTestLooper.startAutoDispatch();
    }

    @After
    public void tearDown() throws Exception {
        new File(InstrumentationRegistry.getContext().getFilesDir(),
                "package-watchdog.xml").delete();
    }

    /**
     * Test registration, unregistration, package expiry and duration reduction
     */
    @Test
    public void testRegistration() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);
        TestObserver observer3 = new TestObserver(OBSERVER_NAME_3);

        // Start observing for observer1 which will be unregistered
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        // Start observing for observer2 which will expire
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A, APP_B), SHORT_DURATION);
        // Start observing for observer3 which will have expiry duration reduced
        watchdog.startObservingHealth(observer3, Arrays.asList(APP_A), LONG_DURATION);

        // Verify packages observed at start
        // 1
        assertEquals(1, watchdog.getPackages(observer1).size());
        assertTrue(watchdog.getPackages(observer1).contains(APP_A));
        // 2
        assertEquals(2, watchdog.getPackages(observer2).size());
        assertTrue(watchdog.getPackages(observer2).contains(APP_A));
        assertTrue(watchdog.getPackages(observer2).contains(APP_B));
        // 3
        assertEquals(1, watchdog.getPackages(observer3).size());
        assertTrue(watchdog.getPackages(observer3).contains(APP_A));

        // Then unregister observer1
        watchdog.unregisterHealthObserver(observer1);

        // Verify observer2 and observer3 left
        // 1
        assertNull(watchdog.getPackages(observer1));
        // 2
        assertEquals(2, watchdog.getPackages(observer2).size());
        assertTrue(watchdog.getPackages(observer2).contains(APP_A));
        assertTrue(watchdog.getPackages(observer2).contains(APP_B));
        // 3
        assertEquals(1, watchdog.getPackages(observer3).size());
        assertTrue(watchdog.getPackages(observer3).contains(APP_A));

        // Then advance time a little and run messages in Handlers so observer2 expires
        Thread.sleep(SHORT_DURATION);
        mTestLooper.dispatchAll();

        // Verify observer3 left with reduced expiry duration
        // 1
        assertNull(watchdog.getPackages(observer1));
        // 2
        assertNull(watchdog.getPackages(observer2));
        // 3
        assertEquals(1, watchdog.getPackages(observer3).size());
        assertTrue(watchdog.getPackages(observer3).contains(APP_A));

        // Then advance time some more and run messages in Handlers so observer3 expires
        Thread.sleep(LONG_DURATION);
        mTestLooper.dispatchAll();

        // Verify observer3 expired
        // 1
        assertNull(watchdog.getPackages(observer1));
        // 2
        assertNull(watchdog.getPackages(observer2));
        // 3
        assertNull(watchdog.getPackages(observer3));
    }

    /**
     * Test package observers are persisted and loaded on startup
     */
    @Test
    public void testPersistence() throws Exception {
        PackageWatchdog watchdog1 = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        watchdog1.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog1.startObservingHealth(observer2, Arrays.asList(APP_A, APP_B), SHORT_DURATION);

        // Verify 2 observers are registered and saved internally
        // 1
        assertEquals(1, watchdog1.getPackages(observer1).size());
        assertTrue(watchdog1.getPackages(observer1).contains(APP_A));
        // 2
        assertEquals(2, watchdog1.getPackages(observer2).size());
        assertTrue(watchdog1.getPackages(observer2).contains(APP_A));
        assertTrue(watchdog1.getPackages(observer2).contains(APP_B));


        // Then advance time and run IO Handler so file is saved
        mTestLooper.dispatchAll();

        // Then start a new watchdog
        PackageWatchdog watchdog2 = createWatchdog();

        // Verify the new watchdog loads observers on startup but nothing registered
        assertEquals(0, watchdog2.getPackages(observer1).size());
        assertEquals(0, watchdog2.getPackages(observer2).size());
        // Verify random observer not saved returns null
        assertNull(watchdog2.getPackages(new TestObserver(OBSERVER_NAME_3)));

        // Then regiser observer1
        watchdog2.registerHealthObserver(observer1);
        watchdog2.registerHealthObserver(observer2);

        // Verify 2 observers are registered after reload
        // 1
        assertEquals(1, watchdog1.getPackages(observer1).size());
        assertTrue(watchdog1.getPackages(observer1).contains(APP_A));
        // 2
        assertEquals(2, watchdog1.getPackages(observer2).size());
        assertTrue(watchdog1.getPackages(observer2).contains(APP_A));
        assertTrue(watchdog1.getPackages(observer2).contains(APP_B));
    }

    /**
     * Test package failure under threshold does not notify observers
     */
    @Test
    public void testNoPackageFailureBeforeThreshold() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);

        // Then fail APP_A below the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT - 1; i++) {
            watchdog.onPackageFailure(new String[]{APP_A});
        }

        // Verify that observers are not notified
        assertEquals(0, observer1.mFailedPackages.size());
        assertEquals(0, observer2.mFailedPackages.size());
    }

    /**
     * Test package failure and notifies all observer since none handles the failure
     */
    @Test
    public void testPackageFailureNotifyAll() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        // Start observing for observer1 and observer2 without handling failures
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A, APP_B), SHORT_DURATION);

        // Then fail APP_A and APP_B above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(new String[]{APP_A, APP_B});
        }

        // Verify all observers are notifed of all package failures
        List<String> observer1Packages = observer1.mFailedPackages;
        List<String> observer2Packages = observer2.mFailedPackages;
        assertEquals(2, observer1Packages.size());
        assertEquals(1, observer2Packages.size());
        assertEquals(APP_A, observer1Packages.get(0));
        assertEquals(APP_B, observer1Packages.get(1));
        assertEquals(APP_A, observer2Packages.get(0));
    }

    /**
     * Test package failure and notifies only one observer because it handles the failure
     */
    @Test
    public void testPackageFailureNotifyOne() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1, true /* shouldHandle */);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2, true /* shouldHandle */);

        // Start observing for observer1 and observer2 with failure handling
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);

        // Then fail APP_A above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(new String[]{APP_A});
        }

        // Verify only one observer is notifed
        assertEquals(1, observer1.mFailedPackages.size());
        assertEquals(APP_A, observer1.mFailedPackages.get(0));
        assertEquals(0, observer2.mFailedPackages.size());
    }

    private PackageWatchdog createWatchdog() {
        return new PackageWatchdog(InstrumentationRegistry.getContext(),
                mTestLooper.getLooper());
    }

    private static class TestObserver implements PackageHealthObserver {
        private final String mName;
        private boolean mShouldHandle;
        final List<String> mFailedPackages = new ArrayList<>();

        TestObserver(String name) {
            mName = name;
        }

        TestObserver(String name, boolean shouldHandle) {
            mName = name;
            mShouldHandle = shouldHandle;
        }

        public boolean onHealthCheckFailed(String packageName) {
            mFailedPackages.add(packageName);
            return mShouldHandle;
        }

        public String getName() {
            return mName;
        }
    }
}
