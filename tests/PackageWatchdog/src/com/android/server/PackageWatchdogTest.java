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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.crashrecovery.flags.Flags;
import android.net.ConnectivityModuleConnector;
import android.net.ConnectivityModuleConnector.ConnectivityModuleHealthListener;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.test.TestLooper;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.util.AtomicFile;
import android.util.LongArrayQueue;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.PackageWatchdog.HealthCheckState;
import com.android.server.PackageWatchdog.MonitoredPackage;
import com.android.server.PackageWatchdog.ObserverInternal;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Test PackageWatchdog.
 */
public class PackageWatchdogTest {
    private static final long RETRY_MAX_COUNT = 30;
    private static final long RETRY_TIMEOUT_MILLIS = 500;

    private static final String APP_A = "com.package.a";
    private static final String APP_B = "com.package.b";
    private static final String APP_C = "com.package.c";
    private static final String APP_D = "com.package.d";
    private static final long VERSION_CODE = 1L;
    private static final String OBSERVER_NAME_1 = "observer1";
    private static final String OBSERVER_NAME_2 = "observer2";
    private static final String OBSERVER_NAME_3 = "observer3";
    private static final String OBSERVER_NAME_4 = "observer4";
    private static final long SHORT_DURATION = TimeUnit.SECONDS.toMillis(10);
    private static final long LONG_DURATION = TimeUnit.SECONDS.toMillis(50);

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private final TestClock mTestClock = new TestClock();
    private TestLooper mTestLooper;
    private Context mSpyContext;
    // Keep track of all created watchdogs to apply device config changes
    private List<PackageWatchdog> mAllocatedWatchdogs;
    @Mock
    private ConnectivityModuleConnector mConnectivityModuleConnector;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock Intent mMockIntent;
    // Mock only sysprop apis
    private PackageWatchdog.BootThreshold mSpyBootThreshold;
    @Captor
    private ArgumentCaptor<ConnectivityModuleHealthListener> mConnectivityModuleCallbackCaptor;
    private MockitoSession mSession;
    private HashMap<String, String> mSystemSettingsMap;
    private HashMap<String, String> mCrashRecoveryPropertiesMap;

    private boolean retry(Supplier<Boolean> supplier) throws Exception {
        for (int i = 0; i < RETRY_MAX_COUNT; ++i) {
            if (supplier.get()) {
                return true;
            }
            Thread.sleep(RETRY_TIMEOUT_MILLIS);
        }
        return false;
    }

    @Before
    public void setUp() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        MockitoAnnotations.initMocks(this);
        new File(InstrumentationRegistry.getContext().getFilesDir(),
                "package-watchdog.xml").delete();
        adoptShellPermissions(Manifest.permission.READ_DEVICE_CONFIG,
                Manifest.permission.WRITE_DEVICE_CONFIG);
        mTestLooper = new TestLooper();
        mSpyContext = spy(InstrumentationRegistry.getContext());
        when(mSpyContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getPackageInfo(anyString(), anyInt())).then(inv -> {
            final PackageInfo res = new PackageInfo();
            res.packageName = inv.getArgument(0);
            res.setLongVersionCode(VERSION_CODE);
            return res;
        });
        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(SystemProperties.class)
                .startMocking();
        mSystemSettingsMap = new HashMap<>();


