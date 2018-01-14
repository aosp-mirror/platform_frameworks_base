/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distriZenbuted on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static junit.framework.Assert.assertFalse;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.media.AudioAttributes;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ZenModeHelperTest extends UiServiceTestCase {

    @Mock ConditionProviders mConditionProviders;
    private TestableLooper mTestableLooper;
    private ZenModeHelper mZenModeHelperSpy;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mZenModeHelperSpy = spy(new ZenModeHelper(getContext(), mTestableLooper.getLooper(),
                mConditionProviders));
    }

    @Test
    public void testZenOff_NoMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_OFF;
        assertTrue(mZenModeHelperSpy.mConfig.allowAlarms);
        mZenModeHelperSpy.applyRestrictions();

        doNothing().when(mZenModeHelperSpy).applyRestrictions(anyBoolean(), anyInt());
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_AllowAlarmsMedia_NoAlarmMediaMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        assertTrue(mZenModeHelperSpy.mConfig.allowAlarms);
        assertTrue(mZenModeHelperSpy.mConfig.allowMediaSystemOther);
        mZenModeHelperSpy.applyRestrictions();
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_DisallowAlarmsMedia_AlarmMediaMuteApplied() {

        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMediaSystemOther = false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMediaSystemOther);
        mZenModeHelperSpy.applyRestrictions();
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_ALARM);

        // Media is a catch-all that includes games and system sounds
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_GAME);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_UNKNOWN);
    }

    @Test
    public void testAlarmsOnly_alarmMediaMuteNotApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMediaSystemOther = false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMediaSystemOther);
        mZenModeHelperSpy.applyRestrictions();

        // Alarms only mode will not silence alarms
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);

        // Alarms only mode will not silence media
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_GAME);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_UNKNOWN);
    }

    @Test
    public void testAlarmsOnly_callsMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        assertTrue(mZenModeHelperSpy.mConfig.allowCalls);
        mZenModeHelperSpy.applyRestrictions();

        // Alarms only mode will silence calls despite priority-mode config
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testAlarmsOnly_allZenConfigToggledCannotBypass_alarmMuteNotApplied() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMediaSystemOther = false;
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMediaSystemOther);
        assertFalse(mZenModeHelperSpy.mConfig.allowReminders);
        assertFalse(mZenModeHelperSpy.mConfig.allowCalls);
        assertFalse(mZenModeHelperSpy.mConfig.allowMessages);
        assertFalse(mZenModeHelperSpy.mConfig.allowEvents);
        assertFalse(mZenModeHelperSpy.mConfig.allowRepeatCallers);
        mZenModeHelperSpy.applyRestrictions();

        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);
    }

    @Test
    public void testZenAllCannotBypass() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMediaSystemOther = false;
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMediaSystemOther);
        assertFalse(mZenModeHelperSpy.mConfig.allowReminders);
        assertFalse(mZenModeHelperSpy.mConfig.allowCalls);
        assertFalse(mZenModeHelperSpy.mConfig.allowMessages);
        assertFalse(mZenModeHelperSpy.mConfig.allowEvents);
        assertFalse(mZenModeHelperSpy.mConfig.allowRepeatCallers);
        mZenModeHelperSpy.applyRestrictions();

        for (int usage : AudioAttributes.SDK_USAGES) {
            boolean shouldMute = AudioAttributes.SUPPRESSIBLE_USAGES.get(usage)
                    != AudioAttributes.SUPPRESSIBLE_NEVER;
            verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(shouldMute, usage);
        }
    }
}
