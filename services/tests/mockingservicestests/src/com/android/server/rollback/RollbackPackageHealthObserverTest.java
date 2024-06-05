/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.rollback;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.crashrecovery.flags.Flags;
import android.os.Handler;
import android.os.MessageQueue;
import android.os.SystemProperties;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.PackageWatchdog;
import com.android.server.SystemConfig;
import com.android.server.pm.ApexManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


@RunWith(AndroidJUnit4.class)
public class RollbackPackageHealthObserverTest {
    @Mock
    private Context mMockContext;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private PackageWatchdog mMockPackageWatchdog;
    @Mock
    RollbackManager mRollbackManager;
    @Mock
    RollbackInfo mRollbackInfo;
    @Mock
    PackageRollbackInfo mPackageRollbackInfo;
    @Mock
    PackageManager mMockPackageManager;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ApexManager mApexManager;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private HashMap<String, String> mSystemSettingsMap;
    private MockitoSession mSession;
    private static final String APP_A = "com.package.a";
    private static final String APP_B = "com.package.b";
    private static final String APP_C = "com.package.c";
    private static final long VERSION_CODE = 1L;
    private static final long VERSION_CODE_2 = 2L;
    private static final String LOG_TAG = "RollbackPackageHealthObserverTest";

    private static final String PROP_DISABLE_HIGH_IMPACT_ROLLBACK_FLAG =
            "persist.device_config.configuration.disable_high_impact_rollback";

    private SystemConfig mSysConfig;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Before
    public void setup() {
        mSysConfig = new SystemConfigTestClass();

        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(PackageWatchdog.class)
                .spyStatic(SystemProperties.class)
                .startMocking();
        mSystemSettingsMap = new HashMap<>();

        // Mock PackageWatchdog
        doAnswer((Answer<PackageWatchdog>) invocationOnMock -> mMockPackageWatchdog)
                .when(() -> PackageWatchdog.getInstance(mMockContext));

        // Mock SystemProperties setter and various getters
        doAnswer((Answer<Void>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);
                    String value = invocationOnMock.getArgument(1);

                    mSystemSettingsMap.put(key, value);
                    return null;
                }
        ).when(() -> SystemProperties.set(anyString(), anyString()));