        // Mock SystemProperties setter and various getters
        doAnswer((Answer<Void>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);
                    String value = invocationOnMock.getArgument(1);

                    mSystemSettingsMap.put(key, value);
                    return null;
                }
        ).when(() -> SystemProperties.set(anyString(), anyString()));

        doAnswer((Answer<Integer>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);
                    int defaultValue = invocationOnMock.getArgument(1);

                    String storedValue = mSystemSettingsMap.get(key);
                    return storedValue == null ? defaultValue : Integer.parseInt(storedValue);
                }
        ).when(() -> SystemProperties.getInt(anyString(), anyInt()));

        doAnswer((Answer<Long>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);
                    long defaultValue = invocationOnMock.getArgument(1);

                    String storedValue = mSystemSettingsMap.get(key);
                    return storedValue == null ? defaultValue : Long.parseLong(storedValue);
                }
        ).when(() -> SystemProperties.getLong(anyString(), anyLong()));

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_EXPLICIT_HEALTH_CHECK_ENABLED,
                Boolean.toString(true), false);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(PackageWatchdog.DEFAULT_TRIGGER_FAILURE_COUNT), false);

        mAllocatedWatchdogs = new ArrayList<>();
    }

    @After
    public void tearDown() throws Exception {
        dropShellPermissions();
        mSession.finishMocking();
        // Clean up listeners since too many listeners will delay notifications significantly
        for (PackageWatchdog watchdog : mAllocatedWatchdogs) {
            watchdog.removePropertyChangedListener();
        }
        mAllocatedWatchdogs.clear();
    }

    @Test
    public void testRegistration_singleObserver() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // The failed packages should be the same as the registered ones to ensure registration is
        // done successfully
        assertThat(observer.mHealthCheckFailedPackages).containsExactly(APP_A);
    }

    @Test
    public void testRegistration_multiObservers() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A, APP_B), SHORT_DURATION);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE),
                        new VersionedPackage(APP_B, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // The failed packages should be the same as the registered ones to ensure registration is
        // done successfully
        assertThat(observer1.mHealthCheckFailedPackages).containsExactly(APP_A);
        assertThat(observer2.mHealthCheckFailedPackages).containsExactly(APP_A, APP_B);
    }

    @Test
    public void testUnregistration_singleObserver() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.unregisterHealthObserver(observer);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // We should have no failed packages to ensure unregistration is done successfully
        assertThat(observer.mHealthCheckFailedPackages).isEmpty();
    }

    @Test
    public void testUnregistration_multiObservers() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.unregisterHealthObserver(observer2);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // observer1 should receive failed packages as intended.
        assertThat(observer1.mHealthCheckFailedPackages).containsExactly(APP_A);
        // observer2 should have no failed packages to ensure unregistration is done successfully
        assertThat(observer2.mHealthCheckFailedPackages).isEmpty();
    }

    @Test
    public void testExpiration_singleObserver() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);
        moveTimeForwardAndDispatch(SHORT_DURATION);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // We should have no failed packages for the fatal failure is raised after expiration
        assertThat(observer.mHealthCheckFailedPackages).isEmpty();
    }

    @Test
    public void testExpiration_multiObservers() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), LONG_DURATION);
        moveTimeForwardAndDispatch(SHORT_DURATION);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // We should have no failed packages for the fatal failure is raised after expiration
        assertThat(observer1.mHealthCheckFailedPackages).isEmpty();
        // We should have failed packages since observer2 hasn't expired
        assertThat(observer2.mHealthCheckFailedPackages).containsExactly(APP_A);
    }

    /** Observing already observed package extends the observation time. */
    @Test
    public void testObserveAlreadyObservedPackage() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        // Start observing APP_A
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);

        // Then advance time half-way
        moveTimeForwardAndDispatch(SHORT_DURATION / 2);

        // Start observing APP_A again
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);

        // Then advance time such that it should have expired were it not for the second observation
        moveTimeForwardAndDispatch((SHORT_DURATION / 2) + 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify that we receive failed packages as expected for APP_A not expired
        assertThat(observer.mHealthCheckFailedPackages).containsExactly(APP_A);
    }

    /**
     * Test package observers are persisted and loaded on startup
     */
    @Test
    public void testPersistence() {
        PackageWatchdog watchdog1 = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        watchdog1.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog1.startObservingHealth(observer2, Arrays.asList(APP_A, APP_B), SHORT_DURATION);
        // Then advance time and run IO Handler so file is saved
        mTestLooper.dispatchAll();
        // Then start a new watchdog
        PackageWatchdog watchdog2 = createWatchdog();
        // Then resume observer1 and observer2
        watchdog2.registerHealthObserver(observer1);
        watchdog2.registerHealthObserver(observer2);
        raiseFatalFailureAndDispatch(watchdog2,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE),
                        new VersionedPackage(APP_B, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // We should receive failed packages as expected to ensure observers are persisted and
        // resumed correctly
        assertThat(observer1.mHealthCheckFailedPackages).containsExactly(APP_A);
        assertThat(observer2.mHealthCheckFailedPackages).containsExactly(APP_A, APP_B);
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
        assertThat(observer1.mHealthCheckFailedPackages).isEmpty();
        assertThat(observer2.mHealthCheckFailedPackages).isEmpty();
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
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_C, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify that observers are not notified
        assertThat(observer1.mHealthCheckFailedPackages).isEmpty();
        assertThat(observer2.mHealthCheckFailedPackages).isEmpty();
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
                public int onHealthCheckFailed(VersionedPackage versionedPackage,
                        int failureReason, int mitigationCount) {
                    if (versionedPackage.getVersionCode() == VERSION_CODE) {
                        // Only rollback for specific versionCode
                        return PackageHealthObserverImpact.USER_IMPACT_LEVEL_30;
                    }
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
                }
            };

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);

        // Then fail APP_A (different version) above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, differentVersionCode)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify that observers are not notified
        assertThat(observer.mHealthCheckFailedPackages).isEmpty();
    }


    /**
     * Test package failure and notifies only least impact observers.
     */
    @Test
    public void testPackageFailureNotifyAllDifferentImpacts() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observerNone = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_0);
        TestObserver observerHigh = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
        TestObserver observerMid = new TestObserver(OBSERVER_NAME_3,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);
        TestObserver observerLow = new TestObserver(OBSERVER_NAME_4,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_10);

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
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE),
                        new VersionedPackage(APP_B, VERSION_CODE),
                        new VersionedPackage(APP_C, VERSION_CODE),
                        new VersionedPackage(APP_D, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify least impact observers are notifed of package failures
        List<String> observerNonePackages = observerNone.mMitigatedPackages;
        List<String> observerHighPackages = observerHigh.mMitigatedPackages;
        List<String> observerMidPackages = observerMid.mMitigatedPackages;
        List<String> observerLowPackages = observerLow.mMitigatedPackages;

        // APP_D failure observed by only observerNone is not caught cos its impact is none
        assertThat(observerNonePackages).isEmpty();
        // APP_C failure is caught by observerHigh cos it's the lowest impact observer
        assertThat(observerHighPackages).containsExactly(APP_C);
        // APP_B failure is caught by observerMid cos it's the lowest impact observer
        assertThat(observerMidPackages).containsExactly(APP_B);
        // APP_A failure is caught by observerLow cos it's the lowest impact observer
        assertThat(observerLowPackages).containsExactly(APP_A);
    }

    @Test
    public void testPackageFailureNotifyAllDifferentImpactsRecoverability() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observerNone = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_0);
        TestObserver observerHigh = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_50);
        TestObserver observerMid = new TestObserver(OBSERVER_NAME_3,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);
        TestObserver observerLow = new TestObserver(OBSERVER_NAME_4,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_10);

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
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE),
                        new VersionedPackage(APP_B, VERSION_CODE),
                        new VersionedPackage(APP_C, VERSION_CODE),
                        new VersionedPackage(APP_D, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify least impact observers are notifed of package failures
        List<String> observerNonePackages = observerNone.mMitigatedPackages;
        List<String> observerHighPackages = observerHigh.mMitigatedPackages;
        List<String> observerMidPackages = observerMid.mMitigatedPackages;
        List<String> observerLowPackages = observerLow.mMitigatedPackages;

        // APP_D failure observed by only observerNone is not caught cos its impact is none
        assertThat(observerNonePackages).isEmpty();
        // APP_C failure is caught by observerHigh cos it's the lowest impact observer
        assertThat(observerHighPackages).containsExactly(APP_C);
        // APP_B failure is caught by observerMid cos it's the lowest impact observer
        assertThat(observerMidPackages).containsExactly(APP_B);
        // APP_A failure is caught by observerLow cos it's the lowest impact observer
        assertThat(observerLowPackages).containsExactly(APP_A);
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
        mSetFlagsRule.disableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observerFirst = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_10);
        TestObserver observerSecond = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);

        // Start observing for observerFirst and observerSecond with failure handling
        watchdog.startObservingHealth(observerFirst, Arrays.asList(APP_A), LONG_DURATION);
        watchdog.startObservingHealth(observerSecond, Arrays.asList(APP_A), LONG_DURATION);

        // Then fail APP_A above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only observerFirst is notifed
        assertThat(observerFirst.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observerSecond.mMitigatedPackages).isEmpty();

        // After observerFirst handles failure, next action it has is high impact
        observerFirst.mImpact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_100;
        observerFirst.mMitigatedPackages.clear();
        observerSecond.mMitigatedPackages.clear();

        // Then fail APP_A again above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only observerSecond is notifed cos it has least impact
        assertThat(observerSecond.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observerFirst.mMitigatedPackages).isEmpty();

        // After observerSecond handles failure, it has no further actions
        observerSecond.mImpact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        observerFirst.mMitigatedPackages.clear();
        observerSecond.mMitigatedPackages.clear();

        // Then fail APP_A again above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only observerFirst is notifed cos it has the only action
        assertThat(observerFirst.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observerSecond.mMitigatedPackages).isEmpty();

        // After observerFirst handles failure, it too has no further actions
        observerFirst.mImpact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        observerFirst.mMitigatedPackages.clear();
        observerSecond.mMitigatedPackages.clear();

        // Then fail APP_A again above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify no observer is notified cos no actions left
        assertThat(observerFirst.mMitigatedPackages).isEmpty();
        assertThat(observerSecond.mMitigatedPackages).isEmpty();
    }

    @Test
    public void testPackageFailureNotifyLeastImpactSuccessivelyRecoverability() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observerFirst = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_10);
        TestObserver observerSecond = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);

        // Start observing for observerFirst and observerSecond with failure handling
        watchdog.startObservingHealth(observerFirst, Arrays.asList(APP_A), LONG_DURATION);
        watchdog.startObservingHealth(observerSecond, Arrays.asList(APP_A), LONG_DURATION);

        // Then fail APP_A above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only observerFirst is notifed
        assertThat(observerFirst.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observerSecond.mMitigatedPackages).isEmpty();

        // After observerFirst handles failure, next action it has is high impact
        observerFirst.mImpact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_50;
        observerFirst.mMitigatedPackages.clear();
        observerSecond.mMitigatedPackages.clear();

        // Then fail APP_A again above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only observerSecond is notifed cos it has least impact
        assertThat(observerSecond.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observerFirst.mMitigatedPackages).isEmpty();

        // After observerSecond handles failure, it has no further actions
        observerSecond.mImpact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        observerFirst.mMitigatedPackages.clear();
        observerSecond.mMitigatedPackages.clear();

        // Then fail APP_A again above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only observerFirst is notifed cos it has the only action
        assertThat(observerFirst.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observerSecond.mMitigatedPackages).isEmpty();

        // After observerFirst handles failure, it too has no further actions
        observerFirst.mImpact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        observerFirst.mMitigatedPackages.clear();
        observerSecond.mMitigatedPackages.clear();

        // Then fail APP_A again above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify no observer is notified cos no actions left
        assertThat(observerFirst.mMitigatedPackages).isEmpty();
        assertThat(observerSecond.mMitigatedPackages).isEmpty();
    }

    /**
     * Test package failure and notifies only one observer even with observer impact tie.
     */
    @Test
    public void testPackageFailureNotifyOneSameImpact() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);

        // Start observing for observer1 and observer2 with failure handling
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);

        // Then fail APP_A above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only one observer is notifed
        assertThat(observer1.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observer2.mMitigatedPackages).isEmpty();
    }

    @Test
    public void testPackageFailureNotifyOneSameImpactRecoverabilityDetection() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_50);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_50);

        // Start observing for observer1 and observer2 with failure handling
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);

        // Then fail APP_A above the threshold
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Verify only one observer is notifed
        assertThat(observer1.mMitigatedPackages).containsExactly(APP_A);
        assertThat(observer2.mMitigatedPackages).isEmpty();
    }

    /**
     * Test package passing explicit health checks does not fail and vice versa.
     */
    @Test
    public void testExplicitHealthChecks() throws Exception {
        TestController controller = new TestController();
        PackageWatchdog watchdog = createWatchdog(controller, true /* withPackagesReady */);
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
        TestObserver observer3 = new TestObserver(OBSERVER_NAME_3,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);


        // Start observing with explicit health checks for APP_A and APP_B respectively
        // with observer1 and observer2
        controller.setSupportedPackages(Arrays.asList(APP_A, APP_B));
        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_B), SHORT_DURATION);

        // Run handler so requests are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify we requested health checks for APP_A and APP_B
        List<String> requestedPackages = controller.getRequestedPackages();
        assertThat(requestedPackages).containsExactly(APP_A, APP_B);

        // Then health check passed for APP_A (observer1 is aware)
        controller.setPackagePassed(APP_A);

        // Then start observing APP_A with explicit health checks for observer3.
        // Observer3 didn't exist when we got the explicit health check above, so
        // it starts out with a non-passing explicit health check and has to wait for a pass
        // otherwise it would be notified of APP_A failure on expiry
        watchdog.startObservingHealth(observer3, Arrays.asList(APP_A), SHORT_DURATION);

        // Then expire observers
        moveTimeForwardAndDispatch(SHORT_DURATION);

        // Verify we cancelled all requests on expiry
        assertThat(controller.getRequestedPackages()).isEmpty();

        // Verify observer1 is not notified
        assertThat(observer1.mMitigatedPackages).isEmpty();

        // Verify observer2 is notifed because health checks for APP_B never passed
        assertThat(observer2.mMitigatedPackages).containsExactly(APP_B);

        // Verify observer3 is notifed because health checks for APP_A did not pass before expiry
        assertThat(observer3.mMitigatedPackages).containsExactly(APP_A);
    }

    /**
     * Test explicit health check state can be disabled and enabled correctly.
     */
    @Test
    public void testExplicitHealthCheckStateChanges() throws Exception {
        TestController controller = new TestController();
        PackageWatchdog watchdog = createWatchdog(controller, true /* withPackagesReady */);
        TestObserver observer = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);

        // Start observing with explicit health checks for APP_A and APP_B
        controller.setSupportedPackages(Arrays.asList(APP_A, APP_B, APP_C));
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer, Arrays.asList(APP_B), LONG_DURATION);

        // Run handler so requests are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify we requested health checks for APP_A and APP_B
        List<String> requestedPackages = controller.getRequestedPackages();
        assertThat(requestedPackages).containsExactly(APP_A, APP_B);

        // Disable explicit health checks (marks APP_A and APP_B as passed)
        setExplicitHealthCheckEnabled(false);

        // Run handler so requests/cancellations are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify all checks are cancelled
        assertThat(controller.getRequestedPackages()).isEmpty();

        // Then expire APP_A
        moveTimeForwardAndDispatch(SHORT_DURATION);

        // Verify APP_A is not failed (APP_B) is not expired yet
        assertThat(observer.mMitigatedPackages).isEmpty();

        // Re-enable explicit health checks
        setExplicitHealthCheckEnabled(true);

        // Run handler so requests/cancellations are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify no requests are made cos APP_A is expired and APP_B was marked as passed
        assertThat(controller.getRequestedPackages()).isEmpty();

        // Then set new supported packages
        controller.setSupportedPackages(Arrays.asList(APP_C));
        // Start observing APP_A and APP_C; only APP_C has support for explicit health checks
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A, APP_C), SHORT_DURATION);

        // Run handler so requests/cancellations are dispatched to the controller
        mTestLooper.dispatchAll();

        // Verify requests are only made for APP_C
        requestedPackages = controller.getRequestedPackages();
        assertThat(requestedPackages).containsExactly(APP_C);

        // Then expire APP_A and APP_C
        moveTimeForwardAndDispatch(SHORT_DURATION);

        // Verify only APP_C is failed because explicit health checks was not supported for APP_A
        assertThat(observer.mMitigatedPackages).containsExactly(APP_C);
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
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);

        // Start observing with explicit health checks for APP_A and
        // package observation duration == LONG_DURATION
        // health check duration == SHORT_DURATION (set by default in the TestController)
        controller.setSupportedPackages(Arrays.asList(APP_A));
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), LONG_DURATION);

        // Then APP_A has exceeded health check duration
        moveTimeForwardAndDispatch(SHORT_DURATION);

        // Verify that health check is failed
        assertThat(observer.mMitigatedPackages).containsExactly(APP_A);

        // Clear failed packages and forward time to expire the observation duration
        observer.mMitigatedPackages.clear();
        moveTimeForwardAndDispatch(LONG_DURATION);

        // Verify that health check failure is not notified again
        assertThat(observer.mMitigatedPackages).isEmpty();
    }

    /**
     * Tests failure when health check duration is different from package observation duration
     * Failure is also notified only once.
     */
    @Test
    public void testExplicitHealthCheckFailureAfterExpiry() {
        TestController controller = new TestController();
        PackageWatchdog watchdog = createWatchdog(controller, true /* withPackagesReady */);
        TestObserver observer = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);

        // Start observing with explicit health checks for APP_A and
        // package observation duration == SHORT_DURATION / 2
        // health check duration == SHORT_DURATION (set by default in the TestController)
        controller.setSupportedPackages(Arrays.asList(APP_A));
        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION / 2);

        // Forward time to expire the observation duration
        moveTimeForwardAndDispatch(SHORT_DURATION / 2);

        // Verify that health check is failed
        assertThat(observer.mMitigatedPackages).containsExactly(APP_A);

        // Clear failed packages and forward time to expire the health check duration
        observer.mMitigatedPackages.clear();
        moveTimeForwardAndDispatch(SHORT_DURATION);

        // Verify that health check failure is not notified again
        assertThat(observer.mMitigatedPackages).isEmpty();
    }

    /** Tests {@link MonitoredPackage} health check state transitions. */
    @Test
    public void testPackageHealthCheckStateTransitions() {
        TestController controller = new TestController();
        PackageWatchdog wd = createWatchdog(controller, true /* withPackagesReady */);
        MonitoredPackage m1 = wd.newMonitoredPackage(APP_A, LONG_DURATION,
                false /* hasPassedHealthCheck */);
        MonitoredPackage m2 = wd.newMonitoredPackage(APP_B, LONG_DURATION, false);
        MonitoredPackage m3 = wd.newMonitoredPackage(APP_C, LONG_DURATION, false);
        MonitoredPackage m4 = wd.newMonitoredPackage(APP_D, LONG_DURATION, SHORT_DURATION, true,
                new LongArrayQueue());

        // Verify transition: inactive -> active -> passed
        // Verify initially inactive
        assertThat(m1.getHealthCheckStateLocked()).isEqualTo(HealthCheckState.INACTIVE);
        // Verify still inactive, until we #setHealthCheckActiveLocked
        assertThat(m1.handleElapsedTimeLocked(SHORT_DURATION)).isEqualTo(HealthCheckState.INACTIVE);
        // Verify now active
        assertThat(m1.setHealthCheckActiveLocked(SHORT_DURATION)).isEqualTo(
                HealthCheckState.ACTIVE);
        // Verify now passed
        assertThat(m1.tryPassHealthCheckLocked()).isEqualTo(HealthCheckState.PASSED);

        // Verify transition: inactive -> active -> failed
        // Verify initially inactive
        assertThat(m2.getHealthCheckStateLocked()).isEqualTo(HealthCheckState.INACTIVE);
        // Verify now active
        assertThat(m2.setHealthCheckActiveLocked(SHORT_DURATION)).isEqualTo(
                HealthCheckState.ACTIVE);
        // Verify now failed
        assertThat(m2.handleElapsedTimeLocked(SHORT_DURATION)).isEqualTo(HealthCheckState.FAILED);

        // Verify transition: inactive -> failed
        // Verify initially inactive
        assertThat(m3.getHealthCheckStateLocked()).isEqualTo(HealthCheckState.INACTIVE);
        // Verify now failed because package expired
        assertThat(m3.handleElapsedTimeLocked(LONG_DURATION)).isEqualTo(HealthCheckState.FAILED);
        // Verify remains failed even when asked to pass
        assertThat(m3.tryPassHealthCheckLocked()).isEqualTo(HealthCheckState.FAILED);

        // Verify transition: passed
        assertThat(m4.getHealthCheckStateLocked()).isEqualTo(HealthCheckState.PASSED);
        // Verify remains passed even if health check fails
        assertThat(m4.handleElapsedTimeLocked(SHORT_DURATION)).isEqualTo(HealthCheckState.PASSED);
        // Verify remains passed even if package expires
        assertThat(m4.handleElapsedTimeLocked(LONG_DURATION)).isEqualTo(HealthCheckState.PASSED);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_REFACTOR_CRASHRECOVERY)
    public void testNetworkStackFailure() {
        mSetFlagsRule.disableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        final PackageWatchdog wd = createWatchdog();

        // Start observing with failure handling
        TestObserver observer = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
        wd.startObservingHealth(observer, Collections.singletonList(APP_A), SHORT_DURATION);

        // Notify of NetworkStack failure
        mConnectivityModuleCallbackCaptor.getValue().onNetworkStackFailure(APP_A);

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify the NetworkStack observer is notified
        assertThat(observer.mMitigatedPackages).containsExactly(APP_A);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_REFACTOR_CRASHRECOVERY)
    public void testNetworkStackFailureRecoverabilityDetection() {
        final PackageWatchdog wd = createWatchdog();

        // Start observing with failure handling
        TestObserver observer = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
        wd.startObservingHealth(observer, Collections.singletonList(APP_A), SHORT_DURATION);

        // Notify of NetworkStack failure
        mConnectivityModuleCallbackCaptor.getValue().onNetworkStackFailure(APP_A);

        // Run handler so package failures are dispatched to observers
        mTestLooper.dispatchAll();

        // Verify the NetworkStack observer is notified
        assertThat(observer.mMitigatedPackages).isEmpty();
    }

    /** Test default values are used when device property is invalid. */
    @Test
    public void testInvalidConfig_watchdogTriggerFailureCount() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(-1), /*makeDefault*/false);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), SHORT_DURATION);
        // Fail APP_A below the threshold which should not trigger package failures
        for (int i = 0; i < PackageWatchdog.DEFAULT_TRIGGER_FAILURE_COUNT - 1; i++) {
            watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
        }
        mTestLooper.dispatchAll();
        assertThat(observer.mHealthCheckFailedPackages).isEmpty();

        // One more to trigger the package failure
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        mTestLooper.dispatchAll();
        assertThat(observer.mHealthCheckFailedPackages).containsExactly(APP_A);
    }

    /** Test default values are used when device property is invalid. */
    @Test
    public void testInvalidConfig_watchdogTriggerDurationMillis() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(2), /*makeDefault*/false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_DURATION_MILLIS,
                Integer.toString(-1), /*makeDefault*/false);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A, APP_B), Long.MAX_VALUE);
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_TRIGGER_FAILURE_DURATION_MS + 1);
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        mTestLooper.dispatchAll();

        // We shouldn't receive APP_A since the interval of 2 failures is greater than
        // DEFAULT_TRIGGER_FAILURE_DURATION_MS.
        assertThat(observer.mHealthCheckFailedPackages).isEmpty();

        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_B, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_TRIGGER_FAILURE_DURATION_MS - 1);
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_B, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        mTestLooper.dispatchAll();

        // We should receive APP_B since the interval of 2 failures is less than
        // DEFAULT_TRIGGER_FAILURE_DURATION_MS.
        assertThat(observer.mHealthCheckFailedPackages).containsExactly(APP_B);
    }

    /**
     * Test default monitoring duration is used when PackageWatchdog#startObservingHealth is offered
     * an invalid durationMs.
     */
    @Test
    public void testInvalidMonitoringDuration_beforeExpiry() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), -1);
        // Note: Don't move too close to the expiration time otherwise the handler will be thrashed
        // by PackageWatchdog#scheduleNextSyncStateLocked which keeps posting runnables with very
        // small timeouts.
        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_OBSERVING_DURATION_MS - 100);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // We should receive APP_A since the observer hasn't expired
        assertThat(observer.mHealthCheckFailedPackages).containsExactly(APP_A);
    }

    /**
     * Test default monitoring duration is used when PackageWatchdog#startObservingHealth is offered
     * an invalid durationMs.
     */
    @Test
    public void testInvalidMonitoringDuration_afterExpiry() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), -1);
        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_OBSERVING_DURATION_MS + 1);
        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // We should receive nothing since the observer has expired
        assertThat(observer.mHealthCheckFailedPackages).isEmpty();
    }

    /** Test we are notified when enough failures are triggered within any window. */
    @Test
    public void testFailureTriggerWindow() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(3), /*makeDefault*/false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_DURATION_MILLIS,
                Integer.toString(1000), /*makeDefault*/false);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer, Arrays.asList(APP_A), Long.MAX_VALUE);
        // Raise 2 failures at t=0 and t=900 respectively
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        moveTimeForwardAndDispatch(900);
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);

        // Raise 2 failures at t=1100
        moveTimeForwardAndDispatch(200);
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        watchdog.onPackageFailure(Arrays.asList(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        mTestLooper.dispatchAll();

        // We should receive APP_A since there are 3 failures within 1000ms window
        assertThat(observer.mHealthCheckFailedPackages).containsExactly(APP_A);
    }

    /** Test that observers execute correctly for failures reasons that go through thresholding. */
    @Test
    public void testNonImmediateFailureReasons() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        TestObserver observer2 = new TestObserver(OBSERVER_NAME_2);

        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);
        watchdog.startObservingHealth(observer2, Arrays.asList(APP_B), SHORT_DURATION);

        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_APP_CRASH);
        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_B,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);

        assertThat(observer1.getLastFailureReason()).isEqualTo(
                PackageWatchdog.FAILURE_REASON_APP_CRASH);
        assertThat(observer2.getLastFailureReason()).isEqualTo(
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);
    }

    /** Test that observers execute correctly for failures reasons that skip thresholding. */
    @Test
    public void testImmediateFailures() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);

        watchdog.startObservingHealth(observer1, Arrays.asList(APP_A), SHORT_DURATION);

        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_NATIVE_CRASH);
        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_B,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK);

        assertThat(observer1.mMitigatedPackages).containsExactly(APP_A, APP_B);
    }

    /**
     * Test that a persistent observer will mitigate failures if it wishes to observe a package.
     */
    @Test
    public void testPersistentObserverWatchesPackage() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver persistentObserver = new TestObserver(OBSERVER_NAME_1);
        persistentObserver.setPersistent(true);
        persistentObserver.setMayObservePackages(true);

        watchdog.startObservingHealth(persistentObserver, Arrays.asList(APP_B), SHORT_DURATION);

        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);
        assertThat(persistentObserver.mHealthCheckFailedPackages).containsExactly(APP_A);
    }

    /**
     * Test that a persistent observer will not mitigate failures if it does not wish to observe
     * a given package.
     */
    @Test
    public void testPersistentObserverDoesNotWatchPackage() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver persistentObserver = new TestObserver(OBSERVER_NAME_1);
        persistentObserver.setPersistent(true);
        persistentObserver.setMayObservePackages(false);

        watchdog.startObservingHealth(persistentObserver, Arrays.asList(APP_B), SHORT_DURATION);

        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);
        assertThat(persistentObserver.mHealthCheckFailedPackages).isEmpty();
    }


    /** Ensure that boot loop mitigation is done when the number of boots meets the threshold. */
    @Test
    public void testBootLoopDetection_meetsThreshold() {
        mSetFlagsRule.disableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver = new TestObserver(OBSERVER_NAME_1);
        watchdog.registerHealthObserver(bootObserver);
        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }
        assertThat(bootObserver.mitigatedBootLoop()).isTrue();
    }

    @Test
    public void testBootLoopDetection_meetsThresholdRecoverability() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver = new TestObserver(OBSERVER_NAME_1);
        watchdog.registerHealthObserver(bootObserver);
        for (int i = 0; i < 15; i++) {
            watchdog.noteBoot();
        }
        assertThat(bootObserver.mitigatedBootLoop()).isTrue();
    }

    /**
     * Ensure that boot loop mitigation is not done when the number of boots does not meet the
     * threshold.
     */
    @Test
    public void testBootLoopDetection_doesNotMeetThreshold() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver = new TestObserver(OBSERVER_NAME_1);
        watchdog.registerHealthObserver(bootObserver);
        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT - 1; i++) {
            watchdog.noteBoot();
        }
        assertThat(bootObserver.mitigatedBootLoop()).isFalse();
    }

    /**
     * Ensure that boot loop mitigation is not done when the number of boots does not meet the
     * threshold.
     */
    @Test
    public void testBootLoopDetection_doesNotMeetThresholdRecoverabilityLowImpact() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);
        watchdog.registerHealthObserver(bootObserver);
        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT - 1; i++) {
            watchdog.noteBoot();
        }
        assertThat(bootObserver.mitigatedBootLoop()).isFalse();
    }

    /**
     * Ensure that boot loop mitigation is done for the observer with the lowest user impact
     */
    @Test
    public void testBootLoopMitigationDoneForLowestUserImpact() {
        mSetFlagsRule.disableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver1 = new TestObserver(OBSERVER_NAME_1);
        bootObserver1.setImpact(PackageHealthObserverImpact.USER_IMPACT_LEVEL_10);
        TestObserver bootObserver2 = new TestObserver(OBSERVER_NAME_2);
        bootObserver2.setImpact(PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);
        watchdog.registerHealthObserver(bootObserver1);
        watchdog.registerHealthObserver(bootObserver2);
        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }
        assertThat(bootObserver1.mitigatedBootLoop()).isTrue();
        assertThat(bootObserver2.mitigatedBootLoop()).isFalse();
    }

    @Test
    public void testBootLoopMitigationDoneForLowestUserImpactRecoverability() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver1 = new TestObserver(OBSERVER_NAME_1);
        bootObserver1.setImpact(PackageHealthObserverImpact.USER_IMPACT_LEVEL_10);
        TestObserver bootObserver2 = new TestObserver(OBSERVER_NAME_2);
        bootObserver2.setImpact(PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);
        watchdog.registerHealthObserver(bootObserver1);
        watchdog.registerHealthObserver(bootObserver2);
        for (int i = 0; i < 15; i++) {
            watchdog.noteBoot();
        }
        assertThat(bootObserver1.mitigatedBootLoop()).isTrue();
        assertThat(bootObserver2.mitigatedBootLoop()).isFalse();
    }

    /**
     * Ensure that the correct mitigation counts are sent to the boot loop observer.
     */
    @Test
    public void testMultipleBootLoopMitigation() {
        mSetFlagsRule.disableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver = new TestObserver(OBSERVER_NAME_1);
        watchdog.registerHealthObserver(bootObserver);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; j++) {
                watchdog.noteBoot();
            }
        }

        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_DEESCALATION_WINDOW_MS + 1);

        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; j++) {
                watchdog.noteBoot();
            }
        }

        assertThat(bootObserver.mBootMitigationCounts).isEqualTo(List.of(1, 2, 3, 4, 1, 2, 3, 4));
    }

    @Test
    public void testMultipleBootLoopMitigationRecoverabilityLowImpact() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver bootObserver = new TestObserver(OBSERVER_NAME_1,
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_30);
        watchdog.registerHealthObserver(bootObserver);
        for (int j = 0; j < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT - 1; j++) {
            watchdog.noteBoot();
        }
        for (int i = 0; i < 4; i++) {
                watchdog.noteBoot();
        }

        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_DEESCALATION_WINDOW_MS + 1);

        for (int j = 0; j < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT - 1; j++) {
            watchdog.noteBoot();
        }
        for (int i = 0; i < 4; i++) {
                watchdog.noteBoot();
        }

        assertThat(bootObserver.mBootMitigationCounts).isEqualTo(List.of(1, 2, 3, 4, 1, 2, 3, 4));
    }

    /**
     * Ensure that passing a null list of failed packages does not cause any mitigation logic to
     * execute.
     */
    @Test
    public void testNullFailedPackagesList() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer1 = new TestObserver(OBSERVER_NAME_1);
        watchdog.startObservingHealth(observer1, List.of(APP_A), LONG_DURATION);

        raiseFatalFailureAndDispatch(watchdog, null, PackageWatchdog.FAILURE_REASON_APP_CRASH);
        assertThat(observer1.mMitigatedPackages).isEmpty();
    }

    /**
     * Test to verify that Package Watchdog syncs health check requests with the controller
     * correctly, and that the requests are only synced when the set of observed packages
     * changes.
     */
    @Test
    public void testSyncHealthCheckRequests() {
        TestController testController = spy(TestController.class);
        testController.setSupportedPackages(List.of(APP_A, APP_B, APP_C));
        PackageWatchdog watchdog = createWatchdog(testController, true);

        TestObserver testObserver1 = new TestObserver(OBSERVER_NAME_1);
        watchdog.registerHealthObserver(testObserver1);
        watchdog.startObservingHealth(testObserver1, List.of(APP_A), LONG_DURATION);
        mTestLooper.dispatchAll();

        TestObserver testObserver2 = new TestObserver(OBSERVER_NAME_2);
        watchdog.registerHealthObserver(testObserver2);
        watchdog.startObservingHealth(testObserver2, List.of(APP_B), LONG_DURATION);
        mTestLooper.dispatchAll();

        TestObserver testObserver3 = new TestObserver(OBSERVER_NAME_3);
        watchdog.registerHealthObserver(testObserver3);
        watchdog.startObservingHealth(testObserver3, List.of(APP_C), LONG_DURATION);
        mTestLooper.dispatchAll();

        watchdog.unregisterHealthObserver(testObserver1);
        mTestLooper.dispatchAll();

        watchdog.unregisterHealthObserver(testObserver2);
        mTestLooper.dispatchAll();

        watchdog.unregisterHealthObserver(testObserver3);
        mTestLooper.dispatchAll();

        List<Set> expectedSyncRequests = List.of(
                Set.of(),
                Set.of(APP_A),
                Set.of(APP_A, APP_B),
                Set.of(APP_A, APP_B, APP_C),
                Set.of(APP_B, APP_C),
                Set.of(APP_C),
                Set.of()
        );
        assertThat(testController.getSyncRequests()).isEqualTo(expectedSyncRequests);
    }

    /**
     * Ensure that the failure history of a package is preserved when making duplicate calls to
     * observe the package.
     */
    @Test
    public void testFailureHistoryIsPreserved() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);
        watchdog.startObservingHealth(observer, List.of(APP_A), SHORT_DURATION);
        for (int i = 0; i < PackageWatchdog.DEFAULT_TRIGGER_FAILURE_COUNT - 1; i++) {
            watchdog.onPackageFailure(List.of(new VersionedPackage(APP_A, VERSION_CODE)),
                    PackageWatchdog.FAILURE_REASON_UNKNOWN);
        }
        mTestLooper.dispatchAll();
        assertThat(observer.mMitigatedPackages).isEmpty();
        watchdog.startObservingHealth(observer, List.of(APP_A), LONG_DURATION);
        watchdog.onPackageFailure(List.of(new VersionedPackage(APP_A, VERSION_CODE)),
                PackageWatchdog.FAILURE_REASON_UNKNOWN);
        mTestLooper.dispatchAll();
        assertThat(observer.mMitigatedPackages).isEqualTo(List.of(APP_A));
    }

    /**
     * Ensure that the sliding window logic results in the correct mitigation count being sent to
     * an observer.
     */
    @Test
    public void testMitigationSlidingWindow() {
        PackageWatchdog watchdog = createWatchdog();
        TestObserver observer = new TestObserver(OBSERVER_NAME_1);
        watchdog.startObservingHealth(observer, List.of(APP_A),
                PackageWatchdog.DEFAULT_OBSERVING_DURATION_MS * 2);


        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);

        moveTimeForwardAndDispatch(TimeUnit.MINUTES.toMillis(10));

        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);
        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);

        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_DEESCALATION_WINDOW_MS
                - TimeUnit.MINUTES.toMillis(1));

        // The first failure will be outside the threshold.
        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);

        moveTimeForwardAndDispatch(TimeUnit.MINUTES.toMillis(20));

        // The next 2 failures will also be outside the threshold.
        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);
        raiseFatalFailureAndDispatch(watchdog, Arrays.asList(new VersionedPackage(APP_A,
                VERSION_CODE)), PackageWatchdog.FAILURE_REASON_UNKNOWN);

        assertThat(observer.mMitigationCounts).isEqualTo(List.of(1, 2, 3, 3, 2, 3));
    }

    @Test
    public void testNormalizingMitigationCalls()  {
        PackageWatchdog watchdog = createWatchdog();

        LongArrayQueue mitigationCalls = new LongArrayQueue();
        mitigationCalls.addLast(1000);
        mitigationCalls.addLast(2000);
        mitigationCalls.addLast(3000);

        MonitoredPackage pkg = watchdog.newMonitoredPackage(
                "test", 123, 456, true, mitigationCalls);

        // Make current system uptime 10000ms.
        moveTimeForwardAndDispatch(9999);

        LongArrayQueue expectedCalls = pkg.normalizeMitigationCalls();

        assertThat(expectedCalls.size()).isEqualTo(mitigationCalls.size());

        for (int i = 0; i < mitigationCalls.size(); i++) {
            assertThat(expectedCalls.get(i)).isEqualTo(mitigationCalls.get(i) - 10000);
        }
    }

    /**
     * Ensure that a {@link MonitoredPackage} may be correctly written and read in order to persist
     * across reboots.
     */
    @Test
    public void testWritingAndReadingMonitoredPackage() throws Exception {
        PackageWatchdog watchdog = createWatchdog();

        LongArrayQueue mitigationCalls = new LongArrayQueue();
        mitigationCalls.addLast(1000);
        mitigationCalls.addLast(2000);
        mitigationCalls.addLast(3000);
        MonitoredPackage writePkg = watchdog.newMonitoredPackage(
                "test.package", 1000, 2000, true, mitigationCalls);

        // Move time forward so that the current uptime is 4000ms. Therefore, the written mitigation
        // calls will each be reduced by 4000.
        moveTimeForwardAndDispatch(3999);
        LongArrayQueue expectedCalls = new LongArrayQueue();
        expectedCalls.addLast(-3000);
        expectedCalls.addLast(-2000);
        expectedCalls.addLast(-1000);
        MonitoredPackage expectedPkg = watchdog.newMonitoredPackage(
                "test.package", 1000, 2000, true, expectedCalls);

        // Write the package
        File tmpFile = File.createTempFile("package-watchdog-test", ".xml");
        AtomicFile testFile = new AtomicFile(tmpFile);
        FileOutputStream stream = testFile.startWrite();
        TypedXmlSerializer outputSerializer = Xml.resolveSerializer(stream);
        outputSerializer.startDocument(null, true);
        writePkg.writeLocked(outputSerializer);
        outputSerializer.endDocument();
        testFile.finishWrite(stream);

        // Read the package
        TypedXmlPullParser parser = Xml.resolvePullParser(testFile.openRead());
        XmlUtils.beginDocument(parser, "package");
        MonitoredPackage readPkg = watchdog.parseMonitoredPackage(parser);

        assertTrue(readPkg.isEqualTo(expectedPkg));
    }

    /**
     * Ensure that a {@link ObserverInternal} may be correctly written and read in order to persist
     * across reboots.
     */
    @Test
    @SuppressWarnings("GuardedBy")
    public void testWritingAndReadingObserverInternalRecoverability() throws Exception {
        PackageWatchdog watchdog = createWatchdog();

        LongArrayQueue mitigationCalls = new LongArrayQueue();
        mitigationCalls.addLast(1000);
        mitigationCalls.addLast(2000);
        mitigationCalls.addLast(3000);
        MonitoredPackage writePkg = watchdog.newMonitoredPackage(
                "test.package", 1000, 2000, true, mitigationCalls);
        final int bootMitigationCount = 4;
        ObserverInternal writeObserver = new ObserverInternal("test", List.of(writePkg),
                bootMitigationCount);

        // Write the observer
        File tmpFile = File.createTempFile("observer-watchdog-test", ".xml");
        AtomicFile testFile = new AtomicFile(tmpFile);
        FileOutputStream stream = testFile.startWrite();
        TypedXmlSerializer outputSerializer = Xml.resolveSerializer(stream);
        outputSerializer.startDocument(null, true);
        writeObserver.writeLocked(outputSerializer);
        outputSerializer.endDocument();
        testFile.finishWrite(stream);

        // Read the observer
        TypedXmlPullParser parser = Xml.resolvePullParser(testFile.openRead());
        XmlUtils.beginDocument(parser, "observer");
        ObserverInternal readObserver = ObserverInternal.read(parser, watchdog);

        assertThat(readObserver.name).isEqualTo(writeObserver.name);
        assertThat(readObserver.getBootMitigationCount()).isEqualTo(bootMitigationCount);
    }

    /**
     * Ensure that boot mitigation counts may be correctly written and read as metadata
     * in order to persist across reboots.
     */
    @Test
    @SuppressWarnings("GuardedBy")
    public void testWritingAndReadingMetadataBootMitigationCountRecoverability() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        String filePath = InstrumentationRegistry.getContext().getFilesDir().toString()
                + "metadata_file.txt";

        ObserverInternal observer1 = new ObserverInternal("test1", List.of(), 1);
        ObserverInternal observer2 = new ObserverInternal("test2", List.of(), 2);
        watchdog.registerObserverInternal(observer1);
        watchdog.registerObserverInternal(observer2);

        mSpyBootThreshold = spy(watchdog.new BootThreshold(
                PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT,
                PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_WINDOW_MS));

        watchdog.saveAllObserversBootMitigationCountToMetadata(filePath);

        observer1.setBootMitigationCount(0);
        observer2.setBootMitigationCount(0);
        assertThat(observer1.getBootMitigationCount()).isEqualTo(0);
        assertThat(observer2.getBootMitigationCount()).isEqualTo(0);

        mSpyBootThreshold.readAllObserversBootMitigationCountIfNecessary(filePath);

        assertThat(observer1.getBootMitigationCount()).isEqualTo(1);
        assertThat(observer2.getBootMitigationCount()).isEqualTo(2);
    }

    /**
     * Tests device config changes are propagated correctly.
     */
    @Test
    public void testDeviceConfigChange_explicitHealthCheckEnabled() throws Exception {
        TestController controller = new TestController();
        PackageWatchdog watchdog = createWatchdog(controller, true /* withPackagesReady */);
        assertThat(controller.mIsEnabled).isTrue();

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_EXPLICIT_HEALTH_CHECK_ENABLED,
                Boolean.toString(false), /*makeDefault*/false);
        retry(() -> !controller.mIsEnabled);
        assertThat(controller.mIsEnabled).isFalse();
    }

    /**
     * Tests device config changes are propagated correctly.
     */
    @Test
    public void testDeviceConfigChange_triggerFailureCount() throws Exception {
        PackageWatchdog watchdog = createWatchdog();

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(777), false);
        retry(() -> watchdog.getTriggerFailureCount() == 777);
        assertThat(watchdog.getTriggerFailureCount()).isEqualTo(777);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(0), false);
        retry(() -> watchdog.getTriggerFailureCount()
                == PackageWatchdog.DEFAULT_TRIGGER_FAILURE_COUNT);
        assertThat(watchdog.getTriggerFailureCount()).isEqualTo(
                PackageWatchdog.DEFAULT_TRIGGER_FAILURE_COUNT);
    }

    /**
     * Tests device config changes are propagated correctly.
     */
    @Test
    public void testDeviceConfigChange_triggerFailureDurationMs() throws Exception {
        PackageWatchdog watchdog = createWatchdog();

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_DURATION_MILLIS,
                Integer.toString(888), false);
        retry(() -> watchdog.getTriggerFailureDurationMs() == 888);
        assertThat(watchdog.getTriggerFailureDurationMs()).isEqualTo(888);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_DURATION_MILLIS,
                Integer.toString(0), false);
        retry(() -> watchdog.getTriggerFailureDurationMs()
                == PackageWatchdog.DEFAULT_TRIGGER_FAILURE_DURATION_MS);
        assertThat(watchdog.getTriggerFailureDurationMs()).isEqualTo(
                PackageWatchdog.DEFAULT_TRIGGER_FAILURE_DURATION_MS);
    }

    /**
     * Tests device config changes are propagated correctly.
     */
    @Test
    public void testRegisterShutdownBroadcastReceiver() {
        PackageWatchdog watchdog = createWatchdog();
        doReturn(mMockIntent).when(mSpyContext)
                .registerReceiverForAllUsers(any(), any(), any(), any());

        watchdog.registerShutdownBroadcastReceiver();
        verify(mSpyContext).registerReceiverForAllUsers(any(), any(), eq(null), eq(null));
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
        // Call updateConfigs() so device config changes take effect immediately
        for (PackageWatchdog watchdog : mAllocatedWatchdogs) {
            watchdog.updateConfigs();
        }
    }

    private void moveTimeForwardAndDispatch(long milliSeconds) {
        // Exhaust all due runnables now which shouldn't be executed after time-leap
        mTestLooper.dispatchAll();
        mTestClock.moveTimeForward(milliSeconds);
        mTestLooper.moveTimeForward(milliSeconds);
        mTestLooper.dispatchAll();
    }

    /** Trigger package failures above the threshold. */
    private void raiseFatalFailureAndDispatch(PackageWatchdog watchdog,
            List<VersionedPackage> packages, int failureReason) {
        long triggerFailureCount = watchdog.getTriggerFailureCount();
        if (failureReason == PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK
                || failureReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
            triggerFailureCount = 1;
        }
        for (int i = 0; i < triggerFailureCount; i++) {
            watchdog.onPackageFailure(packages, failureReason);
        }
        mTestLooper.dispatchAll();
        if (Flags.recoverabilityDetection()) {
            moveTimeForwardAndDispatch(watchdog.DEFAULT_MITIGATION_WINDOW_MS);
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
                        mConnectivityModuleConnector, mTestClock);
        mockCrashRecoveryProperties(watchdog);

        // Verify controller is not automatically started
        assertThat(controller.mIsEnabled).isFalse();
        if (withPackagesReady) {
            // Only capture the NetworkStack callback for the latest registered watchdog
            reset(mConnectivityModuleConnector);
            watchdog.onPackagesReady();
            // Verify controller by default is started when packages are ready
            assertThat(controller.mIsEnabled).isTrue();

            if (!Flags.refactorCrashrecovery()) {
                verify(mConnectivityModuleConnector).registerHealthListener(
                        mConnectivityModuleCallbackCaptor.capture());
            }
        }
        mAllocatedWatchdogs.add(watchdog);
        return watchdog;
    }

    // Mock CrashRecoveryProperties as they cannot be accessed due to SEPolicy restrictions
    private void mockCrashRecoveryProperties(PackageWatchdog watchdog) {
        mCrashRecoveryPropertiesMap = new HashMap<>();

        try {
            mSpyBootThreshold = spy(watchdog.new BootThreshold(
                    PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT,
                    PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_WINDOW_MS));

            doAnswer((Answer<Integer>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.rescue_boot_count", "0");
                return Integer.parseInt(storedValue);
            }).when(mSpyBootThreshold).getCount();
            doAnswer((Answer<Void>) invocationOnMock -> {
                int count = invocationOnMock.getArgument(0);
                mCrashRecoveryPropertiesMap.put("crashrecovery.rescue_boot_count",
                        Integer.toString(count));
                return null;
            }).when(mSpyBootThreshold).setCount(anyInt());

            doAnswer((Answer<Integer>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.boot_mitigation_count", "0");
                return Integer.parseInt(storedValue);
            }).when(mSpyBootThreshold).getMitigationCount();
            doAnswer((Answer<Void>) invocationOnMock -> {
                int count = invocationOnMock.getArgument(0);
                mCrashRecoveryPropertiesMap.put("crashrecovery.boot_mitigation_count",
                        Integer.toString(count));
                return null;
            }).when(mSpyBootThreshold).setMitigationCount(anyInt());

            doAnswer((Answer<Long>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.rescue_boot_start", "0");
                return Long.parseLong(storedValue);
            }).when(mSpyBootThreshold).getStart();
            doAnswer((Answer<Void>) invocationOnMock -> {
                long count = invocationOnMock.getArgument(0);
                mCrashRecoveryPropertiesMap.put("crashrecovery.rescue_boot_start",
                        Long.toString(count));
                return null;
            }).when(mSpyBootThreshold).setStart(anyLong());

            doAnswer((Answer<Long>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.boot_mitigation_start", "0");
                return Long.parseLong(storedValue);
            }).when(mSpyBootThreshold).getMitigationStart();
            doAnswer((Answer<Void>) invocationOnMock -> {
                long count = invocationOnMock.getArgument(0);
                mCrashRecoveryPropertiesMap.put("crashrecovery.boot_mitigation_start",
                        Long.toString(count));
                return null;
            }).when(mSpyBootThreshold).setMitigationStart(anyLong());

            Field mBootThresholdField = watchdog.getClass().getDeclaredField("mBootThreshold");
            mBootThresholdField.setAccessible(true);
            mBootThresholdField.set(watchdog, mSpyBootThreshold);
        } catch (Exception e) {
            // tests will fail, just printing the error
            System.out.println("Error detected while spying BootThreshold" + e.getMessage());
        }
    }

    private static class TestObserver implements PackageHealthObserver {
        private final String mName;
        private int mImpact;
        private int mLastFailureReason;
        private boolean mIsPersistent = false;
        private boolean mMayObservePackages = false;
        private boolean mMitigatedBootLoop = false;
        final List<String> mHealthCheckFailedPackages = new ArrayList<>();
        final List<String> mMitigatedPackages = new ArrayList<>();
        final List<Integer> mMitigationCounts = new ArrayList<>();
        final List<Integer> mBootMitigationCounts = new ArrayList<>();

        TestObserver(String name) {
            mName = name;
            mImpact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_30;
        }

        TestObserver(String name, int impact) {
            mName = name;
            mImpact = impact;
        }

        public int onHealthCheckFailed(VersionedPackage versionedPackage, int failureReason,
                int mitigationCount) {
            mHealthCheckFailedPackages.add(versionedPackage.getPackageName());
            return mImpact;
        }

        public boolean execute(VersionedPackage versionedPackage, int failureReason,
                int mitigationCount) {
            mMitigatedPackages.add(versionedPackage.getPackageName());
            mMitigationCounts.add(mitigationCount);
            mLastFailureReason = failureReason;
            return true;
        }

        public String getUniqueIdentifier() {
            return mName;
        }

        public boolean isPersistent() {
            return mIsPersistent;
        }

        public boolean mayObservePackage(String packageName) {
            return mMayObservePackages;
        }

        public int onBootLoop(int level) {
            return mImpact;
        }

        public boolean executeBootLoopMitigation(int level) {
            mMitigatedBootLoop = true;
            mBootMitigationCounts.add(level);
            return true;
        }

        public boolean mitigatedBootLoop() {
            return mMitigatedBootLoop;
        }

        public int getLastFailureReason() {
            return mLastFailureReason;
        }

        public void setPersistent(boolean persistent) {
            mIsPersistent = persistent;
        }

        public void setImpact(int impact) {
            mImpact = impact;
        }

        public void setMayObservePackages(boolean mayObservePackages) {
            mMayObservePackages = mayObservePackages;
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
        private List<Set> mSyncRequests = new ArrayList<>();

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
            mSyncRequests.add(packages);
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

        public List<Set> getSyncRequests() {
            return mSyncRequests;
        }
    }

    private static class TestClock implements PackageWatchdog.SystemClock {
        // Note 0 is special to the internal clock of PackageWatchdog. We need to start from
        // a non-zero value in order not to disrupt the logic of PackageWatchdog.
        private long mUpTimeMillis = 1;
        @Override
        public long uptimeMillis() {
            return mUpTimeMillis;
        }
        public void moveTimeForward(long milliSeconds) {
            mUpTimeMillis += milliSeconds;
        }
    }
}
