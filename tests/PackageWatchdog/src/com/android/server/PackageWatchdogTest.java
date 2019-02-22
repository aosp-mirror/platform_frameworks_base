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

import android.content.pm.VersionedPackage;
import android.os.test.TestLooper;
import android.support.test.InstrumentationRegistry;

import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;

import org.junit.Before;
import org.junit.Ignore;
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
    private static final String APP_C = "com.package.c";
    private static final String APP_D = "com.package.d";
    private static final long VERSION_CODE = 1L;
    private static final String OBSERVER_NAME_1 = "observer1";
    private static final String OBSERVER_NAME_2 = "observer2";
    private static final String OBSERVER_NAME_3 = "observer3";
    private static final String OBSERVER_NAME_4 = "observer4";
    private static final long SHORT_DURATION = TimeUnit.SECONDS.toMillis(1);
    private static final long LONG_DURATION = TimeUnit.SECONDS.toMillis(5);
    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        new File(InstrumentationRegistry.getContext().getFilesDir(),
                "package-watchdog.xml").delete();
        mTestLooper = new TestLooper();
        mTestLooper.startAutoDispatch();
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
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION, false);
        // Start observing for observer2 which will expire
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A, APP_B), SHORT_DURATION,
                false);
        // Start observing for observer3 which will have expiry duration reduced
        watchdog.startObservingHealth(observer3, Arrays.asList(APP_A), LONG_DURATION, false);

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

        watchdog1.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION, false);
        watchdog1.startObservingHealth(observer2, Arrays.asList(APP_A, APP_B), SHORT_DURATION,
                false);

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

        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION, false);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION, false);

        // Then fail APP_A below the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT - 1; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)));
        }

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify that observers are not notified
        assertEquals(0, observer1.mFailedPackages.size());
        assertEquals(0, observer2.mFailedPackages.size());
    }

    /**
     * Test package failure and does not notify any observer because they are not observing
     * the failed packages.
     */
    @Test
    public void testPackageFailureDifferentPackageNotifyNone() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);


        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION, false);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_B), SHORT_DURATION, false);

        // Then fail APP_C (not observed) above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_C, VERSION_CODE)));
        }

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify that observers are not notified
        assertEquals(0, observer1.mFailedPackages.size());
        assertEquals(0, observer2.mFailedPackages.size());
    }

    /**
     * Test package failure and does not notify any observer because the failed package version
     * does not match the available rollback-from-version.
     */
    @Test
    public void testPackageFailureDifferentVersionNotifyNone() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        long differentVersionCode = 2L;
        TestObserver observer = new TestObserver(OBSERVER_NAME_1) {
                @Override
                public int onHealthCheckFailed(VersionedPackage versionedPackage) {
                    if (versionedPackage.getVersionCode() == VERSION_CODE) {
                        // Only rollback for specific versionCode
                        return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
                    }
                    return PackageHealthObserverImpact.USER_IMPACT_NONE;
                }
            };

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION, false);

        // Then fail APP_A (different version) above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(
                            new VersionedPackage(APP_A, differentVersionCode)));
        }

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify that observers are not notified
        assertEquals(0, observer.mFailedPackages.size());
    }


    /**
     * Test package failure and notifies only least impact observers.
     */
    @Test
    public void testPackageFailureNotifyAllDifferentImpacts() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observerNone = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_NONE);
        TestObserver observerHigh = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
        TestObserver observerMid = new TestObserver(OBSERVER_NAME_3,
                PackageHealthObserverImpact.USER_IMPACT_MEDIUM);
        TestObserver observerLow = new TestObserver(OBSERVER_NAME_4,
                PackageHealthObserverImpact.USER_IMPACT_LOW);

        // Start observing for all impact observers
        watchdog.startObservingHealth(observerNone, Arrays.asList(APP_A, APP_B, APP_C, APP_D),
                SHORT_DURATION, false);
        watchdog.startObservingHealth(observerHigh, Arrays.asList(APP_A, APP_B, APP_C),
                SHORT_DURATION, false);
        watchdog.startObservingHealth(observerMid, Arrays.asList(APP_A, APP_B),
                SHORT_DURATION, false);
        watchdog.startObservingHealth(observerLow, Arrays.asList(APP_A),
                SHORT_DURATION, false);

        // Then fail all apps above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE),
                    new VersionedPackage(APP_B, VERSION_CODE),
                    new VersionedPackage(APP_C, VERSION_CODE),
                    new VersionedPackage(APP_D, VERSION_CODE)));
        }

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify least impact observers are notifed of package failures
        List<String> observerNonePackages = observerNone.mFailedPackages;
        List<String> observerHighPackages = observerHigh.mFailedPackages;
        List<String> observerMidPackages = observerMid.mFailedPackages;
        List<String> observerLowPackages = observerLow.mFailedPackages;

        // APP_D failure observed by only observerNone is not caught cos its impact is none
        assertEquals(0, observerNonePackages.size());
        // APP_C failure is caught by observerHigh cos it's the lowest impact observer
        assertEquals(1, observerHighPackages.size());
        assertEquals(APP_C, observerHighPackages.get(0));
        // APP_B failure is caught by observerMid cos it's the lowest impact observer
        assertEquals(1, observerMidPackages.size());
        assertEquals(APP_B, observerMidPackages.get(0));
        // APP_A failure is caught by observerLow cos it's the lowest impact observer
        assertEquals(1, observerLowPackages.size());
        assertEquals(APP_A, observerLowPackages.get(0));
    }

    /**
     * Test package failure and least impact observers are notified successively.
     * State transistions:
     *
     * <ul>
     * <li>(observer1:low, observer2:mid) -> {observer1}
     * <li>(observer1:high, observer2:mid) -> {observer2}
     * <li>(observer1:high, observer2:none) -> {observer1}
     * <li>(observer1:none, observer2:none) -> {}
     * <ul>
     */
    @Test
    public void testPackageFailureNotifyLeastImpactSuccessively() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observerFirst = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LOW);
        TestObserver observerSecond = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_MEDIUM);

        // Start observing for observerFirst and observerSecond with failure handling
        watchdog.startObservingHealth(observerFirst, Arrays.asList(APP_A), LONG_DURATION, false);
        watchdog.startObservingHealth(observerSecond, Arrays.asList(APP_A), LONG_DURATION, false);

        // Then fail APP_A above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)));
        }
        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify only observerFirst is notifed
        assertEquals(1, observerFirst.mFailedPackages.size());
        assertEquals(APP_A, observerFirst.mFailedPackages.get(0));
        assertEquals(0, observerSecond.mFailedPackages.size());

        // After observerFirst handles failure, next action it has is high impact
        observerFirst.mImpact = PackageHealthObserverImpact.USER_IMPACT_HIGH;
        observerFirst.mFailedPackages.clear();
        observerSecond.mFailedPackages.clear();

        // Then fail APP_A again above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)));
        }
        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify only observerSecond is notifed cos it has least impact
        assertEquals(1, observerSecond.mFailedPackages.size());
        assertEquals(APP_A, observerSecond.mFailedPackages.get(0));
        assertEquals(0, observerFirst.mFailedPackages.size());

        // After observerSecond handles failure, it has no further actions
        observerSecond.mImpact = PackageHealthObserverImpact.USER_IMPACT_NONE;
        observerFirst.mFailedPackages.clear();
        observerSecond.mFailedPackages.clear();

        // Then fail APP_A again above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)));
        }
        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify only observerFirst is notifed cos it has the only action
        assertEquals(1, observerFirst.mFailedPackages.size());
        assertEquals(APP_A, observerFirst.mFailedPackages.get(0));
        assertEquals(0, observerSecond.mFailedPackages.size());

        // After observerFirst handles failure, it too has no further actions
        observerFirst.mImpact = PackageHealthObserverImpact.USER_IMPACT_NONE;
        observerFirst.mFailedPackages.clear();
        observerSecond.mFailedPackages.clear();

        // Then fail APP_A again above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)));
        }
        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify no observer is notified cos no actions left
        assertEquals(0, observerFirst.mFailedPackages.size());
        assertEquals(0, observerSecond.mFailedPackages.size());
    }

    /**
     * Test package failure and notifies only one observer even with observer impact tie.
     */
    @Test
    public void testPackageFailureNotifyOneSameImpact() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);

        // Start observing for observer1 and observer2 with failure handling
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION, false);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION, false);

        // Then fail APP_A above the threshold
        for (int i = 0; i < TRIGGER_FAILURE_COUNT; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)));
        }

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify only one observer is notifed
        assertEquals(1, observer1.mFailedPackages.size());
        assertEquals(APP_A, observer1.mFailedPackages.get(0));
        assertEquals(0, observer2.mFailedPackages.size());
    }

    // TODO: Unignore test after package failure is triggered on observer expiry with failing
    // explicit health check
    /**
     * Test explicit health check status determines package failure or success on expiry
     */
    @Ignore
    @Test
    public void testPackageFailureExplicitHealthCheck() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
        TestObserver observer3 = new TestObserver(OBSERVER_NAME_3,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);


        // Start observing with explicit health checks for APP_A and APP_B respectively
        // with observer1 and observer2
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION, true);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_B), SHORT_DURATION, true);
        // Explicit health check passed for APP_A (observer1 is aware)
        watchdog.onExplicitHealthCheckPassed(APP_A);
        // Start observing APP_A with explicit health checks for observer3.
        // Observer3 didn't exist when we got the explicit health check above, so
        // it starts out with a non-passing explicit health check and has to wait for a pass
        // otherwise it would be notified of APP_A failure on expiry
        watchdog.startObservingHealth(observer3, Arrays.asList(APP_A), SHORT_DURATION, true);

        // Then expire observers
        Thread.sleep(SHORT_DURATION);
        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify observer1 is not notified
        assertEquals(0, observer1.mFailedPackages.size());

        // Verify observer2 is notifed because health checks for APP_B never passed
        assertEquals(1, observer2.mFailedPackages.size());
        assertEquals(APP_B, observer2.mFailedPackages.get(0));

        // Verify observer3 is notifed because health checks for APP_A did not pass before expiry
        assertEquals(1, observer3.mFailedPackages.size());
        assertEquals(APP_A, observer3.mFailedPackages.get(0));
    }

    private PackageWatchdog createWatchdog() {
        return new PackageWatchdog(InstrumentationRegistry.getContext(),
                mTestLooper.getLooper());
    }

    private static class TestObserver implements PackageHealthObserver {
        private final String mName;
        private int mImpact;
        final List<String> mFailedPackages = new ArrayList<>();

        TestObserver(String name) {
            mName = name;
            mImpact = PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
        }

        TestObserver(String name, int impact) {
            mName = name;
            mImpact = impact;
        }

        public int onHealthCheckFailed(VersionedPackage versionedPackage) {
            return mImpact;
        }

        public boolean execute(VersionedPackage versionedPackage) {
            mFailedPackages.add(versionedPackage.getPackageName());
            return true;
        }

        public String getName() {
            return mName;
        }
    }
}
