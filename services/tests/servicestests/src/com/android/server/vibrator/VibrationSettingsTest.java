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

import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationAttributes.USAGE_UNKNOWN;
import static android.os.Vibrator.VIBRATION_INTENSITY_HIGH;
import static android.os.Vibrator.VIBRATION_INTENSITY_LOW;
import static android.os.Vibrator.VIBRATION_INTENSITY_MEDIUM;
import static android.os.Vibrator.VIBRATION_INTENSITY_OFF;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
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

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock
    private VibrationSettings.OnVibratorSettingsChanged mListenerMock;
    @Mock
    private PowerManagerInternal mPowerManagerInternalMock;

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

        // Simulate System defaults.
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
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
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

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
    }

    @Test
    public void shouldIgnoreVibration_fromBackground_doesNotIgnoreUsagesFromAllowlist() {
        int[] expectedAllowedVibrations = new int[] {
                USAGE_RINGTONE,
                USAGE_ALARM,
                USAGE_NOTIFICATION,
                USAGE_COMMUNICATION_REQUEST,
                USAGE_HARDWARE_FEEDBACK,
                USAGE_PHYSICAL_EMULATION,
        };

        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);

        for (int usage : expectedAllowedVibrations) {
            assertNull("Error for usage " + VibrationAttributes.usageToString(usage),
                    mVibrationSettings.shouldIgnoreVibration(UID,
                            VibrationAttributes.createForUsage(usage)));
        }
    }

    @Test
    public void shouldIgnoreVibration_fromBackground_ignoresUsagesNotInAllowlist() {
        int[] expectedIgnoredVibrations = new int[] {
                USAGE_TOUCH,
                USAGE_UNKNOWN,
        };

        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);

        for (int usage : expectedIgnoredVibrations) {
            assertEquals("Error for usage " + VibrationAttributes.usageToString(usage),
                    Vibration.Status.IGNORED_BACKGROUND,
                    mVibrationSettings.shouldIgnoreVibration(UID,
                            VibrationAttributes.createForUsage(usage)));
        }
    }

    @Test
    public void shouldIgnoreVibration_fromForeground_allowsAnyUsage() {
        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0, 0);

        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_ALARM)));
    }

    @Test
    public void shouldIgnoreVibration_inBatterySaverMode_doesNotIgnoreUsagesFromAllowlist() {
        int[] expectedAllowedVibrations = new int[] {
                USAGE_RINGTONE,
                USAGE_ALARM,
                USAGE_COMMUNICATION_REQUEST,
        };

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        for (int usage : expectedAllowedVibrations) {
            assertNull("Error for usage " + VibrationAttributes.usageToString(usage),
                    mVibrationSettings.shouldIgnoreVibration(UID,
                            VibrationAttributes.createForUsage(usage)));
        }
    }

    @Test
    public void shouldIgnoreVibration_inBatterySaverMode_ignoresUsagesNotInAllowlist() {
        int[] expectedIgnoredVibrations = new int[] {
                USAGE_NOTIFICATION,
                USAGE_HARDWARE_FEEDBACK,
                USAGE_PHYSICAL_EMULATION,
                USAGE_TOUCH,
                USAGE_UNKNOWN,
        };

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        for (int usage : expectedIgnoredVibrations) {
            assertEquals("Error for usage " + VibrationAttributes.usageToString(usage),
                    Vibration.Status.IGNORED_FOR_POWER,
                    mVibrationSettings.shouldIgnoreVibration(UID,
                            VibrationAttributes.createForUsage(usage)));
        }
    }

    @Test
    public void shouldIgnoreVibration_notInBatterySaverMode_allowsAnyUsage() {
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);

        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_COMMUNICATION_REQUEST)));
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeSilent_ignoresRingtoneAndTouch() {
        // Vibrating settings on are overruled by ringer mode.
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setRingerMode(AudioManager.RINGER_MODE_SILENT);

        assertEquals(Vibration.Status.IGNORED_FOR_RINGER_MODE,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_RINGTONE)));
        assertEquals(Vibration.Status.IGNORED_FOR_RINGER_MODE,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_COMMUNICATION_REQUEST)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_HARDWARE_FEEDBACK)));
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeVibrate_allowsAllVibrations() {
        // Vibrating settings off are overruled by ringer mode.
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);

        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_PHYSICAL_EMULATION)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_RINGTONE)));
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeNormalAndRingSettingsOff_ignoresRingtoneOnly() {
        // Vibrating settings off are respected for normal ringer mode.
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);

        assertEquals(Vibration.Status.IGNORED_FOR_RINGER_MODE,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_RINGTONE)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_NOTIFICATION)));
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeNormalAndRingSettingsOn_allowsAllVibrations() {
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);

        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_RINGTONE)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_ALARM)));
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeNormalAndRampingRingerOn_allowsAllVibrations() {
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 1);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);

        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_RINGTONE)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_COMMUNICATION_REQUEST)));
    }

    @Test
    public void shouldIgnoreVibration_withHapticFeedbackSettingsOff_ignoresTouchVibration() {
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

        assertEquals(Vibration.Status.IGNORED_FOR_SETTINGS,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_RINGTONE)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_HARDWARE_FEEDBACK)));
    }

    @Test
    public void shouldIgnoreVibration_withHardwareFeedbackSettingsOff_ignoresHardwareVibrations() {
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

        assertEquals(Vibration.Status.IGNORED_FOR_SETTINGS,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_HARDWARE_FEEDBACK)));
        assertEquals(Vibration.Status.IGNORED_FOR_SETTINGS,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_PHYSICAL_EMULATION)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_NOTIFICATION)));
    }

    @Test
    public void shouldIgnoreVibration_withNotificationSettingsOff_ignoresNotificationVibrations() {
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);

        assertEquals(Vibration.Status.IGNORED_FOR_SETTINGS,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_NOTIFICATION)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_ALARM)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_RINGTONE)));
    }

    @Test
    public void shouldIgnoreVibration_withRingSettingsOff_ignoresRingtoneVibrations() {
        // Vibrating settings on are overruled by ring intensity setting.
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 1);
        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);

        assertEquals(Vibration.Status.IGNORED_FOR_SETTINGS,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(USAGE_RINGTONE)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_NOTIFICATION)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_ALARM)));
        assertNull(mVibrationSettings.shouldIgnoreVibration(UID,
                VibrationAttributes.createForUsage(USAGE_TOUCH)));
    }

    @Test
    public void shouldVibrateInputDevices_returnsSettingsValue() {
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        assertTrue(mVibrationSettings.shouldVibrateInputDevices());

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        assertFalse(mVibrationSettings.shouldVibrateInputDevices());
    }

    @Test
    public void getDefaultIntensity_beforeSystemReady_returnsMediumToAllExceptAlarm() {
        mFakeVibrator.setDefaultHapticFeedbackIntensity(VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultRingVibrationIntensity(VIBRATION_INTENSITY_HIGH);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

        VibrationSettings vibrationSettings = new VibrationSettings(mContextSpy,
                new Handler(mTestLooper.getLooper()));

        assertEquals(VIBRATION_INTENSITY_HIGH,
                vibrationSettings.getDefaultIntensity(USAGE_ALARM));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(USAGE_TOUCH));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(USAGE_PHYSICAL_EMULATION));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(USAGE_NOTIFICATION));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(USAGE_UNKNOWN));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                vibrationSettings.getDefaultIntensity(USAGE_RINGTONE));
    }

    @Test
    public void getDefaultIntensity_returnsIntensityFromVibratorService() {
        mFakeVibrator.setDefaultHapticFeedbackIntensity(VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(VIBRATION_INTENSITY_MEDIUM);
        mFakeVibrator.setDefaultRingVibrationIntensity(VIBRATION_INTENSITY_LOW);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getDefaultIntensity(USAGE_ALARM));
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getDefaultIntensity(USAGE_TOUCH));
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getDefaultIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getDefaultIntensity(USAGE_PHYSICAL_EMULATION));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getDefaultIntensity(USAGE_NOTIFICATION));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getDefaultIntensity(USAGE_UNKNOWN));
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getDefaultIntensity(USAGE_RINGTONE));
    }

    @Test
    public void getCurrentIntensity_returnsIntensityFromSettings() {
        mFakeVibrator.setDefaultHapticFeedbackIntensity(VIBRATION_INTENSITY_OFF);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(VIBRATION_INTENSITY_OFF);
        mFakeVibrator.setDefaultRingVibrationIntensity(VIBRATION_INTENSITY_OFF);

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);

        assertEquals(VIBRATION_INTENSITY_HIGH, mVibrationSettings.getCurrentIntensity(USAGE_ALARM));
        assertEquals(VIBRATION_INTENSITY_HIGH, mVibrationSettings.getCurrentIntensity(USAGE_TOUCH));
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(USAGE_PHYSICAL_EMULATION));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_NOTIFICATION));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_UNKNOWN));
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(USAGE_RINGTONE));
    }

    @Test
    public void getCurrentIntensity_updateTriggeredAfterUserSwitched() {
        mFakeVibrator.setDefaultRingVibrationIntensity(VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_HIGH);
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_RINGTONE));

        // Switching user is not working with FakeSettingsProvider.
        // Testing the broadcast flow manually.
        Settings.System.putIntForUser(mContextSpy.getContentResolver(),
                Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW,
                UserHandle.USER_CURRENT);
        mVibrationSettings.mUserReceiver.onReceive(mContextSpy,
                new Intent(Intent.ACTION_USER_SWITCHED));
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(USAGE_RINGTONE));
    }

    @Test
    public void getCurrentIntensity_noHardwareFeedbackValueUsesHapticFeedbackValue() {
        mFakeVibrator.setDefaultHapticFeedbackIntensity(VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        assertEquals(VIBRATION_INTENSITY_OFF, mVibrationSettings.getCurrentIntensity(USAGE_TOUCH));
        // If haptic feedback is off, fallback to default value.
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_PHYSICAL_EMULATION));

        // Switching user is not working with FakeSettingsProvider.
        // Testing the broadcast flow manually.
        Settings.System.putIntForUser(mContextSpy.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH,
                UserHandle.USER_CURRENT);
        mVibrationSettings.mUserReceiver.onReceive(mContextSpy,
                new Intent(Intent.ACTION_USER_SWITCHED));
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_TOUCH));
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_PHYSICAL_EMULATION));
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

    private void setRingerMode(int ringerMode) {
        mAudioManager.setRingerModeInternal(ringerMode);
        assertEquals(ringerMode, mAudioManager.getRingerModeInternal());
    }
}
