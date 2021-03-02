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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;

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

    private static final int UID = 1;
    private static final PowerSaveState NORMAL_POWER_STATE = new PowerSaveState.Builder().build();
    private static final PowerSaveState LOW_POWER_STATE = new PowerSaveState.Builder()
            .setBatterySaverEnabled(true).build();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock private VibrationSettings.OnVibratorSettingsChanged mListenerMock;
    @Mock private PowerManagerInternal mPowerManagerInternalMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private AudioManager mAudioManager;
    private FakeVibrator mFakeVibrator;
    private VibrationSettings mVibrationSettings;
    private PowerManagerInternal.LowPowerModeListener mRegisteredPowerModeListener;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mFakeVibrator = new FakeVibrator();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mFakeVibrator);
        doAnswer(invocation -> {
            mRegisteredPowerModeListener = invocation.getArgument(0);
            return null;
        }).when(mPowerManagerInternalMock).registerLowPowerModeObserver(any());

        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternalMock);

        mAudioManager = mContextSpy.getSystemService(AudioManager.class);
        mVibrationSettings = new VibrationSettings(mContextSpy,
                new Handler(mTestLooper.getLooper()));
        mVibrationSettings.onSystemReady();

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        setGlobalSetting(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
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
    public void addListener_lowPowerModeChangeTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE); // No change.

        verify(mListenerMock, times(2)).onChange();
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
    public void shouldVibrateForRingerMode_beforeSystemReady_returnsFalseOnlyForRingtone() {
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setRingerMode(AudioManager.RINGER_MODE_MAX);
        VibrationSettings vibrationSettings = new VibrationSettings(mContextSpy,
                new Handler(mTestLooper.getLooper()));

        assertFalse(vibrationSettings.shouldVibrateForRingerMode(
                VibrationAttributes.USAGE_RINGTONE));
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(VibrationAttributes.USAGE_ALARM));
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(VibrationAttributes.USAGE_TOUCH));
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(
                VibrationAttributes.USAGE_NOTIFICATION));
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(
                VibrationAttributes.USAGE_COMMUNICATION_REQUEST));
    }

    @Test
    public void shouldVibrateForRingerMode_withoutRingtoneUsage_returnsTrue() {
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(VibrationAttributes.USAGE_ALARM));
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(VibrationAttributes.USAGE_TOUCH));
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(
                VibrationAttributes.USAGE_NOTIFICATION));
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(
                VibrationAttributes.USAGE_COMMUNICATION_REQUEST));
    }

    @Test
    public void shouldVibrateForRingerMode_withVibrateWhenRinging_ignoreSettingsForSilentMode() {
        int usageRingtone = VibrationAttributes.USAGE_RINGTONE;
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);

        setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertFalse(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_MAX);
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));
    }

    @Test
    public void shouldVibrateForRingerMode_withApplyRampingRinger_ignoreSettingsForSilentMode() {
        int usageRingtone = VibrationAttributes.USAGE_RINGTONE;
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 1);

        setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertFalse(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_MAX);
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));
    }

    @Test
    public void shouldVibrateForRingerMode_withAllSettingsOff_onlyVibratesForVibrateMode() {
        int usageRingtone = VibrationAttributes.USAGE_RINGTONE;
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);

        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        assertTrue(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertFalse(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_MAX);
        assertFalse(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        assertFalse(mVibrationSettings.shouldVibrateForRingerMode(usageRingtone));
    }

    @Test
    public void shouldVibrateForUid_withForegroundOnlyUsage_returnsTrueWhInForeground() {
        assertTrue(mVibrationSettings.shouldVibrateForUid(UID, VibrationAttributes.USAGE_TOUCH));

        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);
        assertFalse(mVibrationSettings.shouldVibrateForUid(UID, VibrationAttributes.USAGE_TOUCH));
    }

    @Test
    public void shouldVibrateForUid_withBackgroundAllowedUsage_returnTrue() {
        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);

        assertTrue(mVibrationSettings.shouldVibrateForUid(UID, VibrationAttributes.USAGE_ALARM));
        assertTrue(mVibrationSettings.shouldVibrateForUid(UID,
                VibrationAttributes.USAGE_COMMUNICATION_REQUEST));
        assertTrue(mVibrationSettings.shouldVibrateForUid(UID,
                VibrationAttributes.USAGE_NOTIFICATION));
        assertTrue(mVibrationSettings.shouldVibrateForUid(UID, VibrationAttributes.USAGE_RINGTONE));
    }

    @Test
    public void shouldVibrateForPowerMode_withLowPowerAndAllowedUsage_returnTrue() {
        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        assertTrue(mVibrationSettings.shouldVibrateForPowerMode(VibrationAttributes.USAGE_ALARM));
        assertTrue(mVibrationSettings.shouldVibrateForPowerMode(
                VibrationAttributes.USAGE_RINGTONE));
        assertTrue(mVibrationSettings.shouldVibrateForPowerMode(
                VibrationAttributes.USAGE_COMMUNICATION_REQUEST));
    }

    @Test
    public void shouldVibrateForPowerMode_withRestrictedUsage_returnsFalseWhileInLowPowerMode() {
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);

        assertTrue(mVibrationSettings.shouldVibrateForPowerMode(VibrationAttributes.USAGE_TOUCH));
        assertTrue(mVibrationSettings.shouldVibrateForPowerMode(
                VibrationAttributes.USAGE_NOTIFICATION));

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        assertFalse(mVibrationSettings.shouldVibrateForPowerMode(VibrationAttributes.USAGE_TOUCH));
        assertFalse(mVibrationSettings.shouldVibrateForPowerMode(
                VibrationAttributes.USAGE_NOTIFICATION));
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
    public void getDefaultIntensity_beforeSystemReady_returnsMediumToAllExceptAlarm() {
        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultRingVibrationIntensity(Vibrator.VIBRATION_INTENSITY_HIGH);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        VibrationSettings vibrationSettings = new VibrationSettings(mContextSpy,
                new Handler(mTestLooper.getLooper()));

        assertEquals(Vibrator.VIBRATION_INTENSITY_HIGH,
                vibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_ALARM));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_TOUCH));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_NOTIFICATION));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_UNKNOWN));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(
                        VibrationAttributes.USAGE_PHYSICAL_EMULATION));
        assertEquals(Vibrator.VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(VibrationAttributes.USAGE_RINGTONE));
    }

    @Test
    public void getDefaultIntensity_returnsIntensityFromVibratorService() {
        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        mFakeVibrator.setDefaultRingVibrationIntensity(Vibrator.VIBRATION_INTENSITY_LOW);

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
        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_OFF);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_OFF);
        mFakeVibrator.setDefaultRingVibrationIntensity(Vibrator.VIBRATION_INTENSITY_OFF);

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

    @Test
    public void getFallbackEffect_returnsEffectsFromSettings() {
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_TICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_TEXTURE_TICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_CLICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_HEAVY_CLICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_DOUBLE_CLICK));
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
    }

    private void setRingerMode(int ringerMode) {
        mAudioManager.setRingerModeInternal(ringerMode);
        assertEquals(ringerMode, mAudioManager.getRingerModeInternal());
    }
}
