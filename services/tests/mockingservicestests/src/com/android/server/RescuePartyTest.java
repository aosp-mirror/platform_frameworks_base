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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyLong;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.RescueParty.DEFAULT_FACTORY_RESET_THROTTLE_DURATION_MIN;
import static com.android.server.RescueParty.LEVEL_FACTORY_RESET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.crashrecovery.flags.Flags;
import android.os.RecoverySystem;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.provider.Settings;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;
import com.android.server.RescueParty.RescuePartyObserver;
import com.android.server.am.SettingsToPropertiesMapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * Test RescueParty.
 */
@RunWith(Parameterized.class)
public class RescuePartyTest {
    @Rule
    public SetFlagsRule mSetFlagsRule;
    private static final long CURRENT_NETWORK_TIME_MILLIS = 0L;
    private static final String FAKE_NATIVE_NAMESPACE1 = "native1";
    private static final String FAKE_NATIVE_NAMESPACE2 = "native2";
    private static final String[] FAKE_RESET_NATIVE_NAMESPACES =
            {FAKE_NATIVE_NAMESPACE1, FAKE_NATIVE_NAMESPACE2};

    private static VersionedPackage sFailingPackage = new VersionedPackage("com.package.name", 1);
    private static final String PROP_DISABLE_RESCUE = "persist.sys.disable_rescue";
    private static final String CALLING_PACKAGE1 = "com.package.name1";
    private static final String CALLING_PACKAGE2 = "com.package.name2";
    private static final String CALLING_PACKAGE3 = "com.package.name3";
    private static final String PERSISTENT_PACKAGE = "com.persistent.package";
    private static final String NON_PERSISTENT_PACKAGE = "com.nonpersistent.package";
    private static final String NAMESPACE1 = "namespace1";
    private static final String NAMESPACE2 = "namespace2";
    private static final String NAMESPACE3 = "namespace3";
    private static final String NAMESPACE4 = "namespace4";
    private static final String PROP_DEVICE_CONFIG_DISABLE_FLAG =
            "persist.device_config.configuration.disable_rescue_party";
    private static final String PROP_DISABLE_FACTORY_RESET_FLAG =
            "persist.device_config.configuration.disable_rescue_party_factory_reset";

    private MockitoSession mSession;
    private HashMap<String, String> mSystemSettingsMap;
    private HashMap<String, String> mCrashRecoveryPropertiesMap;
    //Records the namespaces wiped by setProperties().
    private HashSet<String> mNamespacesWiped;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mMockContext;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private PackageWatchdog mMockPackageWatchdog;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ContentResolver mMockContentResolver;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private PackageManager mPackageManager;

    // Mock only sysprop apis
    private PackageWatchdog.BootThreshold mSpyBootThreshold;

    @Captor
    private ArgumentCaptor<DeviceConfig.MonitorCallback> mMonitorCallbackCaptor;
    @Captor
    private ArgumentCaptor<List<String>> mPackageListCaptor;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getFlags() {
        return FlagsParameterization.allCombinationsOf(
                Flags.FLAG_RECOVERABILITY_DETECTION,
                Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS);
    }

