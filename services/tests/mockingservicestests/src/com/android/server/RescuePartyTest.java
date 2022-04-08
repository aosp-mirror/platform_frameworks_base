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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.VersionedPackage;
import android.os.Bundle;
import android.os.RecoverySystem;
import android.os.RemoteCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;

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
    private static final String NAMESPACE1 = "namespace1";
    private static final String NAMESPACE2 = "namespace2";
    private static final String PROP_DEVICE_CONFIG_DISABLE_FLAG =
            "persist.device_config.configuration.disable_rescue_party";

    private MockitoSession mSession;
    private HashMap<String, String> mSystemSettingsMap;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mMockContext;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private PackageWatchdog mMockPackageWatchdog;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ContentResolver mMockContentResolver;

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

        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
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

        // Mock PackageWatchdog
        doAnswer((Answer<PackageWatchdog>) invocationOnMock -> mMockPackageWatchdog)
                .when(() -> PackageWatchdog.getInstance(mMockContext));

        doReturn(CURRENT_NETWORK_TIME_MILLIS).when(() -> RescueParty.getElapsedRealtime());

        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL,
                Integer.toString(RescueParty.LEVEL_NONE));
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
        noteBoot();

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, /*resetNamespaces=*/ null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        noteBoot();

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES, /*resetNamespaces=*/ null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        noteBoot();

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, /*resetNamespaces=*/ null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        noteBoot();

        verify(() -> RecoverySystem.rebootPromptAndWipeUserData(mMockContext, RescueParty.TAG));
        assertEquals(LEVEL_FACTORY_RESET,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
    }

    @Test
    public void testPersistentAppCrashDetectionWithExecutionForAllRescueLevels() {
        notePersistentAppCrash();

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, /*resetNamespaces=*/ null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash();

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES, /*resetNamespaces=*/ null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash();

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, /*resetNamespaces=*/ null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash();

        verify(() -> RecoverySystem.rebootPromptAndWipeUserData(mMockContext, RescueParty.TAG));
        assertEquals(LEVEL_FACTORY_RESET,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
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
        // Fake DeviceConfig value changes
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE1));
        verify(mMockPackageWatchdog).startObservingHealth(observer,
                Arrays.asList(CALLING_PACKAGE1), RescueParty.DEFAULT_OBSERVING_DURATION_MS);
        monitorCallback.sendResult(getConfigNamespaceUpdateBundle(NAMESPACE2));
        verify(mMockPackageWatchdog, times(2)).startObservingHealth(eq(observer),
                mPackageListCaptor.capture(),
                eq(RescueParty.DEFAULT_OBSERVING_DURATION_MS));
        assertTrue(mPackageListCaptor.getValue().containsAll(
                Arrays.asList(CALLING_PACKAGE1, CALLING_PACKAGE2)));
        // Perform and verify scoped resets
        final String[] expectedResetNamespaces = new String[]{NAMESPACE1, NAMESPACE2};
        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_CRASH);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, expectedResetNamespaces);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);
        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES, expectedResetNamespaces);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);
        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS, /*resetNamespaces=*/null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        observer.execute(new VersionedPackage(
                CALLING_PACKAGE1, 1), PackageWatchdog.FAILURE_REASON_APP_CRASH);
        verify(() -> RecoverySystem.rebootPromptAndWipeUserData(mMockContext, RescueParty.TAG));
        assertTrue(RescueParty.isAttemptingFactoryReset());
    }

    @Test
    public void testIsAttemptingFactoryReset() {
        for (int i = 0; i < LEVEL_FACTORY_RESET; i++) {
            noteBoot();
        }
        verify(() -> RecoverySystem.rebootPromptAndWipeUserData(mMockContext, RescueParty.TAG));
        assertTrue(RescueParty.isAttemptingFactoryReset());
    }

    @Test
    public void testOnSettingsProviderPublishedExecutesRescueLevels() {
        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(1));

        RescueParty.onSettingsProviderPublished(mMockContext);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS, /*resetNamespaces=*/ null);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
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
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING), false);

        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        assertTrue(RescuePartyObserver.getInstance(mMockContext).execute(sFailingPackage,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING));
    }

    @Test
    public void testDisablingRescueByDeviceConfigFlag() {
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(false));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(true));

        assertEquals(RescuePartyObserver.getInstance(mMockContext).execute(sFailingPackage,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING), false);

        // Restore the property value initalized in SetUp()
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
        SystemProperties.set(PROP_DEVICE_CONFIG_DISABLE_FLAG, Boolean.toString(false));
    }

    @Test
    public void testHealthCheckLevels() {
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);

        // Ensure that no action is taken for cases where the failure reason is unknown
        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                LEVEL_FACTORY_RESET));
        assertEquals(observer.onHealthCheckFailed(null, PackageWatchdog.FAILURE_REASON_UNKNOWN),
                PackageHealthObserverImpact.USER_IMPACT_NONE);

        /*
        For the following cases, ensure that the returned user impact corresponds with the user
        impact of the next available rescue level, not the current one.
         */
        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_NONE));
        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING),
                PackageHealthObserverImpact.USER_IMPACT_LOW);

        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS));
        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING),
                PackageHealthObserverImpact.USER_IMPACT_LOW);


        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES));
        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING),
                PackageHealthObserverImpact.USER_IMPACT_HIGH);


        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS));
        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING),
                PackageHealthObserverImpact.USER_IMPACT_HIGH);


        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                LEVEL_FACTORY_RESET));
        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING),
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
    }

    @Test
    public void testBootLoopLevels() {
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);

        /*
         Ensure that the returned user impact corresponds with the user impact of the next available
         rescue level, not the current one.
         */
        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_NONE));
        assertEquals(observer.onBootLoop(), PackageHealthObserverImpact.USER_IMPACT_LOW);

        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS));
        assertEquals(observer.onBootLoop(), PackageHealthObserverImpact.USER_IMPACT_LOW);

        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES));
        assertEquals(observer.onBootLoop(), PackageHealthObserverImpact.USER_IMPACT_HIGH);

        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS));
        assertEquals(observer.onBootLoop(), PackageHealthObserverImpact.USER_IMPACT_HIGH);

        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                LEVEL_FACTORY_RESET));
        assertEquals(observer.onBootLoop(), PackageHealthObserverImpact.USER_IMPACT_HIGH);
    }

    @Test
    public void testRescueLevelIncrementsWhenExecuted() {
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);
        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_NONE));
        observer.execute(sFailingPackage,
                PackageWatchdog.FAILURE_REASON_APP_CRASH);
        assertEquals(SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, -1),
                RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS);
    }

    private void verifySettingsResets(int resetMode, String[] resetNamespaces) {
        verify(() -> Settings.Global.resetToDefaultsAsUser(mMockContentResolver, null,
                resetMode, UserHandle.USER_SYSTEM));
        verify(() -> Settings.Secure.resetToDefaultsAsUser(eq(mMockContentResolver), isNull(),
                eq(resetMode), anyInt()));
        // Verify DeviceConfig resets
        if (resetNamespaces == null) {
            verify(() -> DeviceConfig.resetToDefaults(resetMode, /*namespace=*/ null));
        } else {
            for (String namespace : resetNamespaces) {
                verify(() -> DeviceConfig.resetToDefaults(resetMode, namespace));
            }
        }
    }

    private void noteBoot() {
        RescuePartyObserver.getInstance(mMockContext).executeBootLoopMitigation();
    }

    private void notePersistentAppCrash() {
        RescuePartyObserver.getInstance(mMockContext).execute(new VersionedPackage(
                "com.package.name", 1), PackageWatchdog.FAILURE_REASON_APP_CRASH);
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
