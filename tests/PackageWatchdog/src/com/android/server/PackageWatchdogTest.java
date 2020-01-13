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

import static android.service.watchdog.ExplicitHealthCheckService.PackageConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.net.NetworkStackClient;
import android.net.NetworkStackClient.NetworkStackHealthListener;
import android.os.Handler;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.util.AtomicFile;

import androidx.test.InstrumentationRegistry;

import com.android.server.PackageWatchdog.MonitoredPackage;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// TODO: Write test without using PackageWatchdog#getPackages. Just rely on
// behavior of observers receiving crash notifications or not to determine if it's registered
// TODO: Use Truth in tests.
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
    private Context mSpyContext;
    @Mock
    private NetworkStackClient mMockNetworkStackClient;
    @Mock
    private PackageManager mMockPackageManager;
    @Captor
    private ArgumentCaptor<NetworkStackHealthListener> mNetworkStackCallbackCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        new File(InstrumentationRegistry.getContext().getFilesDir(),
                "package-watchdog.xml").delete();
        adoptShellPermissions(Manifest.permission.READ_DEVICE_CONFIG);
        mTestLooper = new TestLooper();
        mSpyContext = spy(InstrumentationRegistry.getContext());
        when(mSpyContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getPackageInfo(anyString(), anyInt())).then(inv -> {
            final PackageInfo res = new PackageInfo();
            res.packageName = inv.getArgument(0);
            res.setLongVersionCode(VERSION_CODE);
            return res;
        });
    }

    @After
    public void tearDown() throws Exception {
        dropShellPermissions();
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

    /** Observing already observed package extends the observation time. */
    @Test
    public void testObserveAlreadyObservedPackage() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        // Start observing APP_A
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);

        // Then advance time half-way
        Thread.sleep(SHORT_DURATION / 2);
        mTestLooper.dispatchAll();

        // Start observing APP_A again
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);

        // Then advance time such that it should have expired were it not for the second observation
        Thread.sleep((SHORT_DURATION / 2) + 1);
        mTestLooper.dispatchAll();

        // Verify that APP_A not expired since second observation extended the time
        assertEquals(1, watchdog.getPackages(observer).size());
        assertTrue(watchdog.getPackages(observer).contains(APP_A));
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

        // Then register observer1
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
        for (int i = 0; i < watchdog.getTriggerFailureCount() - 1; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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


        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_B), SHORT_DURATION);

        // Then fail APP_C (not observed) above the threshold
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_C, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);

        // Then fail APP_A (different version) above the threshold
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(
                            new VersionedPackage(APP_A, differentVersionCode)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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
                SHORT_DURATION);
        watchdog.startObservingHealth(observerHigh, Arrays.asList(APP_A, APP_B, APP_C),
                SHORT_DURATION);
        watchdog.startObservingHealth(observerMid, Arrays.asList(APP_A, APP_B),
                SHORT_DURATION);
        watchdog.startObservingHealth(observerLow, Arrays.asList(APP_A),
                SHORT_DURATION);

        // Then fail all apps above the threshold
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE),
                    new VersionedPackage(APP_B, VERSION_CODE),
                    new VersionedPackage(APP_C, VERSION_CODE),
                    new VersionedPackage(APP_D, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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
        watchdog.startObservingHealth(observerFirst, Arrays.asList(APP_A), LONG_DURATION);
        watchdog.startObservingHealth(observerSecond, Arrays.asList(APP_A), LONG_DURATION);

        // Then fail APP_A above the threshold
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
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
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);

        // Then fail APP_A above the threshold
        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
        }

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify only one observer is notifed
        assertEquals(1, observer1.mFailedPackages.size());
        assertEquals(APP_A, observer1.mFailedPackages.get(0));
        assertEquals(0, observer2.mFailedPackages.size());
    }

    /**
     * Test package passing explicit health checks does not fail and vice versa.
     */
    @Test
    public void testExplicitHealthChecks() throws Exception {
        TestController controller = new TestController();
        PackageWatchdog watchdog = createWatchdog(controller, true /* withPackagesReady */);
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
        TestObserver observer3 = new TestObserver(OBSERVER_NAME_3,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);


        // Start observing with explicit health checks for APP_A and APP_B respectively
        // with observer1 and observer2
        controller.setSupportedPackages(Arrays.asList(APP_A, APP_B));
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_B), SHORT_DURATION);

        // Run handler so requests are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify we requested health checks for APP_A and APP_B
        List<String> requestedPackages = controller.getRequestedPackages();
        assertEquals(2, requestedPackages.size());
        assertEquals(APP_A, requestedPackages.get(0));
        assertEquals(APP_B, requestedPackages.get(1));

        // Then health check passed for APP_A (observer1 is aware)
        controller.setPackagePassed(APP_A);

        // Then start observing APP_A with explicit health checks for observer3.
        // Observer3 didn't exist when we got the explicit health check above, so
        // it starts out with a non-passing explicit health check and has to wait for a pass
        // otherwise it would be notified of APP_A failure on expiry
        watchdog.startObservingHealth(observer3, Arrays.asList(APP_A), SHORT_DURATION);

        // Then expire observers
        Thread.sleep(SHORT_DURATION);
        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify we cancelled all requests on expiry
        assertEquals(0, controller.getRequestedPackages().size());

        // Verify observer1 is not notified
        assertEquals(0, observer1.mFailedPackages.size());

        // Verify observer2 is notifed because health checks for APP_B never passed
        assertEquals(1, observer2.mFailedPackages.size());
        assertEquals(APP_B, observer2.mFailedPackages.get(0));

        // Verify observer3 is notifed because health checks for APP_A did not pass before expiry
        assertEquals(1, observer3.mFailedPackages.size());
        assertEquals(APP_A, observer3.mFailedPackages.get(0));
    }

    /**
     * Test explicit health check state can be disabled and enabled correctly.
     */
    @Test
    public void testExplicitHealthCheckStateChanges() throws Exception {
        adoptShellPermissions(
                Manifest.permission.WRITE_DEVICE_CONFIG,
                Manifest.permission.READ_DEVICE_CONFIG);

        TestController controller = new TestController();
        PackageWatchdog watchdog = createWatchdog(controller, true /* withPackagesReady */);
        TestObserver observer = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_MEDIUM);

        // Start observing with explicit health checks for APP_A and APP_B
        controller.setSupportedPackages(Arrays.asList(APP_A, APP_B, APP_C));
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer, Arrays.asList(APP_B), LONG_DURATION);

        // Run handler so requests are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify we requested health checks for APP_A and APP_B
        List<String> requestedPackages = controller.getRequestedPackages();
        assertEquals(2, requestedPackages.size());
        assertEquals(APP_A, requestedPackages.get(0));
        assertEquals(APP_B, requestedPackages.get(1));

        // Disable explicit health checks (marks APP_A and APP_B as passed)
        setExplicitHealthCheckEnabled(false);

        // Run handler so requests/cancellations are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify all checks are cancelled
        assertEquals(0, controller.getRequestedPackages().size());

        // Then expire APP_A
        Thread.sleep(SHORT_DURATION);
        mTestLooper.dispatchAll();

        // Verify APP_A is not failed (APP_B) is not expired yet
        assertEquals(0, observer.mFailedPackages.size());

        // Re-enable explicit health checks
        setExplicitHealthCheckEnabled(true);

        // Run handler so requests/cancellations are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify no requests are made cos APP_A is expired and APP_B was marked as passed
        assertEquals(0, controller.getRequestedPackages().size());

        // Then set new supported packages
        controller.setSupportedPackages(Arrays.asList(APP_C));
        // Start observing APP_A and APP_C; only APP_C has support for explicit health checks
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A, APP_C), SHORT_DURATION);

        // Run handler so requests/cancellations are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify requests are only made for APP_C
        requestedPackages = controller.getRequestedPackages();
        assertEquals(1, requestedPackages.size());
        assertEquals(APP_C, requestedPackages.get(0));

        // Then expire APP_A and APP_C
        Thread.sleep(SHORT_DURATION);
        mTestLooper.dispatchAll();

        // Verify only APP_C is failed because explicit health checks was not supported for APP_A
        assertEquals(1, observer.mFailedPackages.size());
        assertEquals(APP_C, observer.mFailedPackages.get(0));
    }

    /**
     * Tests failure when health check duration is different from package observation duration
     * Failure is also notified only once.
     */
    @Test
    public void testExplicitHealthCheckFailureBeforeExpiry() throws Exception {
        TestController controller = new TestController();
        PackageWatchdog watchdog = createWatchdog(controller, true /* withPackagesReady */);
        TestObserver observer = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_MEDIUM);

        // Start observing with explicit health checks for APP_A and
        // package observation duration == LONG_DURATION
        // health check duration == SHORT_DURATION (set by default in the TestController)
        controller.setSupportedPackages(Arrays.asList(APP_A));
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), LONG_DURATION);

        // Then APP_A has exceeded health check duration
        Thread.sleep(SHORT_DURATION);
        mTestLooper.dispatchAll();

        // Verify that health check is failed
        assertEquals(1, observer.mFailedPackages.size());
        assertEquals(APP_A, observer.mFailedPackages.get(0));

        // Then clear failed packages and start observing a random package so requests are synced
        // and PackageWatchdog#onSupportedPackages is called and APP_A has a chance to fail again
        // this time due to package expiry.
        observer.mFailedPackages.clear();
        watchdog.startObservingHealth(observer, Arrays.asList(APP_B), LONG_DURATION);

        // Verify that health check failure is not notified again
        assertTrue(observer.mFailedPackages.isEmpty());
    }

    /** Tests {@link MonitoredPackage} health check state transitions. */
    @Test
    public void testPackageHealthCheckStateTransitions() {
        TestController controller = new TestController();
        PackageWatchdog wd = createWatchdog(controller, true /* withPackagesReady */);
        MonitoredPackage m1 = wd.new MonitoredPackage(APP_A, LONG_DURATION,
                false /* hasPassedHealthCheck */);
        MonitoredPackage m2 = wd.new MonitoredPackage(APP_B, LONG_DURATION, false);
        MonitoredPackage m3 = wd.new MonitoredPackage(APP_C, LONG_DURATION, false);
        MonitoredPackage m4 = wd.new MonitoredPackage(APP_D, LONG_DURATION, SHORT_DURATION, true);

        // Verify transition: inactive -> active -> passed
        // Verify initially inactive
        assertEquals(MonitoredPackage.STATE_INACTIVE, m1.getHealthCheckStateLocked());
        // Verify still inactive, until we #setHealthCheckActiveLocked
        assertEquals(MonitoredPackage.STATE_INACTIVE, m1.handleElapsedTimeLocked(SHORT_DURATION));
        // Verify now active
        assertEquals(MonitoredPackage.STATE_ACTIVE, m1.setHealthCheckActiveLocked(SHORT_DURATION));
        // Verify now passed
        assertEquals(MonitoredPackage.STATE_PASSED, m1.tryPassHealthCheckLocked());

        // Verify transition: inactive -> active -> failed
        // Verify initially inactive
        assertEquals(MonitoredPackage.STATE_INACTIVE, m2.getHealthCheckStateLocked());
        // Verify now active
        assertEquals(MonitoredPackage.STATE_ACTIVE, m2.setHealthCheckActiveLocked(SHORT_DURATION));
        // Verify now failed
        assertEquals(MonitoredPackage.STATE_FAILED, m2.handleElapsedTimeLocked(SHORT_DURATION));

        // Verify transition: inactive -> failed
        // Verify initially inactive
        assertEquals(MonitoredPackage.STATE_INACTIVE, m3.getHealthCheckStateLocked());
        // Verify now failed because package expired
        assertEquals(MonitoredPackage.STATE_FAILED, m3.handleElapsedTimeLocked(LONG_DURATION));
        // Verify remains failed even when asked to pass
        assertEquals(MonitoredPackage.STATE_FAILED, m3.tryPassHealthCheckLocked());

        // Verify transition: passed
        assertEquals(MonitoredPackage.STATE_PASSED, m4.getHealthCheckStateLocked());
        // Verify remains passed even if health check fails
        assertEquals(MonitoredPackage.STATE_PASSED, m4.handleElapsedTimeLocked(SHORT_DURATION));
        // Verify remains passed even if package expires
        assertEquals(MonitoredPackage.STATE_PASSED, m4.handleElapsedTimeLocked(LONG_DURATION));
    }

    @Test
    public void testNetworkStackFailure() {
        final PackageWatchdog wd = createWatchdog();

        // Start observing with failure handling
        TestObserver observer = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
        wd.startObservingHealth(observer, Collections.singletonList(APP_A), SHORT_DURATION);

        // Notify of NetworkStack failure
        mNetworkStackCallbackCaptor.getValue().onNetworkStackFailure(APP_A);

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify the NetworkStack observer is notified
        assertEquals(1, observer.mFailedPackages.size());
        assertEquals(APP_A, observer.mFailedPackages.get(0));
    }

    /** Test that observers execute correctly for different failure reasons */
    @Test
    public void testFailureReasons() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);
        TestObserver observer3 = new TestObserver(OBSERVER_NAME_3);
        TestObserver observer4 = new TestObserver(OBSERVER_NAME_4);

        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_B), SHORT_DURATION);
        watchdog.startObservingHealth(observer3, Arrays.asList(APP_C), SHORT_DURATION);
        watchdog.startObservingHealth(observer4, Arrays.asList(APP_D), SHORT_DURATION);

        for (int i = 0; i < watchdog.getTriggerFailureCount(); i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_NATIVE_CRASH);
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_B, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK);
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_C, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_APP_CRASH);
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_D, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);
        }

        // Run handler so requests are dispatched to the controller
        mTestLooper.dispatchAll();

        assertTrue(observer1.getLastFailureReason()
                == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH);
        assertTrue(observer2.getLastFailureReason()
                == PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK);
        assertTrue(observer3.getLastFailureReason()
                == PackageWatchdog.FAILURE_REASON_APP_CRASH);
        assertTrue(observer4.getLastFailureReason()
                == PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);
    }

    private void adoptShellPermissions(String... permissions) {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(permissions);
    }

    private void dropShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private void setExplicitHealthCheckEnabled(boolean enabled) {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_EXPLICIT_HEALTH_CHECK_ENABLED,
                Boolean.toString(enabled), /*makeDefault*/false);
        //give time for DeviceConfig to broadcast the property value change
        try {
            Thread.sleep(SHORT_DURATION);
        } catch (InterruptedException e) {
            fail("Thread.sleep unexpectedly failed!");
        }
    }

    private PackageWatchdog createWatchdog() {
        return createWatchdog(new TestController(), true /* withPackagesReady */);
    }

    private PackageWatchdog createWatchdog(TestController controller, boolean withPackagesReady) {
        AtomicFile policyFile =
                new AtomicFile(new File(mSpyContext.getFilesDir(), "package-watchdog.xml"));
        Handler handler = new Handler(mTestLooper.getLooper());
        PackageWatchdog watchdog =
                new PackageWatchdog(mSpyContext, policyFile, handler, handler, controller,
                        mMockNetworkStackClient);
        // Verify controller is not automatically started
        assertFalse(controller.mIsEnabled);
        if (withPackagesReady) {
            // Only capture the NetworkStack callback for the latest registered watchdog
            reset(mMockNetworkStackClient);
            watchdog.onPackagesReady();
            // Verify controller by default is started when packages are ready
            assertTrue(controller.mIsEnabled);

            verify(mMockNetworkStackClient).registerHealthListener(
                    mNetworkStackCallbackCaptor.capture());
        }
        return watchdog;
    }

    private static class TestObserver implements PackageHealthObserver {
        private final String mName;
        private int mImpact;
        private int mLastFailureReason;
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

        public boolean execute(VersionedPackage versionedPackage, int failureReason) {
            mFailedPackages.add(versionedPackage.getPackageName());
            mLastFailureReason = failureReason;
            return true;
        }

        public String getName() {
            return mName;
        }

        public int getLastFailureReason() {
            return mLastFailureReason;
        }
    }

    private static class TestController extends ExplicitHealthCheckController {
        TestController() {
            super(null /* controller */);
        }

        private boolean mIsEnabled;
        private List<String> mSupportedPackages = new ArrayList<>();
        private List<String> mRequestedPackages = new ArrayList<>();
        private Consumer<String> mPassedConsumer;
        private Consumer<List<PackageConfig>> mSupportedConsumer;
        private Runnable mNotifySyncRunnable;

        @Override
        public void setEnabled(boolean enabled) {
            mIsEnabled = enabled;
            if (!mIsEnabled) {
                mSupportedPackages.clear();
            }
        }

        @Override
        public void setCallbacks(Consumer<String> passedConsumer,
                Consumer<List<PackageConfig>> supportedConsumer, Runnable notifySyncRunnable) {
            mPassedConsumer = passedConsumer;
            mSupportedConsumer = supportedConsumer;
            mNotifySyncRunnable = notifySyncRunnable;
        }

        @Override
        public void syncRequests(Set<String> packages) {
            mRequestedPackages.clear();
            if (mIsEnabled) {
                packages.retainAll(mSupportedPackages);
                mRequestedPackages.addAll(packages);
                List<PackageConfig> packageConfigs = new ArrayList<>();
                for (String packageName: packages) {
                    packageConfigs.add(new PackageConfig(packageName, SHORT_DURATION));
                }
                mSupportedConsumer.accept(packageConfigs);
            } else {
                mSupportedConsumer.accept(Collections.emptyList());
            }
        }

        public void setSupportedPackages(List<String> packages) {
            mSupportedPackages.clear();
            mSupportedPackages.addAll(packages);
        }

        public void setPackagePassed(String packageName) {
            mPassedConsumer.accept(packageName);
        }

        public List<String> getRequestedPackages() {
            if (mIsEnabled) {
                return mRequestedPackages;
            } else {
                return Collections.emptyList();
            }
        }
    }
}
