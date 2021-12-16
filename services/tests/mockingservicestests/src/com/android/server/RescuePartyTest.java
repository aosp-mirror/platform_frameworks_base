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
import static com.android.server.RescueParty.LEVEL_FACTORY_RESET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Bundle;
import android.os.RecoverySystem;
import android.os.RemoteCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.ArraySet;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;
import com.android.server.RescueParty.RescuePartyObserver;
import com.android.server.am.SettingsToPropertiesMapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Test RescueParty.
 */
public class RescuePartyTest {
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

    @Captor
    private ArgumentCaptor<RemoteCallback> mMonitorCallbackCaptor;
    @Captor
    private ArgumentCaptor<List<String>> mPackageListCaptor;

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
                        .spyStatic(Settings.Config.class)
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

        doReturn(CURRENT_NETWORK_TIME_MILLIS).when(() -> RescueParty.getElapsedRealtime());

        SystemProperties.set(RescueParty.PROP_RESCUE_BOOT_COUNT, Integer.toString(0));
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(false));
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
    }

    @Test
    public void testBootLoopDetectionWithExecutionForAllRescueLevels() {
        RescueParty.onSettingsProviderPublished(mMockContext);
        verify(() -> Settings.Config.registerMonitorCallback(eq(mMockContentResolver),
                mMonitorCallbackCaptor.capture()));
        HashMap<String, Integer> verifiedTimesMap = new HashMap<String, Integer>();

        noteBoot(1);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, /*resetNamespaces=*/ null,
                verifiedTimesMap);

        // Record DeviceConfig accesses
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);
        RemoteCallback monitorCallback = mMonitorCallbackCaptor.getValue();
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE1));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE2));

        final String[] expectedAllResetNamespaces = new String[]{NAMESPACE1, NAMESPACE2};

        noteBoot(2);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES, expectedAllResetNamespaces,
                verifiedTimesMap);

        noteBoot(3);

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, expectedAllResetNamespaces,
                verifiedTimesMap);

        noteBoot(4);
        assertTrue(RescueParty.isRebootPropertySet());

        noteBoot(5);
        assertTrue(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testPersistentAppCrashDetectionWithExecutionForAllRescueLevels() {
        noteAppCrash(1, true);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, /*resetNamespaces=*/ null,
                /*configResetVerifiedTimesMap=*/ null);

        noteAppCrash(2, true);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES, /*resetNamespaces=*/ null,
                /*configResetVerifiedTimesMap=*/ null);

        noteAppCrash(3, true);

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, /*resetNamespaces=*/ null,
                /*configResetVerifiedTimesMap=*/ null);

        noteAppCrash(4, true);
        assertTrue(RescueParty.isRebootPropertySet());

        noteAppCrash(5, true);
        assertTrue(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testNonPersistentAppOnlyPerformsFlagResets() {
        noteAppCrash(1, false);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, /*resetNamespaces=*/ null,
                /*configResetVerifiedTimesMap=*/ null);

        noteAppCrash(2, false);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES, /*resetNamespaces=*/ null,
                /*configResetVerifiedTimesMap=*/ null);

        noteAppCrash(3, false);

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, /*resetNamespaces=*/ null,
                /*configResetVerifiedTimesMap=*/ null);

        noteAppCrash(4, false);
        assertFalse(RescueParty.isRebootPropertySet());

        noteAppCrash(5, false);
        assertFalse(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testNonPersistentAppCrashDetectionWithScopedResets() {
        RescueParty.onSettingsProviderPublished(mMockContext);
        verify(() -> Settings.Config.registerMonitorCallback(eq(mMockContentResolver),
                mMonitorCallbackCaptor.capture()));

        // Record DeviceConfig accesses
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);
        RemoteCallback monitorCallback = mMonitorCallbackCaptor.getValue();
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE1));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE3));
        // Fake DeviceConfig value changes
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE1));
        verify(mMockPackageWatchdog).startObservingHealth(observer,
                Arrays.asList(CALLING_PACKAGE1), RescueParty.DEFAULT_OBSERVING_DURATION_MS);
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE2));
        verify(mMockPackageWatchdog, times(2)).startObservingHealth(eq(observer),
                mPackageListCaptor.capture(),
                eq(RescueParty.DEFAULT_OBSERVING_DURATION_MS));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE3));
        verify(mMockPackageWatchdog).startObservingHealth(observer,
                Arrays.asList(CALLING_PACKAGE2), RescueParty.DEFAULT_OBSERVING_DURATION_MS);
        assertTrue(mPackageListCaptor.getValue().containsAll(
                Arrays.asList(CALLING_PACKAGE1, CALLING_PACKAGE2)));
        // Perform and verify scoped resets
        final String[] expectedResetNamespaces = new String[]{NAMESPACE1, NAMESPACE2};
        final String[] expectedAllResetNamespaces =
                new String[]{NAMESPACE1, NAMESPACE2, NAMESPACE3};
        HashMap<String, Integer> verifiedTimesMap = new HashMap<String, Integer>();
        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, expectedResetNamespaces,
                verifiedTimesMap);

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 2);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES, expectedResetNamespaces,
                verifiedTimesMap);

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 3);
        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, expectedAllResetNamespaces,
                verifiedTimesMap);

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 4);
        assertFalse(RescueParty.isRebootPropertySet());

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 5);
        assertFalse(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testNonDeviceConfigSettingsOnlyResetOncePerLevel() {
        RescueParty.onSettingsProviderPublished(mMockContext);
        verify(() -> Settings.Config.registerMonitorCallback(eq(mMockContentResolver),
                mMonitorCallbackCaptor.capture()));

        // Record DeviceConfig accesses
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);
        RemoteCallback monitorCallback = mMonitorCallbackCaptor.getValue();
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE1));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE3));
        // Fake DeviceConfig value changes
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE1));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE2));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE3));
        // Perform and verify scoped resets
        final String[] expectedPackage1ResetNamespaces = new String[]{NAMESPACE1, NAMESPACE2};
        final String[] expectedPackage2ResetNamespaces = new String[]{NAMESPACE2, NAMESPACE3};
        final String[] expectedAllResetNamespaces =
                new String[]{NAMESPACE1, NAMESPACE2, NAMESPACE3};
        HashMap<String, Integer> verifiedTimesMap = new HashMap<String, Integer>();
        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS,
                expectedPackage1ResetNamespaces, verifiedTimesMap);

        // Settings.Global & Settings.Secure should still remain the same execution times.
        observer.execute(new VersionedPackage(
                CALLING_PACKAGE2, 1), PackageWatchdog.FAILURE_REASON_APP_CRASH, 1);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS,
                expectedPackage2ResetNamespaces, verifiedTimesMap);

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 2);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES,
                expectedPackage1ResetNamespaces, verifiedTimesMap);

        // Settings.Global & Settings.Secure should still remain the same execution times.
        observer.execute(new VersionedPackage(
                CALLING_PACKAGE2, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 2);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES,
                expectedPackage2ResetNamespaces, verifiedTimesMap);

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 3);
        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, expectedAllResetNamespaces,
                verifiedTimesMap);

        // Settings.Global & Settings.Secure should still remain the same execution times.
        observer.execute(new VersionedPackage(
                CALLING_PACKAGE2, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 3);
        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, expectedAllResetNamespaces,
                verifiedTimesMap);

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 4);
        assertFalse(RescueParty.isRebootPropertySet());

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 5);
        assertFalse(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testIsAttemptingFactoryReset() {
        for (int i = 0; i < LEVEL_FACTORY_RESET; i++) {
            noteBoot(i + 1);
        }
        assertTrue(RescueParty.isAttemptingFactoryReset());
        assertTrue(RescueParty.isFactoryResetPropertySet());
    }

    @Test
    public void testNativeRescuePartyResets() {
        doReturn(true).when(() -> SettingsToPropertiesMapper.isNativeFlagsResetPerformed());
        doReturn(FAKE_RESET_NATIVE_NAMESPACES).when(
                () -> SettingsToPropertiesMapper.getResetNativeCategories());

        RescueParty.onSettingsProviderPublished(mMockContext);

        verify(() -> DeviceConfig.resetToDefaults(Settings.RESET_MODE_TRUSTED_DEFAULTS,
                FAKE_NATIVE_NAMESPACE1));
        verify(() -> DeviceConfig.resetToDefaults(Settings.RESET_MODE_TRUSTED_DEFAULTS,
                FAKE_NATIVE_NAMESPACE2));
    }

    @Test
    public void testExplicitlyEnablingAndDisablingRescue() {
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(false));
        SystemProperties.set(PROP_DISABLE_RESCUE, Boolean.toString(true));
        assertEquals(RescuePartyObserver.getInstance(mMockContext).execute(sFailingPackage,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1), false);

        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        assertTrue(RescuePartyObserver.getInstance(mMockContext).execute(sFailingPackage,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1));
    }

    @Test
    public void testDisablingRescueByDeviceConfigFlag() {
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(false));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(true));

        assertEquals(RescuePartyObserver.getInstance(mMockContext).execute(sFailingPackage,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1), false);

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
    public void testHealthCheckLevels() {
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);

        // Ensure that no action is taken for cases where the failure reason is unknown
        assertEquals(observer.onHealthCheckFailed(null, PackageWatchdog.FAILURE_REASON_UNKNOWN, 1),
                PackageHealthObserverImpact.USER_IMPACT_NONE);

        // Ensure the correct user impact is returned for each mitigation count.
        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 1),
                PackageHealthObserverImpact.USER_IMPACT_LOW);

        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 2),
                PackageHealthObserverImpact.USER_IMPACT_LOW);

        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 3),
                PackageHealthObserverImpact.USER_IMPACT_HIGH);

        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING, 4),
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
    }

    @Test
    public void testBootLoopLevels() {
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);

        assertEquals(observer.onBootLoop(0), PackageHealthObserverImpact.USER_IMPACT_NONE);
        assertEquals(observer.onBootLoop(1), PackageHealthObserverImpact.USER_IMPACT_LOW);
        assertEquals(observer.onBootLoop(2), PackageHealthObserverImpact.USER_IMPACT_LOW);
        assertEquals(observer.onBootLoop(3), PackageHealthObserverImpact.USER_IMPACT_HIGH);
        assertEquals(observer.onBootLoop(4), PackageHealthObserverImpact.USER_IMPACT_HIGH);
        assertEquals(observer.onBootLoop(5), PackageHealthObserverImpact.USER_IMPACT_HIGH);
    }

    @Test
    public void testResetDeviceConfigForPackagesOnlyRuntimeMap() {
        RescueParty.onSettingsProviderPublished(mMockContext);
        verify(() -> Settings.Config.registerMonitorCallback(eq(mMockContentResolver),
                mMonitorCallbackCaptor.capture()));

        // Record DeviceConfig accesses
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);
        RemoteCallback monitorCallback = mMonitorCallbackCaptor.getValue();
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE1));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE3));
        // Fake DeviceConfig value changes
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE1));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE2));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE3));

        doReturn("").when(() -> DeviceConfig.getString(
                eq(RescueParty.NAMESPACE_CONFIGURATION),
                eq(RescueParty.NAMESPACE_TO_PACKAGE_MAPPING_FLAG),
                eq("")));

        RescueParty.resetDeviceConfigForPackages(Arrays.asList(new String[]{CALLING_PACKAGE1}));
        ArraySet<String> expectedNamespacesWiped = new ArraySet<String>(
                Arrays.asList(new String[]{NAMESPACE1, NAMESPACE2}));
        assertEquals(mNamespacesWiped, expectedNamespacesWiped);
    }

    @Test
    public void testResetDeviceConfigForPackagesOnlyPresetMap() {
        RescueParty.onSettingsProviderPublished(mMockContext);
        verify(() -> Settings.Config.registerMonitorCallback(eq(mMockContentResolver),
                mMonitorCallbackCaptor.capture()));

        String presetMapping = NAMESPACE1 + ":" + CALLING_PACKAGE1 + ","
                + NAMESPACE2 + ":" + CALLING_PACKAGE2 + ","
                + NAMESPACE3 +  ":" + CALLING_PACKAGE1;
        doReturn(presetMapping).when(() -> DeviceConfig.getString(
                eq(RescueParty.NAMESPACE_CONFIGURATION),
                eq(RescueParty.NAMESPACE_TO_PACKAGE_MAPPING_FLAG),
                eq("")));

        RescueParty.resetDeviceConfigForPackages(Arrays.asList(new String[]{CALLING_PACKAGE1}));
        ArraySet<String> expectedNamespacesWiped = new ArraySet<String>(
                Arrays.asList(new String[]{NAMESPACE1, NAMESPACE3}));
        assertEquals(mNamespacesWiped, expectedNamespacesWiped);
    }

    @Test
    public void testResetDeviceConfigForPackagesBothMaps() {
        RescueParty.onSettingsProviderPublished(mMockContext);
        verify(() -> Settings.Config.registerMonitorCallback(eq(mMockContentResolver),
                mMonitorCallbackCaptor.capture()));

        // Record DeviceConfig accesses
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);
        RemoteCallback monitorCallback = mMonitorCallbackCaptor.getValue();
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE1));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE2));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE3));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE3, NAMESPACE4));
        // Fake DeviceConfig value changes
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE1));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE2));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE3));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE4));

        String presetMapping = NAMESPACE1 + ":" + CALLING_PACKAGE1 + ","
                + NAMESPACE2 + ":" + CALLING_PACKAGE2 + ","
                + NAMESPACE4 + ":" + CALLING_PACKAGE3;
        doReturn(presetMapping).when(() -> DeviceConfig.getString(
                eq(RescueParty.NAMESPACE_CONFIGURATION),
                eq(RescueParty.NAMESPACE_TO_PACKAGE_MAPPING_FLAG),
                eq("")));

        RescueParty.resetDeviceConfigForPackages(
                Arrays.asList(new String[]{CALLING_PACKAGE1, CALLING_PACKAGE2}));
        ArraySet<String> expectedNamespacesWiped = new ArraySet<String>(
                Arrays.asList(new String[]{NAMESPACE1, NAMESPACE2, NAMESPACE3}));
        assertEquals(mNamespacesWiped, expectedNamespacesWiped);
    }

    @Test
    public void testResetDeviceConfigNoExceptionWhenFlagMalformed() {
        RescueParty.onSettingsProviderPublished(mMockContext);
        verify(() -> Settings.Config.registerMonitorCallback(eq(mMockContentResolver),
                mMonitorCallbackCaptor.capture()));

        // Record DeviceConfig accesses
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);
        RemoteCallback monitorCallback = mMonitorCallbackCaptor.getValue();
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE1, NAMESPACE1));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE2, NAMESPACE3));
        monitorCallback.sendResult(getConfigAccessBundle(CALLING_PACKAGE3, NAMESPACE4));
        // Fake DeviceConfig value changes
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE1));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE2));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE3));
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE4));

        String invalidPresetMapping = NAMESPACE2 + ":" + CALLING_PACKAGE2 + ","
                + NAMESPACE1 + "." + CALLING_PACKAGE2;
        doReturn(invalidPresetMapping).when(() -> DeviceConfig.getString(
                eq(RescueParty.NAMESPACE_CONFIGURATION),
                eq(RescueParty.NAMESPACE_TO_PACKAGE_MAPPING_FLAG),
                eq("")));

        RescueParty.resetDeviceConfigForPackages(
                Arrays.asList(new String[]{CALLING_PACKAGE1, CALLING_PACKAGE2}));
        ArraySet<String> expectedNamespacesWiped = new ArraySet<String>(
                Arrays.asList(new String[]{NAMESPACE1, NAMESPACE3}));
        assertEquals(mNamespacesWiped, expectedNamespacesWiped);
    }

    private void verifySettingsResets(int resetMode, String[] resetNamespaces,
            HashMap<String, Integer> configResetVerifiedTimesMap) {
        verify(() -> Settings.Global.resetToDefaultsAsUser(mMockContentResolver, null,
                resetMode, UserHandle.USER_SYSTEM));
        verify(() -> Settings.Secure.resetToDefaultsAsUser(eq(mMockContentResolver), isNull(),
                eq(resetMode), anyInt()));
        // Verify DeviceConfig resets
        if (resetNamespaces == null) {
            verify(() -> DeviceConfig.resetToDefaults(anyInt(), anyString()), never());
        } else {
            for (String namespace : resetNamespaces) {
                int verifiedTimes = 0;
                if (configResetVerifiedTimesMap != null
                        && configResetVerifiedTimesMap.get(namespace) != null) {
                    verifiedTimes = configResetVerifiedTimesMap.get(namespace);
                }
                verify(() -> DeviceConfig.resetToDefaults(RescueParty.DEVICE_CONFIG_RESET_MODE,
                        namespace), times(verifiedTimes + 1));
                if (configResetVerifiedTimesMap != null) {
                    configResetVerifiedTimesMap.put(namespace, verifiedTimes + 1);
                }
            }
        }
    }

    private void noteBoot(int mitigationCount) {
        RescuePartyObserver.getInstance(mMockContext).executeBootLoopMitigation(mitigationCount);
    }

    private void noteAppCrash(int mitigationCount, boolean isPersistent) {
        String packageName = isPersistent ? PERSISTENT_PACKAGE : NON_PERSISTENT_PACKAGE;
        RescuePartyObserver.getInstance(mMockContext).execute(new VersionedPackage(
                packageName, 1), PackageWatchdog.FAILURE_REASON_APP_CRASH, mitigationCount);
    }

    private Bundle getConfigAccessBundle(String callingPackage, String namespace) {
        Bundle result = new Bundle();
        result.putString(Settings.EXTRA_MONITOR_CALLBACK_TYPE, Settings.EXTRA_ACCESS_CALLBACK);
        result.putString(Settings.EXTRA_CALLING_PACKAGE, callingPackage);
        result.putString(Settings.EXTRA_NAMESPACE, namespace);
        return result;
    }

    private Bundle getConfigNamespaceUpdateBundle(String updatedNamespace) {
        Bundle result = new Bundle();
        result.putString(Settings.EXTRA_MONITOR_CALLBACK_TYPE,
                Settings.EXTRA_NAMESPACE_UPDATED_CALLBACK);
        result.putString(Settings.EXTRA_NAMESPACE, updatedNamespace);
        return result;
    }
}
