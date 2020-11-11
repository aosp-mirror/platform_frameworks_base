/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioManager;
import android.os.Handler;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link VibrationSettings}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibrationSettingsTest
 */
@Presubmit
public class VibrationSettingsTest {

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    // TODO(b/131311651): replace with a FakeVibrator instead.
    @Mock private Vibrator mVibratorMock;
    @Mock private VibrationSettings.OnVibratorSettingsChanged mListenerMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private AudioManager mAudioManager;
    private VibrationSettings mVibrationSettings;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mVibratorMock);
        when(mVibratorMock.hasVibrator()).thenReturn(true);

        mAudioManager = mContextSpy.getSystemService(AudioManager.class);
        mVibrationSettings = new VibrationSettings(
                mContextSpy, new Handler(mTestLooper.getLooper()));

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
    }

    @After
    public void tearDown() throws Exception {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Test
    public void addListener_settingsChangeTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_ALARMS);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        verify(mListenerMock, times(7)).onChange();
    }

    @Test
    public void removeListener_noMoreCallbacksToListener() {
        mVibrationSettings.addListener(mListenerMock);

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        verify(mListenerMock).onChange();

        mVibrationSettings.removeListener(mListenerMock);

        verifyNoMoreInteractions(mListenerMock);
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_ALARMS);
    }

    @Test
    public void shouldVibrateForRingtones_withVibrateWhenRinging_onlyIgnoreSettingsForSilentMode() {
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, mAudioManager.getRingerMode());
        assertFalse(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_MAX);
        assertEquals(AudioManager.RINGER_MODE_MAX, mAudioManager.getRingerMode());
        assertTrue(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        assertEquals(AudioManager.RINGER_MODE_NORMAL, mAudioManager.getRingerMode());
        assertTrue(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, mAudioManager.getRingerMode());
        assertTrue(mVibrationSettings.shouldVibrateForRingtone());
    }

    @Test
    public void shouldVibrateForRingtones_withApplyRampingRinger_onlyIgnoreSettingsForSilentMode() {
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 1);

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, mAudioManager.getRingerMode());
        assertFalse(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_MAX);
        assertEquals(AudioManager.RINGER_MODE_MAX, mAudioManager.getRingerMode());
        assertTrue(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        assertEquals(AudioManager.RINGER_MODE_NORMAL, mAudioManager.getRingerMode());
        assertTrue(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, mAudioManager.getRingerMode());
        assertTrue(mVibrationSettings.shouldVibrateForRingtone());
    }

    @Test
    public void shouldVibrateForRingtones_withAllSettingsOff_onlyVibratesForVibrateMode() {
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, mAudioManager.getRingerMode());
        assertTrue(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, mAudioManager.getRingerMode());
        assertFalse(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_MAX);
        assertEquals(AudioManager.RINGER_MODE_MAX, mAudioManager.getRingerMode());
        assertFalse(mVibrationSettings.shouldVibrateForRingtone());

        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        assertEquals(AudioManager.RINGER_MODE_NORMAL, mAudioManager.getRingerMode());
        assertFalse(mVibrationSettings.shouldVibrateForRingtone());
    }

    @Test
    public void shouldVibrateInputDevices_returnsSettingsValue() {
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        assertTrue(mVibrationSettings.shouldVibrateInputDevices());

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        assertFalse(mVibrationSettings.shouldVibrateInputDevices());
    }

    @Test
    public void isInZenMode_returnsSettingsValue() {
        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
        assertFalse(mVibrationSettings.isInZenMode());

        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);
        assertTrue(mVibrationSettings.isInZenMode());
        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_ALARMS);
        assertTrue(mVibrationSettings.isInZenMode());

        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
        assertFalse(mVibrationSettings.isInZenMode());

        setGlobalSetting(Settings.Global.ZEN_MODE,
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        assertTrue(mVibrationSettings.isInZenMode());
    }

    @Test
    public void getDefaultIntensity_returnsIntensityFromVibratorService() {
        when(mVibratorMock.getDefaultHapticFeedbackIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_HIGH);
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorMock.getDefaultRingVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_LOW);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        assertEquals(Vibrator.VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_ALARM));
        assertEquals(Vibrator.VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_TOUCH));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_NOTIFICATION));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_UNKNOWN));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getDefaultIntensity(
                        VibrationAttributes.USAGE_PHYSICAL_EMULATION));
        assertEquals(Vibrator.VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_RINGTONE));
    }

    @Test
    public void getCurrentIntensity_returnsIntensityFromSettings() {
        when(mVibratorMock.getDefaultHapticFeedbackIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_OFF);
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_OFF);
        when(mVibratorMock.getDefaultRingVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_OFF);

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);

        assertEquals(Vibrator.VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_ALARM));
        assertEquals(Vibrator.VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_TOUCH));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_NOTIFICATION));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_UNKNOWN));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(
                        VibrationAttributes.USAGE_PHYSICAL_EMULATION));
        assertEquals(Vibrator.VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(VibrationAttributes.USAGE_RINGTONE));
    }

    private void setUserSetting(String settingName, int value) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, UserHandle.USER_CURRENT);
        // FakeSettingsProvider don't support testing triggering ContentObserver yet.
        mVibrationSettings.updateSettings();
    }

    private void setGlobalSetting(String settingName, int value) {
        Settings.Global.putInt(mContextSpy.getContentResolver(), settingName, value);
        // FakeSettingsProvider don't support testing triggering ContentObserver yet.
        mVibrationSettings.updateSettings();
        mAudioManager.reloadAudioSettings();
    }
}