        doAnswer((Answer<Boolean>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);
                    boolean defaultValue = invocationOnMock.getArgument(1);

                    String storedValue = mSystemSettingsMap.get(key);
                    return storedValue == null ? defaultValue : Boolean.parseBoolean(storedValue);
                }
        ).when(() -> SystemProperties.getBoolean(anyString(), anyBoolean()));

        SystemProperties.set(PROP_DISABLE_HIGH_IMPACT_ROLLBACK_FLAG, Boolean.toString(false));
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
    }

    /**
     * Subclass of SystemConfig without running the constructor.
     */
    private class SystemConfigTestClass extends SystemConfig {
        SystemConfigTestClass() {
            super(false);
        }
    }

    @Test
    public void testHealthCheckLevels() {
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        VersionedPackage testFailedPackage = new VersionedPackage(APP_A, VERSION_CODE);
        VersionedPackage secondFailedPackage = new VersionedPackage(APP_B, VERSION_CODE);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);

        // Crashes with no rollbacks available
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_NATIVE_CRASH, 1));
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));

        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(mRollbackInfo));
        when(mRollbackInfo.getPackages()).thenReturn(List.of(mPackageRollbackInfo));
        when(mPackageRollbackInfo.getVersionRolledBackFrom()).thenReturn(testFailedPackage);

        // native crash
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_30,
                observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_NATIVE_CRASH, 1));
        // non-native crash for the package
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_30,
                observer.onHealthCheckFailed(testFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));
        // non-native crash for a different package
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                observer.onHealthCheckFailed(secondFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                observer.onHealthCheckFailed(secondFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 2));
        // Subsequent crashes when rollbacks have completed
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of());
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(testFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 3));
    }

    @Test
    public void testIsPersistent() {
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        assertTrue(observer.isPersistent());
    }

    @Test
    public void testMayObservePackage_withoutAnyRollback() {
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of());
        assertFalse(observer.mayObservePackage(APP_A));
    }

    @Test
    public void testMayObservePackage_forPersistentApp()
            throws PackageManager.NameNotFoundException {
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ApplicationInfo info = new ApplicationInfo();
        info.flags = ApplicationInfo.FLAG_PERSISTENT | ApplicationInfo.FLAG_SYSTEM;
        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(mRollbackInfo));
        when(mRollbackInfo.getPackages()).thenReturn(List.of(mPackageRollbackInfo));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getApplicationInfo(APP_A, 0)).thenReturn(info);
        assertTrue(observer.mayObservePackage(APP_A));
    }

    @Test
    public void testMayObservePackage_forNonPersistentApp()
            throws PackageManager.NameNotFoundException {
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(mRollbackInfo));
        when(mRollbackInfo.getPackages()).thenReturn(List.of(mPackageRollbackInfo));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getApplicationInfo(APP_A, 0))
                .thenThrow(new PackageManager.NameNotFoundException());
        assertFalse(observer.mayObservePackage(APP_A));
    }

    /**
     * Test that when impactLevel is low returns user impact level 70
     */
    @Test
    public void healthCheckFailed_impactLevelLow_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        VersionedPackage secondFailedPackage = new VersionedPackage(APP_B, VERSION_CODE);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                observer.onHealthCheckFailed(secondFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));
    }

    /**
     * HealthCheckFailed should only return low impact rollbacks. High impact rollbacks are only
     * for bootloop.
     */
    @Test
    public void healthCheckFailed_impactLevelHigh_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        VersionedPackage secondFailedPackage = new VersionedPackage(APP_B, VERSION_CODE);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(secondFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));
    }

    /**
     * When the rollback impact level is manual only return user impact level 0. (User impact level
     * 0 is ignored by package watchdog)
     */
    @Test
    public void healthCheckFailed_impactLevelManualOnly_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_ONLY_MANUAL);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        VersionedPackage secondFailedPackage = new VersionedPackage(APP_B, VERSION_CODE);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(secondFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));
    }

    /**
     * When both low impact and high impact are present, return 70.
     */
    @Test
    public void healthCheckFailed_impactLevelLowAndHigh_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        VersionedPackage failedPackage = new VersionedPackage(APP_C, VERSION_CODE);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                observer.onHealthCheckFailed(failedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));
    }

    /**
     * When low impact rollback is available roll it back.
     */
    @Test
    public void execute_impactLevelLow_nativeCrash_rollback()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        VersionedPackage secondFailedPackage = new VersionedPackage(APP_B, VERSION_CODE);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.execute(secondFailedPackage,
                PackageWatchdog.FAILURE_REASON_NATIVE_CRASH, 1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager).getAvailableRollbacks();
        verify(mRollbackManager).commitRollback(eq(rollbackId), any(), any());
    }

    /**
     * Rollback the failing package if rollback is available for it
     */
    @Test
    public void execute_impactLevelLow_rollbackFailedPackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.execute(appBFrom, PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager).commitRollback(argument.capture(), any(), any());
        // Rollback package App B as the failing package is B
        assertThat(argument.getValue()).isEqualTo(rollbackId2);
    }

    /**
     * Rollback all available rollbacks if the rollback is not available for failing package.
     */
    @Test
    public void execute_impactLevelLow_rollbackAll()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        VersionedPackage failedPackage = new VersionedPackage(APP_C, VERSION_CODE);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.execute(failedPackage, PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, times(2)).commitRollback(
                argument.capture(), any(), any());
        // Rollback A and B when the failing package doesn't have a rollback
        assertThat(argument.getAllValues()).isEqualTo(List.of(rollbackId1, rollbackId2));
    }

    /**
     * rollback low impact package if both low and high impact packages are available
     */
    @Test
    public void execute_impactLevelLowAndHigh_rollbackLow()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        VersionedPackage failedPackage = new VersionedPackage(APP_C, VERSION_CODE);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.execute(failedPackage, PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, times(1)).commitRollback(
                argument.capture(), any(), any());
        // Rollback A and B when the failing package doesn't have a rollback
        assertThat(argument.getAllValues()).isEqualTo(List.of(rollbackId1));
    }

    /**
     * Don't roll back high impact package if only high impact package is available. high impact
     * rollback to be rolled back only on bootloop.
     */
    @Test
    public void execute_impactLevelHigh_rollbackHigh()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        VersionedPackage failedPackage = new VersionedPackage(APP_C, VERSION_CODE);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.execute(failedPackage, PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, never()).commitRollback(argument.capture(), any(), any());

    }

    /**
     * Test that when impactLevel is low returns user impact level 70
     */
    @Test
    public void onBootLoop_impactLevelLow_onePackage() throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                observer.onBootLoop(1));
    }

    @Test
    public void onBootLoop_impactLevelHigh_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_90,
                observer.onBootLoop(1));
    }

    @Test
    public void onBootLoop_impactLevelHighDisableHighImpactRollback_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        SystemProperties.set(PROP_DISABLE_HIGH_IMPACT_ROLLBACK_FLAG, Boolean.toString(true));
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onBootLoop(1));
    }

    /**
     * When the rollback impact level is manual only return user impact level 0. (User impact level
     * 0 is ignored by package watchdog)
     */
    @Test
    public void onBootLoop_impactLevelManualOnly_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_ONLY_MANUAL);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onBootLoop(1));
    }

    /**
     * When both low impact and high impact are present, return 70.
     */
    @Test
    public void onBootLoop_impactLevelLowAndHigh_onePackage()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appAFrom, appATo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(1, List.of(packageRollbackInfo),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null, false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                observer.onBootLoop(1));
    }

    /**
     * Rollback all available rollbacks if the rollback is not available for failing package.
     */
    @Test
    public void executeBootLoopMitigation_impactLevelLow_rollbackAll()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.executeBootLoopMitigation(1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, times(2)).commitRollback(
                argument.capture(), any(), any());
        // Rollback A and B when the failing package doesn't have a rollback
        assertThat(argument.getAllValues()).isEqualTo(List.of(rollbackId1, rollbackId2));
    }

    /**
     * rollback low impact package if both low and high impact packages are available
     */
    @Test
    public void executeBootLoopMitigation_impactLevelLowAndHigh_rollbackLow()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.executeBootLoopMitigation(1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, times(1)).commitRollback(
                argument.capture(), any(), any());
        // Rollback A and B when the failing package doesn't have a rollback
        assertThat(argument.getAllValues()).isEqualTo(List.of(rollbackId1));
    }

    /**
     * Rollback high impact package if only high impact package is available
     */
    @Test
    public void executeBootLoopMitigation_impactLevelHigh_rollbackHigh()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.executeBootLoopMitigation(1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, times(1)).commitRollback(
                argument.capture(), any(), any());
        // Rollback high impact packages when no other rollback available
        assertThat(argument.getAllValues()).isEqualTo(List.of(rollbackId2));
    }

    /**
     * Rollback only low impact available rollbacks if both low and manual only are available.
     */
    @Test
    public void execute_impactLevelLowAndManual_rollbackLowImpactOnly()
            throws PackageManager.NameNotFoundException, InterruptedException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        int rollbackId2 = 2;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoB),
                false, null, 222,
                PackageManager.ROLLBACK_USER_IMPACT_ONLY_MANUAL);
        VersionedPackage failedPackage = new VersionedPackage(APP_C, VERSION_CODE);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.execute(failedPackage, PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, times(1)).commitRollback(
                argument.capture(), any(), any());
        assertThat(argument.getAllValues()).isEqualTo(List.of(rollbackId1));
    }

    /**
     * Do not roll back if only manual rollback is available.
     */
    @Test
    public void execute_impactLevelManual_rollbackLowImpactOnly()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_ONLY_MANUAL);
        VersionedPackage failedPackage = new VersionedPackage(APP_C, VERSION_CODE);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(rollbackInfo1));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.execute(failedPackage, PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, never()).commitRollback(argument.capture(), any(), any());
    }

    /**
     * Rollback alphabetically first package if multiple high impact rollbacks are available.
     */
    @Test
    public void executeBootLoopMitigation_impactLevelHighMultiplePackage_rollbackHigh()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        int rollbackId1 = 1;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoB),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        int rollbackId2 = 2;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        VersionedPackage failedPackage = new VersionedPackage(APP_C, VERSION_CODE);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.executeBootLoopMitigation(1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, times(1)).commitRollback(
                argument.capture(), any(), any());
        // Rollback APP_A because it is first alphabetically
        assertThat(argument.getAllValues()).isEqualTo(List.of(rollbackId2));
    }

    /**
     * Don't roll back if kill switch is enabled.
     */
    @Test
    public void executeBootLoopMitigation_impactLevelHighKillSwitchTrue_rollbackHigh()
            throws PackageManager.NameNotFoundException {
        mSetFlagsRule.enableFlags(Flags.FLAG_RECOVERABILITY_DETECTION);
        SystemProperties.set(PROP_DISABLE_HIGH_IMPACT_ROLLBACK_FLAG, Boolean.toString(true));
        int rollbackId1 = 1;
        VersionedPackage appBFrom = new VersionedPackage(APP_B, VERSION_CODE_2);
        VersionedPackage appBTo = new VersionedPackage(APP_B, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoB = new PackageRollbackInfo(appBFrom, appBTo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo1 = new RollbackInfo(rollbackId1, List.of(packageRollbackInfoB),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        int rollbackId2 = 2;
        VersionedPackage appAFrom = new VersionedPackage(APP_A, VERSION_CODE_2);
        VersionedPackage appATo = new VersionedPackage(APP_A, VERSION_CODE);
        PackageRollbackInfo packageRollbackInfoA = new PackageRollbackInfo(appAFrom, appATo,
                null, null , false, false,
                null);
        RollbackInfo rollbackInfo2 = new RollbackInfo(rollbackId2, List.of(packageRollbackInfoA),
                false, null, 111,
                PackageManager.ROLLBACK_USER_IMPACT_HIGH);
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext, mApexManager));
        ArgumentCaptor<Integer> argument = ArgumentCaptor.forClass(Integer.class);

        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(rollbackInfo1, rollbackInfo2));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.getModuleInfo(any(), eq(0))).thenReturn(null);

        observer.executeBootLoopMitigation(1);
        waitForIdleHandler(observer.getHandler(), Duration.ofSeconds(10));

        verify(mRollbackManager, never()).commitRollback(
                argument.capture(), any(), any());
    }

    private void waitForIdleHandler(Handler handler, Duration timeout) {
        final MessageQueue queue = handler.getLooper().getQueue();
        final CountDownLatch latch = new CountDownLatch(1);
        queue.addIdleHandler(() -> {
            latch.countDown();
            // Remove idle handler
            return false;
        });
        try {
            latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("Interrupted unexpectedly: " + e);
        }
    }
}
