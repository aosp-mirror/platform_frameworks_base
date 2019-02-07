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
import android.os.RecoverySystem;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

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
    private static final int PERSISTENT_APP_UID = 12;
    private static final long CURRENT_NETWORK_TIME_MILLIS = 0L;

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
                        .spyStatic(SystemProperties.class)
                        .spyStatic(Settings.Global.class)
                        .spyStatic(Settings.Secure.class)
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

        doReturn(CURRENT_NETWORK_TIME_MILLIS).when(() -> RescueParty.getElapsedRealtime());
        RescueParty.resetAllThresholds();

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
        notePersistentAppCrash(RescueParty.TRIGGER_COUNT);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_DEFAULTS);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash(RescueParty.TRIGGER_COUNT);

        verifySettingsResets(Settings.RESET_MODE_UNTRUSTED_CHANGES);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash(RescueParty.TRIGGER_COUNT);

        verifySettingsResets(Settings.RESET_MODE_TRUSTED_DEFAULTS);
        assertEquals(RescueParty.LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS,
                SystemProperties.getInt(RescueParty.PROP_RESCUE_LEVEL, RescueParty.LEVEL_NONE));

        notePersistentAppCrash(RescueParty.TRIGGER_COUNT);

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
    public void testPersistentAppCrashDetectionWithWrongInterval() {
        notePersistentAppCrash(RescueParty.TRIGGER_COUNT - 1);

        // last persistent app crash is just outside of the boot loop detection window
        doReturn(CURRENT_NETWORK_TIME_MILLIS
                + RescueParty.PERSISTENT_APP_CRASH_TRIGGER_WINDOW_MILLIS + 1)
                .when(() -> RescueParty.getElapsedRealtime());
        notePersistentAppCrash(/*numTimes=*/1);

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
    public void testPersistentAppCrashDetectionWithProperInterval() {
        notePersistentAppCrash(RescueParty.TRIGGER_COUNT - 1);

        // last persistent app crash is just inside of the boot loop detection window
        doReturn(CURRENT_NETWORK_TIME_MILLIS
                + RescueParty.PERSISTENT_APP_CRASH_TRIGGER_WINDOW_MILLIS)
                .when(() -> RescueParty.getElapsedRealtime());
        notePersistentAppCrash(/*numTimes=*/1);

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
    public void testPersistentAppCrashDetectionWithWrongTriggerCount() {
        notePersistentAppCrash(RescueParty.TRIGGER_COUNT - 1);
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

    private void verifySettingsResets(int resetMode) {
        verify(() -> Settings.Global.resetToDefaultsAsUser(mMockContentResolver, null,
                resetMode,
                UserHandle.USER_SYSTEM));
        verify(() -> Settings.Secure.resetToDefaultsAsUser(eq(mMockContentResolver), isNull(),
                eq(resetMode), anyInt()));
    }

    private void noteBoot(int numTimes) {
        for (int i = 0; i < numTimes; i++) {
            RescueParty.noteBoot(mMockContext);
        }
    }

    private void notePersistentAppCrash(int numTimes) {
        for (int i = 0; i < numTimes; i++) {
            RescueParty.notePersistentAppCrash(mMockContext, PERSISTENT_APP_UID);
        }
    }
}
