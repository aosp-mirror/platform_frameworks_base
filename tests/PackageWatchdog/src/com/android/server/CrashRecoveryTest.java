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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.crashrecovery.flags.Flags;
import android.net.ConnectivityModuleConnector;
import android.net.ConnectivityModuleConnector.ConnectivityModuleHealthListener;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.test.TestLooper;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.util.AtomicFile;

import androidx.test.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.RescueParty.RescuePartyObserver;
import com.android.server.pm.ApexManager;
import com.android.server.rollback.RollbackPackageHealthObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Test CrashRecovery, integration tests that include PackageWatchdog, RescueParty and
 * RollbackPackageHealthObserver
 */
public class CrashRecoveryTest {
    private static final String PROP_DEVICE_CONFIG_DISABLE_FLAG =
            "persist.device_config.configuration.disable_rescue_party";

    private static final String APP_A = "com.package.a";
    private static final String APP_B = "com.package.b";
    private static final String APP_C = "com.package.c";
    private static final long VERSION_CODE = 1L;
    private static final long SHORT_DURATION = TimeUnit.SECONDS.toMillis(1);

    private static final RollbackInfo ROLLBACK_INFO_LOW = getRollbackInfo(APP_A, VERSION_CODE, 1,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
    private static final RollbackInfo ROLLBACK_INFO_HIGH = getRollbackInfo(APP_B, VERSION_CODE, 2,
            PackageManager.ROLLBACK_USER_IMPACT_HIGH);
    private static final RollbackInfo ROLLBACK_INFO_MANUAL = getRollbackInfo(APP_C, VERSION_CODE, 3,
            PackageManager.ROLLBACK_USER_IMPACT_ONLY_MANUAL);

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private final TestClock mTestClock = new TestClock();
    private TestLooper mTestLooper;
    private Context mSpyContext;
    // Keep track of all created watchdogs to apply device config changes
    private List<PackageWatchdog> mAllocatedWatchdogs;
    @Mock
    private ConnectivityModuleConnector mConnectivityModuleConnector;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ApexManager mApexManager;
    @Mock
    RollbackManager mRollbackManager;
    // Mock only sysprop apis
    private PackageWatchdog.BootThreshold mSpyBootThreshold;
    @Captor
    private ArgumentCaptor<ConnectivityModuleHealthListener> mConnectivityModuleCallbackCaptor;
    private MockitoSession mSession;
    private HashMap<String, String> mSystemSettingsMap;
    private HashMap<String, String> mCrashRecoveryPropertiesMap;

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
                .spyStatic(RescueParty.class)
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

        doAnswer((Answer<Boolean>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);
                    boolean defaultValue = invocationOnMock.getArgument(1);

                    String storedValue = mSystemSettingsMap.get(key);
                    return storedValue == null ? defaultValue : Boolean.parseBoolean(storedValue);
                }
        ).when(() -> SystemProperties.getBoolean(anyString(), anyBoolean()));

        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(false));

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_EXPLICIT_HEALTH_CHECK_ENABLED,
                Boolean.toString(true), false);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PackageWatchdog.PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(PackageWatchdog.DEFAULT_TRIGGER_FAILURE_COUNT), false);

        mAllocatedWatchdogs = new ArrayList<>();
        RescuePartyObserver.reset();
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
    public void testBootLoopWithRescueParty() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RescuePartyObserver rescuePartyObserver = setUpRescuePartyObserver(watchdog);

        verify(rescuePartyObserver, never()).executeBootLoopMitigation(1);

        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }

        verify(rescuePartyObserver).executeBootLoopMitigation(1);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(2);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(2);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(3);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(3);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(4);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(4);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(5);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(5);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(6);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(6);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(7);
    }

    @Test
    public void testBootLoopWithRollbackPackageHealthObserver() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RollbackPackageHealthObserver rollbackObserver =
                setUpRollbackPackageHealthObserver(watchdog);

        verify(rollbackObserver, never()).executeBootLoopMitigation(1);

        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }

        verify(rollbackObserver).executeBootLoopMitigation(1);
        verify(rollbackObserver, never()).executeBootLoopMitigation(2);

        // Update the list of available rollbacks after executing bootloop mitigation once
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_HIGH,
                ROLLBACK_INFO_MANUAL));

        watchdog.noteBoot();

        verify(rollbackObserver).executeBootLoopMitigation(2);
        verify(rollbackObserver, never()).executeBootLoopMitigation(3);

        // Update the list of available rollbacks after executing bootloop mitigation once
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_MANUAL));

        watchdog.noteBoot();

        verify(rollbackObserver, never()).executeBootLoopMitigation(3);
    }

    @Test
    @DisableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testBootLoopWithRescuePartyAndRollbackObserver() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RescuePartyObserver rescuePartyObserver = setUpRescuePartyObserver(watchdog);
        RollbackPackageHealthObserver rollbackObserver =
                setUpRollbackPackageHealthObserver(watchdog);

        verify(rescuePartyObserver, never()).executeBootLoopMitigation(1);
        verify(rollbackObserver, never()).executeBootLoopMitigation(1);
        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }
        verify(rescuePartyObserver).executeBootLoopMitigation(1);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(2);
        verify(rollbackObserver, never()).executeBootLoopMitigation(1);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(2);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(3);
        verify(rollbackObserver, never()).executeBootLoopMitigation(2);

        watchdog.noteBoot();

        verify(rescuePartyObserver, never()).executeBootLoopMitigation(3);
        verify(rollbackObserver).executeBootLoopMitigation(1);
        verify(rollbackObserver, never()).executeBootLoopMitigation(2);
        // Update the list of available rollbacks after executing bootloop mitigation once
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_HIGH,
                ROLLBACK_INFO_MANUAL));

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(3);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(4);
        verify(rollbackObserver, never()).executeBootLoopMitigation(2);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(4);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(5);
        verify(rollbackObserver, never()).executeBootLoopMitigation(2);

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(5);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(6);
        verify(rollbackObserver, never()).executeBootLoopMitigation(2);

        watchdog.noteBoot();

        verify(rescuePartyObserver, never()).executeBootLoopMitigation(6);
        verify(rollbackObserver).executeBootLoopMitigation(2);
        verify(rollbackObserver, never()).executeBootLoopMitigation(3);
        // Update the list of available rollbacks after executing bootloop mitigation
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_MANUAL));

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(6);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(7);
        verify(rollbackObserver, never()).executeBootLoopMitigation(3);

        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_DEESCALATION_WINDOW_MS + 1);
        Mockito.reset(rescuePartyObserver);

        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }
        verify(rescuePartyObserver).executeBootLoopMitigation(1);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(2);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testBootLoopWithRescuePartyAndRollbackObserverNoFlags() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RescuePartyObserver rescuePartyObserver = setUpRescuePartyObserver(watchdog);
        RollbackPackageHealthObserver rollbackObserver =
                setUpRollbackPackageHealthObserver(watchdog);

        verify(rescuePartyObserver, never()).executeBootLoopMitigation(1);
        verify(rollbackObserver, never()).executeBootLoopMitigation(1);
        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }
        verify(rescuePartyObserver).executeBootLoopMitigation(1);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(2);
        verify(rollbackObserver, never()).executeBootLoopMitigation(1);

        watchdog.noteBoot();

        verify(rescuePartyObserver, never()).executeBootLoopMitigation(2);
        verify(rollbackObserver).executeBootLoopMitigation(1);
        verify(rollbackObserver, never()).executeBootLoopMitigation(2);
        // Update the list of available rollbacks after executing bootloop mitigation once
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_HIGH,
                ROLLBACK_INFO_MANUAL));

        watchdog.noteBoot();

        verify(rescuePartyObserver, never()).executeBootLoopMitigation(2);
        verify(rollbackObserver).executeBootLoopMitigation(2);
        verify(rollbackObserver, never()).executeBootLoopMitigation(3);
        // Update the list of available rollbacks after executing bootloop mitigation
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_MANUAL));

        watchdog.noteBoot();

        verify(rescuePartyObserver).executeBootLoopMitigation(2);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(3);
        verify(rollbackObserver, never()).executeBootLoopMitigation(3);

        moveTimeForwardAndDispatch(PackageWatchdog.DEFAULT_DEESCALATION_WINDOW_MS + 1);
        Mockito.reset(rescuePartyObserver);

        for (int i = 0; i < PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT; i++) {
            watchdog.noteBoot();
        }
        verify(rescuePartyObserver).executeBootLoopMitigation(1);
        verify(rescuePartyObserver, never()).executeBootLoopMitigation(2);
    }

    @Test
    @DisableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testCrashLoopWithRescuePartyAndRollbackObserver() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RescuePartyObserver rescuePartyObserver = setUpRescuePartyObserver(watchdog);
        RollbackPackageHealthObserver rollbackObserver =
                setUpRollbackPackageHealthObserver(watchdog);
        VersionedPackage versionedPackageA = new VersionedPackage(APP_A, VERSION_CODE);

        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).then(inv -> {
            ApplicationInfo info = new ApplicationInfo();
            info.flags |= ApplicationInfo.FLAG_PERSISTENT
                    | ApplicationInfo.FLAG_SYSTEM;
            return info;
        });

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: SCOPED_DEVICE_CONFIG_RESET
        verify(rescuePartyObserver).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rollbackObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: ALL_DEVICE_CONFIG_RESET
        verify(rescuePartyObserver).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 3);
        verify(rollbackObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: WARM_REBOOT
        verify(rescuePartyObserver).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 3);
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 4);
        verify(rollbackObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: Low impact rollback
        verify(rollbackObserver).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 4);

        // update available rollbacks to mock rollbacks being applied after the call to
        // rollbackObserver.execute
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(ROLLBACK_INFO_HIGH, ROLLBACK_INFO_MANUAL));

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // DEFAULT_MAJOR_USER_IMPACT_LEVEL_THRESHOLD reached. No more mitigations applied
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 4);
        verify(rollbackObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testCrashLoopWithRescuePartyAndRollbackObserverEnableDeprecateFlagReset()
            throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RescuePartyObserver rescuePartyObserver = setUpRescuePartyObserver(watchdog);
        RollbackPackageHealthObserver rollbackObserver =
                setUpRollbackPackageHealthObserver(watchdog);
        VersionedPackage versionedPackageA = new VersionedPackage(APP_A, VERSION_CODE);

        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).then(inv -> {
            ApplicationInfo info = new ApplicationInfo();
            info.flags |= ApplicationInfo.FLAG_PERSISTENT
                    | ApplicationInfo.FLAG_SYSTEM;
            return info;
        });


        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: WARM_REBOOT
        verify(rescuePartyObserver).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rollbackObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: Low impact rollback
        verify(rollbackObserver).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);

        // update available rollbacks to mock rollbacks being applied after the call to
        // rollbackObserver.execute
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(ROLLBACK_INFO_HIGH, ROLLBACK_INFO_MANUAL));

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageA), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // DEFAULT_MAJOR_USER_IMPACT_LEVEL_THRESHOLD reached. No more mitigations applied
        verify(rescuePartyObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rollbackObserver, never()).execute(versionedPackageA,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
    }

    @Test
    @DisableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testCrashLoopSystemUIWithRescuePartyAndRollbackObserver() throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RescuePartyObserver rescuePartyObserver = setUpRescuePartyObserver(watchdog);
        RollbackPackageHealthObserver rollbackObserver =
                setUpRollbackPackageHealthObserver(watchdog);
        String systemUi = "com.android.systemui";
        VersionedPackage versionedPackageUi = new VersionedPackage(
                systemUi, VERSION_CODE);
        RollbackInfo rollbackInfoUi = getRollbackInfo(systemUi, VERSION_CODE, 1,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_LOW,
                ROLLBACK_INFO_HIGH, ROLLBACK_INFO_MANUAL, rollbackInfoUi));

        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).then(inv -> {
            ApplicationInfo info = new ApplicationInfo();
            info.flags |= ApplicationInfo.FLAG_PERSISTENT
                    | ApplicationInfo.FLAG_SYSTEM;
            return info;
        });

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: SCOPED_DEVICE_CONFIG_RESET
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: ALL_DEVICE_CONFIG_RESET
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 3);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: WARM_REBOOT
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 3);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 4);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: Low impact rollback
        verify(rollbackObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 4);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);

        // update available rollbacks to mock rollbacks being applied after the call to
        // rollbackObserver.execute
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(ROLLBACK_INFO_HIGH, ROLLBACK_INFO_MANUAL));

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: RESET_SETTINGS_UNTRUSTED_DEFAULTS
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 4);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 5);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: RESET_SETTINGS_UNTRUSTED_CHANGES
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 5);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 6);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: RESET_SETTINGS_TRUSTED_DEFAULTS
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 6);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 7);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: Factory reset. High impact rollbacks are performed only for boot loops.
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 7);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 8);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testCrashLoopSystemUIWithRescuePartyAndRollbackObserverEnableDeprecateFlagReset()
            throws Exception {
        PackageWatchdog watchdog = createWatchdog();
        RescuePartyObserver rescuePartyObserver = setUpRescuePartyObserver(watchdog);
        RollbackPackageHealthObserver rollbackObserver =
                setUpRollbackPackageHealthObserver(watchdog);
        String systemUi = "com.android.systemui";
        VersionedPackage versionedPackageUi = new VersionedPackage(
                systemUi, VERSION_CODE);
        RollbackInfo rollbackInfoUi = getRollbackInfo(systemUi, VERSION_CODE, 1,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_LOW,
                ROLLBACK_INFO_HIGH, ROLLBACK_INFO_MANUAL, rollbackInfoUi));

        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).then(inv -> {
            ApplicationInfo info = new ApplicationInfo();
            info.flags |= ApplicationInfo.FLAG_PERSISTENT
                    | ApplicationInfo.FLAG_SYSTEM;
            return info;
        });


        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: WARM_REBOOT
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: Low impact rollback
        verify(rollbackObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);

        // update available rollbacks to mock rollbacks being applied after the call to
        // rollbackObserver.execute
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(
                List.of(ROLLBACK_INFO_HIGH, ROLLBACK_INFO_MANUAL));

        raiseFatalFailureAndDispatch(watchdog,
                Arrays.asList(versionedPackageUi), PackageWatchdog.FAILURE_REASON_APP_CRASH);

        // Mitigation: Factory reset. High impact rollbacks are performed only for boot loops.
        verify(rescuePartyObserver).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
        verify(rescuePartyObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 3);
        verify(rollbackObserver, never()).execute(versionedPackageUi,
                PackageWatchdog.FAILURE_REASON_APP_CRASH, 2);
    }

    RollbackPackageHealthObserver setUpRollbackPackageHealthObserver(PackageWatchdog watchdog) {
        RollbackPackageHealthObserver rollbackObserver =
                spy(new RollbackPackageHealthObserver(mSpyContext, mApexManager));
        when(mSpyContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(ROLLBACK_INFO_LOW,
                ROLLBACK_INFO_HIGH, ROLLBACK_INFO_MANUAL));
        when(mSpyContext.getPackageManager()).thenReturn(mMockPackageManager);

        watchdog.registerHealthObserver(rollbackObserver);
        return rollbackObserver;
    }
    RescuePartyObserver setUpRescuePartyObserver(PackageWatchdog watchdog) {
        setCrashRecoveryPropRescueBootCount(0);
        RescuePartyObserver rescuePartyObserver = spy(RescuePartyObserver.getInstance(mSpyContext));
        assertFalse(RescueParty.isRebootPropertySet());
        watchdog.registerHealthObserver(rescuePartyObserver);
        return rescuePartyObserver;
    }

    private static RollbackInfo getRollbackInfo(String packageName, long versionCode,
            int rollbackId, int rollbackUserImpact) {
        VersionedPackage appFrom = new VersionedPackage(packageName, versionCode + 1);
        VersionedPackage appTo = new VersionedPackage(packageName, versionCode);
        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(appFrom, appTo, null,
                null, false, false, null);
        RollbackInfo rollbackInfo = new RollbackInfo(rollbackId, List.of(packageRollbackInfo),
                false, null, 111, rollbackUserImpact);
        return rollbackInfo;
    }

    private void adoptShellPermissions(String... permissions) {
        androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(permissions);
    }

    private void dropShellPermissions() {
        androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
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

            verify(mConnectivityModuleConnector).registerHealthListener(
                    mConnectivityModuleCallbackCaptor.capture());
        }
        mAllocatedWatchdogs.add(watchdog);
        return watchdog;
    }

    // Mock CrashRecoveryProperties as they cannot be accessed due to SEPolicy restrictions
    private void mockCrashRecoveryProperties(PackageWatchdog watchdog) {
        mCrashRecoveryPropertiesMap = new HashMap<>();

        // mock properties in RescueParty
        try {

            doAnswer((Answer<Boolean>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.attempting_factory_reset", "false");
                return Boolean.parseBoolean(storedValue);
            }).when(() -> RescueParty.isFactoryResetPropertySet());
            doAnswer((Answer<Void>) invocationOnMock -> {
                boolean value = invocationOnMock.getArgument(0);
                mCrashRecoveryPropertiesMap.put("crashrecovery.attempting_factory_reset",
                        Boolean.toString(value));
                return null;
            }).when(() -> RescueParty.setFactoryResetProperty(anyBoolean()));

            doAnswer((Answer<Boolean>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.attempting_reboot", "false");
                return Boolean.parseBoolean(storedValue);
            }).when(() -> RescueParty.isRebootPropertySet());
            doAnswer((Answer<Void>) invocationOnMock -> {
                boolean value = invocationOnMock.getArgument(0);
                setCrashRecoveryPropAttemptingReboot(value);
                return null;
            }).when(() -> RescueParty.setRebootProperty(anyBoolean()));

            doAnswer((Answer<Long>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("persist.crashrecovery.last_factory_reset", "0");
                return Long.parseLong(storedValue);
            }).when(() -> RescueParty.getLastFactoryResetTimeMs());
            doAnswer((Answer<Void>) invocationOnMock -> {
                long value = invocationOnMock.getArgument(0);
                setCrashRecoveryPropLastFactoryReset(value);
                return null;
            }).when(() -> RescueParty.setLastFactoryResetTimeMs(anyLong()));

            doAnswer((Answer<Integer>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.max_rescue_level_attempted", "0");
                return Integer.parseInt(storedValue);
            }).when(() -> RescueParty.getMaxRescueLevelAttempted());
            doAnswer((Answer<Void>) invocationOnMock -> {
                int value = invocationOnMock.getArgument(0);
                mCrashRecoveryPropertiesMap.put("crashrecovery.max_rescue_level_attempted",
                        Integer.toString(value));
                return null;
            }).when(() -> RescueParty.setMaxRescueLevelAttempted(anyInt()));

        } catch (Exception e) {
            // tests will fail, just printing the error
            System.out.println("Error while mocking crashrecovery properties " + e.getMessage());
        }

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

    private void setCrashRecoveryPropRescueBootCount(int count) {
        mCrashRecoveryPropertiesMap.put("crashrecovery.rescue_boot_count",
                Integer.toString(count));
    }

    private void setCrashRecoveryPropAttemptingReboot(boolean value) {
        mCrashRecoveryPropertiesMap.put("crashrecovery.attempting_reboot",
                Boolean.toString(value));
    }

    private void setCrashRecoveryPropLastFactoryReset(long value) {
        mCrashRecoveryPropertiesMap.put("persist.crashrecovery.last_factory_reset",
                Long.toString(value));
    }

    private static class TestController extends ExplicitHealthCheckController {
        TestController() {
            super(null /* controller */);
        }

        private boolean mIsEnabled;
        private List<String> mSupportedPackages = new ArrayList<>();
        private List<String> mRequestedPackages = new ArrayList<>();
        private Consumer<List<PackageConfig>> mSupportedConsumer;
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
            mSupportedConsumer = supportedConsumer;
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

    private void moveTimeForwardAndDispatch(long milliSeconds) {
        // Exhaust all due runnables now which shouldn't be executed after time-leap
        mTestLooper.dispatchAll();
        mTestClock.moveTimeForward(milliSeconds);
        mTestLooper.moveTimeForward(milliSeconds);
        mTestLooper.dispatchAll();
    }

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
}
