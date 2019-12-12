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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.VersionedPackage;
import android.os.RecoverySystem;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;
import com.android.server.RescueParty.RescuePartyObserver;
import com.android.server.am.SettingsToPropertiesMapper;
import com.android.server.utils.FlagNamespaceUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.HashMap;

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

    private MockitoSession mSession;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mMockContext;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ContentResolver mMockContentResolver;

    private HashMap<String, String> mSystemSettingsMap;

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
                        .startMocking();
        mSystemSettingsMap = new HashMap<>();

        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);


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


        doReturn(CURRENT_NETWORK_TIME_MILLIS).when(() -> RescueParty.getElapsedRealtime());
        RescueParty.resetAllThresholds();
        FlagNamespaceUtils.resetKnownResetNamespacesFlagCounterForTest();

        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL,
                Integer.toString(RescueParty.LEVEL_NONE));
        SystemProperties.set(RescueParty.PROP_RESCUE_BOOT_COUNT, Integer.toString(0));
        SystemProperties.set(RescueParty.PROP_ENABLE_RESCUE, Boolean.toString(true));
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
    }

    @Test
    public void testBootLoopDetectionWithExecutionForAllRescueLevels() {
        noteBoot(RescueParty.TRIGGER_COUNT);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        noteBoot(RescueParty.TRIGGER_COUNT);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        noteBoot(RescueParty.TRIGGER_COUNT);

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        noteBoot(RescueParty.TRIGGER_COUNT);

        verify(() -> RecoverySystem.rebootPromptAndWipeUserData(mMockContext, RescueParty.TAG));
        assertEquals(RescueParty.LEVEL_FACTORY_RESET,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
    }

    @Test
    public void testPersistentAppCrashDetectionWithExecutionForAllRescueLevels() {
        notePersistentAppCrash();

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash();

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash();

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash();

        verify(() -> RecoverySystem.rebootPromptAndWipeUserData(mMockContext, RescueParty.TAG));
        assertEquals(RescueParty.LEVEL_FACTORY_RESET,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
    }

    @Test
    public void testBootLoopDetectionWithWrongInterval() {
        noteBoot(RescueParty.TRIGGER_COUNT - 1);

        // last boot is just outside of the boot loop detection window
        doReturn(CURRENT_NETWORK_TIME_MILLIS + RescueParty.BOOT_TRIGGER_WINDOW_MILLIS + 1).when(
                () -> RescueParty.getElapsedRealtime());
        noteBoot(/*numTimes=*/1);

        assertEquals(RescueParty.LEVEL_NONE,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
    }

    @Test
    public void testBootLoopDetectionWithProperInterval() {
        noteBoot(RescueParty.TRIGGER_COUNT - 1);

        // last boot is just inside of the boot loop detection window
        doReturn(CURRENT_NETWORK_TIME_MILLIS + RescueParty.BOOT_TRIGGER_WINDOW_MILLIS).when(
                () -> RescueParty.getElapsedRealtime());
        noteBoot(/*numTimes=*/1);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
    }

    @Test
    public void testBootLoopDetectionWithWrongTriggerCount() {
        noteBoot(RescueParty.TRIGGER_COUNT - 1);
        assertEquals(RescueParty.LEVEL_NONE,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));
    }

    @Test
    public void testIsAttemptingFactoryReset() {
        noteBoot(RescueParty.TRIGGER_COUNT * 4);

        verify(() -> RecoverySystem.rebootPromptAndWipeUserData(mMockContext, RescueParty.TAG));
        assertTrue(RescueParty.isAttemptingFactoryReset());
    }

    @Test
    public void testOnSettingsProviderPublishedExecutesRescueLevels() {
        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(1));

        RescueParty.onSettingsProviderPublished(mMockContext);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS);
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

        ExtendedMockito.verify(
                () -> DeviceConfig.setProperty(FlagNamespaceUtils.NAMESPACE_RESCUE_PARTY,
                        FlagNamespaceUtils.RESET_PLATFORM_PACKAGE_FLAG + 0,
                        FAKE_NATIVE_NAMESPACE1, /*makeDefault=*/true));
        ExtendedMockito.verify(
                () -> DeviceConfig.setProperty(FlagNamespaceUtils.NAMESPACE_RESCUE_PARTY,
                        FlagNamespaceUtils.RESET_PLATFORM_PACKAGE_FLAG + 1,
                        FAKE_NATIVE_NAMESPACE2, /*makeDefault=*/true));
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
    public void testHealthCheckLevels() {
        RescuePartyObserver observer = RescuePartyObserver.getInstance(mMockContext);

        // Ensure that no action is taken for cases where the failure reason is unknown
        SystemProperties.set(RescueParty.PROP_RESCUE_LEVEL, Integer.toString(
                RescueParty.LEVEL_FACTORY_RESET));
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
                RescueParty.LEVEL_FACTORY_RESET));
        assertEquals(observer.onHealthCheckFailed(null,
                PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING),
                PackageHealthObserverImpact.USER_IMPACT_HIGH);
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

    private void verifySettingsResets(int resetMode) {
        verify(() -> Settings.Global.resetToDefaultsAsUser(mMockContentResolver, null,
                resetMode, UserHandle.USER_SYSTEM));
        verify(() -> Settings.Secure.resetToDefaultsAsUser(eq(mMockContentResolver), isNull(),
                eq(resetMode), anyInt()));
    }

    private void noteBoot(int numTimes) {
        for (int i = 0; i < numTimes; i++) {
            RescueParty.noteBoot(mMockContext);
        }
    }

    private void notePersistentAppCrash() {
        RescuePartyObserver.getInstance(mMockContext).execute(new VersionedPackage(
                "com.package.name", 1), PackageWatchdog.FAILURE_REASON_UNKNOWN);
    }
}