    public RescuePartyTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    @Before
    public void setUp() throws Exception {
        mSession =
                ExtendedMockito.mockitoSession().initMocks(
                        this)
                        .strictness(Strictness.LENIENT)
                        .spyStatic(DeviceConfig.class)
                        .spyStatic(SystemProperties.class)
                        .spyStatic(Settings.Global.class)
                        .spyStatic(Settings.Secure.class)
                        .spyStatic(SettingsToPropertiesMapper.class)
                        .spyStatic(RecoverySystem.class)
                        .spyStatic(RescueParty.class)
                        .spyStatic(PackageWatchdog.class)
                        .startMocking();
        mSystemSettingsMap = new HashMap<>();
        mNamespacesWiped = new HashSet<>();

        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        ApplicationInfo persistentApplicationInfo = new ApplicationInfo();
        persistentApplicationInfo.flags |=
                ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_PERSISTENT;

        // If the package name is PERSISTENT_PACKAGE, then set the flags to be persistent and
        // system. Don't set any flags otherwise.
        when(mPackageManager.getApplicationInfo(eq(PERSISTENT_PACKAGE),
                anyInt())).thenReturn(persistentApplicationInfo);
        when(mPackageManager.getApplicationInfo(eq(NON_PERSISTENT_PACKAGE),
                anyInt())).thenReturn(new ApplicationInfo());
        // Reset observer instance to get new mock context on every run
        RescuePartyObserver.reset();

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

        // Mock DeviceConfig
        doAnswer((Answer<Boolean>) invocationOnMock -> true)
                .when(() -> DeviceConfig.setProperty(anyString(), anyString(), anyString(),
                        anyBoolean()));
        doAnswer((Answer<Void>) invocationOnMock -> null)
                .when(() -> DeviceConfig.resetToDefaults(anyInt(), anyString()));
        doAnswer((Answer<Boolean>) invocationOnMock -> {
                    DeviceConfig.Properties properties = invocationOnMock.getArgument(0);
                    String namespace = properties.getNamespace();
                    // record a wipe
                    if (properties.getKeyset().isEmpty()) {
                        mNamespacesWiped.add(namespace);
                    }
                    return true;
                }
        ).when(() -> DeviceConfig.setProperties(any(DeviceConfig.Properties.class)));

        // Mock PackageWatchdog
        doAnswer((Answer<PackageWatchdog>) invocationOnMock -> mMockPackageWatchdog)
                .when(() -> PackageWatchdog.getInstance(mMockContext));
        mockCrashRecoveryProperties(mMockPackageWatchdog);

        doReturn(CURRENT_NETWORK_TIME_MILLIS).when(() -> RescueParty.getElapsedRealtime());

        setCrashRecoveryPropRescueBootCount(0);
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(false));
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
    }

    @Test
    @EnableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testBootLoopNoFlags() {
        // this is old test where the flag needs to be disabled
        noteBoot(1);
        assertTrue(RescueParty.isRebootPropertySet());

        setCrashRecoveryPropAttemptingReboot(false);
        noteBoot(2);
        assertTrue(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    @EnableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testPersistentAppCrashNoFlags() {
        // this is old test where the flag needs to be disabled
        noteAppCrash(1, true);
        assertTrue(RescueParty.isRebootPropertySet());

        setCrashRecoveryPropAttemptingReboot(false);
        noteAppCrash(2, true);
        assertTrue(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testIsRecoveryTriggeredReboot() {
        for (int i = 0; i < LEVEL_FACTORY_RESET; i++) {
            noteBoot(i + 1);
        }
        assertFalse(RescueParty.isFactoryResetPropertySet());
        setCrashRecoveryPropAttemptingReboot(false);
        noteBoot(LEVEL_FACTORY_RESET + 1);
        assertTrue(RescueParty.isRecoveryTriggeredReboot());
        assertTrue(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testIsRecoveryTriggeredRebootOnlyAfterRebootCompleted() {
        for (int i = 0; i < LEVEL_FACTORY_RESET; i++) {
            noteBoot(i + 1);
        }
        int mitigationCount = LEVEL_FACTORY_RESET + 1;
        assertFalse(RescueParty.isFactoryResetPropertySet());
        noteBoot(mitigationCount++);
        assertFalse(RescueParty.isFactoryResetPropertySet());
        noteBoot(mitigationCount++);
        assertFalse(RescueParty.isFactoryResetPropertySet());
        noteBoot(mitigationCount++);
        setCrashRecoveryPropAttemptingReboot(false);
        noteBoot(mitigationCount + 1);
        assertTrue(RescueParty.isRecoveryTriggeredReboot());
        assertTrue(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testThrottlingOnBootFailures() {
        setCrashRecoveryPropAttemptingReboot(false);
        long now = System.currentTimeMillis();
        long beforeTimeout = now - TimeUnit.MINUTES.toMillis(
                DEFAULT_FACTORY_RESET_THROTTLE_DURATION_MIN - 1);
        setCrashRecoveryPropLastFactoryReset(beforeTimeout);
        for (int i = 1; i <= LEVEL_FACTORY_RESET; i++) {
            noteBoot(i);
        }
        assertFalse(RescueParty.isRecoveryTriggeredReboot());
    }

    @Test
    public void testThrottlingOnAppCrash() {
        setCrashRecoveryPropAttemptingReboot(false);
        long now = System.currentTimeMillis();
        long beforeTimeout = now - TimeUnit.MINUTES.toMillis(
                DEFAULT_FACTORY_RESET_THROTTLE_DURATION_MIN - 1);
        setCrashRecoveryPropLastFactoryReset(beforeTimeout);
        for (int i = 0; i <= LEVEL_FACTORY_RESET; i++) {
            noteAppCrash(i + 1, true);
        }
        assertFalse(RescueParty.isRecoveryTriggeredReboot());
    }

    @Test
    public void testNotThrottlingAfterTimeoutOnBootFailures() {
        setCrashRecoveryPropAttemptingReboot(false);
        long now = System.currentTimeMillis();
        long afterTimeout = now - TimeUnit.MINUTES.toMillis(
                DEFAULT_FACTORY_RESET_THROTTLE_DURATION_MIN + 1);
        setCrashRecoveryPropLastFactoryReset(afterTimeout);
        for (int i = 1; i <= LEVEL_FACTORY_RESET; i++) {
            noteBoot(i);
        }
        assertTrue(RescueParty.isRecoveryTriggeredReboot());
    }

    @Test
    public void testNotThrottlingAfterTimeoutOnAppCrash() {
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        setCrashRecoveryPropAttemptingReboot(false);
        long now = System.currentTimeMillis();
        long afterTimeout = now - TimeUnit.MINUTES.toMillis(
                DEFAULT_FACTORY_RESET_THROTTLE_DURATION_MIN + 1);
        setCrashRecoveryPropLastFactoryReset(afterTimeout);
        for (int i = 0; i <= LEVEL_FACTORY_RESET; i++) {
            noteAppCrash(i + 1, true);
        }
        assertTrue(RescueParty.isRecoveryTriggeredReboot());
    }

    @Test
    public void testExplicitlyEnablingAndDisablingRescue() {
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(false));
        SystemProperties.set(PROP_DISABLE_RESCUE, Boolean.toString(true));
        assertEquals(RescuePartyObserver.getInstance(mMockContext).onExecuteHealthCheckMitigation(
                sFailingPackage, PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1), false);

        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        assertTrue(RescuePartyObserver.getInstance(mMockContext).onExecuteHealthCheckMitigation(
                sFailingPackage, PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1));
    }

    @Test
    public void testDisablingRescueByDeviceConfigFlag() {
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(false));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(true));

        assertEquals(RescuePartyObserver.getInstance(mMockContext).onExecuteHealthCheckMitigation(
                sFailingPackage, PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1), false);

        // Restore the property value initialized in SetUp()
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(false));
    }

    @Test
    public void testDisablingFactoryResetByDeviceConfigFlag() {
        SystemProperties.set(PROP_DISABLE_FACTORY_RESET_FLAG, Boolean.toString(true));

        for (int i = 0; i < LEVEL_FACTORY_RESET; i++) {
            noteBoot(i + 1);
        }
        assertFalse(RescueParty.isFactoryResetPropertySet());

        // Restore the property value initialized in SetUp()
        SystemProperties.set(PROP_DISABLE_FACTORY_RESET_FLAG, "");
    }

    @Test
    @EnableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testHealthCheckLevelsNoFlags() {
        // this is old test where the flag needs to be disabled
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);

        // Ensure that no action is taken for cases where the failure reason is unknown
        assertEquals(observer.onHealthCheckFailed(null, PackageWatchdog.FAILURE_REASON_UNKNOWN, 1),
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_0);

        // Ensure the correct user impact is returned for each mitigation count.
        assertEquals(observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1),
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_50);

        assertEquals(observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 2),
                PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEPRECATE_FLAGS_AND_SETTINGS_RESETS)
    public void testBootLoopLevelsNoFlags() {
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);

        assertEquals(observer.onBootLoop(1), PackageHealthObserverImpact.USER_IMPACT_LEVEL_50);
        assertEquals(observer.onBootLoop(2), PackageHealthObserverImpact.USER_IMPACT_LEVEL_100);
    }


    private void verifySettingsResets(int resetMode, String[] resetNamespaces,
            HashMap<String, Integer> configResetVerifiedTimesMap) {
        verifyOnlySettingsReset(resetMode);
    }

    private void verifyOnlySettingsReset(int resetMode) {
        verify(() -> Settings.Global.resetToDefaultsAsUser(mMockContentResolver, null,
                resetMode, UserHandle.USER_SYSTEM));
        verify(() -> Settings.Secure.resetToDefaultsAsUser(eq(mMockContentResolver), isNull(),
                eq(resetMode), anyInt()));
    }

    private void verifyNoSettingsReset(int resetMode) {
        verify(() -> Settings.Global.resetToDefaultsAsUser(mMockContentResolver, null,
                resetMode, UserHandle.USER_SYSTEM), never());
        verify(() -> Settings.Secure.resetToDefaultsAsUser(eq(mMockContentResolver), isNull(),
                eq(resetMode), anyInt()), never());
    }

    private void noteBoot(int mitigationCount) {
        RescuePartyObserver.getInstance(mMockContext).onExecuteBootLoopMitigation(mitigationCount);
    }

    private void noteAppCrash(int mitigationCount, boolean isPersistent) {
        String packageName = isPersistent ? PERSISTENT_PACKAGE : NON_PERSISTENT_PACKAGE;
        RescuePartyObserver.getInstance(mMockContext).onExecuteHealthCheckMitigation(
                new VersionedPackage(packageName, 1), PackageWatchdog.FAILURE_REASON_APP_CRASH,
                mitigationCount);
    }

    // Mock CrashRecoveryProperties as they cannot be accessed due to SEPolicy restrictions
    private void mockCrashRecoveryProperties(PackageWatchdog watchdog) {
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

        // mock properties in BootThreshold
        try {
            mSpyBootThreshold = spy(watchdog.new BootThreshold(
                    PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_COUNT,
                    PackageWatchdog.DEFAULT_BOOT_LOOP_TRIGGER_WINDOW_MS));
            mCrashRecoveryPropertiesMap = new HashMap<>();

            doAnswer((Answer<Integer>) invocationOnMock -> {
                String storedValue = mCrashRecoveryPropertiesMap
                        .getOrDefault("crashrecovery.rescue_boot_count", "0");
                return Integer.parseInt(storedValue);
            }).when(mSpyBootThreshold).getCount();
            doAnswer((Answer<Void>) invocationOnMock -> {
                int count = invocationOnMock.getArgument(0);
                setCrashRecoveryPropRescueBootCount(count);
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
            System.out.println("Error while spying BootThreshold " + e.getMessage());
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
}
